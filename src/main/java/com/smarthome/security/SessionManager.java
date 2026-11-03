package com.smarthome.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages session tokens for authenticated clients.
 * Tokens are cryptographically random and have an expiration time.
 * Supports RBAC roles: ADMIN, USER, DEVICE.
 */
public class SessionManager {
    private static final long DEFAULT_TOKEN_TTL_MS = 30 * 60 * 1000; // 30 minutes
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public static class SessionInfo {
        public final String username;
        public final String role; // ADMIN, USER, or DEVICE
        public final String deviceId; // null for controllers
        public final long createdAt;
        public final long expiresAt;

        public SessionInfo(String username, String role, String deviceId, long ttlMs) {
            this.username = username;
            this.role = role;
            this.deviceId = deviceId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + ttlMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * Create a new session token for a user.
     */
    public String createToken(String username, String role, String deviceId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        sessions.put(token, new SessionInfo(username, role, deviceId, DEFAULT_TOKEN_TTL_MS));
        return token;
    }

    /**
     * Validate a token. Returns SessionInfo if valid, null if invalid or expired.
     */
    public SessionInfo validateToken(String token) {
        if (token == null || token.isBlank()) return null;
        SessionInfo info = sessions.get(token);
        if (info == null) return null;
        if (info.isExpired()) {
            sessions.remove(token);
            return null;
        }
        return info;
    }

    /**
     * Revoke a token.
     */
    public void revokeToken(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * Revoke all tokens for a given username. Used when disabling/deleting a user.
     */
    public void revokeAllForUser(String username) {
        if (username == null) return;
        sessions.entrySet().removeIf(entry -> username.equals(entry.getValue().username));
    }

    /**
     * Clean up expired tokens (should be called periodically).
     */
    public void cleanExpiredTokens() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Check if a token has a specific role.
     */
    public boolean hasRole(String token, String requiredRole) {
        SessionInfo info = validateToken(token);
        return info != null && requiredRole.equals(info.role);
    }

    /**
     * Check if a token has ADMIN role.
     */
    public boolean isAdmin(String token) {
        return hasRole(token, "ADMIN");
    }

    /**
     * Check if a token has ADMIN or USER role (i.e. a controller, not a device).
     */
    public boolean isController(String token) {
        SessionInfo info = validateToken(token);
        return info != null && ("ADMIN".equals(info.role) || "USER".equals(info.role));
    }

    public int getActiveSessionCount() {
        cleanExpiredTokens();
        return sessions.size();
    }
}
