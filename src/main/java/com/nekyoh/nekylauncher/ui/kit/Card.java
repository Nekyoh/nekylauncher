package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Rounded surface panel; optionally interactive (hover highlight + click). */
public class Card extends JPanel {
    private Color bg = Theme.SURFACE;
    private Color hoverBg = Theme.SURFACE_HOVER;
    private Color border = Theme.BORDER;
    private int radius = Theme.RADIUS;
    private boolean hover;
    private boolean interactive;
    private boolean selected;

    public Card() { this(new BorderLayout()); }

    public Card(LayoutManager lm) {
        super(lm);
        setOpaque(false);
    }

    public Card colors(Color bg, Color border) {
        this.bg = bg;
        this.border = border;
        repaint();
        return this;
    }

    public Card radius(int r) {
        this.radius = r;
        return this;
    }

    public Card pad(int t, int l, int b, int r) {
        setBorder(BorderFactory.createEmptyBorder(t, l, b, r));
        return this;
    }

    public Card setSelected(boolean s) {
        selected = s;
        repaint();
        return this;
    }

    public boolean isHovered() { return hover; }

    /** Makes the card react to hover and clicks (on itself and children). */
    public Card onClick(Runnable r) {
        interactive = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { setHover(true); }
            @Override public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this);
                if (!contains(p)) setHover(false);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && r != null) r.run();
            }
        };
        addMouseListener(ma);
        return this;
    }

    /** Propagates hover/click listeners to a child that would otherwise swallow events. */
    public void bindChild(Component c) {
        for (var l : getMouseListeners()) c.addMouseListener(l);
    }

    protected void setHover(boolean h) {
        if (hover != h) {
            hover = h;
            repaint();
            onHoverChanged(h);
        }
    }

    protected void onHoverChanged(boolean h) { }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.hq(g.create());
        RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, radius * 2, radius * 2);
        g2.setColor(interactive && hover ? hoverBg : bg);
        g2.fill(r);
        if (selected) {
            g2.setColor(Theme.alpha(Theme.CYAN, 0.75));
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(r);
        } else if (border != null) {
            g2.setColor(interactive && hover ? Theme.BORDER_STRONG : border);
            g2.draw(r);
        }
        g2.dispose();
    }
}
