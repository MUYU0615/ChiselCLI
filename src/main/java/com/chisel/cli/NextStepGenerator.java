package com.chisel.cli;

import com.chisel.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下一步建议生成器：Agent 完成一轮任务后，异步调 LLM 生成 2-3 条
 * 「接下来可以做什么」的候选指令（Claude Code 式）。
 *
 * 设计要点：
 * - 异步执行：不阻塞 Agent 主流程（生成建议要额外调一次 LLM，秒级）
 * - 轻量 prompt：不带工具 schema，要求短输出（每条 ≤60 字符），控制成本
 * - 失败静默：LLM 调用失败/超时不打扰用户，仅清空建议
 * - 复用当前 LlmClient（跟随 /model 切换）
 */
public class NextStepGenerator {
    private static final Logger log = LoggerFactory.getLogger(NextStepGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SUGGESTIONS = 3;

    private static final String PROMPT_TEMPLATE = """
            基于刚才完成的任务和对话，给用户推荐 2-3 条「接下来可以做什么」的下一步指令。
            要求：
            1. 每条是用户可以直接输入执行的指令（可能包含 slash 命令，如 /plan、/index、/search、/save 等）
            2. 简短具体，每条不超过 60 个字符，不要解释
            3. 只输出 JSON 数组，如 ["/index 更新索引", "再读一下 src/main/java/com/chisel/agent/Agent.java", "运行测试验证改动"]
            4. 不要编造不存在的命令；不确定时给通用但有用的建议
            5. 用中文

            对话摘要：
            %s
            """;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chisel-next-step-suggest");
        thread.setDaemon(true);
        return thread;
    });

    private final NextStepSuggestions suggestions;

    public NextStepGenerator(NextStepSuggestions suggestions) {
        this.suggestions = suggestions;
    }

    /** 异步生成建议；不阻塞调用方。context 是最近对话的简要文本。 */
    public void generateAsync(LlmClient llmClient, String context) {
        if (llmClient == null) {
            suggestions.clear();
            return;
        }
        String prompt = PROMPT_TEMPLATE.formatted(truncate(context, 4000));
        executor.submit(() -> {
            try {
                List<LlmClient.Message> messages = List.of(
                        LlmClient.Message.system("你是 ChiselCLI 的下一步建议生成器，只输出 JSON 数组。"),
                        LlmClient.Message.user(prompt));
                LlmClient.ChatResponse response = llmClient.chat(messages, List.of());
                List<String> parsed = parseJsonArray(response.content());
                suggestions.set(parsed);
            } catch (Exception e) {
                log.debug("next step suggestion generation failed: {}", e.getMessage());
                suggestions.clear();
            }
        });
    }

    static List<String> parseJsonArray(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        String trimmed = content.trim();
        // 兼容 ```json 包裹
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first >= 0 && last > first) {
                trimmed = trimmed.substring(first + 1, last).trim();
            }
        }
        try {
            if (trimmed.startsWith("[")) {
                ArrayNode array = (ArrayNode) MAPPER.readTree(trimmed);
                for (com.fasterxml.jackson.databind.JsonNode node : array) {
                    String text = node.asText("").trim();
                    if (!text.isEmpty()) {
                        result.add(text);
                    }
                }
            } else {
                // 非 JSON：按行/编号提取
                for (String line : trimmed.split("\n")) {
                    String cleaned = line.replaceFirst("^\\d+[.、)]\\s*", "").trim();
                    if (!cleaned.isEmpty()) {
                        result.add(cleaned);
                    }
                }
            }
        } catch (Exception e) {
            // JSON 解析失败：按行提取
            for (String line : trimmed.split("\n")) {
                String cleaned = line.replaceFirst("^\\d+[.、)]\\s*", "").trim();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
        }
        return result.size() > MAX_SUGGESTIONS ? result.subList(0, MAX_SUGGESTIONS) : result;
    }

    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    public void close() {
        executor.shutdownNow();
    }
}
