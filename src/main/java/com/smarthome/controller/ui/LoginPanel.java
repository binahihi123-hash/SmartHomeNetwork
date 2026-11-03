package com.smarthome.controller.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Professional dark-themed login screen for the Smart Home Controller.
 * Center-aligned card with server connection fields and credentials.
 */
public class LoginPanel extends JPanel {

    private final JTextField txtHost;
    private final JTextField txtPort;
    private final JTextField txtUsername;
    private final JPasswordField txtPassword;
    private final JButton btnLogin;
    private final JLabel lblStatus;

    public LoginPanel() {
        setLayout(new GridBagLayout());
        setBackground(BG_DARK);

        // Center card
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(40, 50, 40, 50)));
        card.setPreferredSize(new Dimension(420, 520));

        // Logo / Title
        JLabel lblIcon = createLabel("\uD83C\uDFE0", new Font("Segoe UI Emoji", Font.PLAIN, 48), ACCENT);
        lblIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblIcon);
        card.add(Box.createVerticalStrut(8));

        JLabel lblTitle = createLabel("Smart Home", FONT_TITLE, TEXT_PRIMARY);
        lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblTitle);

        JLabel lblSubtitle = createLabel("Hệ thống điều khiển nhà thông minh", FONT_SMALL, TEXT_SECONDARY);
        lblSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblSubtitle);

        card.add(Box.createVerticalStrut(30));

        // Server fields
        card.add(createFieldLabel("Máy chủ"));
        JPanel serverRow = new JPanel(new BorderLayout(8, 0));
        serverRow.setOpaque(false);
        serverRow.setMaximumSize(new Dimension(320, 38));

        txtHost = createTextField("127.0.0.1", 14);
        txtHost.setText("127.0.0.1");
        serverRow.add(txtHost, BorderLayout.CENTER);

        txtPort = createTextField("5000", 5);
        txtPort.setText("5000");
        txtPort.setPreferredSize(new Dimension(70, 38));
        serverRow.add(txtPort, BorderLayout.EAST);
        card.add(serverRow);

        card.add(Box.createVerticalStrut(16));

        // Username
        card.add(createFieldLabel("Tài khoản"));
        txtUsername = createTextField("admin", 20);
        txtUsername.setMaximumSize(new Dimension(320, 38));
        card.add(txtUsername);

        card.add(Box.createVerticalStrut(16));

        // Password (NOT pre-filled for security)
        card.add(createFieldLabel("Mật khẩu"));
        txtPassword = createPasswordField(20);
        txtPassword.setMaximumSize(new Dimension(320, 38));
        card.add(txtPassword);

        card.add(Box.createVerticalStrut(24));

        // Login button
        btnLogin = createAccentButton("Đăng nhập");
        btnLogin.setMaximumSize(new Dimension(320, 42));
        btnLogin.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(btnLogin);

        card.add(Box.createVerticalStrut(12));

        // Status label
        lblStatus = createLabel("", FONT_SMALL, TEXT_SECONDARY);
        lblStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lblStatus);

        // Enter key triggers login
        txtPassword.addActionListener(e -> btnLogin.doClick());
        txtUsername.addActionListener(e -> txtPassword.requestFocus());

        add(card);
    }

    private JLabel createFieldLabel(String text) {
        JLabel lbl = createLabel(text, FONT_SMALL, TEXT_SECONDARY);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
        return lbl;
    }

    // === Accessors ===
    public String getHost() { return txtHost.getText().trim(); }
    public String getPort() { return txtPort.getText().trim(); }
    public String getUsername() { return txtUsername.getText().trim(); }
    public String getPassword() { return new String(txtPassword.getPassword()); }

    public void setLoginAction(ActionListener action) {
        btnLogin.addActionListener(action);
    }

    public void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText(text);
            lblStatus.setForeground(color);
        });
    }

    public void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            btnLogin.setEnabled(!loading);
            btnLogin.setText(loading ? "Đang kết nối..." : "Đăng nhập");
        });
    }
}
