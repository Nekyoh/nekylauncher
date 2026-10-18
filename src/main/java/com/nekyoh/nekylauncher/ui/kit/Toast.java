package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Transient notifications stacked at the bottom-right of the window. */
public final class Toast {
    private Toast() {}

    public enum Kind { INFO, SUCCESS, WARNING, ERROR }

    private static JPanel host;

    public static void init(JRootPane root) {
        JLayeredPane layers = root.getLayeredPane();
        host = new JPanel(null);
        host.setOpaque(false);
        layers.add(host, JLayeredPane.POPUP_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { relayout(); }
        });
    }

    public static void show(String message, Kind kind) { show(message, kind, null, null); }

    public static void show(String message, Kind kind, String actionText, Runnable action) {
        if (host == null) return;
        SwingUtilities.invokeLater(() -> {
            Item item = new Item(message, kind, actionText, action);
            host.add(item);
            relayout();
            int ms = kind == Kind.ERROR ? 9000 : action != null ? 7000 : 4500;
            Timer t = new Timer(ms, e -> remove(item));
            t.setRepeats(false);
            t.start();
        });
    }

    private static void remove(Item item) {
        host.remove(item);
        relayout();
    }

    private static void relayout() {
        JLayeredPane layers = (JLayeredPane) host.getParent();
        if (layers == null) return;
        int W = layers.getWidth(), H = layers.getHeight();
        int w = 360;
        int y = H - 18;
        Component[] cs = host.getComponents();
        int totalH = 0;
        for (int i = cs.length - 1; i >= 0; i--) {
            Component c = cs[i];
            c.setSize(w, 10);
            int h = c.getPreferredSize().height;
            totalH += h + 10;
        }
        host.setBounds(W - w - 18, Math.max(0, H - 18 - totalH), w, totalH);
        y = totalH;
        for (int i = cs.length - 1; i >= 0; i--) {
            Component c = cs[i];
            int h = c.getPreferredSize().height;
            y -= h + 10;
            c.setBounds(0, y, w, h);
        }
        host.revalidate();
        host.repaint();
    }

    private static final class Item extends JPanel {
        private final Color accent;

        Item(String message, Kind kind, String actionText, Runnable action) {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(12, 14, 12, 12));
            accent = switch (kind) {
                case SUCCESS -> Theme.GREEN;
                case WARNING -> Theme.AMBER;
                case ERROR -> Theme.RED;
                default -> Theme.CYAN;
            };
            Icons.Name icon = switch (kind) {
                case SUCCESS -> Icons.Name.CHECK;
                case WARNING, ERROR -> Icons.Name.WARNING;
                default -> Icons.Name.INFO;
            };
            JLabel ic = new JLabel(Icons.get(icon, 18, accent));
            ic.setVerticalAlignment(SwingConstants.TOP);
            add(ic, BorderLayout.WEST);
            JTextArea text = Ui.paragraph(message, Theme.TEXT, 13);
            add(text, BorderLayout.CENTER);
            if (action != null) {
                JLabel link = Ui.link(actionText, () -> {
                    action.run();
                    Toast.remove(this);
                });
                link.setVerticalAlignment(SwingConstants.TOP);
                add(link, BorderLayout.EAST);
            }
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Toast.remove(Item.this); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 22, 22);
            g2.setColor(Theme.SURFACE_2);
            g2.fill(r);
            g2.setColor(Theme.BORDER_STRONG);
            g2.draw(r);
            g2.setColor(accent);
            g2.fill(new RoundRectangle2D.Float(0, 10, 3, getHeight() - 20, 3, 3));
            g2.dispose();
        }
    }
}
