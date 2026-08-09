package com.chisel.mcp.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthChallengeTest {

    @Test
    void parsesMcpOAuthChallenge() {
        OAuthChallenge challenge = OAuthChallenge.parse("MCP-OAuth");
        assertNotNull(challenge);
        assertTrue(challenge.isMcpOAuth());
        assertFalse(challenge.isBearer());
        assertNull(challenge.resourceMetadataUrl());
    }

    @Test
    void parsesBearerChallengeWithResourceMetadata() {
        OAuthChallenge challenge = OAuthChallenge.parse(
                "Bearer resource_metadata=\"https://auth.example.com/.well-known/oauth-protected-resource\"");
        assertNotNull(challenge);
        assertTrue(challenge.isBearer());
        assertEquals("https://auth.example.com/.well-known/oauth-protected-resource",
                challenge.resourceMetadataUrl());
    }

    @Test
    void returnsNullForUnrelatedChallenge() {
        assertNull(OAuthChallenge.parse("Basic realm=\"x\""));
        assertNull(OAuthChallenge.parse(""));
        assertNull(OAuthChallenge.parse(null));
        assertNull(OAuthChallenge.parse("Bearer"));
        assertNull(OAuthChallenge.parse("Bearer error=\"invalid_token\""));
    }

    @Test
    void extractsQuotedAttributeWithOtherAttributes() {
        assertEquals("v1",
                OAuthChallenge.extractQuotedAttribute("realm=\"x\", foo=\"v1\", bar=\"v2\"", "foo"));
        assertEquals("v2",
                OAuthChallenge.extractQuotedAttribute("realm=\"x\", foo=\"v1\", bar=\"v2\"", "bar"));
        assertNull(OAuthChallenge.extractQuotedAttribute("realm=\"x\", foo=\"v1\"", "missing"));
    }
}
