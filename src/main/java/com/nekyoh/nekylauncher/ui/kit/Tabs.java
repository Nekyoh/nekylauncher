package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Underlined tab strip + card content area. */
public class Tabs extends JPanel {
    private record Tab(String key, String title, Icons.Name icon, Component content) {}

    private final List<Tab> tabs = new ArrayList<>();
    private final Header header = new Header();
    private final JPanel body = new JPanel(new CardLayout());
    private int selected;
    private final List<Consumer<String>> listeners = new ArrayList<>();

    public Tabs() {
        super(new BorderLayout(0, 14));
        setOpaque(false);
        body.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    public Tabs addTab(String key, String title, Icons.Name icon, Component content) {
        tabs.add(new Tab(key, title, icon, content));
        body.add(content, key);
        header.revalidate();
        return this;
    }

    public void select(String key) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).key.equals(key)) select(i);
    }

    public void select(int i) {
        selected = i;
        ((CardLayout) body.getLayout()).show(body, tabs.get(i).key);
        header.repaint();
        listeners.forEach(l -> l.accept(tabs.get(i).key));
    }

    public String selectedKey() { return tabs.isEmpty() ? null : tabs.get(selected).key; }

    public Tabs onChange(Consumer<String> c) {
        listeners.add(c);
        return this;
    }

    private final class Header extends JComponent {
        private int hover = -1;
        private int[] xs = new int[0];

        Header() {
            setFont(Theme.font(13.5f, Theme.Weight.SEMIBOLD));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = at(e.getX());
                    if (h != hover) { hover = h; repaint(); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    int i = at(e.getX());
                    if (i >= 0) select(i);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        int at(int x) {
            for (int i = 0; i + 1 < xs.length; i++) if (x >= xs[i] && x < xs[i + 1]) return i;
            return -1;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(100, 40); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int h = getHeight();
            g2.setColor(Theme.BORDER);
            g2.fillRect(0, h - 1, getWidth(), 1);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            xs = new int[tabs.size() + 1];
            int x = 0;
            for (int i = 0; i < tabs.size(); i++) {
                Tab t = tabs.get(i);
                int w = fm.stringWidth(t.title) + 28 + (t.icon != null ? 22 : 0);
                xs[i] = x;
                boolean sel = i == selected;
                if (i == hover && !sel) {
                    g2.setColor(Theme.alpha(Theme.SURFACE_3, 0.6));
                    g2.fill(new RoundRectangle2D.Float(x + 2, 4, w - 4, h - 10, 14, 14));
                }
                Color c = sel ? Theme.TEXT : Theme.TEXT_2;
                int cx = x + 14;
                if (t.icon != null) {
                    Icons.get(t.icon, 16, sel ? Theme.CYAN : Theme.TEXT_3).paintIcon(this, g2, cx, (h - 16) / 2 - 1);
                    cx += 22;
                }
                g2.setColor(c);
                g2.drawString(t.title, cx, (h - fm.getHeight()) / 2 + fm.getAscent() - 1);
                if (sel) {
                    g2.setPaint(new GradientPaint(x + 8, 0, Theme.CYAN, x + w - 8, 0, new Color(0x38BDF8)));
                    g2.fill(new RoundRectangle2D.Float(x + 10, h - 3, w - 20, 3, 3, 3));
                }
                x += w;
            }
            xs[tabs.size()] = x;
            g2.dispose();
        }
    }
}
