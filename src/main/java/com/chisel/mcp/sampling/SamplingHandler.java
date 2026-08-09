package com.chisel.mcp.sampling;

import com.chisel.llm.LlmClient;
import com.chisel.policy.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 处理 server → client 的 {@code sampling/createMessage} JSON-RPC 请求。
 *
 * <p>MCP server 声明 {@code sampling} capability 后，可请求 client 用其 LLM 生成回复
 * （server 自身没有模型）。ChiselCLI 复用当前 {@link ChiselConfig} 默认 provider 的
 * LLM client 完成生成，并把结果作为 JSON-RPC 响应返回给 server。</p>
 *
 * <p>安全模型：sampling 是「外部 server 花 ChiselCLI 的 API 额度」——终端默认允许但审计
 * （server 只能发起文本生成，无法访问本地工具）；微信等非交互通道由上层策略默认拒绝
 * （见 {@code WechatPolicyDecider}）。server 请求里带 {@code toolCall} 字段时直接拒绝，
 * 本期不做 tool-use 执行。</p>
 */
public class SamplingHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<LlmClient> llmSupplier;
    private final AuditLog auditLog;

    public SamplingHandler(Supplier<LlmClient> llmSupplier, AuditLog auditLog) {
        this.llmSupplier = llmSupplier;
        this.auditLog = auditLog;
    }

    /**
     * 处理 sampling/createMessage 请求体，返回应作为 JSON-RPC result 的 JsonNode。
     * 无法处理时抛 IOException，由调用方转成 JSON-RPC error。
     */
    public JsonNode handle(JsonNode params) throws IOException {
        long start = System.nanoTime();
        String args = params == null ? "{}" : params.toString();
        if (params == null) {
            throw new IOException("sampling/createMessage 缺少 params");
        }
        JsonNode toolCall = params.path("toolCall");
        if (!toolCall.isNull() && !toolCall.isMissingNode()) {
            auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                    "sampling/createMessage", args,
                    "server 请求带 toolCall 字段，ChiselCLI 不执行 sampling 工具调用", elapsedMillis(start)));
            throw new IOException("sampling toolCall 不支持：ChiselCLI 不会执行 server 请求的工具调用");
        }

        LlmClient llm = llmSupplier == null ? null : llmSupplier.get();
        if (llm == null) {
            auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                    "sampling/createMessage", args,
                    "没有可用的 LLM client（未配置 API Key）", elapsedMillis(start)));
            throw new IOException("ChiselCLI 未配置可用的 LLM client，无法响应 sampling 请求");
        }

        List<LlmClient.Message> messages = toLlmMessages(params.path("messages"));
        if (messages.isEmpty()) {
            throw new IOException("sampling/createMessage 缺少 messages");
        }
        String systemPrompt = params.path("systemPrompt").asText("");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(0, LlmClient.Message.system(systemPrompt));
        }

        LlmClient.ChatResponse response = llm.chat(messages, List.of());

        auditLog.record(AuditLog.AuditEntry.allow(
                "sampling/createMessage", args, elapsedMillis(start)));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("role", "assistant");
        ObjectNode content = result.putObject("content");
        content.put("type", "text");
        content.put("text", response.content() == null ? "" : response.content());
        result.put("model", llm.getModelName());
        result.put("stopReason", "endTurn");
        return result;
    }

    private List<LlmClient.Message> toLlmMessages(JsonNode messagesNode) {
        List<LlmClient.Message> messages = new ArrayList<>();
        if (messagesNode == null || !messagesNode.isArray()) {
            return messages;
        }
        for (JsonNode node : messagesNode) {
            String role = node.path("role").asText("");
            if (role.isBlank()) {
                continue;
            }
            JsonNode content = node.path("content");
            String text = content.isTextual()
                    ? content.asText()
                    : content.path("text").asText("");
            if (text == null || text.isBlank()) {
                continue;
            }
            messages.add(new LlmClient.Message(role, text));
        }
        return messages;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
