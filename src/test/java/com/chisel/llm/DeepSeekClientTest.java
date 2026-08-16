package com.chisel.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekClientTest {

    @Test
    void appendsChatCompletionsToBaseUrl() {
        assertEquals("https://opencode.ai/zen/go/v1/chat/completions",
                DeepSeekClient.toChatCompletionsUrl("https://opencode.ai/zen/go/v1"));
    }

    @Test
    void keepsCompleteUrlAsIs() {
        assertEquals("https://api.deepseek.com/chat/completions",
                DeepSeekClient.toChatCompletionsUrl("https://api.deepseek.com/chat/completions"));
    }

    @Test
    void trimsTrailingSlash() {
        assertEquals("https://example.com/v1/chat/completions",
                DeepSeekClient.toChatCompletionsUrl("https://example.com/v1/"));
    }

    @Test
    void fallsBackToOfficialUrlWhenBlank() {
        assertEquals("https://api.deepseek.com/chat/completions",
                DeepSeekClient.toChatCompletionsUrl(null));
        assertEquals("https://api.deepseek.com/chat/completions",
                DeepSeekClient.toChatCompletionsUrl("   "));
    }

    @Test
    void officialBaseUrlGetsChatCompletionsAppended() {
        assertEquals("https://api.deepseek.com/chat/completions",
                DeepSeekClient.toChatCompletionsUrl("https://api.deepseek.com"));
    }
}
