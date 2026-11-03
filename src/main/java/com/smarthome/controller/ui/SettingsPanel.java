package com.smarthome.controller.ui;

import javax.swing.*;
import java.awt.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Settings panel showing network info, connection config, and system information.
 * Helps users configure LAN connections for multi-machine deployment.
 */
public class SettingsPanel extends JPanel {

    private final JLabel lblServerHost;
    private final JLabel lblServerPort;
    private final JLabel lblConnectionStatus;
    private final JLabel lblUsername;
    private final JTextArea txtNetworkInfo;

    public SettingsPanel() {
        setLayout(new BorderLayout(GAP, GAP));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        // Header
        add(createLabel("Cài đặt & Thông tin hệ thống", FONT_TITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        // Connection info card
        JPanel connCard = createCard();
        connCard.setLayout(new GridLayout(4, 2, 8, 8));
        connCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));

        connCard.add(createLabel("Máy chủ:", FONT_BODY, TEXT_SECONDARY));
        lblServerHost = createLabel("—", FONT_BODY, TEXT_PRIMARY);
        connCard.add(lblServerHost);

        connCard.add(createLabel("Cổng:", FONT_BODY, TEXT_SECONDARY));
        lblServerPort = createLabel("—", FONT_BODY, TEXT_PRIMARY);
        connCard.add(lblServerPort);

        connCard.add(createLabel("Trạng thái:", FONT_BODY, TEXT_SECONDARY));
        lblConnectionStatus = createLabel("Chưa kết nối", FONT_BODY, RED);
        connCard.add(lblConnectionStatus);

        connCard.add(createLabel("Tài khoản:", FONT_BODY, TEXT_SECONDARY));
        lblUsername = createLabel("—", FONT_BODY, TEXT_PRIMARY);
        connCard.add(lblUsername);

        center.add(connCard);
        center.add(Box.createVerticalStrut(GAP));

        // Network info card
        JPanel netCard = createCard();
        netCard.setLayout(new BorderLayout(0, 8));
        netCard.add(createLabel("Thông tin mạng LAN", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        txtNetworkInfo = new JTextArea(8, 40);
        txtNetworkInfo.setEditable(false);
        txtNetworkInfo.setFont(FONT_MONO);
        txtNetworkInfo.setForeground(TEXT_SECONDARY);
        txtNetworkInfo.setBackground(BG_SURFACE);
        txtNetworkInfo.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        loadNetworkInfo();

        JScrollPane scroll = new JScrollPane(txtNetworkInfo);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        netCard.add(scroll, BorderLayout.CENTER);

        center.add(netCard);
        center.add(Box.createVerticalStrut(GAP));

        // Deployment guide card
        JPanel guideCard = createCard();
        guideCard.setLayout(new BorderLayout(0, 8));
        guideCard.add(createLabel("Hướng dẫn triển khai LAN", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        JTextArea txtGuide = new JTextArea();
        txtGuide.setEditable(false);
        txtGuide.setFont(FONT_BODY);
        txtGuide.setForeground(TEXT_SECONDARY);
        txtGuide.setBackground(BG_SURFACE);
        txtGuide.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        txtGuide.setLineWrap(true);
        txtGuide.setWrapStyleWord(true);
        txtGuide.setText("""
                === TRIỂN KHAI LAN (WiFi cùng mạng) ===

                1. Máy Server: Chạy run-server.bat để khởi động Hub Server (port 5000).
                   Server tự động bind 0.0.0.0, cho phép kết nối từ bất kỳ máy nào trong mạng LAN.

                2. Máy Device: Chạy run-devices.bat. Sửa biến HOST trong file bat
                   thành IP LAN của máy Server (VD: 192.168.1.100).

                3. Máy Controller: Chạy run-gui.bat. Nhập IP LAN của Server vào ô "Máy chủ"
                   trên màn hình đăng nhập rồi đăng nhập.

                4. Tài khoản mặc định: admin/admin123 hoặc user/user123.

                5. Kiểm tra tường lửa: Đảm bảo port 5000 được mở trên máy Server.

                === KẾT NỐI TỪ XA (4G / Internet) ===

                Cách 1 — Ngrok (đơn giản nhất):
                  • Cài ngrok từ https://ngrok.com
                  • Chạy: ngrok tcp 5000
                  • Dùng URL ngrok (VD: 0.tcp.ngrok.io:12345) làm Host và Port khi đăng nhập.

                Cách 2 — Port Forwarding trên Router:
                  • Đăng nhập router → Port Forwarding / Virtual Server
                  • Thêm rule: External Port 5000 → IP máy Server, Port 5000, TCP
                  • Client 4G dùng Public IP (kiểm tra tại whatismyip.com) và port 5000.

                Cách 3 — Tailscale / ZeroTier (VPN an toàn):
                  • Cài Tailscale trên cả server và client
                  • Kết nối qua Tailscale IP (100.x.x.x), không cần mở port.

                === ĐỒNG BỘ THỜI GIAN THỰC ===

                • Tự động bật Auto-Reconnect khi đăng nhập (hỗ trợ 4G mất sóng).
                • Mọi thay đổi từ bất kỳ client nào được broadcast ngay đến tất cả.
                • Sau khi reconnect, client tự động tải lại toàn bộ trạng thái từ server.
                """);

        JScrollPane guideScroll = new JScrollPane(txtGuide);
        guideScroll.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        guideCard.add(guideScroll, BorderLayout.CENTER);

        center.add(guideCard);

        JScrollPane mainScroll = new JScrollPane(center);
        mainScroll.setOpaque(false);
        mainScroll.getViewport().setOpaque(false);
        mainScroll.setBorder(null);
        mainScroll.getVerticalScrollBar().setUnitIncrement(16);
        add(mainScroll, BorderLayout.CENTER);
    }

    private void loadNetworkInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("Hostname: ").append(InetAddress.getLocalHost().getHostName()).append("\n");
            sb.append("IP Local: ").append(InetAddress.getLocalHost().getHostAddress()).append("\n\n");

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.getHostAddress().contains(":")) continue; // skip IPv6
                    sb.append(ni.getDisplayName()).append(": ").append(addr.getHostAddress()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("Không thể lấy thông tin mạng: ").append(e.getMessage());
        }
        txtNetworkInfo.setText(sb.toString());
    }

    // === Public update methods ===

    public void updateConnectionInfo(String host, String port, String username, boolean connected) {
        SwingUtilities.invokeLater(() -> {
            lblServerHost.setText(host != null ? host : "—");
            lblServerPort.setText(port != null ? port : "—");
            lblUsername.setText(username != null ? username : "—");
            if (connected) {
                lblConnectionStatus.setText("Đã kết nối");
                lblConnectionStatus.setForeground(GREEN);
            } else {
                lblConnectionStatus.setText("Chưa kết nối");
                lblConnectionStatus.setForeground(RED);
            }
        });
    }
}
