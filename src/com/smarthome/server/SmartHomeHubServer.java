package com.smarthome.server;

import com.smarthome.common.*;
import com.smarthome.persistence.DatabaseManager;
import com.smarthome.security.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Smart Hub Server - Central component of the Hub-and-Spoke architecture.
 * Routes commands from Controller Clients to Device Clients and returns responses.
 * Supports RBAC: ADMIN and USER roles with server-side authorization.
 */
public class SmartHomeHubServer {
    private static final Logger logger = Logger.getLogger(SmartHomeHubServer.class.getName());

    private final int port;
    private final ExecutorService threadPool;
    private final SessionManager sessionManager;
    private final TLSConfig tlsConfig;
    private DatabaseManager db;

    // Connected device clients: deviceId -> ClientConnection
    private final ConcurrentHashMap<String, ClientConnection> deviceClients = new ConcurrentHashMap<>();

    // Connected controller clients: token -> ClientConnection
    private final ConcurrentHashMap<String, ClientConnection> controllerClients = new ConcurrentHashMap<>();

    // Device registry: deviceId -> DeviceInfo (includes both online and offline devices)
    private final ConcurrentHashMap<String, DeviceInfo> deviceRegistry = new ConcurrentHashMap<>();

    // Pending requests: requestId -> PendingRequest
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    // Heartbeat interval
    private static final long HEARTBEAT_INTERVAL_MS = 15000;
    private static final long HEARTBEAT_TIMEOUT_MS = 45000;
    private static final long REQUEST_TIMEOUT_MS = 10000;
    private static final int MAX_CONNECTIONS = 50;

    private ScheduledExecutorService scheduler;

    /**
     * Represents a pending control command waiting for device response.
     */
    static class PendingRequest {
        final String requestId;
        final String controllerToken;
        final String deviceId;
        final String command;
        final long createdAt;
        final CompletableFuture<Message> responseFuture;

        PendingRequest(String requestId, String controllerToken, String deviceId, String command) {
            this.requestId = requestId;
            this.controllerToken = controllerToken;
            this.deviceId = deviceId;
            this.command = command;
            this.createdAt = System.currentTimeMillis();
            this.responseFuture = new CompletableFuture<>();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > REQUEST_TIMEOUT_MS;
        }
    }

    /**
     * Represents a connected client (either Controller or Device).
     */
    static class ClientConnection {
        final Socket socket;
        final MessageIO messageIO;
        final String role; // ADMIN, USER, or DEVICE
        String token;
        String username;
        String deviceId;
        long lastHeartbeat;

        ClientConnection(Socket socket, MessageIO messageIO, String role) {
            this.socket = socket;
            this.messageIO = messageIO;
            this.role = role;
            this.lastHeartbeat = System.currentTimeMillis();
        }
    }

    public SmartHomeHubServer(int port) {
        this.port = port;
        this.threadPool = new ThreadPoolExecutor(
                0, MAX_CONNECTIONS, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>()
        );
        this.sessionManager = new SessionManager();
        this.tlsConfig = TLSConfig.fromEnvironment();
    }

    public void start() {
        running = true;

        try {
            // Initialize database
            db = new DatabaseManager("smarthome.db");
            logger.info("Database initialized");

            // Load known devices into registry
            loadDevicesFromDb();

            // Create server socket (TLS or plain)
            serverSocket = tlsConfig.createServerSocket(port);
            serverSocket.setSoTimeout(1000); // allow periodic check of running flag

            // Start maintenance scheduler
            scheduler = Executors.newScheduledThreadPool(2);
            scheduler.scheduleAtFixedRate(this::heartbeatCheck, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::cleanupExpiredRequests, 5000, 5000, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(sessionManager::cleanExpiredTokens, 60000, 60000, TimeUnit.MILLISECONDS);

            db.logEvent("SERVER_START", "SERVER", "Smart Hub Server started on port " + port);

            System.out.println("====================================");
            System.out.println("       SMART HOME HUB SERVER");
            System.out.println("====================================");
            System.out.println("Port: " + port);
            System.out.println("TLS: " + (tlsConfig.isTlsEnabled() ? "ENABLED" : "DISABLED (dev mode)"));
            System.out.println("RBAC: ENABLED (ADMIN/USER)");
            System.out.println("Waiting for connections...\n");

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(60000); // 60s read timeout
                    logger.info("New connection from " + clientSocket.getInetAddress());
                    threadPool.submit(() -> handleNewConnection(clientSocket));
                } catch (SocketTimeoutException e) {
                    // Normal - allows checking running flag
                } catch (java.net.SocketException e) {
                    if (!running) {
                        break; // Expected during shutdown
                    }
                    logger.log(Level.WARNING, "Server socket exception: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Server error", e);
            System.err.println("Server error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    private void loadDevicesFromDb() {
        try {
            List<DeviceInfo> devices = db.getAllDevices();
            for (DeviceInfo d : devices) {
                d.setConnectionStatus("OFFLINE"); // All offline on startup
                d.setDeviceStatus("UNKNOWN"); // Don't trust DB status until device confirms
                deviceRegistry.put(d.getDeviceId(), d);
            }
            logger.info("Loaded " + devices.size() + " devices from database");
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load devices from DB", e);
        }
    }

    /**
     * Handle a new TCP connection. Reads the first message to determine client type.
     */
    private void handleNewConnection(Socket socket) {
        try {
            MessageIO io = new MessageIO(socket);
            Message firstMsg = io.receive();

            if (firstMsg == null) {
                socket.close();
                return;
            }

            MessageType type = firstMsg.getMessageType();

            if (type == MessageType.LOGIN) {
                handleControllerLogin(socket, io, firstMsg);
            } else if (type == MessageType.REGISTER_DEVICE) {
                handleDeviceRegistration(socket, io, firstMsg);
            } else {
                io.send(Message.error(firstMsg.getRequestId(),
                        "First message must be LOGIN or REGISTER_DEVICE"));
                socket.close();
            }

        } catch (IOException e) {
            logger.fine("Connection error: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Handle controller login request. Reads role from DB and enforces enabled status.
     */
    private void handleControllerLogin(Socket socket, MessageIO io, Message loginMsg) {
        try {
            String username = loginMsg.getUsername();
            String password = loginMsg.getPassword();

            if (username == null || password == null || username.isBlank() || password.isBlank()) {
                io.send(Message.loginResult(loginMsg.getRequestId(), false, null, "Username and password required"));
                socket.close();
                return;
            }

            // Check if user is enabled
            try {
                if (!db.isUserEnabled(username) && db.userExists(username)) {
                    io.send(Message.loginResult(loginMsg.getRequestId(), false, null, "Tài khoản đã bị vô hiệu hóa"));
                    db.logEvent("LOGIN_DISABLED", username, "Disabled user attempted login");
                    socket.close();
                    return;
                }
            } catch (SQLException e) {
                // continue with authentication
            }

            boolean authenticated = db.authenticateUser(username, password);
            if (!authenticated) {
                io.send(Message.loginResult(loginMsg.getRequestId(), false, null, "Invalid credentials"));
                db.logEvent("LOGIN_FAILED", username, "Failed login attempt");
                logger.warning("Failed login: " + username + " from " + socket.getInetAddress());
                socket.close();
                return;
            }

            // Get role from database instead of hardcoding
            String role = db.getUserRole(username);
            if (role == null) role = "USER"; // default fallback

            // Create session with actual role from DB
            String token = sessionManager.createToken(username, role, null);
            ClientConnection conn = new ClientConnection(socket, io, role);
            conn.token = token;
            conn.username = username;
            controllerClients.put(token, conn);

            // Send login result WITH ROLE
            io.send(Message.loginResultWithRole(loginMsg.getRequestId(), true, token, role, "Login successful"));

            // Send device list
            sendDeviceList(io, token);

            db.logEvent("LOGIN", username, "Controller logged in (" + role + ") from " + socket.getInetAddress());
            logger.info("Controller logged in: " + username + " [" + role + "]");

            // Start listening for commands
            handleControllerSession(conn);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Login error", e);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Handle device registration.
     */
    private void handleDeviceRegistration(Socket socket, MessageIO io, Message regMsg) {
        try {
            String deviceId = regMsg.getDeviceId();
            String deviceType = regMsg.getDeviceType();
            String deviceName = regMsg.getDeviceName();
            String deviceSecret = regMsg.getPassword();

            if (deviceId == null || deviceId.isBlank() || deviceType == null || deviceType.isBlank()) {
                io.send(Message.registerResult(regMsg.getRequestId(), false, null, "deviceId and deviceType required"));
                socket.close();
                return;
            }

            // Authenticate device
            boolean authOk;
            if (db.isKnownDevice(deviceId)) {
                authOk = db.authenticateDevice(deviceId, deviceSecret);
            } else {
                // New device - register it
                String secretHash = deviceSecret != null ? com.smarthome.security.PasswordHasher.hash(deviceSecret) : null;
                db.registerDevice(deviceId, deviceType, deviceName != null ? deviceName : deviceId, secretHash);
                authOk = true;
            }

            if (!authOk) {
                io.send(Message.registerResult(regMsg.getRequestId(), false, null, "Device authentication failed"));
                socket.close();
                return;
            }

            // Check if device is already connected
            ClientConnection existingConn = deviceClients.get(deviceId);
            if (existingConn != null) {
                // Disconnect old connection
                try {
                    existingConn.messageIO.send(Message.error(null, "Another instance connected"));
                    existingConn.socket.close();
                } catch (IOException ignored) {}
                deviceClients.remove(deviceId);
                logger.info("Disconnected old instance of device: " + deviceId);
            }

            // Create session token for device
            String token = sessionManager.createToken(deviceId, "DEVICE", deviceId);
            ClientConnection conn = new ClientConnection(socket, io, "DEVICE");
            conn.token = token;
            conn.deviceId = deviceId;
            deviceClients.put(deviceId, conn);

            // Update registry
            DeviceInfo info = deviceRegistry.computeIfAbsent(deviceId,
                    id -> new DeviceInfo(id, deviceType, deviceName != null ? deviceName : id));
            info.setConnectionStatus("ONLINE");
            info.setLastSeen(System.currentTimeMillis());

            // Send registration result FIRST so device knows it is connected
            io.send(Message.registerResult(regMsg.getRequestId(), true, token, "Device registered: " + deviceId));

            db.logEvent("DEVICE_REGISTER", deviceId, "Device connected: " + deviceType + " - " + deviceName);
            db.updateDeviceLastSeen(deviceId);
            logger.info("Device registered: " + deviceId + " (" + deviceType + ")");

            // Notify controllers about device online
            broadcastDeviceListToControllers();

            // Request current status from device (after registration confirmed)
            Message statusReq = new Message(MessageType.STATUS_REQUEST)
                    .setDeviceId(deviceId);
            io.send(statusReq);

            // Start listening for device responses
            handleDeviceSession(conn);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Device registration error", e);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Listen for commands from a controller. Includes RBAC authorization for each command type.
     */
    private void handleControllerSession(ClientConnection conn) {
        try {
            while (running && !conn.socket.isClosed()) {
                Message msg = conn.messageIO.receive();
                if (msg == null) break; // connection closed

                conn.lastHeartbeat = System.currentTimeMillis();

                MessageType type = msg.getMessageType();
                if (type == null) {
                    conn.messageIO.send(Message.error(msg.getRequestId(), "Unknown message type: " + msg.getType()));
                    continue;
                }

                switch (type) {
                    case CONTROL_COMMAND -> handleControlCommand(conn, msg);
                    case DEVICE_LIST_REQUEST -> sendDeviceList(conn.messageIO, conn.token);
                    case STATUS_REQUEST -> handleStatusRequest(conn, msg);
                    case HEARTBEAT -> {
                        conn.messageIO.send(Message.heartbeatAck());
                    }
                    case VOICE_COMMAND -> handleVoiceCommand(conn, msg);
                    case HISTORY_REQUEST -> handleHistoryRequest(conn, msg);
                    case USER_MANAGEMENT -> handleUserManagement(conn, msg);
                    case CHANGE_PASSWORD -> handleChangePassword(conn, msg);
                    case LOGOUT -> {
                        logger.info("Controller logout: " + conn.username);
                        try {
                            db.logEvent("LOGOUT", conn.username, "Controller logged out");
                        } catch (SQLException ignored) {}
                        break;
                    }
                    default -> conn.messageIO.send(Message.error(msg.getRequestId(),
                            "Unsupported message type for controller: " + type));
                }
            }
        } catch (IOException e) {
            logger.fine("Controller disconnected: " + conn.username + " - " + e.getMessage());
        } finally {
            controllerClients.remove(conn.token);
            sessionManager.revokeToken(conn.token);
            try { conn.socket.close(); } catch (IOException ignored) {}
            try {
                db.logEvent("DISCONNECT", conn.username, "Controller disconnected");
            } catch (SQLException ignored) {}
            logger.info("Controller session ended: " + conn.username);
        }
    }

    /**
     * Listen for responses from a device.
     */
    private void handleDeviceSession(ClientConnection conn) {
        try {
            while (running && !conn.socket.isClosed()) {
                Message msg = conn.messageIO.receive();
                if (msg == null) break;

                conn.lastHeartbeat = System.currentTimeMillis();

                MessageType type = msg.getMessageType();
                if (type == null) {
                    conn.messageIO.send(Message.error(msg.getRequestId(), "Unknown message type"));
                    continue;
                }

                switch (type) {
                    case DEVICE_RESPONSE -> handleDeviceResponse(conn, msg);
                    case STATUS_UPDATE -> {
                        // Check if this status update is a response to a pending request
                        String reqId = msg.getRequestId();
                        if (reqId != null) {
                            PendingRequest pending = pendingRequests.remove(reqId);
                            if (pending != null) {
                                pending.responseFuture.complete(msg);
                            }
                        }
                        handleStatusUpdate(conn, msg);
                    }
                    case HEARTBEAT -> {
                        conn.messageIO.send(Message.heartbeatAck());
                    }
                    case HEARTBEAT_ACK -> {
                        // Device acknowledged heartbeat from server
                    }
                    default -> conn.messageIO.send(Message.error(msg.getRequestId(),
                            "Unsupported message type for device: " + type));
                }
            }
        } catch (IOException e) {
            logger.fine("Device disconnected: " + conn.deviceId + " - " + e.getMessage());
        } finally {
            // Mark device as offline
            deviceClients.remove(conn.deviceId);
            DeviceInfo info = deviceRegistry.get(conn.deviceId);
            if (info != null) {
                info.setConnectionStatus("OFFLINE");
            }
            sessionManager.revokeToken(conn.token);
            try { conn.socket.close(); } catch (IOException ignored) {}

            // Fail any pending requests for this device
            for (PendingRequest pr : pendingRequests.values()) {
                if (conn.deviceId.equals(pr.deviceId)) {
                    pr.responseFuture.complete(Message.error(pr.requestId, "Device disconnected"));
                    pendingRequests.remove(pr.requestId);
                }
            }

            try {
                db.logEvent("DEVICE_DISCONNECT", conn.deviceId, "Device went offline");
            } catch (SQLException ignored) {}
            logger.info("Device offline: " + conn.deviceId);

            // Notify controllers
            broadcastDeviceListToControllers();
        }
    }

    /**
     * Handle a control command from a controller - route to the correct device.
     * Both ADMIN and USER roles can control devices.
     */
    private void handleControlCommand(ClientConnection controller, Message msg) throws IOException {
        // Validate token - accept ADMIN or USER role
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null || (!("ADMIN".equals(session.role)) && !("USER".equals(session.role)))) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid or expired token"));
            return;
        }

        String deviceId = msg.getDeviceId();
        String command = msg.getCommand();

        if (deviceId == null || command == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "deviceId and command required"));
            return;
        }

        // Validate command
        if (!isValidCommand(command)) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid command: " + command));
            return;
        }

        // Check if device exists in registry
        DeviceInfo deviceInfo = deviceRegistry.get(deviceId);
        if (deviceInfo == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Unknown device: " + deviceId));
            return;
        }

        // Check if device is online
        ClientConnection deviceConn = deviceClients.get(deviceId);
        if (deviceConn == null || !deviceInfo.isOnline()) {
            controller.messageIO.send(Message.error(msg.getRequestId(),
                    "Device " + deviceId + " is OFFLINE. Cannot execute command."));
            try {
                db.logCommand(msg.getRequestId(), controller.username, deviceId, command,
                        "FAILED", "Device offline", 0);
            } catch (SQLException ignored) {}
            return;
        }

        // Create pending request
        PendingRequest pending = new PendingRequest(
                msg.getRequestId(), controller.token, deviceId, command);
        pendingRequests.put(msg.getRequestId(), pending);

        // Forward command to device
        Message deviceCmd = new Message(MessageType.CONTROL_COMMAND)
                .setRequestId(msg.getRequestId())
                .setDeviceId(deviceId)
                .setCommand(command)
                .setPayload(msg.getPayload());

        try {
            deviceConn.messageIO.send(deviceCmd);
            logger.info("Routed command " + command + " to device " + deviceId + " (req: " + msg.getRequestId() + ")");
        } catch (IOException e) {
            pendingRequests.remove(msg.getRequestId());
            controller.messageIO.send(Message.error(msg.getRequestId(),
                    "Failed to send command to device: " + e.getMessage()));
            return;
        }

        // Wait for response with timeout
        long startTime = System.currentTimeMillis();
        try {
            Message response = pending.responseFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            // Forward response to controller
            response.setRequestId(msg.getRequestId());
            controller.messageIO.send(response);

            // Log command
            db.logCommand(msg.getRequestId(), controller.username, deviceId, command,
                    response.getStatus(), response.getMessage(), elapsed);

        } catch (TimeoutException e) {
            pendingRequests.remove(msg.getRequestId());
            controller.messageIO.send(Message.error(msg.getRequestId(),
                    "Device " + deviceId + " did not respond within timeout"));
            try {
                db.logCommand(msg.getRequestId(), controller.username, deviceId, command,
                        "TIMEOUT", "Device did not respond", REQUEST_TIMEOUT_MS);
            } catch (SQLException ignored) {}
        } catch (Exception e) {
            pendingRequests.remove(msg.getRequestId());
            controller.messageIO.send(Message.error(msg.getRequestId(),
                    "Error waiting for device response: " + e.getMessage()));
        }
    }

    /**
     * Handle a device response - match to pending request and complete the future.
     */
    private void handleDeviceResponse(ClientConnection device, Message msg) {
        String requestId = msg.getRequestId();
        if (requestId == null) {
            logger.warning("Device response without requestId from " + device.deviceId);
            return;
        }

        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending == null) {
            logger.fine("No pending request for: " + requestId);
            return;
        }

        // Verify device is the expected one
        if (!device.deviceId.equals(pending.deviceId)) {
            logger.warning("Device response from wrong device: expected " + pending.deviceId + ", got " + device.deviceId);
            return;
        }

        // Update device status based on response
        if ("SUCCESS".equals(msg.getStatus())) {
            DeviceInfo info = deviceRegistry.get(device.deviceId);
            if (info != null && msg.getDeviceStatus() != null) {
                info.setDeviceStatus(msg.getDeviceStatus());
            }
        }

        // Complete the future to unblock the controller handler
        pending.responseFuture.complete(msg);

        logger.info("Device response for req " + requestId + ": " + msg.getStatus());
    }

    /**
     * Handle a status update from a device (proactive push).
     */
    private void handleStatusUpdate(ClientConnection device, Message msg) {
        DeviceInfo info = deviceRegistry.get(device.deviceId);
        if (info != null) {
            if (msg.getDeviceStatus() != null) {
                info.setDeviceStatus(msg.getDeviceStatus());
            }
            info.setLastSeen(System.currentTimeMillis());
        }
        try {
            db.updateDeviceLastSeen(device.deviceId);
        } catch (SQLException ignored) {}
        logger.fine("Status update from " + device.deviceId + ": " + msg.getDeviceStatus());
    }

    /**
     * Handle voice/text command parsing.
     */
    private void handleVoiceCommand(ClientConnection controller, Message msg) throws IOException {
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid token"));
            return;
        }

        String text = msg.getCommand();
        if (text == null || text.isBlank()) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Empty voice command"));
            return;
        }

        VoiceCommandParser.ParsedCommand parsed = VoiceCommandParser.parse(text);

        if (parsed.ambiguous || parsed.deviceId == null || parsed.command == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(),
                    parsed.description + ". Vui lòng nhập rõ hơn, ví dụ: bật đèn phòng khách"));
            return;
        }

        // Convert to control command and process
        Message controlMsg = Message.controlCommand(msg.getToken(), parsed.deviceId, parsed.command)
                .setRequestId(msg.getRequestId());
        handleControlCommand(controller, controlMsg);
    }

    /**
     * Handle status request from controller.
     */
    private void handleStatusRequest(ClientConnection controller, Message msg) throws IOException {
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid token"));
            return;
        }

        String deviceId = msg.getDeviceId();
        if (deviceId != null) {
            // Single device status
            DeviceInfo info = deviceRegistry.get(deviceId);
            if (info == null) {
                controller.messageIO.send(Message.error(msg.getRequestId(), "Unknown device: " + deviceId));
                return;
            }

            // If device is online, request fresh status
            ClientConnection deviceConn = deviceClients.get(deviceId);
            if (deviceConn != null && info.isOnline()) {
                PendingRequest pending = new PendingRequest(msg.getRequestId(), controller.token, deviceId, "STATUS");
                pendingRequests.put(msg.getRequestId(), pending);

                Message statusReq = new Message(MessageType.STATUS_REQUEST)
                        .setRequestId(msg.getRequestId())
                        .setDeviceId(deviceId);
                deviceConn.messageIO.send(statusReq);

                try {
                    Message response = pending.responseFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    controller.messageIO.send(response);
                } catch (Exception e) {
                    pendingRequests.remove(msg.getRequestId());
                    // Send cached status
                    Message cached = new Message(MessageType.STATUS_UPDATE)
                            .setRequestId(msg.getRequestId())
                            .setDeviceId(deviceId)
                            .setConnectionStatus(info.getConnectionStatus())
                            .setDeviceStatus(info.getDeviceStatus())
                            .setMessage("Cached status (device did not respond in time)");
                    controller.messageIO.send(cached);
                }
            } else {
                // Device offline, send cached info
                Message cached = new Message(MessageType.STATUS_UPDATE)
                        .setRequestId(msg.getRequestId())
                        .setDeviceId(deviceId)
                        .setConnectionStatus("OFFLINE")
                        .setDeviceStatus(info.getDeviceStatus())
                        .setMessage("Device is offline");
                controller.messageIO.send(cached);
            }
        } else {
            // All devices status
            sendDeviceList(controller.messageIO, controller.token);
        }
    }

    /**
     * Handle history request. ADMIN sees all, USER sees only own history.
     */
    private void handleHistoryRequest(ClientConnection controller, Message msg) throws IOException {
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid token"));
            return;
        }
        try {
            List<String> history;
            if ("ADMIN".equals(session.role)) {
                history = db.getCommandHistory(50);
            } else {
                history = db.getCommandHistoryForUser(session.username, 50);
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("history", history);
            Message response = new Message(MessageType.HISTORY_RESPONSE)
                    .setRequestId(msg.getRequestId())
                    .setStatus("SUCCESS")
                    .setPayload(payload);
            controller.messageIO.send(response);
        } catch (SQLException e) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Database error: " + e.getMessage()));
        }
    }

    /**
     * Handle USER_MANAGEMENT requests. ADMIN only.
     * Actions: LIST_USERS, CREATE_USER, DISABLE_USER, ENABLE_USER, SET_ROLE, RESET_PASSWORD
     */
    private void handleUserManagement(ClientConnection controller, Message msg) throws IOException {
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid token"));
            return;
        }

        // Authorization: ADMIN only
        if (!"ADMIN".equals(session.role)) {
            controller.messageIO.send(Message.permissionDenied(msg.getRequestId(),
                    "Chỉ ADMIN mới có quyền quản lý người dùng"));
            logger.warning("PERMISSION_DENIED: " + session.username + " attempted USER_MANAGEMENT");
            return;
        }

        String action = msg.getAction();
        if (action == null || action.isBlank()) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Missing action field"));
            return;
        }

        try {
            switch (action) {
                case "LIST_USERS" -> {
                    List<Map<String, String>> users = db.getAllUsers();
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("users", users);
                    Message response = new Message(MessageType.USER_MANAGEMENT_RESULT)
                            .setRequestId(msg.getRequestId())
                            .setAction("LIST_USERS")
                            .setStatus("SUCCESS")
                            .setPayload(payload);
                    controller.messageIO.send(response);
                }
                case "CREATE_USER" -> {
                    String targetUser = msg.getTargetUsername();
                    String newPass = msg.getNewPassword();
                    String role = msg.getRole();
                    if (targetUser == null || targetUser.isBlank() || newPass == null || newPass.isBlank()) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Username and password required"));
                        return;
                    }
                    if (role == null || role.isBlank()) role = "USER";
                    if (!"ADMIN".equals(role) && !"USER".equals(role)) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Role must be ADMIN or USER"));
                        return;
                    }
                    boolean created = db.createUser(targetUser, newPass, role);
                    if (created) {
                        db.logEvent("USER_CREATED", session.username, "Created user: " + targetUser + " (" + role + ")");
                        controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                                .setRequestId(msg.getRequestId())
                                .setAction("CREATE_USER")
                                .setStatus("SUCCESS")
                                .setMessage("Đã tạo tài khoản: " + targetUser));
                    } else {
                        controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                                .setRequestId(msg.getRequestId())
                                .setAction("CREATE_USER")
                                .setStatus("FAILURE")
                                .setMessage("Tên người dùng đã tồn tại: " + targetUser));
                    }
                }
                case "DISABLE_USER" -> {
                    String targetUser = msg.getTargetUsername();
                    if (targetUser == null || targetUser.isBlank()) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Target username required"));
                        return;
                    }
                    // Protect last admin
                    String targetRole = db.getUserRole(targetUser);
                    if ("ADMIN".equals(targetRole) && db.countAdmins() <= 1) {
                        controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                                .setRequestId(msg.getRequestId())
                                .setAction("DISABLE_USER")
                                .setStatus("FAILURE")
                                .setMessage("Không thể vô hiệu hóa admin cuối cùng"));
                        return;
                    }
                    db.setUserEnabled(targetUser, false);
                    sessionManager.revokeAllForUser(targetUser);
                    db.logEvent("USER_DISABLED", session.username, "Disabled user: " + targetUser);
                    controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                            .setRequestId(msg.getRequestId())
                            .setAction("DISABLE_USER")
                            .setStatus("SUCCESS")
                            .setMessage("Đã vô hiệu hóa: " + targetUser));
                }
                case "ENABLE_USER" -> {
                    String targetUser = msg.getTargetUsername();
                    if (targetUser == null || targetUser.isBlank()) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Target username required"));
                        return;
                    }
                    db.setUserEnabled(targetUser, true);
                    db.logEvent("USER_ENABLED", session.username, "Enabled user: " + targetUser);
                    controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                            .setRequestId(msg.getRequestId())
                            .setAction("ENABLE_USER")
                            .setStatus("SUCCESS")
                            .setMessage("Đã kích hoạt: " + targetUser));
                }
                case "SET_ROLE" -> {
                    String targetUser = msg.getTargetUsername();
                    String newRole = msg.getRole();
                    if (targetUser == null || newRole == null) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Username and role required"));
                        return;
                    }
                    if (!"ADMIN".equals(newRole) && !"USER".equals(newRole)) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Role must be ADMIN or USER"));
                        return;
                    }
                    // Protect last admin when demoting
                    String currentRole = db.getUserRole(targetUser);
                    if ("ADMIN".equals(currentRole) && "USER".equals(newRole) && db.countAdmins() <= 1) {
                        controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                                .setRequestId(msg.getRequestId())
                                .setAction("SET_ROLE")
                                .setStatus("FAILURE")
                                .setMessage("Không thể hạ quyền admin cuối cùng"));
                        return;
                    }
                    db.setUserRole(targetUser, newRole);
                    sessionManager.revokeAllForUser(targetUser); // Force re-login to get new role
                    db.logEvent("ROLE_CHANGED", session.username, "Changed " + targetUser + " role to " + newRole);
                    controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                            .setRequestId(msg.getRequestId())
                            .setAction("SET_ROLE")
                            .setStatus("SUCCESS")
                            .setMessage("Đã đổi vai trò " + targetUser + " thành " + newRole));
                }
                case "RESET_PASSWORD" -> {
                    String targetUser = msg.getTargetUsername();
                    String newPass = msg.getNewPassword();
                    if (targetUser == null || newPass == null || newPass.isBlank()) {
                        controller.messageIO.send(Message.error(msg.getRequestId(), "Username and new password required"));
                        return;
                    }
                    db.updatePassword(targetUser, newPass);
                    sessionManager.revokeAllForUser(targetUser);
                    db.logEvent("PASSWORD_RESET", session.username, "Reset password for: " + targetUser);
                    controller.messageIO.send(new Message(MessageType.USER_MANAGEMENT_RESULT)
                            .setRequestId(msg.getRequestId())
                            .setAction("RESET_PASSWORD")
                            .setStatus("SUCCESS")
                            .setMessage("Đã đặt lại mật khẩu cho: " + targetUser));
                }
                default -> controller.messageIO.send(Message.error(msg.getRequestId(), "Unknown action: " + action));
            }
        } catch (SQLException e) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Database error: " + e.getMessage()));
        }
    }

    /**
     * Handle CHANGE_PASSWORD request. Any authenticated user can change their own password.
     */
    private void handleChangePassword(ClientConnection controller, Message msg) throws IOException {
        SessionManager.SessionInfo session = sessionManager.validateToken(msg.getToken());
        if (session == null) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Invalid token"));
            return;
        }

        String oldPassword = msg.getPassword();
        String newPassword = msg.getNewPassword();
        if (oldPassword == null || newPassword == null || newPassword.isBlank()) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Old and new password required"));
            return;
        }

        if (newPassword.length() < 4) {
            controller.messageIO.send(new Message(MessageType.CHANGE_PASSWORD_RESULT)
                    .setRequestId(msg.getRequestId())
                    .setStatus("FAILURE")
                    .setMessage("Mật khẩu mới phải có ít nhất 4 ký tự"));
            return;
        }

        try {
            // Verify old password
            boolean valid = db.authenticateUser(session.username, oldPassword);
            if (!valid) {
                controller.messageIO.send(new Message(MessageType.CHANGE_PASSWORD_RESULT)
                        .setRequestId(msg.getRequestId())
                        .setStatus("FAILURE")
                        .setMessage("Mật khẩu cũ không đúng"));
                return;
            }

            db.updatePassword(session.username, newPassword);
            db.logEvent("PASSWORD_CHANGED", session.username, "Password changed by user");
            controller.messageIO.send(new Message(MessageType.CHANGE_PASSWORD_RESULT)
                    .setRequestId(msg.getRequestId())
                    .setStatus("SUCCESS")
                    .setMessage("Đã đổi mật khẩu thành công"));

        } catch (SQLException e) {
            controller.messageIO.send(Message.error(msg.getRequestId(), "Database error: " + e.getMessage()));
        }
    }

    /**
     * Send the full device list to a controller.
     */
    private void sendDeviceList(MessageIO io, String token) throws IOException {
        List<Map<String, String>> deviceList = new ArrayList<>();
        for (DeviceInfo info : deviceRegistry.values()) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("deviceId", info.getDeviceId());
            d.put("deviceType", info.getDeviceType());
            d.put("deviceName", info.getDeviceName());
            d.put("connectionStatus", info.getConnectionStatus());
            d.put("deviceStatus", info.getDeviceStatus());
            deviceList.add(d);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("devices", deviceList);

        Message msg = new Message(MessageType.DEVICE_LIST)
                .setStatus("SUCCESS")
                .setPayload(payload);
        io.send(msg);
    }

    /**
     * Broadcast updated device list to all connected controllers.
     */
    private void broadcastDeviceListToControllers() {
        for (ClientConnection ctrl : controllerClients.values()) {
            try {
                sendDeviceList(ctrl.messageIO, ctrl.token);
            } catch (IOException e) {
                logger.fine("Failed to broadcast to controller: " + e.getMessage());
            }
        }
    }

    private boolean isValidCommand(String command) {
        return command != null && (
                command.equals("ON") || command.equals("OFF") ||
                command.equals("STATUS") || command.equals("TOGGLE")
        );
    }

    /**
     * Periodic heartbeat check for all connections.
     */
    private void heartbeatCheck() {
        long now = System.currentTimeMillis();

        // Check device connections
        for (Map.Entry<String, ClientConnection> entry : deviceClients.entrySet()) {
            ClientConnection conn = entry.getValue();
            if (now - conn.lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                logger.warning("Heartbeat timeout for device: " + entry.getKey());
                try { conn.socket.close(); } catch (IOException ignored) {}
            } else {
                try {
                    conn.messageIO.send(Message.heartbeat());
                } catch (IOException e) {
                    logger.fine("Failed to send heartbeat to device: " + entry.getKey());
                }
            }
        }

        // Check controller connections
        for (Map.Entry<String, ClientConnection> entry : controllerClients.entrySet()) {
            ClientConnection conn = entry.getValue();
            if (now - conn.lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                logger.warning("Heartbeat timeout for controller: " + conn.username);
                try { conn.socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Clean up expired pending requests.
     */
    private void cleanupExpiredRequests() {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            if (entry.getValue().isExpired()) {
                entry.getValue().responseFuture.complete(
                        Message.error(entry.getKey(), "Request timeout"));
                pendingRequests.remove(entry.getKey());
            }
        }
    }

    /**
     * Graceful shutdown.
     */
    public void shutdown() {
        running = false;
        logger.info("Shutting down server...");

        // Stop scheduler
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        // Close all device connections
        for (ClientConnection conn : deviceClients.values()) {
            try {
                conn.messageIO.send(Message.error(null, "Server shutting down"));
                conn.socket.close();
            } catch (IOException ignored) {}
        }
        deviceClients.clear();

        // Close all controller connections
        for (ClientConnection conn : controllerClients.values()) {
            try {
                conn.messageIO.send(Message.error(null, "Server shutting down"));
                conn.socket.close();
            } catch (IOException ignored) {}
        }
        controllerClients.clear();

        // Shutdown thread pool
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
        }

        // Close server socket
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }

        // Close database
        if (db != null) {
            try {
                db.logEvent("SERVER_STOP", "SERVER", "Smart Hub Server stopped");
            } catch (SQLException ignored) {}
            db.close();
        }

        logger.info("Server shut down complete");
    }

    public boolean isRunning() { return running; }

    public static void main(String[] args) {
        int port = 5000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ", using default 5000");
            }
        }

        SmartHomeHubServer server = new SmartHomeHubServer(port);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.shutdown();
        }));

        server.start();
    }
}
