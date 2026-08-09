package com.chisel.mcp.oauth;

/**
 * 解析 HTTP 响应里的 {@code WWW-Authenticate} 挑战头。
 *
 * <p>MCP 规范（2025-03-26）定义了两种需要 OAuth 的挑战：</p>
 * <ul>
 *   <li>{@code WWW-Authenticate: MCP-OAuth} — 简单形式，客户端需要去
 *       {@code {baseUrl}/.well-known/oauth-protected-resource} 发现授权服务器元数据</li>
 *   <li>{@code WWW-Authenticate: Bearer resource_metadata="..."} — 带 resource metadata
 *       URL，客户端直接读该 URL 获取 protected resource 元数据（RFC 8707 resource 扩展）</li>
 * </ul>
 *
 * <p>本类只负责解析挑战头本身；发现授权服务器元数据、PKCE 与 token 交换在
 * {@link McpOAuthClient} 里完成。</p>
 */
public final class OAuthChallenge {

    private final String scheme;
    private final String resourceMetadataUrl;

    private OAuthChallenge(String scheme, String resourceMetadataUrl) {
        this.scheme = scheme;
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    /** 从响应头解析挑战；没有可识别的 OAuth 挑战时返回 {@code null}。 */
    public static OAuthChallenge parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String trimmed = headerValue.trim();
        if (trimmed.startsWith("MCP-OAuth")) {
            return new OAuthChallenge("MCP-OAuth", null);
        }
        if (trimmed.startsWith("Bearer")) {
            String rest = trimmed.substring("Bearer".length()).trim();
            String metadata = extractQuotedAttribute(rest, "resource_metadata");
            if (metadata != null) {
                return new OAuthChallenge("Bearer", metadata);
            }
        }
        return null;
    }

    /** 解析形如 {@code key="value"} 的 quoted 属性；找不到返回 {@code null}。 */
    static String extractQuotedAttribute(String header, String key) {
        if (header == null || key == null) {
            return null;
        }
        int idx = 0;
        while (idx < header.length()) {
            int equals = header.indexOf('=', idx);
            if (equals < 0) {
                return null;
            }
            String name = header.substring(idx, equals).trim();
            // 跳过前导逗号（"realm=.., foo=.." 里第二个属性的 name 会带 ", " 前缀）
            name = name.replaceFirst("^,+", "").trim();
            int quoteStart = equals + 1;
            while (quoteStart < header.length() && header.charAt(quoteStart) == ' ') {
                quoteStart++;
            }
            if (quoteStart >= header.length() || header.charAt(quoteStart) != '"') {
                idx = equals + 1;
                continue;
            }
            int quoteEnd = header.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) {
                return null;
            }
            if (name.equals(key)) {
                return header.substring(quoteStart + 1, quoteEnd);
            }
            idx = quoteEnd + 1;
        }
        return null;
    }

    public boolean isMcpOAuth() {
        return "MCP-OAuth".equals(scheme);
    }

    public boolean isBearer() {
        return "Bearer".equals(scheme);
    }

    /** Bearer 挑战里的 resource metadata URL；MCP-OAuth 挑战为 {@code null}。 */
    public String resourceMetadataUrl() {
        return resourceMetadataUrl;
    }
}
