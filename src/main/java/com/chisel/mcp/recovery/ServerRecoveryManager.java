package com.chisel.mcp.recovery;

import com.chisel.mcp.McpServer;
import com.chisel.mcp.McpServerStatus;
import com.chisel.mcp.McpServerManager;
import com.chisel.policy.AuditLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP server 崩溃自动恢复：工具调用失败（IO 异常 / 进程退出）时，指数退避后自动重启，
 * 不阻塞主流程。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>退避序列 1s → 2s → 4s → 8s → 16s → 30s（封顶），默认最多 3 次重试</li>
 *   <li>重启成功（状态回到 READY）后重置计数</li>
 *   <li>连续失败超过上限 → 标记 ERROR，等用户手动 {@code /mcp restart <name>}</li>
 *   <li>同一时刻每个 server 只有一个恢复任务在跑（{@code restarting} 去重）</li>
 * </ul>
 */
public class ServerRecoveryManager implements AutoCloseable {
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final McpServerManager manager;
    private final AuditLog auditLog;
    private final int maxAttempts;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "chisel-mcp-recovery");
        thread.setDaemon(true);
        return thread;
    });

    public ServerRecoveryManager(McpServerManager manager, AuditLog auditLog) {
        this(manager, auditLog, DEFAULT_MAX_ATTEMPTS);
    }

    public ServerRecoveryManager(McpServerManager manager, AuditLog auditLog, int maxAttempts) {
        this.manager = manager;
        this.auditLog = auditLog;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * 通知一次工具调用失败。若 server 仍处于 READY 且未在恢复中，调度自动重启。
     * 幂等：同一 server 的连续失败只触发一次调度。
     */
    public void onToolFailure(String serverName, Throwable cause) {
        if (serverName == null) {
            return;
        }
        McpServer server = manager.server(serverName);
        if (server == null || server.status() != McpServerStatus.READY) {
            return;
        }
        AttemptState state = attempts.computeIfAbsent(serverName, ignored -> new AttemptState());
        if (!state.markScheduled()) {
            return; // 已有恢复任务在跑
        }
        executor.submit(() -> recover(serverName, state, cause));
    }

    /** 手动重启成功后调用，重置恢复计数。 */
    public void reset(String serverName) {
        attempts.remove(serverName);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 当前恢复尝试信息（供 /mcp 状态行展示）。 */
    public RecoveryInfo info(String serverName) {
        AttemptState state = attempts.get(serverName);
        if (state == null) {
            return RecoveryInfo.none();
        }
        return new RecoveryInfo(state.attempt.get(), state.scheduled, state.lastError);
    }

    private void recover(String serverName, AttemptState state, Throwable firstCause) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt = state.attempt.incrementAndGet();
            long backoff = BACKOFF_SECONDS[Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)];
            state.lastError = firstCause == null ? null : firstCause.getMessage();
            auditLog.record(AuditLog.AuditEntry.error(
                    "mcp_recovery_restart", serverName,
                    "attempt " + attempt + "/" + maxAttempts + ": " + state.lastError,
                    backoff * 1000));
            try {
                TimeUnit.SECONDS.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.markDone();
                return;
            }
            String result = manager.restart(serverName);
            McpServer server = manager.server(serverName);
            if (server != null && server.status() == McpServerStatus.READY) {
                auditLog.record(AuditLog.AuditEntry.allow(
                        "mcp_recovery_restart", serverName, attempt * 1000));
                attempts.remove(serverName);
                return;
            }
            if (server != null && server.status() != McpServerStatus.STARTING) {
                // 重启失败（ERROR），记录原因后继续退避
                state.lastError = server.errorMessage() == null ? result : server.errorMessage();
            }
        }
        state.markDone();
        auditLog.record(AuditLog.AuditEntry.error(
                "mcp_recovery_give_up", serverName,
                "连续 " + maxAttempts + " 次自动重启失败: " + state.lastError,
                0));
    }

    public record RecoveryInfo(int attempt, boolean scheduled, String lastError) {
        public static RecoveryInfo none() {
            return new RecoveryInfo(0, false, null);
        }

        public boolean isActive() {
            return scheduled;
        }
    }

    private static class AttemptState {
        private final AtomicInteger attempt = new AtomicInteger(0);
        private volatile boolean scheduled;
        private volatile String lastError;

        synchronized boolean markScheduled() {
            if (scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }

        void markDone() {
            scheduled = false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
