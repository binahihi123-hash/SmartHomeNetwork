package com.smarthome.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a JSON protocol message for the Smart Home system.
 * Each message is serialized as a single JSON line terminated by \n.
 */
public class Message {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_MESSAGE_LENGTH = 65536;

    private String type;
    private String requestId;
    private String token;
    private String deviceId;
    private String deviceType;
    private String deviceName;
    private String command;
    private Map<String, Object> payload;
    private String status;
    private String message;
    private String timestamp;
    private String username;
    private String password;
    private String role; // ADMIN, USER, or DEVICE
    private String connectionStatus; // ONLINE or OFFLINE
    private String deviceStatus; // ON or OFF
    private String action; // For USER_MANAGEMENT: CREATE_USER, LIST_USERS, DISABLE_USER, ENABLE_USER, SET_ROLE, RESET_PASSWORD
    private String targetUsername; // For USER_MANAGEMENT operations
    private String newPassword; // For CHANGE_PASSWORD / RESET_PASSWORD
    private String room; // Device room/location

    public Message() {
        this.requestId = UUID.randomUUID().toString().substring(0, 8);
        this.timestamp = Instant.now().toString();
    }

    public Message(MessageType type) {
        this();
        this.type = type.name();
    }

    // Serialization
    public String toJson() {
        return GSON.toJson(this);
    }

    public static Message fromJson(String json) throws JsonSyntaxException {
        if (json == null || json.isBlank()) {
            throw new JsonSyntaxException("Empty message");
        }
        if (json.length() > MAX_MESSAGE_LENGTH) {
            throw new JsonSyntaxException("Message too long: " + json.length() + " bytes");
        }
        Message msg = GSON.fromJson(json, Message.class);
        if (msg.type == null || msg.type.isBlank()) {
            throw new JsonSyntaxException("Missing required field: type");
        }
        return msg;
    }

    public MessageType getMessageType() {
        try {
            return MessageType.valueOf(this.type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Builder pattern methods
    public Message setType(MessageType type) { this.type = type.name(); return this; }
    public Message setType(String type) { this.type = type; return this; }
    public Message setRequestId(String requestId) { this.requestId = requestId; return this; }
    public Message setToken(String token) { this.token = token; return this; }
    public Message setDeviceId(String deviceId) { this.deviceId = deviceId; return this; }
    public Message setDeviceType(String deviceType) { this.deviceType = deviceType; return this; }
    public Message setDeviceName(String deviceName) { this.deviceName = deviceName; return this; }
    public Message setCommand(String command) { this.command = command; return this; }
    public Message setPayload(Map<String, Object> payload) { this.payload = payload; return this; }
    public Message setStatus(String status) { this.status = status; return this; }
    public Message setMessage(String message) { this.message = message; return this; }
    public Message setTimestamp(String timestamp) { this.timestamp = timestamp; return this; }
    public Message setUsername(String username) { this.username = username; return this; }
    public Message setPassword(String password) { this.password = password; return this; }
    public Message setRole(String role) { this.role = role; return this; }
    public Message setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; return this; }
    public Message setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; return this; }
    public Message setAction(String action) { this.action = action; return this; }
    public Message setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; return this; }
    public Message setNewPassword(String newPassword) { this.newPassword = newPassword; return this; }
    public Message setRoom(String room) { this.room = room; return this; }

    // Getters
    public String getType() { return type; }
    public String getRequestId() { return requestId; }
    public String getToken() { return token; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceType() { return deviceType; }
    public String getDeviceName() { return deviceName; }
    public String getCommand() { return command; }
    public Map<String, Object> getPayload() { return payload; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getTimestamp() { return timestamp; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getRole() { return role; }
    public String getConnectionStatus() { return connectionStatus; }
    public String getDeviceStatus() { return deviceStatus; }
    public String getAction() { return action; }
    public String getTargetUsername() { return targetUsername; }
    public String getNewPassword() { return newPassword; }
    public String getRoom() { return room; }

    // Factory methods for common messages
    public static Message loginRequest(String username, String hashedPassword) {
        return new Message(MessageType.LOGIN)
                .setUsername(username)
                .setPassword(hashedPassword)
                .setRole("CONTROLLER");
    }

    public static Message loginResult(String requestId, boolean success, String token, String message) {
        return new Message(MessageType.LOGIN_RESULT)
                .setRequestId(requestId)
                .setStatus(success ? "SUCCESS" : "FAILURE")
                .setToken(token)
                .setMessage(message);
    }

    public static Message loginResultWithRole(String requestId, boolean success, String token, String role, String message) {
        return new Message(MessageType.LOGIN_RESULT)
                .setRequestId(requestId)
                .setStatus(success ? "SUCCESS" : "FAILURE")
                .setToken(token)
                .setRole(role)
                .setMessage(message);
    }

    public static Message permissionDenied(String requestId, String message) {
        return new Message(MessageType.PERMISSION_DENIED)
                .setRequestId(requestId)
                .setStatus("DENIED")
                .setMessage(message);
    }

    public static Message registerDevice(String deviceId, String deviceType, String deviceName, String deviceSecret) {
        return new Message(MessageType.REGISTER_DEVICE)
                .setDeviceId(deviceId)
                .setDeviceType(deviceType)
                .setDeviceName(deviceName)
                .setPassword(deviceSecret)
                .setRole("DEVICE");
    }

    public static Message registerResult(String requestId, boolean success, String token, String message) {
        return new Message(MessageType.REGISTER_RESULT)
                .setRequestId(requestId)
                .setStatus(success ? "SUCCESS" : "FAILURE")
                .setToken(token)
                .setMessage(message);
    }

    public static Message controlCommand(String token, String deviceId, String command) {
        return new Message(MessageType.CONTROL_COMMAND)
                .setToken(token)
                .setDeviceId(deviceId)
                .setCommand(command);
    }

    public static Message deviceResponse(String requestId, String deviceId, boolean success, String message) {
        return new Message(MessageType.DEVICE_RESPONSE)
                .setRequestId(requestId)
                .setDeviceId(deviceId)
                .setStatus(success ? "SUCCESS" : "FAILURE")
                .setMessage(message);
    }

    public static Message error(String requestId, String message) {
        return new Message(MessageType.ERROR)
                .setRequestId(requestId)
                .setStatus("ERROR")
                .setMessage(message);
    }

    public static Message heartbeat() {
        return new Message(MessageType.HEARTBEAT);
    }

    public static Message heartbeatAck() {
        return new Message(MessageType.HEARTBEAT_ACK);
    }

    public static Message deviceListRequest(String token) {
        return new Message(MessageType.DEVICE_LIST_REQUEST)
                .setToken(token);
    }

    public static Message statusRequest(String token, String deviceId) {
        return new Message(MessageType.STATUS_REQUEST)
                .setToken(token)
                .setDeviceId(deviceId);
    }

    @Override
    public String toString() {
        return "Message{type=" + type + ", requestId=" + requestId + ", deviceId=" + deviceId + "}";
    }
}
