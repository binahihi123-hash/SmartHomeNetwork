package com.smarthome.test;

import com.smarthome.common.*;
import com.smarthome.controller.ControllerClient;
import com.smarthome.device.DeviceCommandHandler;
import com.smarthome.device.DeviceSimulator;
import com.smarthome.persistence.DatabaseManager;
import com.smarthome.security.PasswordHasher;
import com.smarthome.security.SessionManager;
import com.smarthome.server.SmartHomeHubServer;

import java.io.*;
import java.net.Socket;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Comprehensive automated test suite covering all requirements + RBAC tests.
 * Tests 1-16: Original functionality tests
 * Tests 17-22: RBAC (Admin/User role-based access control) tests
 */
public class SystemTestSuite {
    private static final int TEST_PORT = 5055;
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  SMART HOME SYSTEM COMPREHENSIVE TEST SUITE");
        System.out.println("  Including RBAC (Admin/User) Authorization Tests");
        System.out.println("==================================================");

        // Delete test database if exists
        new File("smarthome.db").delete();

        // 1. Unit tests (no server needed)
        test1_PasswordHasher();
        test2_SessionManager();
        test3_VoiceCommandParser();
        test4_DeviceCommandHandler();
        test5_JsonSerializationAndLimits();
        test6_TcpFramingFragmentation();

        // 2. Integration tests (with Hub Server running)
        SmartHomeHubServer server = new SmartHomeHubServer(TEST_PORT);
        Thread serverThread = new Thread(server::start, "ServerTestThread");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            Thread.sleep(1500); // Wait for server to bind port and init DB

            test7_LoginSuccessAndFailure();
            test8_UnauthenticatedControllerDenied();
            test9_DeviceRegistrationAndRegistry();
            test10_ControlCommandRoutingFlow();
            test11_OfflineDeviceHandling();
            test12_InvalidJsonHandling();
            test13_DeviceStatusQuery();
            test14_ConcurrentControllers();
            test15_DeviceDisconnectAndReconnect();
            test16_HistoryPersistence();

            // 3. RBAC tests
            test17_AdminLoginGetsAdminRole();
            test18_UserLoginGetsUserRole();
            test19_UserDeniedUserManagement();
            test20_AdminCreatesAndDisablesUser();
            test21_DisabledUserCannotLogin();
            test22_ChangePasswordWorks();

        } catch (Exception e) {
            System.err.println("Unexpected test suite error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.shutdown();
            System.out.println("\n==================================================");
            System.out.println("  TEST RESULTS: " + testsPassed + " PASSED, " + testsFailed + " FAILED");
            System.out.println("  TOTAL: " + (testsPassed + testsFailed) + " tests");
            System.out.println("==================================================");
            if (testsFailed > 0) {
                System.exit(1);
            }
        }
    }

    private static void pass(String testName) {
        testsPassed++;
        System.out.println("  [PASS] " + testName);
    }

    private static void fail(String testName, String reason) {
        testsFailed++;
        System.err.println("  [FAIL] " + testName + ": " + reason);
    }

    // === UNIT TESTS ===

    private static void test1_PasswordHasher() {
        System.out.println("\n--- 1. Testing PasswordHasher ---");
        String pass = "secret123";
        String hash = PasswordHasher.hash(pass);
        if (hash != null && hash.contains(":") && PasswordHasher.verify(pass, hash)) {
            if (!PasswordHasher.verify("wrongpass", hash)) {
                pass("Password hashing and salt verification");
            } else {
                fail("PasswordHasher", "Accepted wrong password");
            }
        } else {
            fail("PasswordHasher", "Hash creation or verification failed");
        }
    }

    private static void test2_SessionManager() {
        System.out.println("\n--- 2. Testing SessionManager ---");
        SessionManager sm = new SessionManager();
        String token = sm.createToken("admin", "ADMIN", null);
        SessionManager.SessionInfo info = sm.validateToken(token);

        if (info != null && "admin".equals(info.username) && "ADMIN".equals(info.role)) {
            if (sm.validateToken("invalid-token") == null) {
                // Test revokeAllForUser
                String token2 = sm.createToken("admin", "ADMIN", null);
                sm.revokeAllForUser("admin");
                if (sm.validateToken(token) == null && sm.validateToken(token2) == null) {
                    pass("Token creation, validation, revocation, and revokeAllForUser");
                } else {
                    fail("SessionManager", "revokeAllForUser did not revoke all tokens");
                }
            } else {
                fail("SessionManager", "Accepted invalid token");
            }
        } else {
            fail("SessionManager", "Token validation failed");
        }
    }

    private static void test3_VoiceCommandParser() {
        System.out.println("\n--- 3. Testing VoiceCommandParser ---");
        VoiceCommandParser.ParsedCommand p1 = VoiceCommandParser.parse("bật đèn phòng khách");
        VoiceCommandParser.ParsedCommand p2 = VoiceCommandParser.parse("tắt quạt");
        VoiceCommandParser.ParsedCommand p3 = VoiceCommandParser.parse("mở máy lạnh");
        VoiceCommandParser.ParsedCommand p4 = VoiceCommandParser.parse("lệnh không xác định xxx");

        if ("DEVICE_LIGHT_01".equals(p1.deviceId) && "ON".equals(p1.command) && !p1.ambiguous
                && "DEVICE_FAN_01".equals(p2.deviceId) && "OFF".equals(p2.command)
                && "DEVICE_AC_01".equals(p3.deviceId) && "ON".equals(p3.command)
                && p4.ambiguous) {
            pass("Vietnamese voice/text command parsing to deviceId + action");
        } else {
            fail("VoiceCommandParser", "Parsing mismatch: p1=" + p1.deviceId + "/" + p1.command);
        }
    }

    private static void test4_DeviceCommandHandler() {
        System.out.println("\n--- 4. Testing DeviceCommandHandler ---");
        DeviceCommandHandler handler = new DeviceCommandHandler("DEVICE_LIGHT_01", "LIGHT");
        DeviceCommandHandler.CommandResult r1 = handler.execute("ON");
        String s1 = handler.getCurrentStatus();
        DeviceCommandHandler.CommandResult r2 = handler.execute("OFF");
        String s2 = handler.getCurrentStatus();
        DeviceCommandHandler.CommandResult r3 = handler.execute("INVALID_ACTION");

        if (r1.success && "ON".equals(s1) && r2.success && "OFF".equals(s2) && !r3.success) {
            pass("Device internal state transition and command rejection");
        } else {
            fail("DeviceCommandHandler", "State transition or validation failed");
        }
    }

    private static void test5_JsonSerializationAndLimits() {
        System.out.println("\n--- 5. Testing JSON Serialization & Size Limits ---");
        Message msg = Message.controlCommand("token123", "DEVICE_LIGHT_01", "ON");
        String json = msg.toJson();
        Message parsed = Message.fromJson(json);

        if ("CONTROL_COMMAND".equals(parsed.getType()) && "DEVICE_LIGHT_01".equals(parsed.getDeviceId())) {
            // Test oversize message rejection
            try {
                String hugeJson = "{\"type\":\"TEST\",\"payload\":\"" + "A".repeat(70000) + "\"}";
                Message.fromJson(hugeJson);
                fail("JSON Limits", "Allowed message > 64KB");
            } catch (Exception expected) {
                pass("JSON serialization, deserialization, and 64KB limit enforcement");
            }
        } else {
            fail("JSON Serialization", "Fields did not round-trip");
        }
    }

    private static void test6_TcpFramingFragmentation() {
        System.out.println("\n--- 6. Testing TCP Framing (Fragmentation & Coalescing) ---");
        try {
            String msg1 = "{\"type\":\"HEARTBEAT\",\"requestId\":\"1\"}\n";
            String msg2 = "{\"type\":\"HEARTBEAT_ACK\",\"requestId\":\"2\"}\n";
            byte[] streamBytes = (msg1 + msg2).getBytes("UTF-8");

            ByteArrayInputStream in = new ByteArrayInputStream(streamBytes);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            String line1 = reader.readLine();
            Message m1 = Message.fromJson(line1);

            String line2 = reader.readLine();
            Message m2 = Message.fromJson(line2);

            if (m1.getMessageType() == MessageType.HEARTBEAT && m2.getMessageType() == MessageType.HEARTBEAT_ACK) {
                pass("TCP newline-delimited framing handles multiple messages correctly");
            } else {
                fail("TCP Framing", "Could not parse coalesced messages");
            }
        } catch (Exception e) {
            fail("TCP Framing", e.getMessage());
        }
    }

    // === INTEGRATION TESTS ===

    private static void test7_LoginSuccessAndFailure() {
        System.out.println("\n--- 7. Testing Login Success & Failure ---");
        try {
            ControllerClient client = new ControllerClient("127.0.0.1", TEST_PORT);
            boolean bad = client.login("admin", "wrongpass");
            if (!bad) {
                boolean good = client.login("admin", "admin123");
                if (good && client.isConnected() && client.getToken() != null) {
                    client.disconnect();
                    pass("Login rejected bad password and accepted correct credentials");
                } else {
                    fail("Login", "Correct credentials rejected");
                }
            } else {
                fail("Login", "Allowed wrong password");
            }
        } catch (Exception e) {
            fail("Login", e.getMessage());
        }
    }

    private static void test8_UnauthenticatedControllerDenied() {
        System.out.println("\n--- 8. Testing Unauthenticated Controller Request Denied ---");
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT);
             MessageIO io = new MessageIO(socket)) {
            Message rogue = Message.controlCommand("fake-token", "DEVICE_LIGHT_01", "ON");
            io.send(rogue);

            Message res = io.receive();
            if (res != null && res.getMessageType() == MessageType.ERROR) {
                pass("First-message enforcement closed/errored unauthenticated socket");
            } else {
                fail("Auth Enforcement", "Did not reject unauthenticated command");
            }
        } catch (Exception e) {
            fail("Auth Enforcement", e.getMessage());
        }
    }

    private static void test9_DeviceRegistrationAndRegistry() {
        System.out.println("\n--- 9. Testing Device Registration & Online Status ---");
        DeviceSimulator dev = new DeviceSimulator(
                "DEVICE_LIGHT_01", "LIGHT", "Đèn phòng khách", "DEVICE_LIGHT_01_secret", "127.0.0.1", TEST_PORT);
        Thread devThread = new Thread(dev::start);
        devThread.setDaemon(true);
        devThread.start();

        try {
            Thread.sleep(1000);
            if (dev.isConnected()) {
                pass("DeviceSimulator connected and registered as DEVICE_LIGHT_01");
            } else {
                fail("Device Registration", "Device not connected");
            }
        } catch (InterruptedException e) {
            fail("Device Registration", e.getMessage());
        }
    }

    private static void test10_ControlCommandRoutingFlow() {
        System.out.println("\n--- 10. Testing Complete Control Command Flow (Controller -> Hub -> Device -> Hub -> Controller) ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            ctrl.login("admin", "admin123");

            Message resOn = ctrl.sendControlCommand("DEVICE_LIGHT_01", "ON");
            if (resOn != null && "SUCCESS".equals(resOn.getStatus())) {
                Message resOff = ctrl.sendControlCommand("DEVICE_LIGHT_01", "OFF");
                if (resOff != null && "SUCCESS".equals(resOff.getStatus())) {
                    ctrl.disconnect();
                    pass("End-to-end command routing executed and confirmed by simulated device");
                } else {
                    ctrl.disconnect();
                    fail("Command Flow", "OFF command failed");
                }
            } else {
                ctrl.disconnect();
                fail("Command Flow", "ON command failed: " + (resOn != null ? resOn.getMessage() : "null"));
            }
        } catch (Exception e) {
            fail("Command Flow", e.getMessage());
        }
    }

    private static void test11_OfflineDeviceHandling() {
        System.out.println("\n--- 11. Testing Command To Offline Device Rejected ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            ctrl.login("admin", "admin123");

            try {
                Message res = ctrl.sendControlCommand("DEVICE_AC_01", "ON");
                if (res != null && ("ERROR".equals(res.getStatus()) || "FAILURE".equals(res.getStatus()))) {
                    pass("Server rejected command to offline device DEVICE_AC_01");
                } else {
                    fail("Offline Device", "Server returned success for offline device!");
                }
            } catch (IOException e) {
                pass("Server properly reported device offline error: " + e.getMessage());
            } finally {
                ctrl.disconnect();
            }
        } catch (Exception e) {
            fail("Offline Device", e.getMessage());
        }
    }

    private static void test12_InvalidJsonHandling() {
        System.out.println("\n--- 12. Testing Invalid JSON Handling ---");
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT);
             PrintWriter pw = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            pw.println("NOT_JSON_AT_ALL_$$$$$$$$$$$$$$$$$");
            String line = br.readLine();
            if (line == null || line.contains("ERROR") || line.contains("Invalid")) {
                pass("Server safely handled malformed non-JSON data");
            } else {
                fail("Invalid JSON", "Unexpected reply: " + line);
            }
        } catch (Exception e) {
            pass("Server safely terminated socket on invalid JSON: " + e.getMessage());
        }
    }

    private static void test13_DeviceStatusQuery() {
        System.out.println("\n--- 13. Testing Status Query for Device ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            ctrl.login("admin", "admin123");

            Message statusRes = ctrl.requestDeviceStatus("DEVICE_LIGHT_01");
            if (statusRes != null && ("SUCCESS".equals(statusRes.getStatus()) || statusRes.getDeviceStatus() != null)) {
                pass("Status query returned device state: " + statusRes.getDeviceStatus());
            } else {
                fail("Device Status Query", "Null or failed response");
            }
            ctrl.disconnect();
        } catch (Exception e) {
            fail("Device Status Query", e.getMessage());
        }
    }

    private static void test14_ConcurrentControllers() {
        System.out.println("\n--- 14. Testing Concurrent Controllers Sending Commands ---");
        ControllerClient c1 = null;
        ControllerClient c2 = null;
        try {
            c1 = new ControllerClient("127.0.0.1", TEST_PORT);
            c2 = new ControllerClient("127.0.0.1", TEST_PORT);

            if (!c1.login("admin", "admin123")) {
                fail("Concurrent Controllers", "c1 login failed");
                return;
            }
            if (!c2.login("user", "user123")) {
                fail("Concurrent Controllers", "c2 login failed");
                c1.disconnect();
                return;
            }

            final ControllerClient fc1 = c1;
            final ControllerClient fc2 = c2;

            CompletableFuture<Boolean> f1 = CompletableFuture.supplyAsync(() -> {
                try {
                    Message r = fc1.sendControlCommand("DEVICE_LIGHT_01", "ON");
                    return r != null && "SUCCESS".equals(r.getStatus());
                } catch (Exception ex) {
                    System.err.println("c1 command exception: " + ex.getMessage());
                    return false;
                }
            });

            CompletableFuture<Boolean> f2 = CompletableFuture.supplyAsync(() -> {
                try {
                    Message r = fc2.sendControlCommand("DEVICE_LIGHT_01", "STATUS");
                    return r != null && "SUCCESS".equals(r.getStatus());
                } catch (Exception ex) {
                    System.err.println("c2 command exception: " + ex.getMessage());
                    return false;
                }
            });

            boolean ok1 = f1.get(30, TimeUnit.SECONDS);
            boolean ok2 = f2.get(30, TimeUnit.SECONDS);

            if (ok1 && ok2) {
                pass("Two controllers (ADMIN + USER) successfully operated concurrently");
            } else {
                fail("Concurrent Controllers", "ok1=" + ok1 + ", ok2=" + ok2);
            }
        } catch (Exception e) {
            fail("Concurrent Controllers", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            e.printStackTrace();
        } finally {
            if (c1 != null) c1.disconnect();
            if (c2 != null) c2.disconnect();
        }
    }

    private static void test15_DeviceDisconnectAndReconnect() {
        System.out.println("\n--- 15. Testing Device Disconnect & Reconnect ---");
        try {
            DeviceSimulator tempDev = new DeviceSimulator(
                    "DEVICE_FAN_01", "FAN", "Quạt", "DEVICE_FAN_01_secret", "127.0.0.1", TEST_PORT);
            Thread t = new Thread(tempDev::start);
            t.setDaemon(true);
            t.start();

            int retries = 0;
            while (!tempDev.isConnected() && retries < 20) {
                Thread.sleep(200);
                retries++;
            }

            if (!tempDev.isConnected()) {
                fail("Device Reconnect", "Initial connection timed out");
                return;
            }

            tempDev.shutdown();
            Thread.sleep(1000);

            DeviceSimulator newDev = new DeviceSimulator(
                    "DEVICE_FAN_01", "FAN", "Quạt", "DEVICE_FAN_01_secret", "127.0.0.1", TEST_PORT);
            Thread t2 = new Thread(newDev::start);
            t2.setDaemon(true);
            t2.start();

            retries = 0;
            while (!newDev.isConnected() && retries < 20) {
                Thread.sleep(200);
                retries++;
            }

            if (newDev.isConnected()) {
                pass("Device successfully re-registered after disconnection");
            } else {
                fail("Device Reconnect", "Re-registration timed out");
            }
        } catch (Exception e) {
            fail("Device Reconnect", e.getMessage());
        }
    }

    private static void test16_HistoryPersistence() {
        System.out.println("\n--- 16. Testing Command History Persistence in SQLite ---");
        try (DatabaseManager db = new DatabaseManager("smarthome.db")) {
            List<String> history = db.getCommandHistory(10);
            if (!history.isEmpty()) {
                pass("Command history persisted in SQLite (" + history.size() + " records found)");
            } else {
                fail("History Persistence", "No history found in database");
            }
        } catch (SQLException e) {
            fail("History Persistence", e.getMessage());
        }
    }

    // === RBAC TESTS ===

    private static void test17_AdminLoginGetsAdminRole() {
        System.out.println("\n--- 17. Testing Admin Login Returns ADMIN Role ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            boolean ok = ctrl.login("admin", "admin123");
            if (ok && "ADMIN".equals(ctrl.getRole())) {
                pass("Admin login correctly returns ADMIN role");
            } else {
                fail("Admin Role", "Expected ADMIN, got: " + ctrl.getRole());
            }
            ctrl.disconnect();
        } catch (Exception e) {
            fail("Admin Role", e.getMessage());
        }
    }

    private static void test18_UserLoginGetsUserRole() {
        System.out.println("\n--- 18. Testing User Login Returns USER Role ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            boolean ok = ctrl.login("user", "user123");
            if (ok && "USER".equals(ctrl.getRole())) {
                pass("User login correctly returns USER role");
            } else {
                fail("User Role", "Expected USER, got: " + ctrl.getRole());
            }
            ctrl.disconnect();
        } catch (Exception e) {
            fail("User Role", e.getMessage());
        }
    }

    private static void test19_UserDeniedUserManagement() {
        System.out.println("\n--- 19. Testing User Denied Access to User Management ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            ctrl.login("user", "user123");

            Message response = ctrl.sendUserManagement("LIST_USERS", null, null, null);
            if (response != null && ("DENIED".equals(response.getStatus())
                    || response.getMessageType() == MessageType.PERMISSION_DENIED)) {
                pass("USER role correctly denied USER_MANAGEMENT access");
            } else {
                fail("RBAC Enforcement", "User was not denied. Status: " +
                        (response != null ? response.getStatus() + " type=" + response.getType() : "null"));
            }
            ctrl.disconnect();
        } catch (Exception e) {
            fail("RBAC Enforcement", e.getMessage());
        }
    }

    private static void test20_AdminCreatesAndDisablesUser() {
        System.out.println("\n--- 20. Testing Admin Creates and Disables User ---");
        try {
            ControllerClient admin = new ControllerClient("127.0.0.1", TEST_PORT);
            admin.login("admin", "admin123");

            // Create a test user
            Message createRes = admin.sendUserManagement("CREATE_USER", "testuser", "testpass", "USER");
            if (createRes == null || !"SUCCESS".equals(createRes.getStatus())) {
                fail("Admin Create User", "Failed to create user: " +
                        (createRes != null ? createRes.getMessage() : "null"));
                admin.disconnect();
                return;
            }

            // Disable the test user
            Message disableRes = admin.sendUserManagement("DISABLE_USER", "testuser", null, null);
            if (disableRes != null && "SUCCESS".equals(disableRes.getStatus())) {
                pass("Admin successfully created and disabled a user");
            } else {
                fail("Admin Disable User", "Failed to disable: " +
                        (disableRes != null ? disableRes.getMessage() : "null"));
            }
            admin.disconnect();
        } catch (Exception e) {
            fail("Admin Create/Disable User", e.getMessage());
        }
    }

    private static void test21_DisabledUserCannotLogin() {
        System.out.println("\n--- 21. Testing Disabled User Cannot Login ---");
        try {
            // testuser was disabled in test20
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            boolean loggedIn = ctrl.login("testuser", "testpass");
            if (!loggedIn) {
                pass("Disabled user 'testuser' correctly rejected at login");
            } else {
                fail("Disabled User Login", "Disabled user was allowed to login!");
                ctrl.disconnect();
            }
        } catch (Exception e) {
            // IOException during login is also acceptable
            pass("Disabled user login correctly failed: " + e.getMessage());
        }
    }

    private static void test22_ChangePasswordWorks() {
        System.out.println("\n--- 22. Testing Change Password ---");
        try {
            ControllerClient ctrl = new ControllerClient("127.0.0.1", TEST_PORT);
            ctrl.login("user", "user123");

            // Change password
            Message chRes = ctrl.changePassword("user123", "newpass456");
            ctrl.disconnect();

            if (chRes != null && "SUCCESS".equals(chRes.getStatus())) {
                // Verify new password works
                Thread.sleep(300);
                ControllerClient ctrl2 = new ControllerClient("127.0.0.1", TEST_PORT);
                boolean ok = ctrl2.login("user", "newpass456");
                if (ok) {
                    // Restore original password
                    ctrl2.changePassword("newpass456", "user123");
                    ctrl2.disconnect();
                    pass("Password changed and verified with new credentials");
                } else {
                    fail("Change Password", "Could not login with new password");
                }
            } else {
                fail("Change Password", "Change request failed: " +
                        (chRes != null ? chRes.getMessage() : "null"));
            }
        } catch (Exception e) {
            fail("Change Password", e.getMessage());
        }
    }
}
