package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Dark rounded combo box with a styled popup. */
public class NCombo<T> extends JComboBox<T> {
    private Function<T, String> display = String::valueOf;

    public NCombo() {
        setUI(new UI());
        setOpaque(false);
        setFont(Theme.font(13.5f));
        setForeground(Theme.TEXT);
        setBackground(Theme.SURFACE_2);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setMaximumRowCount(12);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRenderer(new DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                setText(value == null ? "" : display.apply((T) value));
                setFont(Theme.font(13.5f));
                setBorder(new EmptyBorder(7, 12, 7, 12));
                setOpaque(index >= 0);
                setBackground(sel ? Theme.SURFACE_3 : Theme.SURFACE_2);
                setForeground(Theme.TEXT);
                return this;
            }
        });
    }

    public NCombo(List<T> items) {
        this();
        setItems(items);
    }

    public NCombo<T> display(Function<T, String> f) {
        display = f;
        repaint();
        return this;
    }

    public void setItems(List<T> items) {
        DefaultComboBoxModel<T> m = new DefaultComboBoxModel<>();
        for (T t : items) m.addElement(t);
        setModel(m);
    }

    @SuppressWarnings("unchecked")
    public T selected() { return (T) getSelectedItem(); }

    public NCombo<T> onSelect(Consumer<T> c) {
        addActionListener(e -> c.accept(selected()));
        return this;
    }

    @Override public Dimension getPreferredSize() { return new Dimension(Math.max(160, super.getPreferredSize().width + 24), 38); }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 38); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.hq(g.create());
        int w = getWidth(), h = getHeight();
        RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 18, 18);
        g2.setColor(isEnabled() ? Theme.SURFACE_2 : Theme.SURFACE);
        g2.fill(r);
        g2.setColor(isPopupVisible() ? Theme.alpha(Theme.CYAN, 0.85) : Theme.BORDER_STRONG);
        g2.draw(r);
        Object v = getSelectedItem();
        @SuppressWarnings("unchecked")
        String text = v == null ? "" : display.apply((T) v);
        g2.setFont(getFont());
        g2.setColor(isEnabled() ? Theme.TEXT : Theme.TEXT_3);
        FontMetrics fm = g2.getFontMetrics();
        int maxW = w - 12 - 34;
        String t = text;
        if (fm.stringWidth(t) > maxW) {
            while (t.length() > 1 && fm.stringWidth(t + "…") > maxW) t = t.substring(0, t.length() - 1);
            t += "…";
        }
        g2.drawString(t, 12, (h - fm.getHeight()) / 2 + fm.getAscent());
        Icons.get(Icons.Name.CHEVRON_DOWN, 16, Theme.TEXT_2).paintIcon(this, g2, w - 28, (h - 16) / 2);
        g2.dispose();
    }

    private static final class UI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton();
            b.setOpaque(false);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override public void paint(Graphics g, JComponent c) { /* painted by NCombo */ }

        @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) { }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox) {
                @Override
                protected JScrollPane createScroller() {
                    JScrollPane sp = super.createScroller();
                    NScroll.style(sp, Theme.SURFACE_2);
                    return sp;
                }
            };
            p.setBorder(new LineBorder(Theme.BORDER_STRONG));
            p.setBackground(Theme.SURFACE_2);
            return p;
        }
    }
}
