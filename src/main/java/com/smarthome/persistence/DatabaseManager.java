package com.smarthome.persistence;

import com.smarthome.common.DeviceInfo;
import com.smarthome.security.PasswordHasher;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite database manager for persistent storage.
 * Stores users (with RBAC roles ADMIN/USER), known devices, command history, and events.
 */
public class DatabaseManager implements AutoCloseable {
    private final Connection connection;

    /** Formatter for local timestamps: 2026-11-01 22:45:30 */
    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Get current local timestamp string matching the system clock.
     * Uses the JVM's default timezone so displayed times match the computer's clock.
     */
    private static String localNow() {
        return LocalDateTime.now().format(LOCAL_FMT);
    }

    public DatabaseManager(String dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(true);
        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Users table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    username TEXT PRIMARY KEY,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'USER',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    last_login TEXT
                )
            """);

            // Known devices table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    device_type TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    device_secret_hash TEXT,
                    last_known_status TEXT DEFAULT 'UNKNOWN',
                    registered_at TEXT NOT NULL,
                    last_seen TEXT
                )
            """);

            // Command history table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS command_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT NOT NULL,
                    username TEXT,
                    device_id TEXT NOT NULL,
                    command TEXT NOT NULL,
                    status TEXT,
                    response_message TEXT,
                    timestamp TEXT NOT NULL,
                    processing_time_ms INTEGER
                )
            """);

            // Events log table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    source TEXT,
                    message TEXT NOT NULL,
                    timestamp TEXT NOT NULL
                )
            """);

            // Safe migration: add 'enabled' column if missing
            safeAddColumn(stmt, "users", "enabled", "INTEGER NOT NULL DEFAULT 1");

            // Migrate old roles: CONTROLLER -> ADMIN for admin, USER for others
            migrateRoles(stmt);

            // Create default users if none exist
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    createDefaultUsers();
                }
            }

            // Register default devices if none exist
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM devices")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    registerDefaultDevices();
                }
            }
        }
    }

    /**
     * Safely add a column if it doesn't already exist (SQLite doesn't support IF NOT EXISTS for ALTER).
     */
    private void safeAddColumn(Statement stmt, String table, String column, String type) {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            System.out.println("[DB] Added column " + column + " to " + table);
        } catch (SQLException e) {
            // Column already exists — ignore
        }
    }

    /**
     * Migrate CONTROLLER role to ADMIN/USER.
     */
    private void migrateRoles(Statement stmt) throws SQLException {
        // Count users with old CONTROLLER role
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'CONTROLLER'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                // Upgrade 'admin' user to ADMIN role
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE users SET role = 'ADMIN' WHERE username = 'admin' AND role = 'CONTROLLER'")) {
                    ps.executeUpdate();
                }
                // Upgrade all other CONTROLLER users to USER role
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE users SET role = 'USER' WHERE role = 'CONTROLLER'")) {
                    ps.executeUpdate();
                }
                System.out.println("[DB] Migrated CONTROLLER roles to ADMIN/USER");
            }
        }
    }

    private void createDefaultUsers() throws SQLException {
        String adminHash = PasswordHasher.hash("admin123");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "admin");
            ps.setString(2, adminHash);
            ps.setString(3, "ADMIN");
            ps.setInt(4, 1);
            ps.setString(5, localNow());
            ps.executeUpdate();
        }

        String userHash = PasswordHasher.hash("user123");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "user");
            ps.setString(2, userHash);
            ps.setString(3, "USER");
            ps.setInt(4, 1);
            ps.setString(5, localNow());
            ps.executeUpdate();
        }
        System.out.println("[DB] Created default users: admin/admin123 (ADMIN), user/user123 (USER)");
    }

    private void registerDefaultDevices() throws SQLException {
        String[][] defaults = {
            {"DEVICE_LIGHT_01", "LIGHT", "Đèn phòng khách"},
            {"DEVICE_LIGHT_02", "LIGHT", "Đèn phòng ngủ"},
            {"DEVICE_FAN_01", "FAN", "Quạt"},
            {"DEVICE_AC_01", "AC", "Máy lạnh"},
            {"DEVICE_TV_01", "TV", "TV"}
        };

        for (String[] d : defaults) {
            String secretHash = PasswordHasher.hash(d[0] + "_secret");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO devices (device_id, device_type, device_name, device_secret_hash, registered_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, d[0]);
                ps.setString(2, d[1]);
                ps.setString(3, d[2]);
                ps.setString(4, secretHash);
                ps.setString(5, localNow());
                ps.executeUpdate();
            }
        }
        System.out.println("[DB] Registered default devices");
    }

    // === User operations ===

    /**
     * Authenticate user. Also checks if the user is enabled.
     */
    public boolean authenticateUser(String username, String password) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password_hash, enabled FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int enabled = rs.getInt("enabled");
                    if (enabled == 0) {
                        return false; // account disabled
                    }
                    boolean valid = PasswordHasher.verify(password, rs.getString("password_hash"));
                    if (valid) {
                        updateLastLogin(username);
                    }
                    return valid;
                }
            }
        }
        return false;
    }

    /**
     * Check if a user account is enabled.
     */
    public boolean isUserEnabled(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT enabled FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("enabled") == 1;
                }
            }
        }
        return false;
    }

    /**
     * Get the role of a user.
     */
    public String getUserRole(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT role FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        }
        return null;
    }

    /**
     * Get all users as list of maps. Each map has: username, role, enabled, created_at, last_login.
     */
    public List<Map<String, String>> getAllUsers() throws SQLException {
        List<Map<String, String>> users = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username, role, enabled, created_at, last_login FROM users ORDER BY username")) {
            while (rs.next()) {
                Map<String, String> u = new LinkedHashMap<>();
                u.put("username", rs.getString("username"));
                u.put("role", rs.getString("role"));
                u.put("enabled", String.valueOf(rs.getInt("enabled")));
                u.put("created_at", rs.getString("created_at"));
                u.put("last_login", rs.getString("last_login") != null ? rs.getString("last_login") : "");
                users.add(u);
            }
        }
        return users;
    }

    /**
     * Create a new user.
     */
    public boolean createUser(String username, String password, String role) throws SQLException {
        if (userExists(username)) return false;
        String hash = PasswordHasher.hash(password);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, 1, ?)")) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, role);
            ps.setString(4, localNow());
            ps.executeUpdate();
        }
        return true;
    }

    /**
     * Check if a user exists.
     */
    public boolean userExists(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Set user enabled/disabled.
     */
    public void setUserEnabled(String username, boolean enabled) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET enabled = ? WHERE username = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /**
     * Set user role.
     */
    public void setUserRole(String username, String role) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET role = ? WHERE username = ?")) {
            ps.setString(1, role);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /**
     * Update user password.
     */
    public void updatePassword(String username, String newPassword) throws SQLException {
        String hash = PasswordHasher.hash(newPassword);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE username = ?")) {
            ps.setString(1, hash);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /**
     * Count number of ADMIN users.
     */
    public int countAdmins() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND enabled = 1")) {
            if (rs.next()) return rs.getInt(1);
        }
        return 0;
    }

    private void updateLastLogin(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET last_login = ? WHERE username = ?")) {
            ps.setString(1, localNow());
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    // === Device operations ===

    public boolean authenticateDevice(String deviceId, String deviceSecret) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT device_secret_hash FROM devices WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("device_secret_hash");
                    if (hash == null || hash.isEmpty()) return true; // no secret set
                    return PasswordHasher.verify(deviceSecret, hash);
                }
            }
        }
        return false;
    }

    public boolean isKnownDevice(String deviceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT device_id FROM devices WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void registerDevice(String deviceId, String deviceType, String deviceName, String secretHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO devices (device_id, device_type, device_name, device_secret_hash, registered_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, deviceId);
            ps.setString(2, deviceType);
            ps.setString(3, deviceName);
            ps.setString(4, secretHash);
            ps.setString(5, localNow());
            ps.executeUpdate();
        }
    }

    public void updateDeviceLastSeen(String deviceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE devices SET last_seen = ? WHERE device_id = ?")) {
            ps.setString(1, localNow());
            ps.setString(2, deviceId);
            ps.executeUpdate();
        }
    }

    public List<DeviceInfo> getAllDevices() throws SQLException {
        List<DeviceInfo> devices = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM devices")) {
            while (rs.next()) {
                DeviceInfo info = new DeviceInfo(
                        rs.getString("device_id"),
                        rs.getString("device_type"),
                        rs.getString("device_name")
                );
                info.setDeviceStatus(rs.getString("last_known_status"));
                devices.add(info);
            }
        }
        return devices;
    }

    // === Command history ===

    public void logCommand(String requestId, String username, String deviceId, String command,
                           String status, String responseMessage, long processingTimeMs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO command_history (request_id, username, device_id, command, status, response_message, timestamp, processing_time_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, requestId);
            ps.setString(2, username);
            ps.setString(3, deviceId);
            ps.setString(4, command);
            ps.setString(5, status);
            ps.setString(6, responseMessage);
            ps.setString(7, localNow());
            ps.setLong(8, processingTimeMs);
            ps.executeUpdate();
        }
    }

    public List<String> getCommandHistory(int limit) throws SQLException {
        List<String> history = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM command_history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(String.format("[%s] %s -> %s %s = %s (%s)",
                            rs.getString("timestamp"),
                            rs.getString("username"),
                            rs.getString("device_id"),
                            rs.getString("command"),
                            rs.getString("status"),
                            rs.getString("response_message")));
                }
            }
        }
        return history;
    }

    /**
     * Get command history for a specific user.
     */
    public List<String> getCommandHistoryForUser(String username, int limit) throws SQLException {
        List<String> history = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM command_history WHERE username = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, username);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(String.format("[%s] %s -> %s %s = %s (%s)",
                            rs.getString("timestamp"),
                            rs.getString("username"),
                            rs.getString("device_id"),
                            rs.getString("command"),
                            rs.getString("status"),
                            rs.getString("response_message")));
                }
            }
        }
        return history;
    }

    // === Events ===

    public void logEvent(String eventType, String source, String message) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events (event_type, source, message, timestamp) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, eventType);
            ps.setString(2, source);
            ps.setString(3, message);
            ps.setString(4, localNow());
            ps.executeUpdate();
        }
    }

    /**
     * Get recent events for admin dashboard.
     */
    public List<String> getRecentEvents(int limit) throws SQLException {
        List<String> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM events ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(String.format("[%s] %s: %s (%s)",
                            rs.getString("timestamp"),
                            rs.getString("event_type"),
                            rs.getString("message"),
                            rs.getString("source")));
                }
            }
        }
        return events;
    }

    /**
     * Get user count.
     */
    public int getUserCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next()) return rs.getInt(1);
        }
        return 0;
    }

    /**
     * Get device count.
     */
    public int getDeviceCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM devices")) {
            if (rs.next()) return rs.getInt(1);
        }
        return 0;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error closing database: " + e.getMessage());
        }
    }
}
