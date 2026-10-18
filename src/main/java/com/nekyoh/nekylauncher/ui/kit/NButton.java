package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Fully custom-painted button with variants, sizes, icon and hover animation. */
public class NButton extends JButton {
    public enum Variant { PRIMARY, PLAY, SECONDARY, GHOST, DANGER, OUTLINE }

    public enum Size {
        SM(30, 12.5f, 12, 15), MD(36, 13, 14, 16), LG(46, 15, 20, 19), XL(58, 18, 30, 22);

        final int height;
        final float font;
        final int padX;
        final int icon;

        Size(int height, float font, int padX, int icon) {
            this.height = height;
            this.font = font;
            this.padX = padX;
            this.icon = icon;
        }
    }

    private Variant variant;
    private final Size size;
    private Icons.Name iconName;
    private float hover;
    private boolean pressed;
    private final Timer anim;
    private boolean hovering;
    private boolean iconOnly;

    public NButton(String text, Icons.Name icon, Variant variant, Size size) {
        super(text);
        this.variant = variant;
        this.size = size;
        this.iconName = icon;
        setFont(Theme.font(size.font, variant == Variant.PLAY ? Theme.Weight.BOLD : Theme.Weight.SEMIBOLD));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        anim = new Timer(15, e -> {
            float target = hovering && isEnabled() ? 1f : 0f;
            hover += (target - hover) * 0.35f;
            if (Math.abs(target - hover) < 0.02f) {
                hover = target;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovering = true; anim.start(); }
            @Override public void mouseExited(MouseEvent e) { hovering = false; pressed = false; anim.start(); }
            @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
            @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
        });
    }

    public NButton(String text, Variant variant) { this(text, null, variant, Size.MD); }

    public NButton(String text, Icons.Name icon, Variant variant) { this(text, icon, variant, Size.MD); }

    /** Square icon-only button with tooltip. */
    public static NButton icon(Icons.Name icon, String tooltip, Variant variant, Size size) {
        NButton b = new NButton("", icon, variant, size);
        b.iconOnly = true;
        b.setToolTipText(tooltip);
        return b;
    }

    public NButton onClick(Runnable r) {
        addActionListener(e -> r.run());
        return this;
    }

    public void setVariant(Variant v) {
        this.variant = v;
        repaint();
    }

    public void setIconName(Icons.Name n) {
        this.iconName = n;
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (iconOnly) return new Dimension(size.height, size.height);
        FontMetrics fm = getFontMetrics(getFont());
        String t = getText();
        int w = (t == null || t.isEmpty()) ? 0 : fm.stringWidth(t);
        if (iconName != null) w += size.icon + (w > 0 ? 8 : 0);
        return new Dimension(w + size.padX * 2, size.height);
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, size.height); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.hq(g.create());
        int w = getWidth(), h = getHeight();
        float arc = Math.min(h, (variant == Variant.PLAY ? 16 : 11) * 2f);
        if (!isEnabled()) g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
        RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
        Color fg;
        switch (variant) {
            case PRIMARY -> {
                Color a = Theme.mix(Theme.CYAN, Color.WHITE, hover * 0.15);
                Color b = Theme.mix(new Color(0x38BDF8), Color.WHITE, hover * 0.15);
                g2.setPaint(new GradientPaint(0, 0, a, w, h, b));
                g2.fill(shape);
                fg = Theme.ON_CYAN;
            }
            case PLAY -> {
                if (hover > 0) {
                    for (int i = 6; i >= 1; i--) {
                        g2.setColor(Theme.alpha(Theme.GREEN, 0.035 * hover * (7 - i)));
                        g2.fill(new RoundRectangle2D.Float(-i + 1, -i + 2, w + 2 * i - 2, h + 2 * i - 2, arc + i * 2, arc + i * 2));
                    }
                }
                Color top = Theme.mix(new Color(0x2FD866), Color.WHITE, hover * 0.1);
                Color bottom = Theme.mix(Theme.GREEN_DARK, new Color(0x16A34A), hover * 0.5);
                g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
                g2.fill(shape);
                g2.setColor(Theme.alpha(Color.WHITE, 0.18));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
                fg = Color.WHITE;
            }
            case SECONDARY -> {
                g2.setColor(Theme.mix(Theme.SURFACE_3, new Color(0x2E3545), hover));
                g2.fill(shape);
                g2.setColor(Theme.BORDER_STRONG);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
                fg = Theme.TEXT;
            }
            case GHOST -> {
                if (hover > 0) {
                    g2.setColor(Theme.alpha(Theme.SURFACE_3, hover));
                    g2.fill(shape);
                }
                fg = Theme.mix(Theme.TEXT_2, Theme.TEXT, hover);
            }
            case DANGER -> {
                g2.setColor(Theme.alpha(Theme.RED, 0.13 + hover * 0.12));
                g2.fill(shape);
                fg = Theme.mix(new Color(0xFB7185), Color.WHITE, hover * 0.3);
            }
            default -> { // OUTLINE
                if (hover > 0) {
                    g2.setColor(Theme.alpha(Theme.CYAN, 0.08 * hover));
                    g2.fill(shape);
                }
                g2.setColor(Theme.mix(Theme.BORDER_STRONG, Theme.CYAN, hover));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
                fg = Theme.TEXT;
            }
        }
        if (pressed) {
            g2.setColor(new Color(0, 0, 0, 40));
            g2.fill(shape);
        }

        // content
        String text = getText();
        FontMetrics fm = g2.getFontMetrics(getFont());
        int tw = text == null || text.isEmpty() ? 0 : fm.stringWidth(text);
        int iw = iconName != null ? size.icon : 0;
        int gap = (iw > 0 && tw > 0) ? 8 : 0;
        int total = iw + gap + tw;
        int x = (w - total) / 2;
        if (iconName != null) {
            Icons.get(iconName, size.icon, fg).paintIcon(this, g2, x, (h - size.icon) / 2);
        }
        if (tw > 0) {
            g2.setFont(getFont());
            g2.setColor(fg);
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(text, x + iw + gap, ty);
        }
        g2.dispose();
    }
}
