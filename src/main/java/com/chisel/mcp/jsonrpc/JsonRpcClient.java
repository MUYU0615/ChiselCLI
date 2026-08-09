package com.chisel.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chisel.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chisel-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<RequestHandler> requestHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ExecutorService requestDispatcher = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "chisel-mcp-server-request");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * server → client 的 JSON-RPC 请求处理器（如 sampling/createMessage）。
     * 返回 {@code null} 表示不处理；返回 {@link RequestResult} 表示响应；
     * 返回 {@link RequestResult#error} 表示拒绝。
     */
    public interface RequestHandler {
        RequestResult handle(String method, JsonNode params);
    }

    /**
     * server → client 请求的处理结果。
     *
     * @param result 成功时的 result 节点（为 null 时返回空对象）
     * @param error  拒绝时的 error 消息（非 null 时以 JSON-RPC error 响应）
     */
    public record RequestResult(JsonNode result, String error) {
        public static RequestResult of(JsonNode result) {
            return new RequestResult(result, null);
        }

        public static RequestResult error(String message) {
            return new RequestResult(null, message);
        }
    }

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            transport.send(request);
            return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    /**
     * 注册 server → client 请求处理器。处理器按注册顺序调用，
     * 返回非 null 结果即停止。
     */
    public void onServerRequest(RequestHandler handler) {
        if (handler != null) {
            requestHandlers.add(handler);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            for (Consumer<JsonNode> listener : notificationListeners) {
                listener.accept(message);
            }
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) {
            JsonNode error = message.get("error");
            if (error != null && !error.isNull()) {
                future.completeExceptionally(new JsonRpcException(
                        error.path("code").asInt(-32603),
                        error.path("message").asText("JSON-RPC error")));
                return;
            }
            future.complete(message.get("result"));
            return;
        }
        // id 不在 pending 中 → server 发起的请求（sampling / roots 等），路由给 handler。
        // 必须在独立线程执行：handler 内部可能调 LLM / 发 JSON-RPC 响应，
        // 若在 transport reader 线程同步执行，会阻塞同 server 其他 pending 响应的读取。
        String method = message.path("method").asText("");
        if (method.isBlank()) {
            return;
        }
        JsonNode params = message.path("params");
        requestDispatcher.submit(() -> dispatchServerRequest(id, method, params));
    }

    private void dispatchServerRequest(long id, String method, JsonNode params) {
        for (RequestHandler handler : requestHandlers) {
            RequestResult result;
            try {
                result = handler.handle(method, params);
            } catch (Exception e) {
                sendResponse(id, null, "server request handler error: " + e.getMessage());
                return;
            }
            if (result != null) {
                sendResponse(id, result.result(), result.error());
                return;
            }
        }
        // 没有 handler 处理该请求：返回 method not found
        sendResponse(id, null, "method not found: " + method);
    }

    private void sendResponse(long id, JsonNode result, String errorMessage) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        if (errorMessage != null && !errorMessage.isBlank()) {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", errorMessage);
        } else {
            response.set("result", result == null ? MAPPER.createObjectNode() : result);
        }
        try {
            transport.send(response);
        } catch (IOException ignored) {
            // best effort：响应发送失败（transport 已关）时忽略
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        requestDispatcher.shutdownNow();
        transport.close();
    }
}
