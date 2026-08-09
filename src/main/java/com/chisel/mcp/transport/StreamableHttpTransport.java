package com.chisel.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chisel.mcp.oauth.McpOAuthClient;
import com.chisel.mcp.oauth.OAuthChallenge;
import com.chisel.mcp.oauth.OAuthRequiredException;
import com.chisel.mcp.oauth.OAuthTokenStore;
import com.chisel.mcp.protocol.McpInitializeRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String url;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final OAuthTokenStore tokenStore;
    private final String serverName;
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this(url, headers, null, null);
    }

    /**
     * @param url        MCP server URL
     * @param headers    静态请求头（用户配置）
     * @param tokenStore OAuth token 存储；为 null 时不做 OAuth（普通 HTTP server）
     * @param serverName server 名（token store 的 key）
     */
    public StreamableHttpTransport(String url, Map<String, String> headers,
                                   OAuthTokenStore tokenStore, String serverName) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.tokenStore = tokenStore;
        this.serverName = serverName;
    }

    @Override
    public void send(JsonNode message) throws IOException {
        Request request = buildRequest(message, accessToken());
        Response response = client.newCall(request).execute();
        if (response.code() == 401 && tokenStore != null) {
            // 尝试用 refresh token 秒级刷新后重试一次；失败上抛 OAuthRequiredException
            OAuthChallenge challenge = OAuthChallenge.parse(response.header("WWW-Authenticate"));
            String refreshed = challenge == null ? null : tryRefresh(challenge);
            response.close();
            if (refreshed != null) {
                request = buildRequest(message, refreshed);
                response = client.newCall(request).execute();
            } else {
                throw new OAuthRequiredException(url, challenge == null
                        ? OAuthChallenge.parse("MCP-OAuth")
                        : challenge);
            }
        }
        try {
            handleResponse(response);
        } finally {
            response.close();
        }
    }

    private void handleResponse(Response response) throws IOException {
        String newSession = response.header("Mcp-Session-Id");
        if (newSession != null && !newSession.isBlank()) {
            sessionId = newSession;
        }
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code() + " " + response.message());
        }
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            return;
        }
        String contentType = response.header("Content-Type", "");
        String raw = responseBody.string();
        // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
        // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<JsonNode> messages = contentType.contains("text/event-stream")
                ? parseSse(raw)
                : List.of(MAPPER.readTree(raw));
        for (JsonNode node : messages) {
            for (Consumer<JsonNode> listener : listeners) {
                listener.accept(node);
            }
        }
    }

    private Request buildRequest(JsonNode message, String accessToken) throws IOException {
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(body);
        headers.forEach(builder::header);
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return builder.build();
    }

    private String accessToken() {
        if (tokenStore == null || serverName == null) {
            return null;
        }
        var token = tokenStore.get(serverName);
        return token == null ? null : token.accessToken();
    }

    /** 用 refresh token 换新 access token；失败返回 null（上层做完整授权）。 */
    private String tryRefresh(OAuthChallenge challenge) {
        if (serverName == null) {
            return null;
        }
        var stored = tokenStore.get(serverName);
        if (stored == null || !stored.hasRefreshToken()) {
            return null;
        }
        try {
            McpOAuthClient oauth = new McpOAuthClient(null, null);
            var metadata = oauth.discover(url, challenge);
            var result = oauth.refresh(metadata.tokenEndpoint(), stored.refreshToken());
            tokenStore.updateRefreshToken(serverName,
                    result.accessToken(), result.refreshToken(),
                    result.expiresInSeconds(), result.scope());
            return result.accessToken();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .delete();
        headers.forEach(builder::header);
        // close 是 best-effort：server 已经关停 / 网络不通时不应该让 ChiselCLI 退出卡住。
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求。
        OkHttpClient closeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        try (Response ignored = closeClient.newCall(builder.build()).execute()) {
            // best effort
        } catch (IOException ignored) {
        }
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }
}
