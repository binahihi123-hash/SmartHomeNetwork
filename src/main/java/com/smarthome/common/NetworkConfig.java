package com.smarthome.common;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * Network configuration loader for SmartHomeNetwork.
 * Supports loading server host/port from:
 *   1. Command-line arguments
 *   2. Properties file (network-config.properties)
 *   3. System properties (-Dsmarthome.server.host=...)
 *   4. Environment variables (SMARTHOME_SERVER_HOST, SMARTHOME_SERVER_PORT)
 *   5. Default values (127.0.0.1:5000)
 *
 * Priority order: args > system properties > env vars > properties file > defaults
 *
 * This class enables easy deployment across different networks (LAN, 4G, Ngrok, VPN).
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "network-config.properties";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private final String serverHost;
    private final int serverPort;
    private final String bindAddress;
    private final String publicHost;
    private final int publicPort;
    private final Properties properties;

    /**
     * Load configuration from all sources with priority resolution.
     */
    public NetworkConfig() {
        this(new String[0]);
    }

    /**
     * Load configuration with command-line arguments having highest priority.
     * @param args [host] [port] or [port] depending on component
     */
    public NetworkConfig(String[] args) {
        properties = loadPropertiesFile();

        // Resolve server host and port using local vars (final fields can only be assigned once)
        String host;
        int port;

        if (args.length > 0 && !args[0].isEmpty() && !args[0].startsWith("-")) {
            // First arg could be host or port
            boolean isPort = false;
            try {
                Integer.parseInt(args[0]);
                isPort = true;
            } catch (NumberFormatException ignored) {}

            if (isPort) {
                host = resolveHost();
                port = Integer.parseInt(args[0]);
            } else {
                host = args[0];
                port = args.length > 1 ? parsePort(args[1]) : resolvePort();
            }
        } else {
            host = resolveHost();
            port = resolvePort();
        }

        this.serverHost = host;
        this.serverPort = port;

        // Bind address (for server)
        this.bindAddress = resolveProperty("smarthome.server.bind", "SMARTHOME_SERVER_BIND", "server.bind", "0.0.0.0");

        // Public endpoint (for Ngrok/port-forwarding)
        this.publicHost = resolveProperty("smarthome.public.host", "SMARTHOME_PUBLIC_HOST", "public.host", null);
        this.publicPort = parsePort(resolveProperty("smarthome.public.port", "SMARTHOME_PUBLIC_PORT", "public.port", String.valueOf(serverPort)));
    }

    private String resolveHost() {
        return resolveProperty("smarthome.server.host", "SMARTHOME_SERVER_HOST", "server.host", DEFAULT_HOST);
    }

    private int resolvePort() {
        return parsePort(resolveProperty("smarthome.server.port", "SMARTHOME_SERVER_PORT", "server.port", String.valueOf(DEFAULT_PORT)));
    }

    /**
     * Resolve a property value from system properties > env vars > properties file > default.
     */
    private String resolveProperty(String sysProp, String envVar, String fileProp, String defaultValue) {
        // 1. System property (-D flag)
        String val = System.getProperty(sysProp);
        if (val != null && !val.isEmpty()) return val;

        // 2. Environment variable
        val = System.getenv(envVar);
        if (val != null && !val.isEmpty()) return val;

        // 3. Properties file
        if (properties != null) {
            val = properties.getProperty(fileProp);
            if (val != null && !val.isEmpty()) return val.trim();
        }

        // 4. Default
        return defaultValue;
    }

    private Properties loadPropertiesFile() {
        Properties props = new Properties();
        // Search in current directory, then parent, then classpath
        String[] searchPaths = {
            CONFIG_FILE,
            "../" + CONFIG_FILE,
            "../../" + CONFIG_FILE
        };

        for (String path : searchPaths) {
            File f = new File(path);
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    props.load(fis);
                    System.out.println("[NetworkConfig] Loaded config from: " + f.getAbsolutePath());
                    return props;
                } catch (IOException e) {
                    System.err.println("[NetworkConfig] Failed to load " + path + ": " + e.getMessage());
                }
            }
        }

        // Try classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                System.out.println("[NetworkConfig] Loaded config from classpath");
                return props;
            }
        } catch (IOException ignored) {}

        return props; // Return empty properties
    }

    private int parsePort(String portStr) {
        try {
            int p = Integer.parseInt(portStr.trim());
            if (p > 0 && p < 65536) return p;
        } catch (NumberFormatException | NullPointerException ignored) {}
        return DEFAULT_PORT;
    }

    // === Getters ===

    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }
    public String getBindAddress() { return bindAddress; }

    /**
     * Get the public host for Ngrok/port-forwarding endpoints.
     * Returns null if no public host is configured.
     */
    public String getPublicHost() { return publicHost; }

    /**
     * Get the public port for Ngrok/port-forwarding endpoints.
     */
    public int getPublicPort() { return publicPort; }

    /**
     * Check if a public (Ngrok/forwarded) endpoint is configured.
     */
    public boolean hasPublicEndpoint() {
        return publicHost != null && !publicHost.isEmpty();
    }

    /**
     * Get the connection endpoint string for display.
     * Shows public endpoint if configured, otherwise server host:port.
     */
    public String getDisplayEndpoint() {
        if (hasPublicEndpoint()) {
            return publicHost + ":" + publicPort + " (public) → " + serverHost + ":" + serverPort + " (local)";
        }
        return serverHost + ":" + serverPort;
    }

    /**
     * Detect and display all local network interfaces for deployment help.
     */
    public static String getNetworkInterfaceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Network Interfaces ===\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;

                sb.append("  ").append(ni.getDisplayName()).append(":\n");
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getHostAddress().contains(":")) continue; // Skip IPv6
                    sb.append("    IP: ").append(addr.getHostAddress()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("  (Không thể lấy thông tin mạng: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Print configuration summary to console.
     */
    public void printConfig(String componentName) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  " + padRight(componentName + " — Network Config", 40) + "║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Server Host : " + padRight(serverHost, 26) + "║");
        System.out.println("║  Server Port : " + padRight(String.valueOf(serverPort), 26) + "║");
        System.out.println("║  Bind Address: " + padRight(bindAddress, 26) + "║");
        if (hasPublicEndpoint()) {
            System.out.println("║  Public Host : " + padRight(publicHost, 26) + "║");
            System.out.println("║  Public Port : " + padRight(String.valueOf(publicPort), 26) + "║");
        }
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println(getNetworkInterfaceInfo());
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    @Override
    public String toString() {
        return "NetworkConfig{host=" + serverHost + ", port=" + serverPort
                + ", bind=" + bindAddress
                + (hasPublicEndpoint() ? ", public=" + publicHost + ":" + publicPort : "")
                + "}";
    }
}
