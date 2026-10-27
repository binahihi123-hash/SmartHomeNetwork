package com.smarthome.controller;

import com.smarthome.common.*;
import com.smarthome.controller.ui.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Main GUI application for the Smart Home Controller.
 * Dark-themed sidebar-based dashboard using FlatLaf.
 * RBAC-aware: shows different panels for ADMIN vs USER roles.
 * ADMIN: Dashboard, Devices, Commands, History, User Management, Settings, Profile, Logout
 * USER:  Dashboard, Devices, Commands, History, Profile, Logout
 */
public class SmartHomeControllerGUI extends JFrame {

    private ControllerClient client;

    // Panels
    private final LoginPanel loginPanel;
    private final DashboardPanel dashboardPanel;
    private final DevicesPanel devicesPanel;
    private final CommandPanel commandPanel;
    private final HistoryPanel historyPanel;
    private final SettingsPanel settingsPanel;
    private final UserManagementPanel userMgmtPanel;
    private final UserProfilePanel userProfilePanel;

    // Layout
    private final CardLayout contentCards;
    private final JPanel contentPanel;
    private JPanel sidebarPanel;
    private final JPanel mainContainer;
    private final JLabel lblStatusBar;

    // Sidebar buttons
    private JButton btnNavDashboard;
    private JButton btnNavDevices;
    private JButton btnNavCommands;
    private JButton btnNavHistory;
    private JButton btnNavSettings;
    private JButton btnNavUserMgmt;
    private JButton btnNavProfile;
    private JButton btnNavLogout;
    private JButton activeNavButton;

    // State
    private String connectedHost;
    private String connectedPort;

    private static final String VIEW_LOGIN     = "LOGIN";
    private static final String VIEW_DASHBOARD = "DASHBOARD";
    private static final String VIEW_DEVICES   = "DEVICES";
    private static final String VIEW_COMMANDS   = "COMMANDS";
    private static final String VIEW_HISTORY   = "HISTORY";
    private static final String VIEW_SETTINGS  = "SETTINGS";
    private static final String VIEW_USERS     = "USERS";
    private static final String VIEW_PROFILE   = "PROFILE";

    public SmartHomeControllerGUI() {
        super("Smart Home Control");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        // Initialize all panels
        loginPanel = new LoginPanel();
        dashboardPanel = new DashboardPanel();
        devicesPanel = new DevicesPanel();
        commandPanel = new CommandPanel();
        historyPanel = new HistoryPanel();
        settingsPanel = new SettingsPanel();
        userMgmtPanel = new UserManagementPanel();
        userProfilePanel = new UserProfilePanel();

        // Setup login action
        loginPanel.setLoginAction(e -> doLogin());

        // === Content area ===
        contentCards = new CardLayout();
        contentPanel = new JPanel(contentCards);
        contentPanel.setBackground(BG_DARK);

        contentPanel.add(dashboardPanel, VIEW_DASHBOARD);
        contentPanel.add(devicesPanel, VIEW_DEVICES);
        contentPanel.add(commandPanel, VIEW_COMMANDS);
        contentPanel.add(historyPanel, VIEW_HISTORY);
        contentPanel.add(settingsPanel, VIEW_SETTINGS);
        contentPanel.add(userMgmtPanel, VIEW_USERS);
        contentPanel.add(userProfilePanel, VIEW_PROFILE);

        // === Main container (sidebar built later per role) ===
        mainContainer = new JPanel(new BorderLayout());
        mainContainer.add(contentPanel, BorderLayout.CENTER);

        // === Status bar ===
        lblStatusBar = new JLabel("  Chưa kết nối");
        lblStatusBar.setFont(FONT_SMALL);
        lblStatusBar.setForeground(TEXT_DIM);
        lblStatusBar.setBackground(BG_SIDEBAR);
        lblStatusBar.setOpaque(true);
        lblStatusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        // === Root layout: Login first, then switch to main ===
        setLayout(new CardLayout());
        getContentPane().add(loginPanel, VIEW_LOGIN);

        JPanel mainWithStatus = new JPanel(new BorderLayout());
        mainWithStatus.add(mainContainer, BorderLayout.CENTER);
        mainWithStatus.add(lblStatusBar, BorderLayout.SOUTH);
        getContentPane().add(mainWithStatus, "MAIN");

        // Show login first
        ((CardLayout) getContentPane().getLayout()).show(getContentPane(), VIEW_LOGIN);

        // Window close handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) client.disconnect();
            }
        });
    }

    /**
     * Build the sidebar based on user role. ADMIN gets more menu items.
     */
    private void buildSidebar(String role) {
        // Remove old sidebar if exists
        if (sidebarPanel != null) {
            mainContainer.remove(sidebarPanel);
        }

        sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setBackground(BG_SIDEBAR);
        sidebarPanel.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER));

        // Sidebar header
        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setBackground(BG_SIDEBAR);
        sidebarHeader.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        sidebarHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        JLabel lblLogo = createLabel("\uD83C\uDFE0 Smart Home", FONT_SUBTITLE, ACCENT);
        sidebarHeader.add(lblLogo, BorderLayout.CENTER);
        sidebarPanel.add(sidebarHeader);

        JPanel divider = new JPanel();
        divider.setBackground(BORDER);
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sidebarPanel.add(divider);
        sidebarPanel.add(Box.createVerticalStrut(8));

        // Nav buttons - common for both roles
        btnNavDashboard = createSidebarButton("Tổng quan", "\u2302");
        btnNavDevices   = createSidebarButton("Thiết bị", "\u2699");
        btnNavCommands  = createSidebarButton("Điều khiển", "\u25B6");
        btnNavHistory   = createSidebarButton("Lịch sử", "\u2630");

        btnNavDashboard.addActionListener(e -> switchView(VIEW_DASHBOARD, btnNavDashboard));
        btnNavDevices.addActionListener(e -> switchView(VIEW_DEVICES, btnNavDevices));
        btnNavCommands.addActionListener(e -> switchView(VIEW_COMMANDS, btnNavCommands));
        btnNavHistory.addActionListener(e -> {
            switchView(VIEW_HISTORY, btnNavHistory);
            historyPanel.loadHistory();
        });

        sidebarPanel.add(btnNavDashboard);
        sidebarPanel.add(btnNavDevices);
        sidebarPanel.add(btnNavCommands);
        sidebarPanel.add(btnNavHistory);

        // ADMIN-only menu items
        if ("ADMIN".equals(role)) {
            btnNavUserMgmt = createSidebarButton("Người dùng", "\u263A");
            btnNavUserMgmt.addActionListener(e -> {
                switchView(VIEW_USERS, btnNavUserMgmt);
                userMgmtPanel.loadUsers();
            });
            sidebarPanel.add(btnNavUserMgmt);

            btnNavSettings = createSidebarButton("Cài đặt", "\u2731");
            btnNavSettings.addActionListener(e -> switchView(VIEW_SETTINGS, btnNavSettings));
            sidebarPanel.add(btnNavSettings);
        }

        // Profile - available for both
        btnNavProfile = createSidebarButton("Hồ sơ", "\u2661");
        btnNavProfile.addActionListener(e -> switchView(VIEW_PROFILE, btnNavProfile));
        sidebarPanel.add(btnNavProfile);

        sidebarPanel.add(Box.createVerticalGlue());

        // Logout at bottom with separator
        JPanel divider2 = new JPanel();
        divider2.setBackground(BORDER);
        divider2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sidebarPanel.add(divider2);

        btnNavLogout = createSidebarButton("Đăng xuất", "\u2190");
        btnNavLogout.addActionListener(e -> doLogout());
        sidebarPanel.add(btnNavLogout);
        sidebarPanel.add(Box.createVerticalStrut(10));

        mainContainer.add(sidebarPanel, BorderLayout.WEST);
        mainContainer.revalidate();
        mainContainer.repaint();
    }

    // === Login ===

    private void doLogin() {
        String host = loginPanel.getHost();
        String portStr = loginPanel.getPort();
        String username = loginPanel.getUsername();
        String password = loginPanel.getPassword();

        if (host.isEmpty() || portStr.isEmpty()) {
            loginPanel.setStatus("Vui lòng nhập địa chỉ máy chủ và cổng", RED);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            loginPanel.setStatus("Cổng không hợp lệ", RED);
            return;
        }

        if (username.isEmpty() || password.isEmpty()) {
            loginPanel.setStatus("Vui lòng nhập tài khoản và mật khẩu", RED);
            return;
        }

        loginPanel.setLoading(true);
        loginPanel.setStatus("Đang kết nối đến " + host + ":" + port + "...", TEXT_SECONDARY);

        new Thread(() -> {
            try {
                client = new ControllerClient(host, port);
                client.setLogCallback(this::onLog);
                client.setDeviceListCallback(this::onDeviceListUpdate);
                client.setConnectionCallback(this::onConnectionChanged);
                client.setResponseCallback(msg -> {
                    if (msg.getMessageType() == MessageType.ERROR) {
                        onLog("Lỗi: " + msg.getMessage());
                    }
                });

                boolean ok = client.login(username, password);

                SwingUtilities.invokeLater(() -> {
                    loginPanel.setLoading(false);
                    if (ok) {
                        connectedHost = host;
                        connectedPort = portStr;

                        String role = client.getRole();

                        // Build sidebar based on role
                        buildSidebar(role);

                        // Setup panel clients
                        devicesPanel.setClient(client);
                        devicesPanel.setLogCallback(this::onLog);
                        commandPanel.setClient(client);
                        commandPanel.setLogCallback(this::onLog);
                        historyPanel.setClient(client);
                        historyPanel.setLogCallback(this::onLog);
                        userMgmtPanel.setClient(client);
                        userMgmtPanel.setLogCallback(this::onLog);
                        userProfilePanel.setClient(client);
                        userProfilePanel.setLogCallback(this::onLog);

                        // Update panels
                        String roleLabel = "ADMIN".equals(role) ? "Quản trị viên" : "Người dùng";
                        dashboardPanel.setWelcome(username + " (" + roleLabel + ")");
                        settingsPanel.updateConnectionInfo(host, portStr, username, true);
                        lblStatusBar.setText("  ● Đã kết nối — " + username + " [" + role + "] @ " + host + ":" + port);
                        lblStatusBar.setForeground(GREEN);

                        // Switch to main view
                        ((CardLayout) getContentPane().getLayout()).show(getContentPane(), "MAIN");
                        switchView(VIEW_DASHBOARD, btnNavDashboard);

                        // Request device list
                        try { client.requestDeviceList(); } catch (IOException ignored) {}
                    } else {
                        loginPanel.setStatus("Đăng nhập thất bại — sai tài khoản hoặc mật khẩu", RED);
                    }
                });

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    loginPanel.setLoading(false);
                    loginPanel.setStatus("Lỗi kết nối: " + e.getMessage(), RED);
                });
            }
        }, "LoginThread").start();
    }

    private void doLogout() {
        if (client != null) {
            client.disconnect();
            client = null;
        }

        devicesPanel.setClient(null);
        commandPanel.setClient(null);
        historyPanel.setClient(null);
        userMgmtPanel.setClient(null);
        userProfilePanel.setClient(null);

        settingsPanel.updateConnectionInfo(null, null, null, false);
        lblStatusBar.setText("  Chưa kết nối");
        lblStatusBar.setForeground(TEXT_DIM);

        loginPanel.setStatus("", TEXT_SECONDARY);
        loginPanel.setLoading(false);

        // Switch back to login
        ((CardLayout) getContentPane().getLayout()).show(getContentPane(), VIEW_LOGIN);
        onLog("Đã đăng xuất");
    }

    // === Navigation ===

    private void switchView(String viewName, JButton navButton) {
        contentCards.show(contentPanel, viewName);
        if (activeNavButton != null) {
            setSidebarActive(activeNavButton, false);
        }
        setSidebarActive(navButton, true);
        activeNavButton = navButton;
    }

    // === Callbacks ===

    private void onDeviceListUpdate(List<Map<String, String>> devices) {
        dashboardPanel.updateDeviceList(devices);
        devicesPanel.updateDeviceList(devices);
    }

    private void onLog(String message) {
        dashboardPanel.addActivity(message);
    }

    private void onConnectionChanged(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            if (!connected && client != null) {
                lblStatusBar.setText("  ○ Mất kết nối");
                lblStatusBar.setForeground(RED);
                settingsPanel.updateConnectionInfo(connectedHost, connectedPort, null, false);
            }
        });
    }

    // === Entry point ===

    public static void main(String[] args) {
        // Setup FlatLaf dark theme
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("Component.arc", 6);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TitlePane.unifiedBackground", true);
        } catch (Exception e) {
            // Fallback to system LAF
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
        }

        SwingUtilities.invokeLater(() -> {
            new SmartHomeControllerGUI().setVisible(true);
        });
    }
}
