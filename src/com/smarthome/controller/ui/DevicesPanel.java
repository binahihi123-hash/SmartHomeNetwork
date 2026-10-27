package com.smarthome.controller.ui;

import com.smarthome.controller.ControllerClient;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Devices panel with device cards in a grid layout.
 * Each card shows device info, connection/status badges, and ON/OFF/STATUS buttons.
 */
public class DevicesPanel extends JPanel {

    private final JPanel gridPanel;
    private ControllerClient client;
    private Consumer<String> logCallback;

    public DevicesPanel() {
        setLayout(new BorderLayout(GAP, GAP));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("Thiết bị", FONT_TITLE, TEXT_PRIMARY), BorderLayout.WEST);

        JButton btnRefresh = createOutlineButton("⟳ Làm mới");
        btnRefresh.addActionListener(e -> refreshDevices());
        header.add(btnRefresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // Grid of device cards
        gridPanel = new JPanel(new GridLayout(0, 2, GAP, GAP));
        gridPanel.setOpaque(false);

        JScrollPane scroll = new JScrollPane(gridPanel);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // Empty state
        showEmptyState();
    }

    public void setClient(ControllerClient client) {
        this.client = client;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    private void showEmptyState() {
        gridPanel.removeAll();
        JPanel empty = createCard();
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        JLabel lbl = createLabel("Chưa có thiết bị nào", FONT_BODY, TEXT_DIM);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.add(Box.createVerticalGlue());
        empty.add(lbl);
        empty.add(Box.createVerticalGlue());
        gridPanel.add(empty);
        gridPanel.revalidate();
        gridPanel.repaint();
    }

    public void updateDeviceList(List<Map<String, String>> devices) {
        SwingUtilities.invokeLater(() -> {
            gridPanel.removeAll();

            if (devices == null || devices.isEmpty()) {
                showEmptyState();
                return;
            }

            // Adjust grid columns based on device count
            int cols = devices.size() <= 2 ? 1 : 2;
            gridPanel.setLayout(new GridLayout(0, cols, GAP, GAP));

            for (Map<String, String> dev : devices) {
                gridPanel.add(createDeviceCard(dev));
            }

            gridPanel.revalidate();
            gridPanel.repaint();
        });
    }

    private JPanel createDeviceCard(Map<String, String> dev) {
        String deviceId = dev.getOrDefault("deviceId", "?");
        String deviceName = dev.getOrDefault("deviceName", deviceId);
        String deviceType = dev.getOrDefault("deviceType", "");
        String connStatus = dev.getOrDefault("connectionStatus", "OFFLINE");
        String devStatus = dev.getOrDefault("deviceStatus", "UNKNOWN");

        boolean isOnline = "ONLINE".equals(connStatus);
        String icon = getDeviceIcon(deviceType);

        JPanel card = createCard();
        card.setLayout(new BorderLayout(8, 8));

        // Top: Icon + Name + Type
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);

        JLabel lblIcon = createLabel(icon, new Font("Segoe UI Emoji", Font.PLAIN, 28), ACCENT);
        topRow.add(lblIcon, BorderLayout.WEST);

        JPanel namePanel = new JPanel();
        namePanel.setOpaque(false);
        namePanel.setLayout(new BoxLayout(namePanel, BoxLayout.Y_AXIS));
        namePanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        namePanel.add(createLabel(deviceName, FONT_SUBTITLE, TEXT_PRIMARY));
        namePanel.add(createLabel(deviceType + " • " + deviceId, FONT_SMALL, TEXT_DIM));
        topRow.add(namePanel, BorderLayout.CENTER);

        card.add(topRow, BorderLayout.NORTH);

        // Center: Status badges
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusPanel.setOpaque(false);

        Color connColor = isOnline ? GREEN : RED;
        statusPanel.add(createStatusBadge(isOnline ? "● ONLINE" : "○ OFFLINE", connColor));

        Color devColor = "ON".equals(devStatus) ? GREEN : ("OFF".equals(devStatus) ? TEXT_DIM : YELLOW);
        statusPanel.add(createStatusBadge(devStatus, devColor));

        card.add(statusPanel, BorderLayout.CENTER);

        // Bottom: Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnPanel.setOpaque(false);

        JButton btnOn = createAccentButton("BẬT");
        btnOn.setEnabled(isOnline);
        btnOn.addActionListener(e -> sendCommand(deviceId, "ON"));
        btnPanel.add(btnOn);

        JButton btnOff = createOutlineButton("TẮT");
        btnOff.setEnabled(isOnline);
        btnOff.addActionListener(e -> sendCommand(deviceId, "OFF"));
        btnPanel.add(btnOff);

        JButton btnStatus = createOutlineButton("Trạng thái");
        btnStatus.setEnabled(isOnline);
        btnStatus.addActionListener(e -> requestStatus(deviceId));
        btnPanel.add(btnStatus);

        card.add(btnPanel, BorderLayout.SOUTH);

        return card;
    }

    private void sendCommand(String deviceId, String command) {
        if (client == null || !client.isConnected()) {
            log("Chưa kết nối!");
            return;
        }
        new Thread(() -> {
            try {
                var response = client.sendControlCommand(deviceId, command);
                if (response != null && "SUCCESS".equals(response.getStatus())) {
                    client.requestDeviceList();
                }
            } catch (IOException e) {
                log("Lỗi gửi lệnh: " + e.getMessage());
            }
        }, "DeviceCmd-" + deviceId).start();
    }

    private void requestStatus(String deviceId) {
        if (client == null || !client.isConnected()) return;
        new Thread(() -> {
            try {
                var response = client.requestDeviceStatus(deviceId);
                if (response != null) {
                    log("Trạng thái " + deviceId + ": kết nối=" + response.getConnectionStatus()
                            + ", thiết bị=" + response.getDeviceStatus());
                }
            } catch (IOException e) {
                log("Lỗi: " + e.getMessage());
            }
        }).start();
    }

    private void refreshDevices() {
        if (client == null || !client.isConnected()) {
            log("Chưa kết nối!");
            return;
        }
        new Thread(() -> {
            try {
                client.requestDeviceList();
            } catch (IOException e) {
                log("Lỗi làm mới: " + e.getMessage());
            }
        }).start();
    }

    private void log(String msg) {
        if (logCallback != null) logCallback.accept(msg);
    }
}
