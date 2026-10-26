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
    PERMISSION_DENIED
}
