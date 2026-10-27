package com.smarthome.controller.ui;

import com.smarthome.common.MessageType;
import com.smarthome.common.VoiceCommandParser;
import com.smarthome.controller.ControllerClient;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.function.Consumer;

import static com.smarthome.controller.ui.UIConstants.*;

/**
 * Command center panel for text/voice commands.
 * Includes command input, quick action buttons, and result display.
 */
public class CommandPanel extends JPanel {

    private final JTextField txtCommand;
    private final JTextArea txtResults;
    private ControllerClient client;
    private Consumer<String> logCallback;

    public CommandPanel() {
        setLayout(new BorderLayout(GAP, GAP));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("Trung tâm điều khiển", FONT_TITLE, TEXT_PRIMARY), BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        // Center content
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        // Command input card
        JPanel inputCard = createCard();
        inputCard.setLayout(new BorderLayout(8, 8));
        inputCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        inputCard.add(createLabel("Nhập lệnh văn bản / giọng nói", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setOpaque(false);

        txtCommand = createTextField("VD: bật đèn phòng khách, tắt quạt, bật máy lạnh...", 30);
        txtCommand.addActionListener(e -> doSendCommand());
        inputRow.add(txtCommand, BorderLayout.CENTER);

        JButton btnSend = createAccentButton("Gửi lệnh");
        btnSend.addActionListener(e -> doSendCommand());
        inputRow.add(btnSend, BorderLayout.EAST);

        inputCard.add(inputRow, BorderLayout.CENTER);

        JLabel hint = createLabel("Hỗ trợ: bật/tắt + tên thiết bị (đèn phòng khách, quạt, máy lạnh, TV...)", FONT_SMALL, TEXT_DIM);
        inputCard.add(hint, BorderLayout.SOUTH);

        center.add(inputCard);
        center.add(Box.createVerticalStrut(GAP));

        // Quick actions card
        JPanel quickCard = createCard();
        quickCard.setLayout(new BorderLayout(0, 8));
        quickCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));

        quickCard.add(createLabel("Thao tác nhanh", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        JPanel quickGrid = new JPanel(new GridLayout(2, 3, 8, 8));
        quickGrid.setOpaque(false);

        String[][] quickActions = {
                {"💡 Bật đèn PK", "DEVICE_LIGHT_01", "ON"},
                {"💡 Tắt đèn PK", "DEVICE_LIGHT_01", "OFF"},
                {"💡 Bật đèn PN", "DEVICE_LIGHT_02", "ON"},
                {"🌀 Bật quạt", "DEVICE_FAN_01", "ON"},
                {"❄ Bật máy lạnh", "DEVICE_AC_01", "ON"},
                {"📺 Bật TV", "DEVICE_TV_01", "ON"}
        };

        for (String[] qa : quickActions) {
            JButton btn = createOutlineButton(qa[0]);
            btn.addActionListener(e -> sendCommand(qa[1], qa[2]));
            quickGrid.add(btn);
        }

        quickCard.add(quickGrid, BorderLayout.CENTER);
        center.add(quickCard);
        center.add(Box.createVerticalStrut(GAP));

        // Results area
        JPanel resultsCard = createCard();
        resultsCard.setLayout(new BorderLayout(0, 8));

        resultsCard.add(createLabel("Kết quả", FONT_SUBTITLE, TEXT_PRIMARY), BorderLayout.NORTH);

        txtResults = new JTextArea(10, 40);
        txtResults.setEditable(false);
        txtResults.setFont(FONT_MONO);
        txtResults.setForeground(TEXT_SECONDARY);
        txtResults.setBackground(BG_SURFACE);
        txtResults.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        txtResults.setText("Sẵn sàng nhận lệnh...\n");

        JScrollPane scroll = new JScrollPane(txtResults);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        resultsCard.add(scroll, BorderLayout.CENTER);

        center.add(resultsCard);
        add(center, BorderLayout.CENTER);
    }

    public void setClient(ControllerClient client) {
        this.client = client;
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    private void doSendCommand() {
        String text = txtCommand.getText().trim();
        if (text.isEmpty()) return;
        if (client == null || !client.isConnected()) {
            appendResult("⚠ Chưa kết nối đến server!");
            return;
        }

        txtCommand.setText("");
        appendResult("→ Gửi: " + text);

        new Thread(() -> {
            try {
                VoiceCommandParser.ParsedCommand parsed = VoiceCommandParser.parse(text);

                if (parsed.ambiguous || parsed.deviceId == null || parsed.command == null) {
                    var response = client.sendVoiceCommand(text);
                    if (response != null) {
                        appendResult("← " + response.getMessage() + " [" + response.getStatus() + "]");
                        if ("SUCCESS".equals(response.getStatus())) {
                            client.requestDeviceList();
                        }
                    }
                } else {
                    appendResult("  Phân tích: " + parsed.command + " → " + parsed.deviceId);
                    var response = client.sendControlCommand(parsed.deviceId, parsed.command);
                    if (response != null) {
                        appendResult("← " + response.getMessage() + " [" + response.getStatus() + "]");
                        if ("SUCCESS".equals(response.getStatus())) {
                            client.requestDeviceList();
                        }
                    }
                }
            } catch (IOException e) {
                appendResult("✗ Lỗi: " + e.getMessage());
            }
        }, "CmdThread").start();
    }

    private void sendCommand(String deviceId, String command) {
        if (client == null || !client.isConnected()) {
            appendResult("⚠ Chưa kết nối!");
            return;
        }
        appendResult("→ " + command + " → " + deviceId);
        new Thread(() -> {
            try {
                var response = client.sendControlCommand(deviceId, command);
                if (response != null) {
                    appendResult("← " + response.getMessage() + " [" + response.getStatus() + "]");
                    if ("SUCCESS".equals(response.getStatus())) {
                        client.requestDeviceList();
                    }
                }
            } catch (IOException e) {
                appendResult("✗ Lỗi: " + e.getMessage());
            }
        }).start();
    }

    public void appendResult(String text) {
        SwingUtilities.invokeLater(() -> {
            String time = java.time.LocalTime.now().withNano(0).toString();
            if (txtResults.getText().startsWith("Sẵn sàng")) {
                txtResults.setText("");
            }
            txtResults.append("[" + time + "] " + text + "\n");
            txtResults.setCaretPosition(txtResults.getDocument().getLength());
        });
        if (logCallback != null) logCallback.accept(text);
    }
}
