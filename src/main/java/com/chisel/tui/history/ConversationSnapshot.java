package com.chisel.tui.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 对话历史快照（TUI 专用）。
 *
 * <p>职责：
 * - 持久化对话历史到 ~/.chisel/history/ 目录
 * - 支持按会话 ID 查询、列出、删除
 * - 每个会话一个 JSONL 文件（每行一条消息）
 *
 * <p>TUI 会话控制器会把用户输入、系统提示和 Agent 输出追加到当前快照。
 */
public class ConversationSnapshot {

    /** 会话目录：默认 ~/.chisel/history，可用 -Dchisel.session.dir 或 CHISEL_SESSION_DIR 覆盖（测试隔离用）。 */
    private static final Path HISTORY_DIR = resolveHistoryDir();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private static Path resolveHistoryDir() {
        String fromProp = System.getProperty("chisel.session.dir");
        if (fromProp != null && !fromProp.isBlank()) {
            return Path.of(fromProp);
        }
        String fromEnv = System.getenv("CHISEL_SESSION_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return Path.of(System.getProperty("user.home"), ".chisel", "history");
    }

    /**
     * 消息记录。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageRecord(
            String role,        // "user" | "assistant" | "tool"
            String content,
            long timestamp,
            Map<String, Object> metadata
    ) {
        public static MessageRecord of(String role, String content) {
            return new MessageRecord(role, content, System.currentTimeMillis(), Map.of());
        }

        public static MessageRecord of(String role, String content, Map<String, Object> metadata) {
            return new MessageRecord(role, content, System.currentTimeMillis(), metadata);
        }
    }

    /**
     * 会话元数据。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionMeta(
            String sessionId,
            String title,          // 会话标题（首条用户消息摘要）
            long createdAt,
            long lastActiveAt,
            int messageCount
    ) {}

    private final String sessionId;
    private final Path sessionFile;
    private final List<MessageRecord> messages;

    /**
     * 创建新会话快照。
     */
    public ConversationSnapshot(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.sessionFile = HISTORY_DIR.resolve(sessionId + ".jsonl");
        this.messages = new ArrayList<>();
    }

    /**
     * 追加消息。
     */
    public void append(MessageRecord message) {
        messages.add(message);
    }

    /**
     * 追加用户消息。
     */
    public void appendUser(String content) {
        append(MessageRecord.of("user", content));
    }

    /**
     * 追加 Assistant 消息。
     */
    public void appendAssistant(String content) {
        append(MessageRecord.of("assistant", content));
    }

    /**
     * 获取所有消息。
     */
    public List<MessageRecord> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 获取会话 ID。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 保存到文件（追加模式写入 JSONL）。
     * 注意：不能用 MAPPER.writeValue(writer, ...)（Jackson 会关闭 writer，
     * 导致多条消息循环写入时报 Stream closed），改为 writeValueAsString 手动写。
     */
    public void save() throws IOException {
        Files.createDirectories(HISTORY_DIR);
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (MessageRecord msg : messages) {
                writer.write(MAPPER.writeValueAsString(msg));
                writer.newLine();
            }
        }
        messages.clear();
    }

    /**
     * 从文件加载会话（Day 5 完整实现）。
     */
    public static ConversationSnapshot load(String sessionId) throws IOException {
        Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return new ConversationSnapshot(sessionId);
        }

        ConversationSnapshot snapshot = new ConversationSnapshot(sessionId);
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                MessageRecord msg = MAPPER.readValue(line, MessageRecord.class);
                snapshot.messages.add(msg);
            }
        }
        return snapshot;
    }

    /**
     * 列出所有会话（按最近活跃排序）。
     */
    public static List<SessionMeta> listSessions() throws IOException {
        if (!Files.isDirectory(HISTORY_DIR)) {
            return List.of();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(HISTORY_DIR, "*.jsonl")) {
            return StreamSupport.stream(stream.spliterator(), false)
                    .map(path -> {
                        String sessionId = path.getFileName().toString().replace(".jsonl", "");
                        try {
                            long lastModified = Files.getLastModifiedTime(path).toMillis();
                            long createdAt = lastModified;
                            int count = 0;
                            String title = "";
                            try (BufferedReader reader = Files.newBufferedReader(path)) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.trim().isEmpty()) continue;
                                    count++;
                                    if (title.isEmpty()) {
                                        MessageRecord msg = MAPPER.readValue(line, MessageRecord.class);
                                        if ("user".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                                            title = msg.content().replace("\n", " ").trim();
                                            createdAt = msg.timestamp();
                                            if (title.length() > 40) {
                                                title = title.substring(0, 40) + "...";
                                            }
                                        }
                                    }
                                }
                            }
                            if (title.isEmpty()) {
                                title = "会话 " + sessionId.substring(0, 8);
                            }
                            return new SessionMeta(sessionId, title, createdAt, lastModified, count);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong((SessionMeta m) -> m.lastActiveAt).reversed())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 删除会话。
     */
    public static void deleteSession(String sessionId) throws IOException {
        Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");
        Files.deleteIfExists(file);
    }

    /**
     * 获取当前会话 ID（时间戳）。
     */
    public static String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + SESSION_SEQUENCE.incrementAndGet();
    }
}
