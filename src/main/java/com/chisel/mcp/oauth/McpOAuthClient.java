package com.chisel.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP OAuth 2.0 Authorization Code + PKCE 客户端。
 *
 * <p>流程（对齐 MCP 规范 2025-03-26）：</p>
 * <ol>
 *   <li>发现授权服务器元数据：从 {@code WWW-Authenticate} 挑战拿到 protected resource
 *       元数据（Bearer 挑战直接给 URL；MCP-OAuth 挑战去 {@code {baseUrl}/.well-known/oauth-protected-resource}），
 *       再读 {@code {authServer}/.well-known/oauth-authorization-server}（RFC 8414）拿
 *       authorization / token endpoint。</li>
 *   <li>PKCE：生成 code_verifier + S256 code_challenge，打开浏览器到授权页。</li>
 *   <li>本地 loopback 回调端口收 code，与 code_verifier 一起换 access/refresh token。</li>
 * </ol>
 *
 * <p>无浏览器环境（headless / Desktop 不可用）时打印授权 URL 并继续等本地回调，
 * 用户可手动粘贴授权结果。</p>
 */
public class McpOAuthClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String WELL_KNOWN_PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
    private static final String WELL_KNOWN_AUTH_SERVER = "/.well-known/oauth-authorization-server";
    private static final int CALLBACK_TIMEOUT_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String clientId;
    private final PrintStream out;
    private final OkHttpClient http;

    public McpOAuthClient(String clientId, PrintStream out) {
        this.clientId = clientId == null || clientId.isBlank() ? "chisel-cli" : clientId;
        this.out = out;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public record AuthorizationMetadata(String authorizationEndpoint, String tokenEndpoint) {
        public static AuthorizationMetadata fromAuthorizationServer(JsonNode metadata) {
            return new AuthorizationMetadata(
                    metadata.path("authorization_endpoint").asText(""),
                    metadata.path("token_endpoint").asText(""));
        }

        public boolean isValid() {
            return authorizationEndpoint != null && !authorizationEndpoint.isBlank()
                    && tokenEndpoint != null && !tokenEndpoint.isBlank();
        }
    }

    public record OAuthResult(String accessToken, String refreshToken, long expiresInSeconds, String scope) {}

    /**
     * 根据挑战发现授权服务器元数据。
     *
     * @param baseUrl   MCP server 的 base URL
     * @param challenge 解析后的挑战；{@code null} 时按 MCP-OAuth 路径探测
     */
    public AuthorizationMetadata discover(String baseUrl, OAuthChallenge challenge) throws IOException {
        String protectedResourceUrl;
        if (challenge != null && challenge.isBearer() && challenge.resourceMetadataUrl() != null) {
            protectedResourceUrl = challenge.resourceMetadataUrl();
        } else {
            protectedResourceUrl = baseUrl + WELL_KNOWN_PROTECTED_RESOURCE;
        }
        JsonNode protectedResource = fetchJson(protectedResourceUrl);
        String authServer = firstText(protectedResource, "authorization_servers", 0);
        if (authServer == null || authServer.isBlank()) {
            throw new IOException("OAuth protected resource 元数据缺少 authorization_servers: " + protectedResourceUrl);
        }
        JsonNode authServerMetadata = fetchJson(trimTrailingSlash(authServer) + WELL_KNOWN_AUTH_SERVER);
        AuthorizationMetadata metadata = AuthorizationMetadata.fromAuthorizationServer(authServerMetadata);
        if (!metadata.isValid()) {
            throw new IOException("授权服务器元数据缺少 authorization_endpoint / token_endpoint: " + authServer);
        }
        return metadata;
    }

    /**
     * 执行完整授权码 + PKCE 流程，返回 token。
     */
    public OAuthResult authorize(AuthorizationMetadata metadata) throws IOException {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = s256Challenge(codeVerifier);
        String state = randomBase64Url(16);

        HttpServer callback = startCallbackServer();
        String redirectUri = "http://127.0.0.1:" + callback.getAddress().getPort() + "/callback";

        String authorizationUrl = metadata.authorizationEndpoint()
                + (metadata.authorizationEndpoint().contains("?") ? "&" : "?")
                + "response_type=code&client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256"
                + "&state=" + state;

        openBrowser(authorizationUrl);

        try {
            String code = waitForCode(state);
            return exchangeCode(metadata.tokenEndpoint(), code, redirectUri, codeVerifier);
        } finally {
            callback.stop(0);
        }
    }

    /**
     * 用 refresh_token 换取新 access token。
     */
    public OAuthResult refresh(String tokenEndpoint, String refreshToken) throws IOException {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IOException("没有可用的 refresh_token");
        }
        FormBody form = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build();
        JsonNode response = postForm(tokenEndpoint, form);
        return parseTokenResponse(response);
    }

    private OAuthResult exchangeCode(String tokenEndpoint, String code, String redirectUri, String codeVerifier)
            throws IOException {
        FormBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("code_verifier", codeVerifier)
                .build();
        JsonNode response = postForm(tokenEndpoint, form);
        return parseTokenResponse(response);
    }

    private OAuthResult parseTokenResponse(JsonNode response) throws IOException {
        String accessToken = response.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new IOException("token endpoint 未返回 access_token: " + response);
        }
        long expiresIn = response.path("expires_in").asLong(3600);
        return new OAuthResult(
                accessToken,
                response.path("refresh_token").asText(""),
                expiresIn,
                response.path("scope").asText(""));
    }

    private JsonNode postForm(String url, FormBody form) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(form)
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("token endpoint 返回 HTTP " + response.code() + ": " + response.message());
            }
            String body = response.body() == null ? "" : response.body().string();
            if (body.isBlank()) {
                throw new IOException("token endpoint 返回空响应");
            }
            return MAPPER.readTree(body);
        }
    }

    private JsonNode fetchJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OAuth 元数据请求失败 HTTP " + response.code() + ": " + url);
            }
            String body = response.body() == null ? "" : response.body().string();
            if (body.isBlank()) {
                throw new IOException("OAuth 元数据请求返回空响应: " + url);
            }
            return MAPPER.readTree(body);
        }
    }

    private HttpServer startCallbackServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", this::handleCallback);
        server.start();
        return server;
    }

    private void handleCallback(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        String code = params.get("code");
        String state = params.get("state");
        String body;
        int status;
        if (code != null && !code.isBlank()) {
            callbacks.complete(new CallbackResult(code, state));
            body = "<html><body><h2>授权成功</h2><p>可以关闭此窗口，回到 ChiselCLI。</p></body></html>";
            status = 200;
        } else {
            body = "<html><body><h2>授权失败</h2><p>未收到授权码。</p></body></html>";
            status = 400;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    private final CompletableFuture<CallbackResult> callbacks = new CompletableFuture<>();

    private String waitForCode(String expectedState) throws IOException {
        CallbackResult result;
        try {
            result = callbacks.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("等待 OAuth 授权回调超时（" + CALLBACK_TIMEOUT_SECONDS + "s）", e);
        }
        if (result.state() == null || !result.state().equals(expectedState)) {
            throw new IOException("OAuth 回调 state 不匹配，可能被 CSRF 攻击");
        }
        return result.code();
    }

    private record CallbackResult(String code, String state) {}

    private void openBrowser(String url) {
        if (out != null) {
            out.println();
            out.println("🔐 请在浏览器中完成 MCP OAuth 授权：");
            out.println("   " + url);
            out.println("   等待授权回调（本机 loopback 端口，最长 " + CALLBACK_TIMEOUT_SECONDS + " 秒）...");
            out.flush();
        }
        boolean opened = false;
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url));
                    opened = true;
                }
            }
        } catch (Exception ignored) {
            // 无桌面环境，fall through 到命令行打开
        }
        if (!opened) {
            tryOpenCommand("open", url);
            tryOpenCommand("xdg-open", url);
            tryOpenCommand("rundll32", "url.dll,FileProtocolHandler " + url);
        }
    }

    private static void tryOpenCommand(String command, String url) {
        try {
            new ProcessBuilder(command, url).start();
        } catch (IOException ignored) {
            // 命令不存在时静默，用户手动打开打印出的 URL
        }
    }

    // ---- PKCE helpers ----

    static String generateCodeVerifier() {
        return randomBase64Url(48);
    }

    static String s256Challenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String randomBase64Url(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static String firstText(JsonNode node, String field, int index) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray() && value.size() > index) {
            return value.get(index).asText("");
        }
        return "";
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
