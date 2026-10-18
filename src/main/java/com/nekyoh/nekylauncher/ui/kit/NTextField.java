package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/** Rounded text field with placeholder and optional leading icon. */
public class NTextField extends JTextField {
    private String placeholder;
    private final Icons.Name icon;
    private boolean error;

    public NTextField(String placeholder) { this(placeholder, null); }

    public NTextField(String placeholder, Icons.Name icon) {
        this.placeholder = placeholder;
        this.icon = icon;
        style(this, icon != null);
    }

    static void style(JTextComponent c, boolean hasIcon) {
        c.setOpaque(false);
        c.setFont(Theme.font(13.5f));
        c.setForeground(Theme.TEXT);
        c.setCaretColor(Theme.CYAN);
        c.setSelectionColor(new Color(0x155E75));
        c.setSelectedTextColor(Theme.TEXT);
        c.setBorder(new EmptyBorder(8, hasIcon ? 36 : 12, 8, 12));
        c.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { c.repaint(); }
            @Override public void focusLost(FocusEvent e) { c.repaint(); }
        });
    }

    public void setPlaceholder(String p) {
        placeholder = p;
        repaint();
    }

    public void setError(boolean e) {
        error = e;
        repaint();
    }

    public NTextField onChange(Consumer<String> c) {
        getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { c.accept(getText()); }
            public void removeUpdate(DocumentEvent e) { c.accept(getText()); }
            public void changedUpdate(DocumentEvent e) { c.accept(getText()); }
        });
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 120), 38);
    }

    @Override
    public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 38); }

    @Override
    protected void paintComponent(Graphics g) {
        paintField(this, g, error, icon, placeholder);
        super.paintComponent(g);
    }

    static void paintField(JTextComponent c, Graphics g, boolean error, Icons.Name icon, String placeholder) {
        Graphics2D g2 = Theme.hq(g.create());
        int w = c.getWidth(), h = c.getHeight();
        RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 18, 18);
        g2.setColor(c.isEnabled() ? Theme.SURFACE_2 : Theme.SURFACE);
        g2.fill(r);
        Color border = error ? Theme.RED : c.isFocusOwner() ? Theme.alpha(Theme.CYAN, 0.85) : Theme.BORDER_STRONG;
        g2.setColor(border);
        g2.setStroke(new BasicStroke(c.isFocusOwner() || error ? 1.4f : 1f));
        g2.draw(r);
        if (icon != null) {
            Icons.get(icon, 16, Theme.TEXT_3).paintIcon(c, g2, 12, (h - 16) / 2);
        }
        if (placeholder != null && c.getDocument().getLength() == 0) {
            g2.setFont(c.getFont());
            g2.setColor(Theme.TEXT_3);
            Insets in = c.getInsets();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(placeholder, in.left, (h - fm.getHeight()) / 2 + fm.getAscent());
        }
        g2.dispose();
    }

    /** Password variant sharing the same look. */
    public static class Password extends JPasswordField {
        private final String placeholder;

        public Password(String placeholder) {
            this.placeholder = placeholder;
            style(this, false);
        }

        @Override public Dimension getPreferredSize() { return new Dimension(Math.max(super.getPreferredSize().width, 120), 38); }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 38); }

        @Override
        protected void paintComponent(Graphics g) {
            paintField(this, g, false, null, placeholder);
            super.paintComponent(g);
        }
    }
}
