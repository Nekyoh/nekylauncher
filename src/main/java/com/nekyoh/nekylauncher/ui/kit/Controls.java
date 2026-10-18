package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Toggle switch, checkbox, slider, progress bar, segmented control. */
public final class Controls {
    private Controls() {}

    // ------------------------------------------------------------------ toggle

    public static final class Toggle extends JComponent {
        private boolean on;
        private float pos;
        private final List<Consumer<Boolean>> listeners = new ArrayList<>();
        private final Timer anim;

        public Toggle(boolean on) {
            this.on = on;
            this.pos = on ? 1 : 0;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            anim = new Timer(15, e -> {
                float t = this.on ? 1 : 0;
                pos += (t - pos) * 0.35f;
                if (Math.abs(t - pos) < 0.02f) {
                    pos = t;
                    ((Timer) e.getSource()).stop();
                }
                repaint();
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (isEnabled()) setOn(!Toggle.this.on, true);
                }
            });
        }

        public boolean isOn() { return on; }

        public void setOn(boolean v, boolean notify) {
            if (on == v) return;
            on = v;
            anim.start();
            if (notify) listeners.forEach(l -> l.accept(v));
        }

        public Toggle onChange(Consumer<Boolean> c) {
            listeners.add(c);
            return this;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(40, 22); }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = 40, h = 22;
            g2.translate(0, (getHeight() - h) / 2);
            g2.setColor(Theme.mix(Theme.SURFACE_3, Theme.CYAN, pos));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            float d = h - 6;
            float x = 3 + pos * (w - 6 - d);
            g2.setColor(pos > 0.5 ? Theme.ON_CYAN : Theme.TEXT_2);
            g2.setColor(Theme.mix(Theme.TEXT_2, Color.WHITE, pos));
            g2.fill(new Ellipse2D.Float(x, 3, d, d));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ checkbox

    public static JCheckBox checkbox(String text, boolean selected) {
        JCheckBox c = new JCheckBox(text, selected);
        c.setOpaque(false);
        c.setFocusPainted(false);
        c.setForeground(Theme.TEXT);
        c.setFont(Theme.font(13.5f));
        c.setIconTextGap(10);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.setIcon(checkIcon(false));
        c.setSelectedIcon(checkIcon(true));
        c.setRolloverIcon(checkIcon(false));
        c.setRolloverSelectedIcon(checkIcon(true));
        return c;
    }

    private static Icon checkIcon(boolean on) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.hq(g.create());
                RoundRectangle2D r = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 17, 17, 9, 9);
                if (on) {
                    g2.setColor(Theme.CYAN);
                    g2.fill(r);
                    Icons.get(Icons.Name.CHECK, 14, Theme.ON_CYAN).paintIcon(c, g2, x + 2, y + 2);
                } else {
                    g2.setColor(Theme.SURFACE_2);
                    g2.fill(r);
                    g2.setColor(Theme.BORDER_STRONG);
                    g2.draw(r);
                }
                g2.dispose();
            }
            public int getIconWidth() { return 18; }
            public int getIconHeight() { return 18; }
        };
    }

    // ------------------------------------------------------------------ slider

    public static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, Math.max(min, Math.min(max, value)));
        s.setOpaque(false);
        s.setFocusable(false);
        s.setUI(new SliderUI(s));
        s.setPreferredSize(new Dimension(220, 26));
        return s;
    }

    private static final class SliderUI extends BasicSliderUI {
        SliderUI(JSlider s) { super(s); }

        @Override protected Dimension getThumbSize() { return new Dimension(16, 16); }

        @Override public void paintFocus(Graphics g) { }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            Rectangle t = trackRect;
            int cy = t.y + t.height / 2;
            g2.setColor(Theme.SURFACE_3);
            g2.fill(new RoundRectangle2D.Float(t.x, cy - 3, t.width, 6, 6, 6));
            int tx = thumbRect.x + thumbRect.width / 2;
            g2.setPaint(new GradientPaint(t.x, 0, Theme.CYAN_DARK, tx, 0, Theme.CYAN));
            g2.fill(new RoundRectangle2D.Float(t.x, cy - 3, Math.max(0, tx - t.x), 6, 6, 6));
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            Rectangle r = thumbRect;
            g2.setColor(Theme.alpha(Theme.CYAN, 0.25));
            g2.fill(new Ellipse2D.Float(r.x - 3, r.y + r.height / 2f - 11, 22, 22));
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Float(r.x, r.y + r.height / 2f - 8, 16, 16));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ progress

    public static final class Progress extends JComponent {
        private double value = -1;
        private float phase;
        private final Timer anim;

        public Progress() {
            anim = new Timer(16, e -> {
                phase = (phase + 0.012f) % 1f;
                repaint();
            });
            setPreferredSize(new Dimension(200, 6));
        }

        /** Negative = indeterminate. */
        public void setValue(double v) {
            value = v;
            if (v < 0 && isShowing()) anim.start();
            else anim.stop();
            repaint();
        }

        @Override public void addNotify() {
            super.addNotify();
            if (value < 0) anim.start();
        }

        @Override public void removeNotify() {
            anim.stop();
            super.removeNotify();
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.alpha(Color.WHITE, 0.08));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            g2.setClip(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            if (value < 0) {
                float seg = w * 0.32f;
                float x = -seg + phase * (w + seg);
                g2.setPaint(new GradientPaint(x, 0, Theme.alpha(Theme.CYAN, 0), x + seg / 2, 0, Theme.CYAN, true));
                g2.fill(new RoundRectangle2D.Float(x, 0, seg, h, h, h));
            } else {
                float fw = (float) (w * Math.min(1, value));
                g2.setPaint(new GradientPaint(0, 0, Theme.CYAN_DARK, Math.max(1, fw), 0, Theme.CYAN));
                g2.fill(new RoundRectangle2D.Float(0, 0, fw, h, h, h));
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ segmented

    /** Pill-shaped single-choice selector. */
    public static final class Segmented extends JComponent {
        private final List<String> labels = new ArrayList<>();
        private int selected;
        private int hover = -1;
        private final List<IntConsumer> listeners = new ArrayList<>();
        private int[] xs = new int[0];

        public Segmented(List<String> labels, int selected) {
            this.labels.addAll(labels);
            this.selected = selected;
            setFont(Theme.font(13, Theme.Weight.MEDIUM));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = indexAt(e.getX());
                    if (h != hover) { hover = h; repaint(); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    int i = indexAt(e.getX());
                    if (i >= 0 && isEnabled()) setSelected(i, true);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private int indexAt(int x) {
            for (int i = 0; i < xs.length - 1; i++) if (x >= xs[i] && x < xs[i + 1]) return i;
            return -1;
        }

        public int getSelected() { return selected; }

        public void setSelected(int i, boolean notify) {
            if (i == selected) return;
            selected = i;
            repaint();
            if (notify) listeners.forEach(l -> l.accept(i));
        }

        public Segmented onChange(IntConsumer c) {
            listeners.add(c);
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = 8;
            for (String l : labels) w += fm.stringWidth(l) + 28;
            return new Dimension(w, 36);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int h = getHeight();
            g2.setColor(Theme.SURFACE_2);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, 18, 18));
            g2.setColor(Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, h - 1, 18, 18));
            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            xs = new int[labels.size() + 1];
            int x = 4;
            for (int i = 0; i < labels.size(); i++) {
                int w = fm.stringWidth(labels.get(i)) + 28;
                xs[i] = x;
                if (i == selected) {
                    g2.setColor(Theme.SURFACE_3);
                    g2.fill(new RoundRectangle2D.Float(x, 4, w, h - 8, 14, 14));
                    g2.setColor(Theme.alpha(Theme.CYAN, 0.45));
                    g2.draw(new RoundRectangle2D.Float(x + 0.5f, 4.5f, w - 1, h - 9, 14, 14));
                } else if (i == hover) {
                    g2.setColor(Theme.alpha(Theme.SURFACE_3, 0.5));
                    g2.fill(new RoundRectangle2D.Float(x, 4, w, h - 8, 14, 14));
                }
                g2.setColor(i == selected ? Theme.TEXT : Theme.TEXT_2);
                g2.drawString(labels.get(i), x + 14, (h - fm.getHeight()) / 2 + fm.getAscent());
                x += w;
            }
            xs[labels.size()] = x;
            g2.dispose();
        }
    }
}
