package com.smarthome.launcher;

import com.smarthome.common.NetworkConfig;
import com.smarthome.device.DeviceLauncher;
import com.smarthome.server.SmartHomeHubServer;

import javax.swing.*;
import java.io.PrintStream;

/**
 * One-click demo launcher that starts all components in a single JVM:
 * 1. Smart Hub Server (port 5000)
 * 2. 3 Device Clients (Light, Fan, AC)
 * 3. Controller GUI
 *
 * Useful for demonstration and testing. For production LAN deployment,
 * run each component separately on different machines.
 * Supports NetworkConfig for flexible host/port configuration.
 */
public class SmartHomeLauncher {

    private static final int SERVER_PORT = 5000;
    private static final String SERVER_HOST = "127.0.0.1";

    public static void main(String[] args) {
        // Use NetworkConfig if available, otherwise defaults
        NetworkConfig config = new NetworkConfig(args);
        int port = config.getServerPort();
        String host = SERVER_HOST; // Launcher always uses localhost since all components are in same JVM

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     SMART HOME SYSTEM - Demo Launcher   ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Starting all components...              ║");
        System.out.println("║  Mode: Single-JVM Demo                   ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
        System.out.println("For 3-machine deployment, run separately:");
        System.out.println("  Machine 1: run-server.bat");
        System.out.println("  Machine 2: run-device.bat");
        System.out.println("  Machine 3: run-client.bat");
        System.out.println();

        // Step 1: Start Server
        System.out.println("[LAUNCHER] Starting Smart Hub Server on port " + port + "...");
        Thread serverThread = new Thread(() -> {
            SmartHomeHubServer server = new SmartHomeHubServer(port, "0.0.0.0");
            server.start();
        }, "ServerThread");
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait for server to initialize
        sleep(2000);
        System.out.println("[LAUNCHER] Server started.");

        // Step 2: Start Device Clients
        System.out.println("[LAUNCHER] Starting Device Clients...");
        Thread devicesThread = new Thread(() -> {
            DeviceLauncher.launchDevices(host, port);
        }, "DevicesThread");
        devicesThread.setDaemon(true);
        devicesThread.start();

        // Wait for devices to register
        sleep(2000);
        System.out.println("[LAUNCHER] Devices registered.");

        // Step 3: Launch Controller GUI
        System.out.println("[LAUNCHER] Starting Controller GUI...");
        System.out.println("[LAUNCHER] Login with:");
        System.out.println("[LAUNCHER]   Admin: admin / admin123");
        System.out.println("[LAUNCHER]   User:  user  / user123");
        System.out.println("[LAUNCHER] Server is at: " + host + ":" + port);
        System.out.println();

        // Launch GUI on EDT
        SwingUtilities.invokeLater(() -> {
            com.smarthome.controller.SmartHomeControllerGUI.main(new String[]{});
        });

        System.out.println("[LAUNCHER] All components started successfully!");
        System.out.println("[LAUNCHER] Press Ctrl+C to stop all components.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
