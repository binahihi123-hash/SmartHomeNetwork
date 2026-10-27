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

    // Callbacks
    private Consumer<List<Map<String, String>>> deviceListCallback;
    private Consumer<String> logCallback;
    private Consumer<Message> responseCallback;
    private Consumer<Boolean> connectionCallback;

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
                connected = false;
                if (connectionCallback != null) connectionCallback.accept(false);
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
