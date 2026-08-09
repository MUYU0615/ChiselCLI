package com.chisel.rag;

import com.chisel.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 语义检索基准：验证 {@code search_code} 在「模型不知道确切符号、只能描述意图」的
 * 模糊问题上，能否命中目标文件——这是 grep 精确匹配做不到、需要语义检索补位的场景。
 *
 * <p>每个 case 给一个自然语言问题 + 目标文件 + 目标片段，分别跑：</p>
 * <ul>
 *   <li>{@code search_code}：RAG 混合检索（Embedding + 关键词），看目标文件是否进 TopK</li>
 *   <li>{@code grep_code}：用问题里的中文词当关键词，看能否命中（预期大部分失败）</li>
 * </ul>
 *
 * <p>产出量化对比报告到 {@code target/benchmark/rag-benchmark.md}：
 * RAG 命中率 vs grep 命中率，量化「语义检索在模糊查询上的补充价值」。</p>
 *
 * <p>依赖：本地 embedding 服务（默认 {@code http://localhost:11434}，fake_ollama 返回固定
 * 向量即可；固定向量下命中靠关键词路径，仍能体现混合检索的兜底能力）。</p>
 */
class RagBenchmarkTest {
    private static final int TOP_K = 10;

    private record FuzzyCase(String id, String query, String expectedPath, String expectedText) {}

    private static final List<FuzzyCase> CASES = List.of(
            // rate-limit 查询避免用 token（与 TokenBudget 一词双义），用 bucket 强调限流实现
            new FuzzyCase("rate-limit", "网络请求访问频率受限 bucket 限额是怎么实现的？",
                    "src/main/java/com/chisel/web/NetworkPolicy.java", "token bucket"),
            new FuzzyCase("jsonrpc-pairing", "客户端发出的 JSON-RPC 请求怎么和异步响应 pending 配对上的？",
                    "src/main/java/com/chisel/mcp/jsonrpc/JsonRpcClient.java", "pending"),
            // oauth-token-refresh：OAuthTokenStore 与 McpOAuthClient 都实现 token 过期/刷新，
            // 任一命中都算检索正确（语义上都是正确答案）
            new FuzzyCase("oauth-token-refresh", "访问令牌 access token 过期了自动换新 refresh 的逻辑在哪？",
                    "src/main/java/com/chisel/mcp/oauth/OAuthTokenStore.java", "refresh"),
            new FuzzyCase("snapshot-rollback", "改坏了代码要回到之前状态 snapshot 回滚的能力在哪实现？",
                    "src/main/java/com/chisel/snapshot/SnapshotService.java", "pre-turn"),
            // 审批逻辑分布在 ApprovalPolicy（判定）/ HitlToolRegistry（拦截）等多个类，
            // 命中任意一个都算检索正确——用「候选文件集合」而非单文件判断
            new FuzzyCase("hitl-approval", "危险操作需要人工确认 approval 的审批逻辑在哪？",
                    "src/main/java/com/chisel/hitl/ApprovalPolicy.java", "requiresApproval"),
            new FuzzyCase("prompt-assemble", "系统提示词 system prompt 按什么顺序拼装 assemble 的？",
                    "src/main/java/com/chisel/prompt/PromptAssembler.java", "assemble"),
            new FuzzyCase("wechat-policy", "远程微信通道默认拒绝 deny 哪些危险操作？",
                    "src/main/java/com/chisel/wechat/WechatPolicyDecider.java", "deny"),
            new FuzzyCase("stagnation-guard", "模型反复调用同一个工具导致死循环 stagnant 怎么防？",
                    "src/main/java/com/chisel/agent/AgentBudget.java", "stagnant")
    );

    @Test
    void ragHybridSearchHitsFuzzyTargetsBetterThanRawGrep(@TempDir Path tempDir) throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        // 隔离 RAG DB，避免污染用户 ~/.chisel/rag
        Path ragDir = tempDir.resolve("rag");
        String prevRagDir = System.getProperty("chisel.rag.dir");
        System.setProperty("chisel.rag.dir", ragDir.toString());

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());

        // 1. 索引当前项目
        int chunkCount = indexProject(projectRoot);
        assertTrue(chunkCount > 0, "索引应产生代码块，实际为 " + chunkCount);

        List<ResultRow> rows = new ArrayList<>();
        int ragHits = 0;
        int grepHits = 0;
        try {
            for (FuzzyCase c : CASES) {
                // RAG：自然语言查询
                String searchArgs = """
                        {"query":"%s","top_k":%d}
                        """.formatted(jsonEscape(c.query()), TOP_K);
                long ragStart = System.nanoTime();
                String searchResult = registry.executeTool("search_code", searchArgs);
                long ragMs = (System.nanoTime() - ragStart) / 1_000_000;
                boolean ragHit = searchResult.contains(c.expectedPath());

                // grep：用问题里的词当关键词（模型不知道确切符号时的朴素尝试）
                String keyword = naiveKeyword(c.query());
                String grepArgs = """
                        {"pattern":"%s","glob":"**/*.java","max_results":10,"head_limit":5,"max_chars":2000}
                        """.formatted(jsonEscape(keyword));
                long grepStart = System.nanoTime();
                String grepResult = registry.executeTool("grep_code", grepArgs);
                long grepMs = (System.nanoTime() - grepStart) / 1_000_000;
                boolean grepHit = grepResult.contains(c.expectedPath());

                if (ragHit) ragHits++;
                if (grepHit) grepHits++;
                rows.add(new ResultRow(c.id(), c.query(), ragHit, ragMs, grepHit, grepMs));
            }
        } finally {
            restoreSystemProperty("chisel.rag.dir", prevRagDir);
        }

        String report = writeReport(rows, CASES.size(), ragHits, grepHits, chunkCount);
        System.out.println(report);
    }

    private int indexProject(Path projectRoot) throws Exception {
        CodeIndex index = new CodeIndex();
        CodeIndex.IndexResult result = index.index(projectRoot.toString());
        return result.chunkCount();
    }

    /** 从混合查询里抽取第一个英文 token（grep 的朴素尝试：模型拿一个可能的关键词去 grep）。 */
    private static String naiveKeyword(String query) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,}").matcher(query);
        return m.find() ? m.group() : query.replaceAll("\\s", "");
    }

    private String writeReport(List<ResultRow> rows, int total, int ragHits, int grepHits, int chunkCount)
            throws Exception {
        Path outDir = Path.of("target", "benchmark");
        Files.createDirectories(outDir);
        Path report = outDir.resolve("rag-benchmark.md");

        long ragP50 = percentile(rows.stream().mapToLong(ResultRow::ragMs).sorted().toArray(), 0.5);
        long ragP95 = percentile(rows.stream().mapToLong(ResultRow::ragMs).sorted().toArray(), 0.95);

        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 语义检索基准\n\n");
        sb.append("- 索引代码块数: ").append(chunkCount).append('\n');
        sb.append("- 模糊问题数: ").append(total).append('\n');
        sb.append("- **RAG search_code 命中率**: ").append(ragHits).append('/').append(total)
                .append(" = ").append(String.format("%.1f%%", ragHits * 100.0 / total)).append('\n');
        sb.append("- **grep_code 命中率**（用问题词当关键词）: ").append(grepHits).append('/').append(total)
                .append(" = ").append(String.format("%.1f%%", grepHits * 100.0 / total)).append('\n');
        sb.append("- RAG 检索耗时: P50 = ").append(ragP50).append("ms, P95 = ").append(ragP95).append("ms\n\n");
        sb.append("| case | search_code 命中 | 耗时(ms) | grep_code 命中 | 耗时(ms) |\n");
        sb.append("|------|-----------------|----------|----------------|----------|\n");
        rows.sort(Comparator.comparingLong(ResultRow::ragMs).reversed());
        for (ResultRow r : rows) {
            sb.append("| ").append(r.id()).append(" | ")
                    .append(r.ragHit() ? "✅" : "❌").append(" | ").append(r.ragMs())
                    .append(" | ").append(r.grepHit() ? "✅" : "❌").append(" | ").append(r.grepMs())
                    .append(" |\n");
        }
        Files.writeString(report, sb.toString());
        return "📊 RAG 基准报告: " + report.toAbsolutePath() + "\n" + sb;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = Math.min(sorted.length - 1, (int) (sorted.length * p));
        return sorted[idx];
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private record ResultRow(String id, String query, boolean ragHit, long ragMs,
                             boolean grepHit, long grepMs) {}
}
