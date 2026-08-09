package com.chisel.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chisel.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pairsResponseByNumericId() throws Exception {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"result":{"ok":true}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonNode result = client.request("ping", MAPPER.createObjectNode(), 1);

        assertTrue(result.path("ok").asBoolean());
        assertTrue(transport.sent.get(0).path("id").isNumber());
    }

    @Test
    void mapsJsonRpcErrorToException() {
        LoopbackTransport transport = new LoopbackTransport("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"missing"}}
                """);
        JsonRpcClient client = new JsonRpcClient(transport);

        JsonRpcException error = assertThrows(JsonRpcException.class,
                () -> client.request("missing", MAPPER.createObjectNode(), 1));
        assertEquals(-32601, error.code());
    }

    @Test
    void routesServerRequestToHandlerAndResponds() throws Exception {
        // server 发起 sampling/createMessage 请求（带 id 但不在 pending 中）
        LoopbackTransport transport = new LoopbackTransport();
        JsonRpcClient client = new JsonRpcClient(transport);

        client.onServerRequest((method, params) -> {
            assertEquals("sampling/createMessage", method);
            assertTrue(params.path("messages").isArray());
            return JsonRpcClient.RequestResult.of(
                    MAPPER.createObjectNode().put("role", "assistant"));
        });

        transport.push("""
                {"jsonrpc":"2.0","id":99,"method":"sampling/createMessage","params":{"messages":[]}}
                """);

        JsonNode sent = awaitSent(transport);
        assertNotNull(sent);
        assertEquals(99, sent.path("id").asLong());
        assertEquals("assistant", sent.path("result").path("role").asText());
        assertTrue(sent.path("error").isMissingNode());
    }

    @Test
    void respondsMethodNotFoundWhenNoHandlerMatches() throws Exception {
        LoopbackTransport transport = new LoopbackTransport();
        JsonRpcClient client = new JsonRpcClient(transport);

        transport.push("""
                {"jsonrpc":"2.0","id":7,"method":"unknown/request"}
                """);

        assertEquals(7, awaitSent(transport).path("id").asLong());
        assertTrue(transport.sent.get(0).path("error").path("message").asText().contains("method not found"));
    }

    @Test
    void handlerErrorBecomesJsonRpcErrorResponse() throws Exception {
        LoopbackTransport transport = new LoopbackTransport();
        JsonRpcClient client = new JsonRpcClient(transport);

        client.onServerRequest((method, params) -> {
            throw new IllegalStateException("boom");
        });
        transport.push("""
                {"jsonrpc":"2.0","id":5,"method":"sampling/createMessage"}
                """);

        assertTrue(awaitSent(transport).path("error").path("message").asText().contains("boom"));
    }

    /** server request 响应是异步派发的，轮询等待 transport 发出响应。 */
    private static JsonNode awaitSent(LoopbackTransport transport) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (!transport.sent.isEmpty()) {
                return transport.sent.get(0);
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static final class LoopbackTransport implements McpTransport {
        private final java.util.List<String> responses = new java.util.ArrayList<>();
        private Consumer<JsonNode> listener;
        private final java.util.List<JsonNode> sent = new java.util.ArrayList<>();

        private LoopbackTransport(String... responses) {
            this.responses.addAll(java.util.Arrays.asList(responses));
        }

        @Override
        public synchronized void send(JsonNode message) throws IOException {
            sent.add(message);
            if (responses.isEmpty()) {
                return;
            }
            String response = responses.remove(0);
            if (response == null || response.isBlank()) {
                return;
            }
            listener.accept(MAPPER.readTree(response));
        }

        /** 手动向 listener 推送一条 server 消息（模拟 server 主动发来的请求）。 */
        void push(String json) {
            try {
                listener.accept(MAPPER.readTree(json));
            } catch (IOException ignored) {
                throw new IllegalStateException("bad push json", ignored);
            }
        }

        @Override
        public void onReceive(Consumer<JsonNode> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
        }
    }
}
