package com.chisel.mcp.recovery;

import com.chisel.mcp.McpServer;
import com.chisel.mcp.McpServerManager;
import com.chisel.mcp.McpServerStatus;
import com.chisel.mcp.config.McpConfigLoader;
import com.chisel.mcp.config.McpServerConfig;
import com.chisel.policy.AuditLog;
import com.chisel.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证退避重试序列、最大次数限制与成功重置。
 * 不启动真实 MCP server：用 stub manager 直接控制 restart 结果。
 */
class ServerRecoveryManagerTest {

    @Test
    void retriesUntilSuccessAndResets(@TempDir Path tempDir) throws Exception {
        AtomicInteger restartCount = new AtomicInteger();
        StubManager manager = new StubManager(tempDir) {
            @Override
            public String restart(String name) {
                int count = restartCount.incrementAndGet();
                if (count >= 3) {
                    server.status(McpServerStatus.READY);
                    return "重启成功";
                }
                server.status(McpServerStatus.ERROR);
                server.errorMessage("attempt " + count + " failed");
                return "重启失败";
            }
        };
        ServerRecoveryManager recovery = new ServerRecoveryManager(manager, new AuditLog(tempDir), 5);

        recovery.onToolFailure("demo", new IOException("connection reset"));

        // 等待恢复完成（退避 1s + 2s + 4s + 成功后）
        waitForIdle(recovery, "demo");

        assertEquals(3, restartCount.get(), "第 3 次重启应成功");
        assertFalse(recovery.info("demo").isActive(), "成功后不应再处于恢复中");
        assertEquals(McpServerStatus.READY, manager.server.status());
    }

    @Test
    void givesUpAfterMaxAttempts(@TempDir Path tempDir) throws Exception {
        AtomicInteger restartCount = new AtomicInteger();
        StubManager manager = new StubManager(tempDir) {
            @Override
            public String restart(String name) {
                restartCount.incrementAndGet();
                server.status(McpServerStatus.ERROR);
                server.errorMessage("always fails");
                return "重启失败";
            }
        };
        ServerRecoveryManager recovery = new ServerRecoveryManager(manager, new AuditLog(tempDir), 3);

        recovery.onToolFailure("demo", new IOException("boom"));

        waitForIdle(recovery, "demo");

        assertEquals(3, restartCount.get(), "最多 3 次自动重启");
        assertFalse(recovery.info("demo").isActive());
        assertEquals(McpServerStatus.ERROR, manager.server.status());
    }

    @Test
    void ignoresFailureWhenServerNotReady(@TempDir Path tempDir) throws Exception {
        AtomicInteger restartCount = new AtomicInteger();
        StubManager manager = new StubManager(tempDir) {
            @Override
            public String restart(String name) {
                restartCount.incrementAndGet();
                return "不应被调用";
            }
        };
        manager.server.status(McpServerStatus.DISABLED);
        ServerRecoveryManager recovery = new ServerRecoveryManager(manager, new AuditLog(tempDir), 3);

        recovery.onToolFailure("demo", new IOException("x"));
        Thread.sleep(100);

        assertEquals(0, restartCount.get(), "非 READY 状态不应触发重启");
    }

    @Test
    void duplicateFailuresOnlyScheduleOnce(@TempDir Path tempDir) throws Exception {
        AtomicInteger restartCount = new AtomicInteger();
        StubManager manager = new StubManager(tempDir) {
            @Override
            public String restart(String name) {
                restartCount.incrementAndGet();
                server.status(McpServerStatus.ERROR);
                server.errorMessage("fail");
                return "重启失败";
            }
        };
        ServerRecoveryManager recovery = new ServerRecoveryManager(manager, new AuditLog(tempDir), 3);

        // 连续 5 次失败，只应调度一次恢复循环
        recovery.onToolFailure("demo", new IOException("1"));
        recovery.onToolFailure("demo", new IOException("2"));
        recovery.onToolFailure("demo", new IOException("3"));
        recovery.onToolFailure("demo", new IOException("4"));
        recovery.onToolFailure("demo", new IOException("5"));

        waitForIdle(recovery, "demo");

        assertEquals(3, restartCount.get(), "5 次失败只应触发 3 次重启（1 次调度）");
    }

    // ---- helpers ----

    private static void waitForIdle(ServerRecoveryManager recovery, String name) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (!recovery.info(name).isActive()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("恢复循环未在预期时间内结束");
    }

    /** stub manager：持有单个 server，restart 由测试控制。 */
    private static class StubManager extends McpServerManager {
        final McpServer server;

        StubManager(Path tempDir) {
            super(new ToolRegistry(), tempDir,
                    new McpConfigLoader(tempDir.resolve("u.json"), tempDir.resolve("p.json"), tempDir));
            McpServerConfig config = new McpServerConfig();
            config.setUrl("http://localhost:1/mcp");
            this.server = new McpServer("demo", config);
            server.status(McpServerStatus.READY);
        }

        @Override
        public McpServer server(String name) {
            return server;
        }

        @Override
        public String restart(String name) {
            return "restart";
        }
    }
}
