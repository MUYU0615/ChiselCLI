package com.chisel.mcp.sampling;

import com.chisel.llm.LlmClient;
import com.chisel.policy.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SamplingHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditLog auditLog(@TempDir Path tempDir) {
        return new AuditLog(tempDir.resolve("audit.jsonl"));
    }

    @Test
    void convertsMessagesAndReturnsAssistantContent(@TempDir Path tempDir) throws Exception {
        LlmClient llm = mock(LlmClient.class);
        when(llm.getModelName()).thenReturn("test-model");
        when(llm.chat(anyList(), anyList())).thenReturn(new LlmClient.ChatResponse(
                "assistant", "你好，我是测试回复", null, List.of(), 10, 20));
        SamplingHandler handler = new SamplingHandler(() -> llm, auditLog(tempDir));

        JsonNode params = MAPPER.readTree("""
                {"messages":[{"role":"user","content":"帮我写个 hello world"}],
                 "systemPrompt":"你是测试助手"}
                """);
        JsonNode result = handler.handle(params);

        assertEquals("assistant", result.path("role").asText());
        assertEquals("text", result.path("content").path("type").asText());
        assertEquals("你好，我是测试回复", result.path("content").path("text").asText());
        assertEquals("test-model", result.path("model").asText());
        assertEquals("endTurn", result.path("stopReason").asText());

        // 验证 systemPrompt 前置注入
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture(), anyList());
        List<LlmClient.Message> messages = captor.getValue();
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("你是测试助手", messages.get(0).content());
        assertEquals("user", messages.get(1).role());
    }

    @Test
    void rejectsToolCallRequest(@TempDir Path tempDir) throws Exception {
        LlmClient llm = mock(LlmClient.class);
        SamplingHandler handler = new SamplingHandler(() -> llm, auditLog(tempDir));

        JsonNode params = MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}],
                 "toolCall":{"name":"someTool","arguments":{}}}
                """);
        Exception error = assertThrows(Exception.class, () -> handler.handle(params));
        assertTrue(error.getMessage().contains("toolCall"));
        verify(llm, never()).chat(anyList(), anyList());
    }

    @Test
    void rejectsWhenNoLlmAvailable(@TempDir Path tempDir) throws Exception {
        SamplingHandler handler = new SamplingHandler(() -> null, auditLog(tempDir));
        JsonNode params = MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}]}
                """);
        Exception error = assertThrows(Exception.class, () -> handler.handle(params));
        assertTrue(error.getMessage().contains("LLM"));
    }

    @Test
    void rejectsMissingMessages(@TempDir Path tempDir) throws Exception {
        LlmClient llm = mock(LlmClient.class);
        SamplingHandler handler = new SamplingHandler(() -> llm, auditLog(tempDir));
        JsonNode params = MAPPER.readTree("""
                {"systemPrompt":"no messages"}
                """);
        Exception error = assertThrows(Exception.class, () -> handler.handle(params));
        assertTrue(error.getMessage().contains("messages"));
    }

    @Test
    void handlesTextContentInArrayForm(@TempDir Path tempDir) throws Exception {
        LlmClient llm = mock(LlmClient.class);
        when(llm.getModelName()).thenReturn("m");
        when(llm.chat(anyList(), anyList())).thenReturn(new LlmClient.ChatResponse(
                "assistant", "ok", null, List.of(), 1, 1));
        SamplingHandler handler = new SamplingHandler(() -> llm, auditLog(tempDir));

        // MCP 规范允许 content 为 {type:"text",text:"..."} 对象
        JsonNode params = MAPPER.readTree("""
                {"messages":[{"role":"user","content":{"type":"text","text":"hi"}}]}
                """);
        JsonNode result = handler.handle(params);
        assertEquals("ok", result.path("content").path("text").asText());
    }

    @Test
    void nullParamsRejected(@TempDir Path tempDir) throws Exception {
        SamplingHandler handler = new SamplingHandler(() -> mock(LlmClient.class), auditLog(tempDir));
        assertThrows(Exception.class, () -> handler.handle(null));
    }
}
