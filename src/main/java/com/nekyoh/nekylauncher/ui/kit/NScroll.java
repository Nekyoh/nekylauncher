package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** Scroll panes with slim rounded scrollbars. */
public final class NScroll {
    private NScroll() {}

    public static JScrollPane wrap(Component c, Color bg) {
        JScrollPane sp = new JScrollPane(c);
        style(sp, bg);
        return sp;
    }

    /** Vertical-only scrolling; the content always matches the viewport width. */
    public static JScrollPane vertical(Component content, Color bg) {
        TrackWidth tw = new TrackWidth(content);
        tw.setBackground(bg);
        JScrollPane sp = wrap(tw, bg);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    static final class TrackWidth extends JPanel implements Scrollable {
        TrackWidth(Component c) {
            super(new BorderLayout());
            setOpaque(false);
            add(c, BorderLayout.CENTER);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 22; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(40, r.height - 60); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport vp && vp.getHeight() > getPreferredSize().height;
        }
    }

    public static JScrollPane style(JScrollPane sp, Color bg) {
        sp.setBorder(new EmptyBorder(0, 0, 0, 0));
        sp.setViewportBorder(null);
        sp.getViewport().setBackground(bg);
        sp.setBackground(bg);
        for (JScrollBar bar : new JScrollBar[]{sp.getVerticalScrollBar(), sp.getHorizontalScrollBar()}) {
            if (bar == null) continue; // combo popups create no horizontal bar
            bar.setUI(new SlimUI());
            bar.setUnitIncrement(22);
            bar.setOpaque(false);
            bar.setPreferredSize(new Dimension(10, 10));
        }
        sp.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, null);
        return sp;
    }

    static final class SlimUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = Theme.SURFACE_3;
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override protected JButton createDecreaseButton(int o) { return zero(); }

        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = Theme.hq(g.create());
            boolean v = scrollbar.getOrientation() == JScrollBar.VERTICAL;
            int pad = 2;
            float x = r.x + pad, y = r.y + pad, w = r.width - pad * 2, h = r.height - pad * 2;
            if (v) { x += 1; w = Math.max(4, w - 2); } else { y += 1; h = Math.max(4, h - 2); }
            g2.setColor(isThumbRollover() || isDragging ? new Color(0x3A4252) : new Color(0x2B3140));
            float arc = Math.min(w, h);
            g2.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
            g2.dispose();
        }
    }
}
