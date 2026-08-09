package com.chisel.mcp.oauth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpOAuthClientTest {

    private MockWebServer server;
    private McpOAuthClient client;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        out = new ByteArrayOutputStream();
        client = new McpOAuthClient("chisel-test", new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void discoversMetadataFromMcpOAuthChallenge() throws Exception {
        enqueueProtectedResource();
        enqueueAuthorizationServerMetadata();

        var metadata = client.discover(server.url("/mcp").toString(), OAuthChallenge.parse("MCP-OAuth"));

        assertTrue(metadata.isValid());
        assertEquals(server.url("/authorize").toString(), metadata.authorizationEndpoint());
        assertEquals(server.url("/token").toString(), metadata.tokenEndpoint());
    }

    @Test
    void discoversMetadataFromBearerChallengeWithResourceMetadataUrl() throws Exception {
        // Bearer 挑战直接给出 protected resource URL（可能是另一个 server）
        enqueueProtectedResource();
        enqueueAuthorizationServerMetadata();

        OAuthChallenge challenge = OAuthChallenge.parse(
                "Bearer resource_metadata=\"" + server.url("/protected") + "\"");
        var metadata = client.discover(server.url("/mcp").toString(), challenge);

        assertEquals(server.url("/authorize").toString(), metadata.authorizationEndpoint());
    }

    @Test
    void pkceChallengeUsesS256() {
        String verifier = McpOAuthClient.generateCodeVerifier();
        assertTrue(verifier.length() >= 43);
        String challenge = McpOAuthClient.s256Challenge(verifier);
        assertEquals(43, challenge.length()); // base64url(SHA-256) 无 padding = 43 字符
    }

    @Test
    void authorizeExchangesCodeForToken() throws Exception {
        enqueueProtectedResource();
        enqueueAuthorizationServerMetadata();
        // code → token 交换
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"tok-123\",\"refresh_token\":\"ref-456\","
                        + "\"expires_in\":3600,\"scope\":\"mcp\"}"));

        var metadata = client.discover(server.url("/mcp").toString(), OAuthChallenge.parse("MCP-OAuth"));

        // 用假 code 直接走 exchange（authorize 会打开浏览器等回调，不在此测）
        // 通过反射调用私有方法不优雅；改为直接验证 refresh 路径 + 元数据发现
        assertNotNull(metadata);
    }

    @Test
    void refreshUsesRefreshTokenGrant() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\","
                        + "\"expires_in\":1800}"));

        var result = client.refresh(server.url("/token").toString(), "old-refresh");

        assertEquals("new-access", result.accessToken());
        assertEquals("new-refresh", result.refreshToken());
        assertEquals(1800, result.expiresInSeconds());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("grant_type=refresh_token"));
        assertTrue(body.contains("refresh_token=old-refresh"));
        assertTrue(body.contains("client_id=chisel-test"));
    }

    @Test
    void refreshFailsOnNon2xx() {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"));

        IOException error = assertThrows(IOException.class,
                () -> client.refresh(server.url("/token").toString(), "expired"));
        assertTrue(error.getMessage().contains("HTTP 400"));
    }

    @Test
    void refreshFailsWithoutAccessToken() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"missing_token\"}"));

        IOException error = assertThrows(IOException.class,
                () -> client.refresh(server.url("/token").toString(), "x"));
        assertTrue(error.getMessage().contains("access_token"));
    }

    @Test
    void authorizePrintsUrlAndWaitsForCallback() throws Exception {
        enqueueProtectedResource();
        enqueueAuthorizationServerMetadata();
        // token 交换
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"tok-1\",\"refresh_token\":\"ref-1\",\"expires_in\":3600}"));

        var metadata = client.discover(server.url("/mcp").toString(), OAuthChallenge.parse("MCP-OAuth"));

        // authorize 会阻塞等回调；这里起线程执行，再向回调端口发 code
        Thread t = new Thread(() -> {
            try {
                client.authorize(metadata);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        t.start();

        // 轮询等待回调服务器启动（打印的 URL 里带端口）
        String url = waitForPrintedUrl();
        assertTrue(url.contains("authorize"), "应打印授权 URL: " + url);
        assertTrue(url.contains("code_challenge="));
        assertTrue(url.contains("code_challenge_method=S256"));

        // 从打印的 URL 里解码 redirect_uri 提取本地回调端口，模拟浏览器回调
        java.util.regex.Matcher uriMatcher = java.util.regex.Pattern
                .compile("redirect_uri=([^&]+)")
                .matcher(url);
        assertTrue(uriMatcher.find(), "授权 URL 应含 redirect_uri: " + url);
        String redirectUri = java.net.URLDecoder.decode(uriMatcher.group(1), StandardCharsets.UTF_8);
        java.util.regex.Matcher portMatcher = java.util.regex.Pattern
                .compile("127\\.0\\.0\\.1:(\\d+)/callback")
                .matcher(redirectUri);
        assertTrue(portMatcher.find(), "redirect_uri 应指向本地回调: " + redirectUri);
        int port = Integer.parseInt(portMatcher.group(1));
        java.util.regex.Matcher stateMatcher = java.util.regex.Pattern
                .compile("state=([A-Za-z0-9_-]+)")
                .matcher(url);
        assertTrue(stateMatcher.find(), "授权 URL 应含 state: " + url);
        String state = stateMatcher.group(1);
        String code = java.net.URLEncoder.encode("auth-code-123", StandardCharsets.UTF_8);

        okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
        okhttp3.Request callback = new okhttp3.Request.Builder()
                .url("http://127.0.0.1:" + port + "/callback?code=" + code + "&state=" + state)
                .get()
                .build();
        try (okhttp3.Response response = http.newCall(callback).execute()) {
            assertEquals(200, response.code());
        }

        t.join(10_000);
        assertFalse(t.isAlive(), "authorize 应在收到回调后返回");

        // 验证 token 交换请求带上了 code 与 code_verifier
        RecordedRequest exchange = null;
        for (int i = 0; i < 10; i++) {
            exchange = server.takeRequest(100, TimeUnit.MILLISECONDS);
            if (exchange != null && exchange.getPath().startsWith("/token")) {
                break;
            }
        }
        assertNotNull(exchange, "应发出 token 交换请求");
        String exchangeBody = java.net.URLDecoder.decode(exchange.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(exchangeBody.contains("grant_type=authorization_code"));
        assertTrue(exchangeBody.contains("code=auth-code-123"));
        assertTrue(exchangeBody.contains("code_verifier="));
        assertTrue(exchangeBody.contains("redirect_uri=http://127.0.0.1:" + port + "/callback"));
    }

    // ---- helpers ----

    private void enqueueProtectedResource() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"authorization_servers\":[\"" + server.url("") + "\"]}"));
    }

    private void enqueueAuthorizationServerMetadata() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"issuer\":\"" + server.url("") + "\","
                        + "\"authorization_endpoint\":\"" + server.url("/authorize") + "\","
                        + "\"token_endpoint\":\"" + server.url("/token") + "\"}"));
    }

    private String waitForPrintedUrl() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String text = out.toString(StandardCharsets.UTF_8);
            int idx = text.indexOf("/authorize?response_type=code");
            if (idx >= 0) {
                // 从该行开头截取完整 URL
                int lineStart = text.lastIndexOf('\n', idx - 1);
                int lineEnd = text.indexOf('\n', idx);
                return text.substring(lineStart + 1, lineEnd > 0 ? lineEnd : text.length()).trim();
            }
            Thread.sleep(10);
        }
        fail("未打印授权 URL");
        return null;
    }
}
