package com.chisel.mcp.oauth;

import java.io.IOException;

/**
 * MCP server 返回 401 + OAuth 挑战，且无法用现有 refresh token 自动解决。
 *
 * <p>抛出场景：transport 层遇 401 且 refresh 失败（没有 refresh token / 刷新被拒）。
 * 由 {@code McpServerManager} 捕获后走完整 OAuth 授权码 + PKCE 浏览器流程。
 * 携带 baseUrl 与挑战，供上层发现授权服务器元数据。</p>
 */
public class OAuthRequiredException extends IOException {
    private final String baseUrl;
    private final OAuthChallenge challenge;

    public OAuthRequiredException(String baseUrl, OAuthChallenge challenge) {
        super("MCP server 需要 OAuth 授权 (HTTP 401): " + baseUrl);
        this.baseUrl = baseUrl;
        this.challenge = challenge;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public OAuthChallenge challenge() {
        return challenge;
    }
}
