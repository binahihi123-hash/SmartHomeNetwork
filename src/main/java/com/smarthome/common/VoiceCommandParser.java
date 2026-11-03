package com.smarthome.common;

import java.util.*;

/**
 * Parses Vietnamese text commands into standardized device commands.
 * Maps natural language phrases to deviceId + command pairs.
 */
public class VoiceCommandParser {

    public static class ParsedCommand {
        public final String deviceId;
        public final String command;
        public final boolean ambiguous;
        public final String originalText;
        public final String description;

        public ParsedCommand(String deviceId, String command, boolean ambiguous, String originalText, String description) {
            this.deviceId = deviceId;
            this.command = command;
            this.ambiguous = ambiguous;
            this.originalText = originalText;
            this.description = description;
        }
    }

    private static final Map<String, String> DEVICE_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> COMMAND_ALIASES = new LinkedHashMap<>();

    static {
        // Device aliases (Vietnamese -> deviceId)
        DEVICE_ALIASES.put("đèn phòng khách", "DEVICE_LIGHT_01");
        DEVICE_ALIASES.put("den phong khach", "DEVICE_LIGHT_01");
        DEVICE_ALIASES.put("đèn phòng ngủ", "DEVICE_LIGHT_02");
        DEVICE_ALIASES.put("den phong ngu", "DEVICE_LIGHT_02");
        DEVICE_ALIASES.put("quạt", "DEVICE_FAN_01");
        DEVICE_ALIASES.put("quat", "DEVICE_FAN_01");
        DEVICE_ALIASES.put("máy lạnh", "DEVICE_AC_01");
        DEVICE_ALIASES.put("may lanh", "DEVICE_AC_01");
        DEVICE_ALIASES.put("điều hòa", "DEVICE_AC_01");
        DEVICE_ALIASES.put("dieu hoa", "DEVICE_AC_01");
        DEVICE_ALIASES.put("tv", "DEVICE_TV_01");
        DEVICE_ALIASES.put("ti vi", "DEVICE_TV_01");
        DEVICE_ALIASES.put("tivi", "DEVICE_TV_01");

        // Command aliases
        COMMAND_ALIASES.put("bật", "ON");
        COMMAND_ALIASES.put("bat", "ON");
        COMMAND_ALIASES.put("mở", "ON");
        COMMAND_ALIASES.put("mo", "ON");
        COMMAND_ALIASES.put("tắt", "OFF");
        COMMAND_ALIASES.put("tat", "OFF");
        COMMAND_ALIASES.put("đóng", "OFF");
        COMMAND_ALIASES.put("dong", "OFF");
        COMMAND_ALIASES.put("trạng thái", "STATUS");
        COMMAND_ALIASES.put("trang thai", "STATUS");
        COMMAND_ALIASES.put("kiểm tra", "STATUS");
        COMMAND_ALIASES.put("kiem tra", "STATUS");
    }

    /**
     * Parse a Vietnamese text command into a structured command.
     * Returns a ParsedCommand. If ambiguous, the caller should confirm with the user.
     */
    public static ParsedCommand parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedCommand(null, null, true, text, "Lệnh trống");
        }

        String normalized = text.trim().toLowerCase();

        // Find command (action)
        String command = null;
        for (Map.Entry<String, String> entry : COMMAND_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                command = entry.getValue();
                break;
            }
        }

        // Find device
        String deviceId = null;
        String deviceDesc = null;
        for (Map.Entry<String, String> entry : DEVICE_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                deviceId = entry.getValue();
                deviceDesc = entry.getKey();
                break;
            }
        }

        if (command == null && deviceId == null) {
            return new ParsedCommand(null, null, true, text, "Không hiểu lệnh: " + text);
        }

        if (command == null) {
            return new ParsedCommand(deviceId, null, true, text, "Không xác định được hành động cho " + deviceDesc);
        }

        if (deviceId == null) {
            return new ParsedCommand(null, command, true, text, "Không xác định được thiết bị");
        }

        return new ParsedCommand(deviceId, command, false, text, "OK: " + command + " " + deviceId);
    }

    /**
     * Register additional device aliases at runtime (e.g., from database).
     */
    public static void addDeviceAlias(String alias, String deviceId) {
        DEVICE_ALIASES.put(alias.toLowerCase(), deviceId);
    }
}
