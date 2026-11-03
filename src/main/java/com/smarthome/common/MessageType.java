package com.smarthome.common;

public enum MessageType {
    // Authentication
    LOGIN,
    LOGIN_RESULT,
    LOGOUT,

    // Device registration
    REGISTER_DEVICE,
    REGISTER_RESULT,

    // Device control
    CONTROL_COMMAND,
    DEVICE_RESPONSE,

    // Status
    DEVICE_LIST,
    DEVICE_LIST_REQUEST,
    STATUS_UPDATE,
    STATUS_REQUEST,

    // System
    HEARTBEAT,
    HEARTBEAT_ACK,
    ERROR,

    // Voice
    VOICE_COMMAND,

    // History
    HISTORY_REQUEST,
    HISTORY_RESPONSE,

    // User management (Admin only)
    USER_MANAGEMENT,
    USER_MANAGEMENT_RESULT,

    // Self-service
    CHANGE_PASSWORD,
    CHANGE_PASSWORD_RESULT,

    // Authorization
    PERMISSION_DENIED,

    // Real-time sync broadcast (server pushes to all controllers)
    SYNC_STATE_BROADCAST,    // Device state changed — broadcast to all controllers
    SYNC_HISTORY_BROADCAST   // New command history entry — broadcast to all controllers
}
