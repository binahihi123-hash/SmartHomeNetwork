package com.smarthome.device;

import java.util.Set;

/**
 * Handles commands for a specific device type.
 * Each device type supports different commands.
 * Simulates hardware behavior with internal state.
 */
public class DeviceCommandHandler {
    private final String deviceId;
    private final String deviceType;
    private String currentStatus = "OFF";

    // Valid commands per device type
    private static final Set<String> LIGHT_COMMANDS = Set.of("ON", "OFF", "STATUS", "TOGGLE");
    private static final Set<String> FAN_COMMANDS = Set.of("ON", "OFF", "STATUS", "TOGGLE");
    private static final Set<String> AC_COMMANDS = Set.of("ON", "OFF", "STATUS", "TOGGLE");
    private static final Set<String> TV_COMMANDS = Set.of("ON", "OFF", "STATUS", "TOGGLE");
    private static final Set<String> DEFAULT_COMMANDS = Set.of("ON", "OFF", "STATUS");

    public static class CommandResult {
        public final boolean success;
        public final String message;

        public CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public DeviceCommandHandler(String deviceId, String deviceType) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
    }

    /**
     * Execute a command and return the result.
     */
    public CommandResult execute(String command) {
        if (command == null || command.isBlank()) {
            return new CommandResult(false, "Empty command");
        }

        command = command.toUpperCase().trim();

        // Check if command is valid for this device type
        if (!isValidCommand(command)) {
            return new CommandResult(false, "Unsupported command '" + command + "' for device type " + deviceType);
        }

        // Simulate processing delay (realistic hardware response)
        try {
            Thread.sleep(100 + (long)(Math.random() * 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        switch (command) {
            case "ON" -> {
                if ("ON".equals(currentStatus)) {
                    return new CommandResult(true, getDeviceLabel() + " is already ON");
                }
                currentStatus = "ON";
                System.out.println("  [HW] " + getDeviceLabel() + " -> ON");
                return new CommandResult(true, getDeviceLabel() + " turned ON");
            }
            case "OFF" -> {
                if ("OFF".equals(currentStatus)) {
                    return new CommandResult(true, getDeviceLabel() + " is already OFF");
                }
                currentStatus = "OFF";
                System.out.println("  [HW] " + getDeviceLabel() + " -> OFF");
                return new CommandResult(true, getDeviceLabel() + " turned OFF");
            }
            case "TOGGLE" -> {
                currentStatus = "ON".equals(currentStatus) ? "OFF" : "ON";
                System.out.println("  [HW] " + getDeviceLabel() + " -> " + currentStatus);
                return new CommandResult(true, getDeviceLabel() + " toggled to " + currentStatus);
            }
            case "STATUS" -> {
                return new CommandResult(true, getDeviceLabel() + " is " + currentStatus);
            }
            default -> {
                return new CommandResult(false, "Unknown command: " + command);
            }
        }
    }

    private boolean isValidCommand(String command) {
        Set<String> validCommands = switch (deviceType.toUpperCase()) {
            case "LIGHT" -> LIGHT_COMMANDS;
            case "FAN" -> FAN_COMMANDS;
            case "AC" -> AC_COMMANDS;
            case "TV" -> TV_COMMANDS;
            default -> DEFAULT_COMMANDS;
        };
        return validCommands.contains(command);
    }

    private String getDeviceLabel() {
        return deviceType + " (" + deviceId + ")";
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceType() {
        return deviceType;
    }
}
