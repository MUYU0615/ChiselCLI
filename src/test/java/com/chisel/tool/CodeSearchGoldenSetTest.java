package com.chisel.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码搜索基准测试：对 golden set 逐条跑「grep_code → read_file」确定性链路，
 * 统计每用例耗时、命中率、输出预算，产出量化报告到 {@code target/benchmark/}，
 * 并断言硬性质量线（100% 命中目标行 + 输出预算内 + 返回 suggested_reads）。
 *
 * <p>运行：{@code mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false}</p>
 *
 * <p>数字说明（本次跑出的真实值会写进报告文件）：</p>
 * <ul>
 *   <li>命中率：命中预期 {@code 文件:行号} 的用例占比（当前 golden set 10 例，目标 100%）</li>
 *   <li>耗时：单用例 grep + read 总耗时，含 P50 / P95</li>
 *   <li>输出预算：grep 结果 ≤ MAX_CHARS 的用例占比</li>
 * </ul>
 */
class CodeSearchGoldenSetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHARS = 6_000;

    @Test
    void grepThenReadGoldenSetStaysWithinBudgetAndFindsExpectedCode() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        List<GoldenCase> cases = loadGoldenSet();

        String previous = System.getProperty("chisel.search.disable.rg");
        System.setProperty("chisel.search.disable.rg", "true");
        List<CaseMetric> metrics = new ArrayList<>();
        int hits = 0;
        int budgetOk = 0;
        try {
            for (GoldenCase goldenCase : cases) {
                long caseStart = System.nanoTime();
                Path expectedFile = projectRoot.resolve(goldenCase.expectedPath()).normalize();
                int expectedLine = lineContaining(expectedFile, goldenCase.expectedText());
                String grepJson = """
                        {"pattern":"%s","glob":"%s","max_results":20,"head_limit":5,"max_chars":%d}
                        """.formatted(jsonEscape(goldenCase.pattern()), jsonEscape(goldenCase.glob()), MAX_CHARS);

                String grepResult = registry.executeTool("grep_code", grepJson);

                boolean budget = grepResult.length() <= MAX_CHARS + 500;
                boolean hit = grepResult.contains(goldenCase.expectedPath() + ":" + expectedLine);
                boolean guided = grepResult.contains("suggested_reads");

                int offset = Math.max(1, expectedLine - 20);
                String readJson = """
                        {"path":"%s","offset":%d,"limit":80}
                        """.formatted(jsonEscape(goldenCase.expectedPath()), offset);
                String readResult = registry.executeTool("read_file", readJson);
                boolean readOk = readResult.contains(goldenCase.expectedText());
                long elapsedMs = (System.nanoTime() - caseStart) / 1_000_000;

                if (hit) hits++;
                if (budget) budgetOk++;
                metrics.add(new CaseMetric(goldenCase.id(), goldenCase.question(),
                        hit, budget, guided, readOk, elapsedMs));

                assertTrue(grepResult.length() <= MAX_CHARS + 500,
                        () -> goldenCase.id() + " exceeded grep output budget: " + grepResult.length());
                assertTrue(hit,
                        () -> goldenCase.id() + " did not locate expected line. Output:\n" + grepResult);
                assertTrue(guided,
                        () -> goldenCase.id() + " should guide the Agent to read nearby lines");
                assertTrue(readOk,
                        () -> goldenCase.id() + " did not read expected context. Output:\n" + readResult);
            }
        } finally {
            restoreSystemProperty("chisel.search.disable.rg", previous);
        }

        writeReport(metrics);
        // 硬性质量线：确定性链路必须 100% 命中 + 全部预算内
        assertTrue(hits == cases.size(),
                "确定性链路命中率应为 100%: " + hits + "/" + cases.size());
        assertTrue(budgetOk == cases.size(),
                "全部用例应在输出预算内: " + budgetOk + "/" + cases.size());
    }

    private List<GoldenCase> loadGoldenSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/code-search/golden-set.json")) {
            assertNotNull(in, "golden-set.json should be packaged as a test resource");
            List<GoldenCase> cases = MAPPER.readValue(in, new TypeReference<>() {});
            assertFalse(cases.isEmpty(), "golden set should contain at least one case");
            return cases;
        }
    }

    private int lineContaining(Path file, String text) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError("Expected text not found in " + file + ": " + text);
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

    private void writeReport(List<CaseMetric> metrics) throws Exception {
        Path outDir = Path.of("target", "benchmark");
        Files.createDirectories(outDir);
        Path report = outDir.resolve("code-search-benchmark.md");

        metrics.sort(Comparator.comparingLong(CaseMetric::elapsedMs));
        long p50 = metrics.get(metrics.size() / 2).elapsedMs();
        long p95 = metrics.get((int) (metrics.size() * 0.95)).elapsedMs();
        long total = metrics.stream().mapToLong(CaseMetric::elapsedMs).sum();
        long hits = metrics.stream().filter(CaseMetric::hit).count();
        long budgetOk = metrics.stream().filter(CaseMetric::budget).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# Code Search Benchmark\n\n");
        sb.append("- 用例数: ").append(metrics.size()).append("\n");
        sb.append("- 命中率（grep 定位到预期 文件:行号）: ").append(hits).append('/').append(metrics.size())
                .append(" = ").append(String.format("%.1f%%", hits * 100.0 / metrics.size())).append('\n');
        sb.append("- 输出预算内占比（grep ≤ ").append(MAX_CHARS).append(" chars）: ").append(budgetOk).append('/')
                .append(metrics.size()).append(" = ").append(String.format("%.1f%%", budgetOk * 100.0 / metrics.size())).append('\n');
        sb.append("- 单用例耗时: P50 = ").append(p50).append("ms, P95 = ").append(p95).append("ms, 合计 = ")
                .append(total).append("ms\n\n");
        sb.append("| case | 命中 | 预算内 | suggested_reads | read 命中 | 耗时(ms) |\n");
        sb.append("|------|------|--------|-----------------|-----------|----------|\n");
        metrics.sort(Comparator.comparingLong(CaseMetric::elapsedMs).reversed());
        for (CaseMetric m : metrics) {
            sb.append("| ").append(m.id()).append(" | ").append(m.hit() ? "✅" : "❌")
                    .append(" | ").append(m.budget() ? "✅" : "❌")
                    .append(" | ").append(m.guided() ? "✅" : "❌")
                    .append(" | ").append(m.readOk() ? "✅" : "❌")
                    .append(" | ").append(m.elapsedMs()).append(" |\n");
        }
        Files.writeString(report, sb.toString());
        System.out.println("📊 代码搜索基准报告: " + report.toAbsolutePath());
        System.out.println(sb);
    }

    private record CaseMetric(String id, String question, boolean hit, boolean budget,
                              boolean guided, boolean readOk, long elapsedMs) {}

    private record GoldenCase(
            String id,
            String question,
            String pattern,
            String glob,
            String expectedPath,
            String expectedText
    ) {}
}
