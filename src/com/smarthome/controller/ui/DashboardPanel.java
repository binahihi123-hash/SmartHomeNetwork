package com.smarthome.controller.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Dashboard overview panel showing:
 * - Stats cards (total devices, online, active)
 * - Quick device list with status
 * - Recent activity log
 */
public class DashboardPanel extends JPanel {

    private final JLabel lblTotalDevices;
    private final JLabel lblOnlineDevices;
    private final JLabel lblActiveDevices;
    private final JPanel deviceListPanel;
    private final JTextArea txtActivity;
    private final JLabel lblWelcome;

    public DashboardPanel() {
        setLayout(new BorderLayout(GAP, GAP));
        setBackground(BG_DARK);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        // === Header ===
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        lblWelcome = createLabel("Tổng quan hệ thống", FONT_TITLE, TEXT_PRIMARY);
        header.add(lblWelcome, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        // === Center: Stats + Devices ===
        JPanel center = new JPanel(new BorderLayout(GAP, GAP));
        center.setOpaque(false);

        // Stats row
        JPanel statsRow = new JPanel(new GridLayout(1, 3, GAP, 0));
        statsRow.setOpaque(false);
        statsRow.setPreferredSize(new Dimension(0, 100));

        lblTotalDevices = createLabel("0", FONT_STAT_NUM, TEXT_PRIMARY);
        statsRow.add(createStatCard("Tổng thiết bị", lblTotalDevices, ACCENT));

        lblOnlineDevices = createLabel("0", FONT_STAT_NUM, GREEN);
        statsRow.add(createStatCard("Đang kết nối", lblOnlineDevices, GREEN));

        lblActiveDevices = createLabel("0", FONT_STAT_NUM, YELLOW);
        statsRow.add(createStatCard("Đang hoạt động", lblActiveDevices, YELLOW));

        center.add(statsRow, BorderLayout.NORTH);

        // Device quick list
        deviceListPanel = new JPanel();
        deviceListPanel.setLayout(new BoxLayout(deviceListPanel, BoxLayout.Y_AXIS));
        deviceListPanel.setOpaque(false);

        JPanel devicesSection = createCard();
        devicesSection.setLayout(new BorderLayout(0, 8));
        devicesSection.add(createLabel("Danh sách thiết bị", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        JScrollPane deviceScroll = new JScrollPane(deviceListPanel);
        deviceScroll.setOpaque(false);
        deviceScroll.getViewport().setOpaque(false);
        deviceScroll.setBorder(null);
        deviceScroll.getVerticalScrollBar().setUnitIncrement(16);
        devicesSection.add(deviceScroll, BorderLayout.CENTER);

        center.add(devicesSection, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // === Activity log ===
        JPanel activitySection = createCard();
        activitySection.setLayout(new BorderLayout(0, 8));
        activitySection.setPreferredSize(new Dimension(0, 160));
        activitySection.add(createLabel("Hoạt động gần đây", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        txtActivity = new JTextArea(4, 40);
        txtActivity.setEditable(false);
        txtActivity.setFont(FONT_MONO);
        txtActivity.setForeground(TEXT_SECONDARY);
        txtActivity.setBackground(BG_SURFACE);
        txtActivity.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        txtActivity.setText("Chưa có hoạt động nào.\n");

        JScrollPane actScroll = new JScrollPane(txtActivity);
        actScroll.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        activitySection.add(actScroll, BorderLayout.CENTER);

        add(activitySection, BorderLayout.SOUTH);
    }

    private JPanel createStatCard(String title, JLabel valueLabel, Color accentColor) {
        JPanel card = createCard();
        card.setLayout(new BorderLayout(0, 4));

        // Accent top stripe
        JPanel stripe = new JPanel();
        stripe.setPreferredSize(new Dimension(0, 3));
        stripe.setBackground(accentColor);
        stripe.setOpaque(true);

        JPanel inner = new JPanel(new BorderLayout(0, 2));
        inner.setOpaque(false);
        inner.add(stripe, BorderLayout.NORTH);
        inner.add(valueLabel, BorderLayout.CENTER);
        inner.add(createLabel(title, FONT_SMALL, TEXT_SECONDARY), BorderLayout.SOUTH);

        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(inner, BorderLayout.CENTER);

        return card;
    }

    // === Public update methods ===

    public void updateDeviceList(List<Map<String, String>> devices) {
        SwingUtilities.invokeLater(() -> {
            deviceListPanel.removeAll();

            int total = devices.size();
            int online = 0;
            int active = 0;

            for (Map<String, String> dev : devices) {
                String deviceId = dev.getOrDefault("deviceId", "?");
                String deviceName = dev.getOrDefault("deviceName", deviceId);
                String deviceType = dev.getOrDefault("deviceType", "");
                String connStatus = dev.getOrDefault("connectionStatus", "OFFLINE");
                String devStatus = dev.getOrDefault("deviceStatus", "UNKNOWN");

                if ("ONLINE".equals(connStatus)) online++;
                if ("ON".equals(devStatus)) active++;

                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                row.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER),
                        BorderFactory.createEmptyBorder(6, 4, 6, 4)));

                // Icon + name
                String icon = getDeviceIcon(deviceType);
                JLabel lblName = createLabel(icon + "  " + deviceName, FONT_BODY, TEXT_PRIMARY);
                row.add(lblName, BorderLayout.WEST);

                // Status badges
                JPanel badges = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
                badges.setOpaque(false);

                Color connColor = "ONLINE".equals(connStatus) ? GREEN : RED;
                badges.add(createStatusBadge(connStatus, connColor));

                Color devColor = "ON".equals(devStatus) ? GREEN : ("OFF".equals(devStatus) ? TEXT_DIM : YELLOW);
                badges.add(createStatusBadge(devStatus, devColor));

                row.add(badges, BorderLayout.EAST);
                deviceListPanel.add(row);
            }

            lblTotalDevices.setText(String.valueOf(total));
            lblOnlineDevices.setText(String.valueOf(online));
            lblActiveDevices.setText(String.valueOf(active));

            deviceListPanel.revalidate();
            deviceListPanel.repaint();
        });
    }

    public void addActivity(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = java.time.LocalTime.now().withNano(0).toString();
            String line = "[" + time + "] " + message + "\n";
            if (txtActivity.getText().startsWith("Chưa có")) {
                txtActivity.setText("");
            }
            txtActivity.append(line);
            txtActivity.setCaretPosition(txtActivity.getDocument().getLength());
        });
    }

    public void setWelcome(String username) {
        lblWelcome.setText("Xin chào, " + username + "!");
    }
}
