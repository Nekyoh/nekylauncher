package com.nekyoh.nekylauncher.ui.window;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.Images;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Window with a custom dark title bar (undecorated + own move/resize/maximize handling),
 * or the native frame when the user prefers it.
 */
public class NFrame extends JFrame {
    private static final int GRIP = 8;
    private static final int MARGIN = 4;

    protected final JPanel body = new JPanel(new BorderLayout());
    private final boolean custom;
    private final Outer outer;
    private TitleBar titleBar;

    public NFrame(String title, boolean systemFrame) {
        super(title);
        this.custom = !systemFrame;
        setIconImages(appIcons());
        body.setBackground(Theme.BG);
        outer = new Outer();
        outer.setBackground(Theme.BG);
        if (custom) {
            setUndecorated(true);
            titleBar = new TitleBar(this, title);
            outer.add(titleBar, BorderLayout.NORTH);
        }
        outer.add(body, BorderLayout.CENTER);
        setContentPane(outer);
        addWindowStateListener(e -> updateMaximizedState());
        getRootPane().setBackground(Theme.BG);
    }

    public static List<Image> appIcons() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) {
            BufferedImage img = Images.resource("/nekylauncher/img/icon-" + s + ".png");
            if (img != null) list.add(img);
        }
        return list;
    }

    public boolean isCustomFrame() { return custom; }

    public JComponent titleBarExtras() { return titleBar != null ? titleBar.extras : null; }

    public boolean isMaximized() { return (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH; }

    public void toggleMaximize() {
        if (isMaximized()) {
            setExtendedState(Frame.NORMAL);
        } else {
            if (custom) setMaximizedBounds(usableScreenBounds());
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    private Rectangle usableScreenBounds() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        // maximized bounds are relative to the screen's origin
        return new Rectangle(in.left, in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }

    private void updateMaximizedState() {
        outer.setBorder(custom && !isMaximized() ? BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN) : null);
        if (titleBar != null) titleBar.updateMaxIcon();
        outer.repaint();
    }

    /** Outer panel: draws the 1px window outline and handles edge resizing. */
    private final class Outer extends JPanel {
        private int dir = 0;
        private Point start;
        private Rectangle startBounds;

        Outer() {
            super(new BorderLayout());
            setBorder(custom ? BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN) : null);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { setCursor(Cursor.getPredefinedCursor(cursorFor(edges(e.getPoint())))); }
                @Override public void mouseExited(MouseEvent e) { if (start == null) setCursor(Cursor.getDefaultCursor()); }
                @Override public void mousePressed(MouseEvent e) {
                    dir = edges(e.getPoint());
                    if (dir != 0) {
                        start = e.getLocationOnScreen();
                        startBounds = NFrame.this.getBounds();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) { start = null; dir = 0; }
                @Override public void mouseDragged(MouseEvent e) {
                    if (start == null || dir == 0) return;
                    Point p = e.getLocationOnScreen();
                    int dx = p.x - start.x, dy = p.y - start.y;
                    Rectangle r = new Rectangle(startBounds);
                    Dimension min = NFrame.this.getMinimumSize();
                    if ((dir & 1) != 0) { r.x += dx; r.width -= dx; }
                    if ((dir & 2) != 0) r.width += dx;
                    if ((dir & 4) != 0) { r.y += dy; r.height -= dy; }
                    if ((dir & 8) != 0) r.height += dy;
                    if (r.width < min.width) { if ((dir & 1) != 0) r.x -= min.width - r.width; r.width = min.width; }
                    if (r.height < min.height) { if ((dir & 4) != 0) r.y -= min.height - r.height; r.height = min.height; }
                    NFrame.this.setBounds(r);
                    NFrame.this.revalidate();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        /** Bitmask: 1 left, 2 right, 4 top, 8 bottom. */
        private int edges(Point p) {
            if (!custom || isMaximized()) return 0;
            int m = 0, w = getWidth(), h = getHeight();
            if (p.x < GRIP) m |= 1;
            if (p.x >= w - GRIP) m |= 2;
            if (p.y < GRIP) m |= 4;
            if (p.y >= h - GRIP) m |= 8;
            return m;
        }

        private int cursorFor(int m) {
            return switch (m) {
                case 1 -> Cursor.W_RESIZE_CURSOR;
                case 2 -> Cursor.E_RESIZE_CURSOR;
                case 4 -> Cursor.N_RESIZE_CURSOR;
                case 8 -> Cursor.S_RESIZE_CURSOR;
                case 5 -> Cursor.NW_RESIZE_CURSOR;
                case 6 -> Cursor.NE_RESIZE_CURSOR;
                case 9 -> Cursor.SW_RESIZE_CURSOR;
                case 10 -> Cursor.SE_RESIZE_CURSOR;
                default -> Cursor.DEFAULT_CURSOR;
            };
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(custom && !isMaximized() ? Theme.SIDEBAR : Theme.BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (custom && !isMaximized()) {
                g.setColor(Theme.BORDER_STRONG);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        }
    }

    /** Draggable title bar with window buttons. */
    static final class TitleBar extends JPanel {
        private final NFrame frame;
        private final WinButton max;
        final JPanel extras = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        private Point dragOffset;

        TitleBar(NFrame frame, String title) {
            super(new BorderLayout());
            this.frame = frame;
            setBackground(Theme.SIDEBAR);
            setPreferredSize(new Dimension(100, 38));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)) {
                @Override public Dimension getPreferredSize() { return new Dimension(super.getPreferredSize().width, 38); }
            };
            left.setOpaque(false);
            BufferedImage mark = Images.resource("/nekylauncher/img/icon-32.png");
            if (mark != null) left.add(new JLabel(new ImageIcon(Images.scale(mark, 18, 18, false))));
            JLabel t = new JLabel(title);
            t.setFont(Theme.font(12.5f, Theme.Weight.MEDIUM));
            t.setForeground(Theme.TEXT_2);
            left.add(t);
            left.setBorder(BorderFactory.createEmptyBorder(9, 4, 0, 0));
            add(left, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            extras.setOpaque(false);
            extras.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 8));
            right.add(extras);
            WinButton min = new WinButton(Icons.Name.MINUS, false, I18n.tr("window.minimize"));
            min.addActionListener(e -> frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED));
            max = new WinButton(Icons.Name.SQUARE, false, I18n.tr("window.maximize"));
            max.addActionListener(e -> frame.toggleMaximize());
            WinButton close = new WinButton(Icons.Name.X, true, I18n.tr("common.close"));
            close.addActionListener(e -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
            right.add(min);
            right.add(max);
            right.add(close);
            add(right, BorderLayout.EAST);

            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) dragOffset = e.getPoint();
                }
                @Override public void mouseReleased(MouseEvent e) { dragOffset = null; }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) frame.toggleMaximize();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragOffset == null) return;
                    Point screen = e.getLocationOnScreen();
                    if (frame.isMaximized()) {
                        // restore and keep the cursor at the same relative spot of the title bar
                        double rel = (double) dragOffset.x / Math.max(1, getWidth());
                        frame.setExtendedState(Frame.NORMAL);
                        dragOffset = new Point((int) (frame.getWidth() * rel), dragOffset.y);
                    }
                    frame.setLocation(screen.x - dragOffset.x, screen.y - dragOffset.y);
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
            left.addMouseListener(drag);
            left.addMouseMotionListener(drag);
        }

        void updateMaxIcon() {
            max.setIcon(frame.isMaximized() ? Icons.Name.RESTORE : Icons.Name.SQUARE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Theme.BORDER);
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
        }
    }

    static final class WinButton extends JButton {
        private Icons.Name icon;
        private final boolean danger;
        private boolean hover;

        WinButton(Icons.Name icon, boolean danger, String tip) {
            this.icon = icon;
            this.danger = danger;
            setToolTipText(tip);
            setPreferredSize(new Dimension(46, 37));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void setIcon(Icons.Name n) {
            icon = n;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            if (hover) {
                g2.setColor(danger ? new Color(0xE11D48) : Theme.SURFACE_3);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            int s = icon == Icons.Name.X ? 16 : 14;
            Icons.get(icon, s, hover && danger ? Color.WHITE : Theme.TEXT_2).paintIcon(this, g2, (getWidth() - s) / 2, (getHeight() - s) / 2);
            g2.dispose();
        }
    }
}
