package com.chisel.cli;

import com.chisel.agent.Agent;
import com.chisel.llm.LlmClient;
import com.chisel.tool.ToolRegistry;
import com.chisel.tui.history.ConversationSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话持久化 + /resume 恢复的端到端验证。
 * 用 stub LLM（无 API key）+ 隔离目录（chisel.session.dir）验证：
 * 落盘 → 列出 → 恢复 → 历史正确。
 */
class SessionResumeTest {

    @TempDir
    Path tempDir;

    private String previousSessionDir;

    @BeforeEach
    void setUp() {
        previousSessionDir = System.getProperty("chisel.session.dir");
        System.setProperty("chisel.session.dir", tempDir.resolve("sessions").toString());
    }

    @AfterEach
    void tearDown() {
        if (previousSessionDir == null) {
            System.clearProperty("chisel.session.dir");
        } else {
            System.setProperty("chisel.session.dir", previousSessionDir);
        }
    }

    private static String uniqueSessionId() {
        return "session_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void savesAndListsSessions() throws Exception {
        String sessionId = uniqueSessionId();
        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        snapshot.append(ConversationSnapshot.MessageRecord.of("user", "帮我看下项目结构"));
        snapshot.append(ConversationSnapshot.MessageRecord.of("assistant", "项目结构如下..."));
        snapshot.save();

        List<ConversationSnapshot.SessionMeta> sessions = ConversationSnapshot.listSessions();
        assertFalse(sessions.isEmpty());
        ConversationSnapshot.SessionMeta meta = sessions.stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow();
        assertTrue(meta.title().contains("帮我看下项目结构"), "标题应来自首条用户消息: " + meta.title());
        assertEquals(2, meta.messageCount());
    }

    @Test
    void loadsSavedSessionMessages() throws Exception {
        String sessionId = uniqueSessionId();
        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        snapshot.append(ConversationSnapshot.MessageRecord.of("user", "你好"));
        snapshot.append(ConversationSnapshot.MessageRecord.of(
                "tool", "工具结果",
                java.util.Map.of("toolCallId", "call_1")));
        snapshot.append(ConversationSnapshot.MessageRecord.of("assistant", "回复"));
        snapshot.save();

        ConversationSnapshot loaded = ConversationSnapshot.load(sessionId);
        assertEquals(3, loaded.getMessages().size());
        assertEquals("call_1", loaded.getMessages().get(1).metadata().get("toolCallId"));
    }

    @Test
    void resumeRestoresHistoryIntoAgent() throws Exception {
        String sessionId = uniqueSessionId();
        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        snapshot.append(ConversationSnapshot.MessageRecord.of("user", "第一期任务"));
        snapshot.append(ConversationSnapshot.MessageRecord.of("assistant", "第一期结果"));
        snapshot.save();

        // 新 Agent（模拟重启后的进程）
        Agent agent = new Agent(new StubLlmClient(), new ToolRegistry());
        List<ConversationSnapshot.SessionMeta> sessions = ConversationSnapshot.listSessions();
        String selected = sessions.stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow().sessionId();
        ConversationSnapshot loaded = ConversationSnapshot.load(selected);
        List<LlmClient.Message> restored = new ArrayList<>();
        for (ConversationSnapshot.MessageRecord record : loaded.getMessages()) {
            restored.add(new LlmClient.Message(record.role(), record.content()));
        }
        agent.restoreHistory(restored);

        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals("system", history.get(0).role(), "system prompt 应在第 0 条");
        assertTrue(history.stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("第一期任务")));
        assertTrue(history.stream().anyMatch(m -> "assistant".equals(m.role()) && m.content().contains("第一期结果")));
    }

    @Test
    void resumeCommandListsAndRestores() throws Exception {
        String sessionId = uniqueSessionId();
        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        snapshot.append(ConversationSnapshot.MessageRecord.of("user", "测试任务"));
        snapshot.save();

        Agent agent = new Agent(new StubLlmClient(), new ToolRegistry());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main.handleResumeCommand(new PrintStream(out, true, StandardCharsets.UTF_8), agent, "");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("测试任务"),
                "列表应显示会话标题: " + out);

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        Main.handleResumeCommand(new PrintStream(out2, true, StandardCharsets.UTF_8), agent, "1");
        String output = out2.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("已恢复会话"), "应提示恢复成功: " + output);
        assertTrue(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role()) && m.content().contains("测试任务")),
                "恢复后历史应包含会话消息");
    }

    @Test
    void resumeWithInvalidIndexShowsError() {
        Agent agent = new Agent(new StubLlmClient(), new ToolRegistry());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main.handleResumeCommand(new PrintStream(out, true, StandardCharsets.UTF_8), agent, "99");
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("超出范围") || output.contains("没有历史会话"),
                "无效序号应提示错误: " + output);
    }

    /** stub LLM：不调真实 API，返回固定回复。 */
    private static class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", "stub 回复", null, List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
