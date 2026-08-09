package com.chisel.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MCP OAuth token 持久化存储，位于 {@code ~/.chisel/mcp/oauth-tokens.json}。
 *
 * <p>按 server 名区分 token（access_token / refresh_token / 过期时间 / scope）。
 * 敏感字段（token 本体）不写入审计日志；文件权限收紧到 600（仅 owner 可读写）。</p>
 */
public class OAuthTokenStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600;

    private final Path file;
    private final Map<String, StoredToken> tokens = new LinkedHashMap<>();

    public OAuthTokenStore(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".chisel", "mcp", "oauth-tokens.json");
    }

    public synchronized StoredToken get(String serverName) {
        StoredToken token = tokens.get(serverName);
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
            return null;
        }
        return token;
    }

    public synchronized void save(String serverName, String accessToken, String refreshToken,
                                  long expiresInSeconds, String scope) {
        StoredToken token = new StoredToken(
                accessToken,
                refreshToken,
                Instant.now().plusSeconds(Math.max(1, expiresInSeconds)).toString(),
                scope);
        tokens.put(serverName, token);
        persist();
    }

    public synchronized void updateRefreshToken(String serverName, String accessToken, String refreshToken,
                                                long expiresInSeconds, String scope) {
        StoredToken existing = tokens.get(serverName);
        String effectiveRefresh = refreshToken;
        if ((effectiveRefresh == null || effectiveRefresh.isBlank()) && existing != null) {
            effectiveRefresh = existing.refreshToken();
        }
        save(serverName, accessToken, effectiveRefresh, expiresInSeconds, scope);
    }

    public synchronized boolean isExpired(String serverName) {
        StoredToken token = tokens.get(serverName);
        if (token == null || token.expiresAt() == null || token.expiresAt().isBlank()) {
            return false;
        }
        try {
            return Instant.parse(token.expiresAt()).isBefore(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void remove(String serverName) {
        tokens.remove(serverName);
        persist();
    }

    public synchronized boolean has(String serverName) {
        return tokens.containsKey(serverName);
    }

    public synchronized Set<String> serverNames() {
        return Set.copyOf(tokens.keySet());
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return;
            }
            TokenFile parsed = MAPPER.readValue(bytes, TokenFile.class);
            tokens.clear();
            if (parsed.tokens != null) {
                tokens.putAll(parsed.tokens);
            }
        } catch (IOException | RuntimeException e) {
            // 损坏的 token 文件不阻塞启动：视为无 token，后续授权会重写
            tokens.clear();
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                secureDirectory(dir);
            }
            TokenFile out = new TokenFile();
            out.tokens = tokens;
            byte[] json = MAPPER.writeValueAsBytes(out);
            Files.write(file, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            secureFile(file);
        } catch (IOException e) {
            // token 持久化失败不抛到上层（OAuth 流程已完成，内存里仍持有 token）
        }
    }

    private static void secureDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.setPosixFilePermissions(dir, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void secureFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    public record StoredToken(String accessToken, String refreshToken, String expiresAt, String scope) {
        public boolean hasRefreshToken() {
            return refreshToken != null && !refreshToken.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenFile {
        public Map<String, StoredToken> tokens = new LinkedHashMap<>();
    }
}
