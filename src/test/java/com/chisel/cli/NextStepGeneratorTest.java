package com.chisel.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NextStepGeneratorTest {

    @Test
    void parsesJsonArray() {
        List<String> parsed = NextStepGenerator.parseJsonArray(
                "[\"/index 更新索引\", \"再读一下 Agent.java\", \"运行测试\"]");
        assertEquals(3, parsed.size());
        assertEquals("/index 更新索引", parsed.get(0));
        assertEquals("运行测试", parsed.get(2));
    }

    @Test
    void parsesJsonArrayWithCodeFence() {
        List<String> parsed = NextStepGenerator.parseJsonArray("""
                ```json
                ["/plan 重构", "/search 限流"]
                ```
                """);
        assertEquals(2, parsed.size());
        assertEquals("/plan 重构", parsed.get(0));
    }

    @Test
    void fallsBackToLineExtractionWhenNotJson() {
        List<String> parsed = NextStepGenerator.parseJsonArray("""
                1. /index 更新索引
                2. 检查测试结果
                """);
        assertTrue(parsed.size() >= 2);
        assertEquals("/index 更新索引", parsed.get(0));
    }

    @Test
    void capsAtThreeSuggestions() {
        List<String> parsed = NextStepGenerator.parseJsonArray(
                "[\"a\", \"b\", \"c\", \"d\", \"e\"]");
        assertEquals(3, parsed.size());
    }

    @Test
    void emptyAndNullReturnEmpty() {
        assertTrue(NextStepGenerator.parseJsonArray(null).isEmpty());
        assertTrue(NextStepGenerator.parseJsonArray("").isEmpty());
        assertTrue(NextStepGenerator.parseJsonArray("  \n \n").isEmpty(), "纯空白应返回空");
    }

    @Test
    void truncateLimitsLength() {
        String longText = "x".repeat(100);
        // truncate 在 maxChars 处截断并追加 ...（总长 = maxChars + 3）
        assertEquals(53, NextStepGenerator.truncate(longText, 50).length());
        assertTrue(NextStepGenerator.truncate(longText, 50).endsWith("..."));
        // 短文本不截断
        assertEquals("abc", NextStepGenerator.truncate("abc", 50));
    }

    @Test
    void formatHintBuildsReadableLine() {
        String hint = NextStepSuggestions.formatHint(List.of("/index", "/plan 重构"));
        assertTrue(hint.contains("1. /index"));
        assertTrue(hint.contains("2. /plan 重构"));
        assertTrue(hint.contains("Tab"));
        assertTrue(NextStepSuggestions.formatHint(List.of()).isEmpty());
    }

    @Test
    void containerStoresAndClears() {
        NextStepSuggestions container = new NextStepSuggestions();
        assertTrue(container.isEmpty());

        container.set(List.of("/index", "  /save 项目用 Java 17  "));
        assertEquals(2, container.current().size());
        assertEquals("/save 项目用 Java 17", container.current().get(1), "应去除首尾空白");

        container.set(new java.util.ArrayList<>(java.util.Arrays.asList("", "  ", null)));
        assertTrue(container.isEmpty(), "空/空白/null 建议应被过滤");

        container.set(List.of("/index"));
        container.clear();
        assertTrue(container.isEmpty());
    }

    @Test
    void listenerReceivesUpdates() {
        NextStepSuggestions container = new NextStepSuggestions();
        List<List<String>> received = new java.util.ArrayList<>();
        container.setListener(received::add);

        container.set(List.of("/index"));
        assertEquals(1, received.size());
        assertEquals("/index", received.get(0).get(0));

        container.set(List.of("/plan"));
        assertEquals(2, received.size(), "每次 set 都应通知 listener");
    }
}
