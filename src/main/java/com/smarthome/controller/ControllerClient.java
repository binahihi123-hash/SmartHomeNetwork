package com.smarthome.controller;

import com.smarthome.common.*;
import com.smarthome.security.TLSConfig;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;

/**
 * Controller Client - Network layer for communicating with Smart Hub Server.
 * Handles login, sending commands, receiving responses, and device list updates.
 * Supports RBAC: stores role from LOGIN_RESULT and provides role-based methods.
 */
public class ControllerClient {
    private static final Logger logger = Logger.getLogger(ControllerClient.class.getName());

    private final String serverHost;
    private final int serverPort;
    private final TLSConfig tlsConfig;

    private Socket socket;
    private MessageIO messageIO;
    private String token;
    private String username;
    private String role; // ADMIN or USER
    private volatile boolean connected = false;

    // Auto-reconnect support for 4G/unstable connections
    private volatile boolean autoReconnectEnabled = false;
    private volatile boolean reconnecting = false;
    private String savedPassword; // stored for auto-reconnect only
    private static final long INITIAL_RECONNECT_DELAY_MS = 2000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    private static final int MAX_RECONNECT_ATTEMPTS = 50;
    private int reconnectAttempts = 0;

    // Callbacks
    private Consumer<List<Map<String, String>>> deviceListCallback;
    private Consumer<String> logCallback;
    private Consumer<Message> responseCallback;
    private Consumer<Boolean> connectionCallback;

    // Real-time sync callbacks (new - for multi-client synchronization)
    private Consumer<Message> syncStateCallback;     // Called when any device state changes
    private Consumer<Message> syncHistoryCallback;   // Called when a new history entry is broadcast

    // Pending requests
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    private Thread listenerThread;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);

    public ControllerClient(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.tlsConfig = TLSConfig.fromEnvironment();
    }

    // === Callback setters ===
    public void setDeviceListCallback(Consumer<List<Map<String, String>>> callback) {
        this.deviceListCallback = callback;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    public void setResponseCallback(Consumer<Message> callback) {
        this.responseCallback = callback;
    }

    public void setConnectionCallback(Consumer<Boolean> callback) {
        this.connectionCallback = callback;
    }

    /**
     * Set callback for real-time device state sync broadcasts from server.
     * Called whenever ANY client triggers a state change on any device.
     */
    public void setSyncStateCallback(Consumer<Message> callback) {
        this.syncStateCallback = callback;
    }

    /**
     * Set callback for real-time command history sync broadcasts from server.
     * Called whenever a new command history entry is created (filtered by role on server).
     */
    public void setSyncHistoryCallback(Consumer<Message> callback) {
        this.syncHistoryCallback = callback;
    }

    /**
     * Enable auto-reconnect for unstable networks (4G, mobile hotspot).
     * When connection drops, client will automatically attempt to reconnect
     * with exponential backoff, re-login with saved credentials, and
     * request a full state sync from server.
     */
    public void setAutoReconnect(boolean enabled) {
        this.autoReconnectEnabled = enabled;
    }

    /** Check if client is currently attempting to reconnect */
    public boolean isReconnecting() { return reconnecting; }

    /** Get number of reconnect attempts made */
    public int getReconnectAttempts() { return reconnectAttempts; }

    /**
     * Connect and login to the server.
     * Returns true if login was successful.
     */
    public boolean login(String username, String password) throws IOException {
        try {
            socket = tlsConfig.createClientSocket(serverHost, serverPort);
            socket.setSoTimeout(30000);
            messageIO = new MessageIO(socket);

            // Send login
            Message loginMsg = Message.loginRequest(username, password);
            messageIO.send(loginMsg);

            // Wait for login result
            Message response = messageIO.receive();
            if (response == null) {
                throw new IOException("No response from server");
            }

            if (response.getMessageType() == MessageType.LOGIN_RESULT) {
                if ("SUCCESS".equals(response.getStatus())) {
                    this.token = response.getToken();
                    this.username = username;
                    this.role = response.getRole(); // Store role from server
                    this.connected = true;
                    this.savedPassword = password; // Save for auto-reconnect
                    this.reconnectAttempts = 0; // Reset on successful login

                    log("Đăng nhập thành công: " + username + " [" + role + "]");
                    if (connectionCallback != null) connectionCallback.accept(true);

                    // Start listener thread
                    startListenerThread();

                    // Start heartbeat
                    heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);

                    return true;
                } else {
                    log("Đăng nhập thất bại: " + response.getMessage());
                    socket.close();
                    return false;
                }
            } else {
                log("Phản hồi không mong đợi: " + response.getType());
                socket.close();
                return false;
            }

        } catch (Exception e) {
            log("Lỗi kết nối: " + e.getMessage());
            connected = false;
            if (connectionCallback != null) connectionCallback.accept(false);
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Start background thread to receive messages from server.
     */
    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            try {
                while (connected && !socket.isClosed()) {
                    Message msg = messageIO.receive();
                    if (msg == null) break;

                    handleServerMessage(msg);
                }
            } catch (IOException e) {
                if (connected) {
                    log("Mất kết nối: " + e.getMessage());
                }
            } finally {
                boolean wasConnected = connected;
                connected = false;
                if (connectionCallback != null) connectionCallback.accept(false);

                // === AUTO-RECONNECT for 4G/unstable networks ===
                if (wasConnected && autoReconnectEnabled && !reconnecting && savedPassword != null) {
                    startAutoReconnect();
                }
            }
        }, "ControllerListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Handle a message from the server.
     */
    @SuppressWarnings("unchecked")
    private void handleServerMessage(Message msg) {
        MessageType type = msg.getMessageType();
        if (type == null) return;

        switch (type) {
            case DEVICE_LIST -> {
                // Update device list
                if (msg.getPayload() != null && msg.getPayload().containsKey("devices")) {
                    Object devicesObj = msg.getPayload().get("devices");
                    if (devicesObj instanceof List<?> list) {
                        List<Map<String, String>> devices = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> map) {
                                Map<String, String> device = new LinkedHashMap<>();
                                map.forEach((k, v) -> device.put(String.valueOf(k), String.valueOf(v)));
                                devices.add(device);
                            }
                        }
                        if (deviceListCallback != null) {
                            deviceListCallback.accept(devices);
                        }
                    }
                }
            }
            case DEVICE_RESPONSE, STATUS_UPDATE, ERROR, HISTORY_RESPONSE,
                 USER_MANAGEMENT_RESULT, CHANGE_PASSWORD_RESULT, PERMISSION_DENIED -> {
                // Check if this is a response to a pending request
                String reqId = msg.getRequestId();
                if (reqId != null) {
                    CompletableFuture<Message> future = pendingRequests.remove(reqId);
                    if (future != null) {
                        future.complete(msg);
                        return;
                    }
                }
                // Otherwise notify through callback
                if (responseCallback != null) {
                    responseCallback.accept(msg);
                }
            }
            case HEARTBEAT -> {
                try {
                    messageIO.send(Message.heartbeatAck());
                } catch (IOException ignored) {}
            }
            case HEARTBEAT_ACK -> {
                // OK
            }
            // === REAL-TIME SYNC: Handle broadcast messages from server ===
            case SYNC_STATE_BROADCAST -> {
                // Another client (or this one) triggered a device state change
                log("⚡ Đồng bộ: " + msg.getDeviceId() + " " + msg.getCommand()
                        + " -> " + msg.getStatus() + " (bởi " + msg.getUsername() + ")");
                if (syncStateCallback != null) {
                    syncStateCallback.accept(msg);
                }
            }
            case SYNC_HISTORY_BROADCAST -> {
                // New command history entry broadcast from server
                log("📋 Lịch sử mới: " + msg.getUsername() + " -> " + msg.getDeviceId()
                        + " " + msg.getCommand());
                if (syncHistoryCallback != null) {
                    syncHistoryCallback.accept(msg);
                }
            }
            default -> {
                log("Message không xử lý: " + type);
            }
        }
    }

    /**
     * Send a control command and wait for response.
     */
    public Message sendControlCommand(String deviceId, String command) throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected or not authenticated");
        }

        Message msg = Message.controlCommand(token, deviceId, command);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        messageIO.send(msg);
        log("Gửi lệnh: " + command + " -> " + deviceId);

        try {
            Message response = future.get(15, TimeUnit.SECONDS);
            log("Phản hồi: " + response.getMessage() + " [" + response.getStatus() + "]");
            return response;
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            log("Hết thời gian chờ phản hồi cho " + deviceId);
            throw new IOException("Request timeout");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Send a voice/text command for server-side parsing.
     */
    public Message sendVoiceCommand(String text) throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected");
        }

        Message msg = new Message(MessageType.VOICE_COMMAND)
                .setToken(token)
                .setCommand(text);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        messageIO.send(msg);
        log("Gửi lệnh văn bản: " + text);

        try {
            Message response = future.get(15, TimeUnit.SECONDS);
            String status = response.getStatus();
            String message = response.getMessage();
            log("Kết quả: " + message + " [" + status + "]");
            return response;
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new IOException("Timeout");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Request device list from server.
     */
    public void requestDeviceList() throws IOException {
        if (!connected || token == null) return;
        messageIO.send(Message.deviceListRequest(token));
    }

    /**
     * Request status of a specific device.
     */
    public Message requestDeviceStatus(String deviceId) throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected");
        }

        Message msg = Message.statusRequest(token, deviceId);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        messageIO.send(msg);

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Request command history.
     */
    public Message requestHistory() throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected");
        }

        Message msg = new Message(MessageType.HISTORY_REQUEST).setToken(token);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        messageIO.send(msg);

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Send a user management request (ADMIN only).
     * Actions: LIST_USERS, CREATE_USER, DISABLE_USER, ENABLE_USER, SET_ROLE, RESET_PASSWORD
     */
    public Message sendUserManagement(String action, String targetUsername, String newPassword, String role) throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected");
        }

        Message msg = new Message(MessageType.USER_MANAGEMENT)
                .setToken(token)
                .setAction(action)
                .setTargetUsername(targetUsername)
                .setNewPassword(newPassword)
                .setRole(role);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        messageIO.send(msg);

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new IOException("Timeout waiting for user management response");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Change own password. Any authenticated user can call this.
     */
    public Message changePassword(String oldPassword, String newPassword) throws IOException {
        if (!connected || token == null) {
            throw new IOException("Not connected");
        }

        Message msg = new Message(MessageType.CHANGE_PASSWORD)
                .setToken(token)
                .setPassword(oldPassword)
                .setNewPassword(newPassword);
        String requestId = msg.getRequestId();

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        messageIO.send(msg);

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new IOException("Timeout waiting for password change response");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new IOException(e.getMessage());
        }
    }

    private void sendHeartbeat() {
        if (connected && messageIO != null) {
            try {
                messageIO.send(Message.heartbeat());
            } catch (IOException e) {
                connected = false;
            }
        }
    }

    /**
     * Auto-reconnect loop with exponential backoff.
     * For 4G/mobile clients: automatically reconnects when connection drops,
     * re-authenticates, and requests full state sync from server.
     * Runs on a daemon thread so it won't prevent JVM exit.
     */
    private void startAutoReconnect() {
        reconnecting = true;
        Thread reconnectThread = new Thread(() -> {
            long delay = INITIAL_RECONNECT_DELAY_MS;

            while (autoReconnectEnabled && !connected && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                log("🔄 Đang kết nối lại... (lần " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS
                        + ", chờ " + (delay / 1000) + "s)");

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                try {
                    // Close old resources
                    if (socket != null) {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                    if (messageIO != null) {
                        messageIO.close();
                    }

                    // Create new connection
                    socket = tlsConfig.createClientSocket(serverHost, serverPort);
                    socket.setSoTimeout(30000);
                    messageIO = new MessageIO(socket);

                    // Re-login with saved credentials
                    Message loginMsg = Message.loginRequest(username, savedPassword);
                    messageIO.send(loginMsg);

                    Message response = messageIO.receive();
                    if (response != null && response.getMessageType() == MessageType.LOGIN_RESULT
                            && "SUCCESS".equals(response.getStatus())) {

                        this.token = response.getToken();
                        this.role = response.getRole();
                        this.connected = true;
                        this.reconnectAttempts = 0;

                        log("✅ Đã kết nối lại thành công! [" + role + "]");
                        if (connectionCallback != null) connectionCallback.accept(true);

                        // Restart listener thread
                        startListenerThread();

                        // Request full state sync from server (real data)
                        requestFullSync();

                        reconnecting = false;
                        return; // Success!
                    } else {
                        log("⚠ Kết nối lại thất bại: " +
                                (response != null ? response.getMessage() : "no response"));
                    }
                } catch (Exception e) {
                    log("⚠ Lỗi kết nối lại: " + e.getMessage());
                }

                // Exponential backoff
                delay = Math.min((long) (delay * 1.5), MAX_RECONNECT_DELAY_MS);
            }

            reconnecting = false;
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                log("❌ Đã hết số lần thử kết nối lại (" + MAX_RECONNECT_ATTEMPTS + ")");
            }
        }, "AutoReconnect-" + username);
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * Request full state sync from server after reconnect.
     * Fetches: device list (real-time) + command history (from DB).
     * This ensures a 4G client that reconnects sees the same state as WiFi clients.
     */
    public void requestFullSync() {
        if (!connected || token == null) return;

        new Thread(() -> {
            try {
                // 1. Request fresh device list
                messageIO.send(Message.deviceListRequest(token));
                log("📡 Yêu cầu đồng bộ danh sách thiết bị...");

                // Small delay to let server process
                Thread.sleep(300);

                // 2. Request command history (server filters by role)
                Message histReq = new Message(MessageType.HISTORY_REQUEST).setToken(token);
                String reqId = histReq.getRequestId();
                CompletableFuture<Message> future = new CompletableFuture<>();
                pendingRequests.put(reqId, future);
                messageIO.send(histReq);
                log("📡 Yêu cầu đồng bộ lịch sử lệnh...");

                // Wait for history response
                try {
                    Message histResponse = future.get(10, TimeUnit.SECONDS);
                    if (histResponse != null && "SUCCESS".equals(histResponse.getStatus())) {
                        log("✅ Đồng bộ hoàn tất — dữ liệu thật từ server");
                        // The response will be handled by syncHistoryCallback or responseCallback
                        if (responseCallback != null) {
                            responseCallback.accept(histResponse);
                        }
                    }
                } catch (Exception e) {
                    pendingRequests.remove(reqId);
                    log("⚠ Đồng bộ lịch sử timeout: " + e.getMessage());
                }

            } catch (Exception e) {
                log("⚠ Lỗi đồng bộ: " + e.getMessage());
            }
        }, "FullSync").start();
    }

    /**
     * Get connection statistics from the underlying MessageIO.
     * Returns null if not connected.
     * Useful for monitoring 4G connection quality.
     */
    public Map<String, Long> getConnectionStats() {
        if (messageIO == null) return null;
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("messagesSent", messageIO.getMessagesSent());
        stats.put("messagesReceived", messageIO.getMessagesReceived());
        stats.put("sendErrors", messageIO.getSendErrors());
        stats.put("uptimeMs", messageIO.getUptimeMs());
        stats.put("reconnectAttempts", (long) reconnectAttempts);
        return stats;
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.accept(message);
        }
        logger.info(message);
    }

    /**
     * Disconnect from server.
     */
    public void disconnect() {
        autoReconnectEnabled = false; // Prevent reconnect on intentional disconnect
        connected = false;
        heartbeatScheduler.shutdownNow();

        // Complete all pending futures exceptionally so callers don't hang
        for (Map.Entry<String, CompletableFuture<Message>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(new IOException("Disconnected"));
            pendingRequests.remove(entry.getKey());
        }

        // Send LOGOUT before closing
        if (messageIO != null) {
            try {
                messageIO.send(new Message(MessageType.LOGOUT).setToken(token));
            } catch (IOException ignored) {}
        }

        // Close socket FIRST to unblock any reader.readLine() in listener thread
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        if (messageIO != null) {
            messageIO.close();
        }
        log("Đã ngắt kết nối");
    }

    public boolean isConnected() { return connected; }
    public String getToken() { return token; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
