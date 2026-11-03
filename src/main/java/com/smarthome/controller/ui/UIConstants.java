package com.smarthome.controller.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Theme constants and helper methods for the Smart Home dark UI.
 * Catppuccin-inspired color scheme with FlatLaf dark base.
 */
public final class UIConstants {
    private UIConstants() {}

    // === Colors ===
    public static final Color BG_DARK      = new Color(0x1E1E2E);
    public static final Color BG_SURFACE   = new Color(0x2A2A3C);
    public static final Color BG_SIDEBAR   = new Color(0x181825);
    public static final Color BG_CARD      = new Color(0x313244);
    public static final Color BG_INPUT     = new Color(0x3B3B50);
    public static final Color BG_HOVER     = new Color(0x414160);

    public static final Color ACCENT       = new Color(0x89B4FA);
    public static final Color ACCENT_DIM   = new Color(0x5580C0);
    public static final Color GREEN        = new Color(0xA6E3A1);
    public static final Color RED          = new Color(0xF38BA8);
    public static final Color YELLOW       = new Color(0xF9E2AF);
    public static final Color ORANGE       = new Color(0xFAB387);
    public static final Color PURPLE       = new Color(0xCBA6F7);
    public static final Color TEAL         = new Color(0x94E2D5);

    public static final Color TEXT_PRIMARY   = new Color(0xCDD6F4);
    public static final Color TEXT_SECONDARY = new Color(0xA6ADC8);
    public static final Color TEXT_DIM       = new Color(0x6C7086);
    public static final Color TEXT_ON_ACCENT = new Color(0x1E1E2E);

    public static final Color BORDER       = new Color(0x45475A);
    public static final Color DIVIDER      = new Color(0x313244);

    // === Fonts ===
    public static final Font FONT_TITLE    = new Font("Segoe UI", Font.BOLD, 22);
    public static final Font FONT_SUBTITLE = new Font("Segoe UI", Font.BOLD, 16);
    public static final Font FONT_BODY     = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_SMALL    = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_MONO     = new Font("Consolas", Font.PLAIN, 13);
    public static final Font FONT_SIDEBAR  = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_BUTTON   = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_STAT_NUM = new Font("Segoe UI", Font.BOLD, 28);

    // === Dimensions ===
    public static final int SIDEBAR_WIDTH  = 200;
    public static final int CARD_ARC       = 12;
    public static final int CARD_PADDING   = 16;
    public static final int GAP            = 12;

    // === Helper: create a rounded card panel ===
    public static JPanel createCard() {
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), CARD_ARC, CARD_ARC);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(CARD_PADDING, CARD_PADDING, CARD_PADDING, CARD_PADDING));
        return card;
    }

    // === Helper: styled button ===
    public static JButton createAccentButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BUTTON);
        btn.setForeground(TEXT_ON_ACCENT);
        btn.setBackground(ACCENT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.putClientProperty("JButton.buttonType", "roundRect");
        return btn;
    }

    public static JButton createOutlineButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BUTTON);
        btn.setForeground(TEXT_PRIMARY);
        btn.setBackground(BG_INPUT);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.putClientProperty("JButton.buttonType", "roundRect");
        return btn;
    }

    // === Helper: styled text field ===
    public static JTextField createTextField(String placeholder, int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(FONT_BODY);
        field.setForeground(TEXT_PRIMARY);
        field.setBackground(BG_INPUT);
        field.setCaretColor(TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        field.putClientProperty("JTextField.placeholderText", placeholder);
        return field;
    }

    // === Helper: styled password field ===
    public static JPasswordField createPasswordField(int columns) {
        JPasswordField field = new JPasswordField(columns);
        field.setFont(FONT_BODY);
        field.setForeground(TEXT_PRIMARY);
        field.setBackground(BG_INPUT);
        field.setCaretColor(TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        return field;
    }

    // === Helper: create label ===
    public static JLabel createLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    // === Helper: sidebar nav item ===
    public static JButton createSidebarButton(String text, String icon) {
        JButton btn = new JButton(icon + "  " + text);
        btn.setFont(FONT_SIDEBAR);
        btn.setForeground(TEXT_SECONDARY);
        btn.setBackground(BG_SIDEBAR);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        return btn;
    }

    // === Helper: set sidebar button active ===
    public static void setSidebarActive(JButton btn, boolean active) {
        if (active) {
            btn.setBackground(ACCENT_DIM);
            btn.setForeground(Color.WHITE);
        } else {
            btn.setBackground(BG_SIDEBAR);
            btn.setForeground(TEXT_SECONDARY);
        }
    }

    // === Helper: status badge ===
    public static JLabel createStatusBadge(String text, Color bgColor) {
        JLabel badge = new JLabel(text, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(FONT_SMALL);
        badge.setForeground(TEXT_ON_ACCENT);
        badge.setOpaque(false);
        badge.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        return badge;
    }

    // === Helper: device type icon (Unicode emoji) ===
    public static String getDeviceIcon(String deviceType) {
        if (deviceType == null) return "\u2699"; // ⚙
        return switch (deviceType.toUpperCase()) {
            case "LIGHT" -> "\uD83D\uDCA1"; // 💡
            case "FAN"   -> "\uD83C\uDF00"; // 🌀
            case "AC"    -> "\u2744";        // ❄
            case "TV"    -> "\uD83D\uDCFA"; // 📺
            default      -> "\u2699";        // ⚙
        };
    }

    // === Helper: wrap panel with padding ===
    public static JPanel wrapWithPadding(JComponent comp, int top, int left, int bottom, int right) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        wrapper.add(comp, BorderLayout.CENTER);
        return wrapper;
    }
}
