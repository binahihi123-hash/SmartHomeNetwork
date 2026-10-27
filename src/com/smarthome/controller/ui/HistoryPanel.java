package com.smarthome.controller.ui;

import com.smarthome.controller.ControllerClient;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * History panel showing command history in a styled table.
 * Fetches data from server (which reads from SQLite database).
 */
public class HistoryPanel extends JPanel {

    private final DefaultTableModel tableModel;
    private final JTable table;
    private ControllerClient client;
    private Consumer<String> logCallback;

    public HistoryPanel() {
        setLayout(new BorderLayout(GAP, GAP));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("Lịch sử điều khiển", FONT_TITLE, TEXT_PRIMARY), BorderLayout.WEST);

        JButton btnRefresh = createAccentButton("⟳ Tải lại");
        btnRefresh.addActionListener(e -> loadHistory());
        header.add(btnRefresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // Table
        String[] columns = {"Thời gian", "Người dùng", "Thiết bị", "Lệnh", "Kết quả", "Chi tiết"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setFont(FONT_BODY);
        table.setForeground(TEXT_PRIMARY);
        table.setBackground(BG_CARD);
        table.setGridColor(BORDER);
        table.setRowHeight(32);
        table.setSelectionBackground(ACCENT_DIM);
        table.setSelectionForeground(Color.WHITE);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1));

        // Header style
        JTableHeader tableHeader = table.getTableHeader();
        tableHeader.setFont(FONT_BUTTON);
        tableHeader.setForeground(TEXT_PRIMARY);
        tableHeader.setBackground(BG_SURFACE);
        tableHeader.setReorderingAllowed(false);

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);

        // Custom renderer for status column
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    String status = value != null ? value.toString() : "";
                    if ("SUCCESS".equals(status)) {
                        c.setForeground(GREEN);
                    } else if ("FAILURE".equals(status) || "ERROR".equals(status)) {
                        c.setForeground(RED);
                    } else {
                        c.setForeground(TEXT_SECONDARY);
                    }
                    c.setBackground(BG_CARD);
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        scroll.getViewport().setBackground(BG_CARD);
        add(scroll, BorderLayout.CENTER);

        // Footer with count
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT));
        footer.setOpaque(false);
        footer.add(createLabel("Nhấn 'Tải lại' để cập nhật dữ liệu từ server", FONT_SMALL, TEXT_DIM));
        add(footer, BorderLayout.SOUTH);
    }

    public void setClient(ControllerClient client) {
        this.client = client;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    @SuppressWarnings("unchecked")
    public void loadHistory() {
        if (client == null || !client.isConnected()) {
            if (logCallback != null) logCallback.accept("Chưa kết nối!");
            return;
        }

        new Thread(() -> {
            try {
                var response = client.requestHistory();
                if (response != null && response.getPayload() != null) {
                    Object histObj = response.getPayload().get("history");
                    if (histObj instanceof List<?> list) {
                        SwingUtilities.invokeLater(() -> {
                            tableModel.setRowCount(0);

                            for (Object item : list) {
                                if (item instanceof String s) {
                                    // Parse format: [timestamp] user -> deviceId command = status (message)
                                    parseAndAddRow(s);
                                }
                            }
                        });
                    }
                }
            } catch (IOException e) {
                if (logCallback != null) logCallback.accept("Lỗi tải lịch sử: " + e.getMessage());
            }
        }, "HistoryLoad").start();
    }

    private void parseAndAddRow(String entry) {
        try {
            // Format: [timestamp] username -> device_id command = status (message)
            // Example: [2024-01-01T12:00:00Z] admin -> DEVICE_LIGHT_01 ON = SUCCESS (Đèn đã bật)
            int bracket1 = entry.indexOf('[');
            int bracket2 = entry.indexOf(']');
            String timestamp = entry.substring(bracket1 + 1, bracket2);

            String rest = entry.substring(bracket2 + 2); // skip "] "
            String[] parts = rest.split(" -> ");
            String username = parts.length > 0 ? parts[0].trim() : "?";

            String commandPart = parts.length > 1 ? parts[1] : rest;
            String[] cmdParts = commandPart.split(" = ");

            String deviceAndCmd = cmdParts[0].trim();
            String[] dc = deviceAndCmd.split(" ", 2);
            String device = dc.length > 0 ? dc[0] : "?";
            String command = dc.length > 1 ? dc[1] : "?";

            String statusAndMsg = cmdParts.length > 1 ? cmdParts[1].trim() : "?";
            int parenIdx = statusAndMsg.indexOf('(');
            String status = parenIdx > 0 ? statusAndMsg.substring(0, parenIdx).trim() : statusAndMsg;
            String message = parenIdx > 0 ? statusAndMsg.substring(parenIdx + 1, statusAndMsg.length() - 1) : "";

            // Format timestamp for display
            String displayTime = timestamp;
            if (timestamp.length() > 19) {
                displayTime = timestamp.substring(0, 19).replace('T', ' ');
            }

            tableModel.addRow(new Object[]{displayTime, username, device, command, status, message});
        } catch (Exception e) {
            // Fallback: put whole entry in first column
            tableModel.addRow(new Object[]{entry, "", "", "", "", ""});
        }
    }
}
