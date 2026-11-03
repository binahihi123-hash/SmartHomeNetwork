package com.smarthome.controller.ui;

import com.smarthome.common.*;
import com.smarthome.controller.ControllerClient;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.function.Consumer;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * User self-service panel: change own password.
 * Available to both ADMIN and USER roles.
 */
public class UserProfilePanel extends JPanel {

    private ControllerClient client;
    private Consumer<String> logCallback;

    private final JPasswordField tfOldPassword;
    private final JPasswordField tfNewPassword;
    private final JPasswordField tfConfirmPassword;
    private final JLabel lblStatus;
    private final JLabel lblUsername;
    private final JLabel lblRole;

    public UserProfilePanel() {
        setLayout(new BorderLayout(0, 20));
        setBackground(BG_DARK);
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Header
        JLabel title = createLabel("Hồ sơ cá nhân", FONT_TITLE, TEXT_PRIMARY);
        add(title, BorderLayout.NORTH);

        // Center form
        JPanel formContainer = new JPanel();
        formContainer.setLayout(new BoxLayout(formContainer, BoxLayout.Y_AXIS));
        formContainer.setBackground(BG_DARK);

        // User info section
        JPanel infoPanel = new JPanel(new GridLayout(2, 2, 10, 8));
        infoPanel.setBackground(BG_SURFACE);
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(15, 15, 15, 15)));
        infoPanel.setMaximumSize(new Dimension(500, 80));
        infoPanel.setAlignmentX(LEFT_ALIGNMENT);

        infoPanel.add(createLabel("Tên đăng nhập:", FONT_BODY, TEXT_SECONDARY));
        lblUsername = createLabel("—", FONT_BODY, TEXT_PRIMARY);
        infoPanel.add(lblUsername);
        infoPanel.add(createLabel("Vai trò:", FONT_BODY, TEXT_SECONDARY));
        lblRole = createLabel("—", FONT_BODY, ACCENT);
        infoPanel.add(lblRole);

        formContainer.add(infoPanel);
        formContainer.add(Box.createVerticalStrut(20));

        // Password change section
        JLabel lblChangePass = createLabel("Đổi mật khẩu", FONT_SUBTITLE, TEXT_PRIMARY);
        lblChangePass.setAlignmentX(LEFT_ALIGNMENT);
        formContainer.add(lblChangePass);
        formContainer.add(Box.createVerticalStrut(10));

        JPanel passPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        passPanel.setBackground(BG_SURFACE);
        passPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(15, 15, 15, 15)));
        passPanel.setMaximumSize(new Dimension(500, 130));
        passPanel.setAlignmentX(LEFT_ALIGNMENT);

        passPanel.add(createLabel("Mật khẩu hiện tại:", FONT_BODY, TEXT_SECONDARY));
        tfOldPassword = new JPasswordField(20);
        passPanel.add(tfOldPassword);

        passPanel.add(createLabel("Mật khẩu mới:", FONT_BODY, TEXT_SECONDARY));
        tfNewPassword = new JPasswordField(20);
        passPanel.add(tfNewPassword);

        passPanel.add(createLabel("Xác nhận mật khẩu mới:", FONT_BODY, TEXT_SECONDARY));
        tfConfirmPassword = new JPasswordField(20);
        passPanel.add(tfConfirmPassword);

        formContainer.add(passPanel);
        formContainer.add(Box.createVerticalStrut(10));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnPanel.setBackground(BG_DARK);
        btnPanel.setAlignmentX(LEFT_ALIGNMENT);

        JButton btnChange = new JButton("Đổi mật khẩu");
        btnChange.setFont(FONT_BODY);
        btnChange.setBackground(ACCENT);
        btnChange.setForeground(Color.WHITE);
        btnChange.setFocusPainted(false);
        btnChange.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        btnChange.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnChange.addActionListener(e -> doChangePassword());
        btnPanel.add(btnChange);

        formContainer.add(btnPanel);
        formContainer.add(Box.createVerticalStrut(10));

        lblStatus = createLabel("", FONT_SMALL, TEXT_DIM);
        lblStatus.setAlignmentX(LEFT_ALIGNMENT);
        formContainer.add(lblStatus);
        formContainer.add(Box.createVerticalGlue());

        add(formContainer, BorderLayout.CENTER);
    }

    public void setClient(ControllerClient client) {
        this.client = client;
        if (client != null) {
            lblUsername.setText(client.getUsername() != null ? client.getUsername() : "—");
            lblRole.setText(client.getRole() != null ? client.getRole() : "—");
        }
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    private void doChangePassword() {
        if (client == null || !client.isConnected()) {
            setStatus("Chưa kết nối", RED);
            return;
        }

        String oldPass = new String(tfOldPassword.getPassword()).trim();
        String newPass = new String(tfNewPassword.getPassword()).trim();
        String confirmPass = new String(tfConfirmPassword.getPassword()).trim();

        if (oldPass.isEmpty() || newPass.isEmpty()) {
            setStatus("Vui lòng nhập đầy đủ mật khẩu", RED);
            return;
        }

        if (!newPass.equals(confirmPass)) {
            setStatus("Mật khẩu mới không khớp", RED);
            return;
        }

        if (newPass.length() < 4) {
            setStatus("Mật khẩu mới phải có ít nhất 4 ký tự", RED);
            return;
        }

        setStatus("Đang xử lý...", TEXT_DIM);

        new Thread(() -> {
            try {
                Message response = client.changePassword(oldPass, newPass);
                SwingUtilities.invokeLater(() -> {
                    if ("SUCCESS".equals(response.getStatus())) {
                        setStatus(response.getMessage(), GREEN);
                        tfOldPassword.setText("");
                        tfNewPassword.setText("");
                        tfConfirmPassword.setText("");
                    } else {
                        setStatus(response.getMessage(), RED);
                    }
                });
                if (logCallback != null) logCallback.accept("Đổi mật khẩu: " + response.getStatus());
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi: " + e.getMessage(), RED));
            }
        }, "ChangePassword").start();
    }

    private void setStatus(String text, Color color) {
        lblStatus.setText(text);
        lblStatus.setForeground(color);
    }

    private static JLabel createLabel(String text, Font font, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(font);
        lbl.setForeground(color);
        return lbl;
    }
}
