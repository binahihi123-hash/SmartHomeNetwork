package com.smarthome.controller.ui;

import com.smarthome.common.*;
import com.smarthome.controller.ControllerClient;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Admin-only panel for managing users. CRUD operations via ControllerClient.
 * Actions: List, Create, Enable/Disable, Set Role, Reset Password.
 */
public class UserManagementPanel extends JPanel {

    private ControllerClient client;
    private Consumer<String> logCallback;

    private final DefaultTableModel tableModel;
    private final JTable userTable;
    private final JLabel lblStatus;

    public UserManagementPanel() {
        setLayout(new BorderLayout(0, 10));
        setBackground(BG_DARK);
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Header
        JLabel title = createLabel("Quản lý người dùng", FONT_TITLE, TEXT_PRIMARY);
        add(title, BorderLayout.NORTH);

        // Table
        String[] columns = {"Tên đăng nhập", "Vai trò", "Trạng thái", "Ngày tạo", "Đăng nhập cuối"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        userTable = new JTable(tableModel);
        userTable.setBackground(BG_SURFACE);
        userTable.setForeground(TEXT_PRIMARY);
        userTable.setSelectionBackground(ACCENT);
        userTable.setSelectionForeground(Color.WHITE);
        userTable.setFont(FONT_BODY);
        userTable.setRowHeight(32);
        userTable.getTableHeader().setBackground(BG_SIDEBAR);
        userTable.getTableHeader().setForeground(TEXT_SECONDARY);
        userTable.getTableHeader().setFont(FONT_BODY);

        JScrollPane scrollPane = new JScrollPane(userTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollPane.getViewport().setBackground(BG_SURFACE);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom panel with buttons
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setBackground(BG_DARK);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonPanel.setBackground(BG_DARK);

        JButton btnRefresh = createButton("Làm mới");
        JButton btnCreate = createButton("Tạo tài khoản");
        JButton btnToggle = createButton("Bật/Tắt");
        JButton btnRole = createButton("Đổi vai trò");
        JButton btnResetPw = createButton("Đặt lại mật khẩu");

        btnRefresh.addActionListener(e -> loadUsers());
        btnCreate.addActionListener(e -> createUser());
        btnToggle.addActionListener(e -> toggleUser());
        btnRole.addActionListener(e -> changeRole());
        btnResetPw.addActionListener(e -> resetPassword());

        buttonPanel.add(btnRefresh);
        buttonPanel.add(btnCreate);
        buttonPanel.add(btnToggle);
        buttonPanel.add(btnRole);
        buttonPanel.add(btnResetPw);

        lblStatus = createLabel("", FONT_SMALL, TEXT_DIM);
        lblStatus.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        bottomPanel.add(buttonPanel, BorderLayout.CENTER);
        bottomPanel.add(lblStatus, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    public void setClient(ControllerClient client) {
        this.client = client;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    /**
     * Load user list from server.
     */
    @SuppressWarnings("unchecked")
    public void loadUsers() {
        if (client == null || !client.isConnected()) {
            setStatus("Chưa kết nối", RED);
            return;
        }
        setStatus("Đang tải...", TEXT_DIM);

        new Thread(() -> {
            try {
                Message response = client.sendUserManagement("LIST_USERS", null, null, null);
                if ("SUCCESS".equals(response.getStatus()) && response.getPayload() != null) {
                    Object usersObj = response.getPayload().get("users");
                    if (usersObj instanceof List<?> list) {
                        SwingUtilities.invokeLater(() -> {
                            tableModel.setRowCount(0);
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> map) {
                                    String username = String.valueOf(map.get("username"));
                                    String role = String.valueOf(map.get("role"));
                                    String enabled = "1".equals(String.valueOf(map.get("enabled"))) ? "Hoạt động" : "Vô hiệu";
                                    String createdAt = String.valueOf(map.get("created_at"));
                                    String lastLogin = String.valueOf(map.get("last_login"));
                                    if ("null".equals(lastLogin) || lastLogin.isEmpty()) lastLogin = "—";
                                    // Trim timestamps
                                    if (createdAt.length() > 19) createdAt = createdAt.substring(0, 19);
                                    if (lastLogin.length() > 19) lastLogin = lastLogin.substring(0, 19);
                                    tableModel.addRow(new Object[]{username, role, enabled, createdAt, lastLogin});
                                }
                            }
                            setStatus(tableModel.getRowCount() + " người dùng", GREEN);
                        });
                    }
                } else {
                    SwingUtilities.invokeLater(() ->
                            setStatus("Lỗi: " + (response.getMessage() != null ? response.getMessage() : "Unknown"), RED));
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi kết nối: " + e.getMessage(), RED));
            }
        }, "LoadUsers").start();
    }

    private void createUser() {
        if (client == null || !client.isConnected()) return;

        JTextField tfUsername = new JTextField(15);
        JPasswordField tfPassword = new JPasswordField(15);
        JComboBox<String> cbRole = new JComboBox<>(new String[]{"USER", "ADMIN"});

        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.add(new JLabel("Tên đăng nhập:"));
        panel.add(tfUsername);
        panel.add(new JLabel("Mật khẩu:"));
        panel.add(tfPassword);
        panel.add(new JLabel("Vai trò:"));
        panel.add(cbRole);

        int result = JOptionPane.showConfirmDialog(this, panel, "Tạo tài khoản mới",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String newUser = tfUsername.getText().trim();
        String newPass = new String(tfPassword.getPassword()).trim();
        String newRole = (String) cbRole.getSelectedItem();

        if (newUser.isEmpty() || newPass.isEmpty()) {
            setStatus("Tên đăng nhập và mật khẩu không được để trống", RED);
            return;
        }

        new Thread(() -> {
            try {
                Message response = client.sendUserManagement("CREATE_USER", newUser, newPass, newRole);
                SwingUtilities.invokeLater(() -> {
                    setStatus(response.getMessage(), "SUCCESS".equals(response.getStatus()) ? GREEN : RED);
                    if ("SUCCESS".equals(response.getStatus())) loadUsers();
                });
                if (logCallback != null) logCallback.accept("Tạo tài khoản: " + newUser + " → " + response.getStatus());
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi: " + e.getMessage(), RED));
            }
        }, "CreateUser").start();
    }

    private void toggleUser() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            setStatus("Vui lòng chọn một người dùng", YELLOW);
            return;
        }
        String username = (String) tableModel.getValueAt(row, 0);
        String currentStatus = (String) tableModel.getValueAt(row, 2);
        boolean isEnabled = "Hoạt động".equals(currentStatus);
        String action = isEnabled ? "DISABLE_USER" : "ENABLE_USER";

        new Thread(() -> {
            try {
                Message response = client.sendUserManagement(action, username, null, null);
                SwingUtilities.invokeLater(() -> {
                    setStatus(response.getMessage(), "SUCCESS".equals(response.getStatus()) ? GREEN : RED);
                    if ("SUCCESS".equals(response.getStatus())) loadUsers();
                });
                if (logCallback != null) logCallback.accept(action + ": " + username + " → " + response.getStatus());
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi: " + e.getMessage(), RED));
            }
        }, "ToggleUser").start();
    }

    private void changeRole() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            setStatus("Vui lòng chọn một người dùng", YELLOW);
            return;
        }
        String username = (String) tableModel.getValueAt(row, 0);
        String currentRole = (String) tableModel.getValueAt(row, 1);
        String newRole = "ADMIN".equals(currentRole) ? "USER" : "ADMIN";

        int confirm = JOptionPane.showConfirmDialog(this,
                "Đổi vai trò của " + username + " từ " + currentRole + " thành " + newRole + "?",
                "Xác nhận", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            try {
                Message response = client.sendUserManagement("SET_ROLE", username, null, newRole);
                SwingUtilities.invokeLater(() -> {
                    setStatus(response.getMessage(), "SUCCESS".equals(response.getStatus()) ? GREEN : RED);
                    if ("SUCCESS".equals(response.getStatus())) loadUsers();
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi: " + e.getMessage(), RED));
            }
        }, "ChangeRole").start();
    }

    private void resetPassword() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            setStatus("Vui lòng chọn một người dùng", YELLOW);
            return;
        }
        String username = (String) tableModel.getValueAt(row, 0);

        JPasswordField tfNewPass = new JPasswordField(15);
        JPanel panel = new JPanel(new GridLayout(1, 2, 5, 5));
        panel.add(new JLabel("Mật khẩu mới:"));
        panel.add(tfNewPass);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Đặt lại mật khẩu cho " + username, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String newPass = new String(tfNewPass.getPassword()).trim();
        if (newPass.isEmpty()) {
            setStatus("Mật khẩu không được để trống", RED);
            return;
        }

        new Thread(() -> {
            try {
                Message response = client.sendUserManagement("RESET_PASSWORD", username, newPass, null);
                SwingUtilities.invokeLater(() ->
                        setStatus(response.getMessage(), "SUCCESS".equals(response.getStatus()) ? GREEN : RED));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> setStatus("Lỗi: " + e.getMessage(), RED));
            }
        }, "ResetPassword").start();
    }

    private void setStatus(String text, Color color) {
        lblStatus.setText(text);
        lblStatus.setForeground(color);
    }

    // UI helpers
    private static JLabel createLabel(String text, Font font, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(font);
        lbl.setForeground(color);
        return lbl;
    }

    private static JButton createButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BODY);
        btn.setBackground(BG_SURFACE);
        btn.setForeground(TEXT_PRIMARY);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
