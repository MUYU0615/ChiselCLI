package com.chisel.mcp.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenStoreTest {

    @Test
    void savesAndLoadsToken(@TempDir Path tempDir) {
        Path file = tempDir.resolve("oauth-tokens.json");
        OAuthTokenStore store = new OAuthTokenStore(file);
        store.save("demo", "access-1", "refresh-1", 3600, "mcp.read");

        OAuthTokenStore reloaded = new OAuthTokenStore(file);
        var token = reloaded.get("demo");
        assertNotNull(token);
        assertEquals("access-1", token.accessToken());
        assertEquals("refresh-1", token.refreshToken());
        assertTrue(reloaded.has("demo"));
    }

    @Test
    void expiredTokenDetected(@TempDir Path tempDir) {
        Path file = tempDir.resolve("oauth-tokens.json");
        OAuthTokenStore store = new OAuthTokenStore(file);
        store.save("demo", "access-1", "refresh-1", 1, null);

        assertFalse(store.isExpired("demo"));
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
        }
        assertTrue(store.isExpired("demo"), "1 秒后 token 应标记为过期");
    }

    @Test
    void updateRefreshKeepsExistingRefreshWhenNewOneAbsent(@TempDir Path tempDir) {
        Path file = tempDir.resolve("oauth-tokens.json");
        OAuthTokenStore store = new OAuthTokenStore(file);
        store.save("demo", "access-1", "refresh-original", 3600, null);

        // 刷新流程：token endpoint 有时不返回新 refresh_token，应保留旧的
        store.updateRefreshToken("demo", "access-2", "", 3600, null);

        var token = store.get("demo");
        assertEquals("access-2", token.accessToken());
        assertEquals("refresh-original", token.refreshToken());
    }

    @Test
    void removeDeletesToken(@TempDir Path tempDir) {
        Path file = tempDir.resolve("oauth-tokens.json");
        OAuthTokenStore store = new OAuthTokenStore(file);
        store.save("demo", "access-1", "refresh-1", 3600, null);
        assertTrue(store.has("demo"));

        store.remove("demo");
        assertFalse(store.has("demo"));
        assertNull(store.get("demo"));

        OAuthTokenStore reloaded = new OAuthTokenStore(file);
        assertFalse(reloaded.has("demo"));
    }

    @Test
    void corruptFileDoesNotBlockStartup(@TempDir Path tempDir) {
        Path file = tempDir.resolve("oauth-tokens.json");
        try {
            Files.writeString(file, "{not valid json");
        } catch (Exception e) {
            fail("写入损坏文件失败");
        }
        OAuthTokenStore store = new OAuthTokenStore(file);
        assertFalse(store.has("anything"));
        assertNull(store.get("anything"));
    }

    @Test
    void setsOwnerOnlyPermissions(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("oauth-tokens.json");
        OAuthTokenStore store = new OAuthTokenStore(file);
        store.save("demo", "secret-access", "secret-refresh", 3600, null);

        var permissions = Files.getPosixFilePermissions(file);
        assertTrue(permissions.stream().noneMatch(p -> p.name().startsWith("OTHERS")));
        assertTrue(permissions.stream().noneMatch(p -> p.name().startsWith("GROUP")));
    }
}
