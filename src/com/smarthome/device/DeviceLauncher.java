package com.smarthome.device;

/**
 * Launcher for predefined device simulators.
 * Run with argument: light1, light2, fan, ac, tv, or all
 */
public class DeviceLauncher {

    public static void main(String[] args) {
        String deviceArg = args.length > 0 ? args[0].toLowerCase() : "all";
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5000;

        switch (deviceArg) {
            case "light1" -> launchDevice("DEVICE_LIGHT_01", "LIGHT", "Đèn phòng khách", host, port);
            case "light2" -> launchDevice("DEVICE_LIGHT_02", "LIGHT", "Đèn phòng ngủ", host, port);
            case "fan" -> launchDevice("DEVICE_FAN_01", "FAN", "Quạt", host, port);
            case "ac" -> launchDevice("DEVICE_AC_01", "AC", "Máy lạnh", host, port);
            case "tv" -> launchDevice("DEVICE_TV_01", "TV", "TV", host, port);
            case "all" -> launchAllDevices(host, port);
            default -> {
                System.out.println("Usage: DeviceLauncher <light1|light2|fan|ac|tv|all> [host] [port]");
                System.out.println("Examples:");
                System.out.println("  DeviceLauncher light1");
                System.out.println("  DeviceLauncher fan 192.168.1.100 5000");
                System.out.println("  DeviceLauncher all");
            }
        }
    }

    private static void launchDevice(String deviceId, String deviceType, String deviceName, String host, int port) {
        String secret = deviceId + "_secret";
        DeviceSimulator device = new DeviceSimulator(deviceId, deviceType, deviceName, secret, host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(device::shutdown));
        device.start();
    }

    /**
     * Public API for launching all devices (used by SmartHomeLauncher).
     * Runs device threads as daemon threads so they don't block JVM shutdown.
     */
    public static void launchDevices(String host, int port) {
        launchAllDevicesDaemon(host, port);
    }

    private static void launchAllDevicesDaemon(String host, int port) {
        String[][] devices = {
            {"DEVICE_LIGHT_01", "LIGHT", "Đèn phòng khách"},
            {"DEVICE_FAN_01", "FAN", "Quạt"},
            {"DEVICE_AC_01", "AC", "Máy lạnh"}
        };

        for (String[] d : devices) {
            String secret = d[0] + "_secret";
            DeviceSimulator sim = new DeviceSimulator(d[0], d[1], d[2], secret, host, port);
            Thread t = new Thread(() -> sim.start(), "Device-" + d[0]);
            t.setDaemon(true);
            t.start();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
    }

    private static void launchAllDevices(String host, int port) {
        System.out.println("Launching all devices...\n");

        String[][] devices = {
            {"DEVICE_LIGHT_01", "LIGHT", "Đèn phòng khách"},
            {"DEVICE_LIGHT_02", "LIGHT", "Đèn phòng ngủ"},
            {"DEVICE_FAN_01", "FAN", "Quạt"},
            {"DEVICE_AC_01", "AC", "Máy lạnh"},
            {"DEVICE_TV_01", "TV", "TV"}
        };

        Thread[] threads = new Thread[devices.length];
        DeviceSimulator[] sims = new DeviceSimulator[devices.length];

        for (int i = 0; i < devices.length; i++) {
            String[] d = devices[i];
            String secret = d[0] + "_secret";
            sims[i] = new DeviceSimulator(d[0], d[1], d[2], secret, host, port);
            final DeviceSimulator sim = sims[i];
            threads[i] = new Thread(() -> sim.start(), "Device-" + d[0]);
            threads[i].setDaemon(false);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down all devices...");
            for (DeviceSimulator sim : sims) {
                sim.shutdown();
            }
        }));

        // Start all devices with slight delay between each
        for (int i = 0; i < threads.length; i++) {
            threads[i].start();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        // Wait for all threads
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
    }
}
