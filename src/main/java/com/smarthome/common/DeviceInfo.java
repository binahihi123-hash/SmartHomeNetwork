package com.smarthome.common;

/**
 * Represents information about a registered device.
 * Separates connection status (ONLINE/OFFLINE) from device status (ON/OFF).
 */
public class DeviceInfo {
    private String deviceId;
    private String deviceType;
    private String deviceName;
    private String connectionStatus; // ONLINE or OFFLINE
    private String deviceStatus;     // ON, OFF, or UNKNOWN
    private long lastSeen;

    public DeviceInfo() {}

    public DeviceInfo(String deviceId, String deviceType, String deviceName) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.deviceName = deviceName;
        this.connectionStatus = "OFFLINE";
        this.deviceStatus = "UNKNOWN";
        this.lastSeen = System.currentTimeMillis();
    }

    // Getters and setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }
    public String getDeviceStatus() { return deviceStatus; }
    public void setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    public boolean isOnline() { return "ONLINE".equals(connectionStatus); }

    @Override
    public String toString() {
        return deviceName + " (" + deviceId + ") [" + connectionStatus + "/" + deviceStatus + "]";
    }
}
