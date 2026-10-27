package com.smarthome.device;

import com.smarthome.common.*;
import com.smarthome.security.TLSConfig;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Device Client - Simulates a smart home device.
 * Connects to the Smart Hub Server via TCP, registers itself,
 * receives commands, processes them, and sends responses.
 * Implements automatic reconnection with exponential backoff.
 */
public class DeviceSimulator {
    private static final Logger logger = Logger.getLogger(DeviceSimulator.class.getName());

    private final String deviceId;
    private final String deviceType;
    private final String deviceName;
    private final String deviceSecret;
    private final String serverHost;
    private final int serverPort;
    private final DeviceCommandHandler commandHandler;
    private final TLSConfig tlsConfig;

    private Socket socket;
    private MessageIO messageIO;
    private String token;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // Reconnection settings
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;
    private static final double RETRY_MULTIPLIER = 2.0;

    // Heartbeat
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public DeviceSimulator(String deviceId, String deviceType, String deviceName,
                           String deviceSecret, String serverHost, int serverPort) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.deviceName = deviceName;
        this.deviceSecret = deviceSecret;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.commandHandler = new DeviceCommandHandler(deviceId, deviceType);
        this.tlsConfig = TLSConfig.fromEnvironment();
    }

    /**
     * Start the device simulator with automatic reconnection.
     */
    public void start() {
        running = true;
        System.out.println("====================================");
        System.out.println("  DEVICE: " + deviceName);
        System.out.println("  ID: " + deviceId);
        System.out.println("  Type: " + deviceType);
        System.out.println("====================================");

        // Start heartbeat sender
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);

        // Connect with auto-reconnect
        long retryDelay = INITIAL_RETRY_DELAY_MS;

        while (running) {
            try {
                connect();
                retryDelay = INITIAL_RETRY_DELAY_MS; // Reset on successful connection
                listen(); // Blocks until disconnected
            } catch (Exception e) {
                connected = false;
                System.err.println("[" + deviceId + "] Connection lost: " + e.getMessage());
                logger.warning("Connection lost for " + deviceId + ": " + e.getMessage());
            }

            if (!running) break;

            // Exponential backoff reconnection
            System.out.println("[" + deviceId + "] Reconnecting in " + (retryDelay / 1000) + "s...");
            try {
                Thread.sleep(retryDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            retryDelay = Math.min((long) (retryDelay * RETRY_MULTIPLIER), MAX_RETRY_DELAY_MS);
        }

        shutdown();
    }

    /**
     * Connect to the server and register the device.
     */
    private void connect() throws Exception {
        System.out.println("[" + deviceId + "] Connecting to " + serverHost + ":" + serverPort + "...");

        socket = tlsConfig.createClientSocket(serverHost, serverPort);
        socket.setSoTimeout(60000);
        messageIO = new MessageIO(socket);

        // Send registration
        Message regMsg = Message.registerDevice(deviceId, deviceType, deviceName, deviceSecret);
        messageIO.send(regMsg);

        // Wait for registration result
        Message response = messageIO.receive();
        if (response == null) {
            throw new IOException("No response from server");
        }

        if (response.getMessageType() == MessageType.REGISTER_RESULT) {
            if ("SUCCESS".equals(response.getStatus())) {
                token = response.getToken();
                connected = true;
                System.out.println("[" + deviceId + "] Registered successfully. Token received.");
                logger.info(deviceId + " registered successfully");
            } else {
                throw new IOException("Registration failed: " + response.getMessage());
            }
        } else {
            throw new IOException("Unexpected response: " + response.getType());
        }
    }

    /**
     * Main listening loop - receive and process commands from server.
     */
    private void listen() throws IOException {
        System.out.println("[" + deviceId + "] Listening for commands...");

        while (running && connected && !socket.isClosed()) {
            Message msg = messageIO.receive();
            if (msg == null) {
                throw new IOException("Server closed connection");
            }

            MessageType type = msg.getMessageType();
            if (type == null) continue;

            switch (type) {
                case CONTROL_COMMAND -> processCommand(msg);
                case STATUS_REQUEST -> sendStatusUpdate(msg.getRequestId());
                case HEARTBEAT -> {
                    messageIO.send(Message.heartbeatAck());
                }
                case HEARTBEAT_ACK -> {
                    // Server acknowledged our heartbeat
                }
                case ERROR -> {
                    System.err.println("[" + deviceId + "] Server error: " + msg.getMessage());
                    if ("Server shutting down".equals(msg.getMessage())) {
                        connected = false;
                    }
                }
                default -> {
                    System.err.println("[" + deviceId + "] Unknown message type: " + type);
                }
            }
        }
    }

    /**
     * Process a control command and send response.
     */
    private void processCommand(Message msg) {
        String command = msg.getCommand();
        String reqDeviceId = msg.getDeviceId();

        System.out.println("[" + deviceId + "] Received command: " + command + " (req: " + msg.getRequestId() + ")");

        // Verify command is for this device
        if (reqDeviceId != null && !reqDeviceId.equals(deviceId)) {
            System.err.println("[" + deviceId + "] Command for wrong device: " + reqDeviceId);
            try {
                messageIO.send(Message.deviceResponse(msg.getRequestId(), deviceId, false,
                        "Command sent to wrong device"));
            } catch (IOException e) {
                logger.warning("Failed to send error response: " + e.getMessage());
            }
            return;
        }

        // Process command through handler
        DeviceCommandHandler.CommandResult result = commandHandler.execute(command);

        // Send response
        try {
            Message response = Message.deviceResponse(msg.getRequestId(), deviceId, result.success, result.message)
                    .setDeviceStatus(commandHandler.getCurrentStatus());
            messageIO.send(response);
            System.out.println("[" + deviceId + "] Response: " + result.message + " (status: " + commandHandler.getCurrentStatus() + ")");
        } catch (IOException e) {
            logger.warning("Failed to send command response: " + e.getMessage());
            connected = false;
        }
    }

    /**
     * Send current status to server.
     */
    private void sendStatusUpdate(String requestId) {
        try {
            Message statusMsg = new Message(MessageType.STATUS_UPDATE)
                    .setRequestId(requestId)
                    .setDeviceId(deviceId)
                    .setDeviceType(deviceType)
                    .setDeviceName(deviceName)
                    .setDeviceStatus(commandHandler.getCurrentStatus())
                    .setConnectionStatus("ONLINE")
                    .setStatus("SUCCESS");
            messageIO.send(statusMsg);
            System.out.println("[" + deviceId + "] Status sent: " + commandHandler.getCurrentStatus());
        } catch (IOException e) {
            logger.warning("Failed to send status update: " + e.getMessage());
            connected = false;
        }
    }

    /**
     * Send periodic heartbeat.
     */
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
     * Shutdown the device simulator.
     */
    public void shutdown() {
        running = false;
        connected = false;
        scheduler.shutdownNow();
        // Close socket FIRST to unblock any blocked readLine() in listener
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        if (messageIO != null) messageIO.close();
        System.out.println("[" + deviceId + "] Device shut down.");
    }

    public boolean isConnected() { return connected; }
    public String getDeviceId() { return deviceId; }

    /**
     * Main method to run a single device simulator.
     * Usage: java DeviceSimulator <deviceId> <deviceType> <deviceName> [host] [port]
     */
    public static void main(String[] args) {
        String deviceId = args.length > 0 ? args[0] : "DEVICE_LIGHT_01";
        String deviceType = args.length > 1 ? args[1] : "LIGHT";
        String deviceName = args.length > 2 ? args[2] : "Đèn phòng khách";
        String host = args.length > 3 ? args[3] : "127.0.0.1";
        int port = args.length > 4 ? Integer.parseInt(args[4]) : 5000;
        String secret = deviceId + "_secret";

        DeviceSimulator device = new DeviceSimulator(deviceId, deviceType, deviceName, secret, host, port);

        Runtime.getRuntime().addShutdownHook(new Thread(device::shutdown));

        device.start();
    }
}
