package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Small factory helpers for consistent typography and common widgets. */
public final class Ui {
    private Ui() {}

    public static JLabel label(String text, float size, Theme.Weight w, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(size, w));
        l.setForeground(color);
        return l;
    }

    public static JLabel h1(String text) { return label(text, 26, Theme.Weight.BOLD, Theme.TEXT); }

    public static JLabel h2(String text) { return label(text, 18, Theme.Weight.SEMIBOLD, Theme.TEXT); }

    public static JLabel h3(String text) { return label(text, 14.5f, Theme.Weight.SEMIBOLD, Theme.TEXT); }

    public static JLabel body(String text) { return label(text, 13.5f, Theme.Weight.REGULAR, Theme.TEXT); }

    public static JLabel muted(String text) { return label(text, 13, Theme.Weight.REGULAR, Theme.TEXT_2); }

    public static JLabel small(String text) { return label(text, 12, Theme.Weight.REGULAR, Theme.TEXT_3); }

    public static JLabel caps(String text) {
        JLabel l = label(text.toUpperCase(), 11, Theme.Weight.SEMIBOLD, Theme.TEXT_3);
        return l;
    }

    /** Multi-line wrapped text. */
    public static JTextArea paragraph(String text, Color color, float size) {
        JTextArea t = new JTextArea();
        // never let a read-only paragraph scroll its parent viewport to its caret
        if (t.getCaret() instanceof javax.swing.text.DefaultCaret dc) dc.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        t.setText(text);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setForeground(color);
        t.setFont(Theme.font(size));
        t.setBorder(null);
        return t;
    }

    /** Clickable text link. */
    public static JLabel link(String text, Runnable action) {
        JLabel l = label(text, 13, Theme.Weight.MEDIUM, Theme.CYAN);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { action.run(); }
            @Override public void mouseEntered(MouseEvent e) { l.setForeground(new Color(0x67E8F9)); }
            @Override public void mouseExited(MouseEvent e) { l.setForeground(Theme.CYAN); }
        });
        return l;
    }

    public static JLabel icon(Icons.Name n, int size, Color c) { return new JLabel(Icons.get(n, size, c)); }

    /** Rounded "pill" badge. */
    public static JComponent chip(String text, Color color) {
        return new Chip(text, color, null);
    }

    public static JComponent chip(String text, Color color, Icons.Name icon) {
        return new Chip(text, color, icon);
    }

    public static final class Chip extends JComponent {
        private String text;
        private final Color color;
        private final Icons.Name icon;

        Chip(String text, Color color, Icons.Name icon) {
            this.text = text;
            this.color = color;
            this.icon = icon;
            setFont(Theme.font(11.5f, Theme.Weight.SEMIBOLD));
        }

        public void setText(String t) {
            text = t;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 18 + (icon != null ? 16 : 0), 22);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.alpha(color, 0.14));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            g2.setColor(Theme.alpha(color, 0.35));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h));
            int x = 9;
            if (icon != null) {
                Icons.get(icon, 12, color).paintIcon(this, g2, x, (h - 12) / 2);
                x += 16;
            }
            g2.setFont(getFont());
            g2.setColor(Theme.mix(color, Color.WHITE, 0.25));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Thin horizontal separator. */
    public static JComponent divider() {
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.BORDER);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        c.setPreferredSize(new Dimension(10, 9));
        return c;
    }

    public static JPanel transparent(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    /** Keeps a component at its preferred width, aligned left, inside stretching layouts. */
    public static JPanel left(JComponent c) {
        JPanel p = transparent(new BorderLayout());
        p.add(c, BorderLayout.WEST);
        return p;
    }

    /** Wraps a component so it keeps its preferred size, centred, inside stretching layouts. */
    public static JPanel center(JComponent c) {
        JPanel p = transparent(new GridBagLayout());
        p.add(c);
        return p;
    }

    public static EmptyBorder pad(int all) { return new EmptyBorder(all, all, all, all); }

    /** Labeled form row: label + hint above the control. */
    public static JComponent field(String label, String hint, JComponent control) {
        Stack s = Stack.v(6);
        s.add(label(label, 13, Theme.Weight.SEMIBOLD, Theme.TEXT));
        if (hint != null && !hint.isBlank()) s.add(paragraph(hint, Theme.TEXT_3, 12));
        s.add(control);
        return s;
    }

    /** Row with title/description on the left and a control on the right (settings style). */
    public static JComponent settingRow(String title, String desc, JComponent control) {
        JPanel row = transparent(new BorderLayout(16, 0));
        Stack text = Stack.v(3);
        text.add(label(title, 13.5f, Theme.Weight.MEDIUM, Theme.TEXT));
        if (desc != null && !desc.isBlank()) text.add(paragraph(desc, Theme.TEXT_3, 12));
        row.add(text, BorderLayout.CENTER);
        JPanel right = transparent(new GridBagLayout());
        right.add(control);
        row.add(right, BorderLayout.EAST);
        row.setBorder(new EmptyBorder(6, 0, 6, 0));
        return row;
    }

    public static String ellipsize(String s, FontMetrics fm, int max) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
