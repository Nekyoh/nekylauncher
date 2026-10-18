package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.kit.Card;
import com.nekyoh.nekylauncher.ui.kit.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/** Grid card for an instance: icon, name, version/loader, last played, hover play button, live progress. */
public final class InstanceCard extends Card {
    private final MainWindow window;
    private final Instance inst;
    private boolean playHover;

    public InstanceCard(MainWindow window, Instance inst) {
        super(null);
        this.window = window;
        this.inst = inst;
        onClick(null);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                boolean over = playBounds().contains(e.getPoint());
                if (over != playHover) {
                    playHover = over;
                    repaint();
                }
            }

            @Override public void mouseExited(MouseEvent e) {
                playHover = false;
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) InstanceMenu.show(window, inst, InstanceCard.this, e.getX(), e.getY());
            }

            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    InstanceMenu.show(window, inst, InstanceCard.this, e.getX(), e.getY());
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (playBounds().contains(e.getPoint())) {
                    if (window.launch.isPreparing(inst)) window.launch.cancel(inst);
                    else window.launch.play(inst);
                } else {
                    window.openInstance(inst);
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    private Rectangle playBounds() {
        int s = 40;
        return new Rectangle(getWidth() - s - 16, (getHeight() - s) / 2, s, s);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = Theme.hq(g.create());
        int w = getWidth(), h = getHeight();
        int icon = 56;
        Art.instanceIcon(g2, inst, 16, (h - icon) / 2, icon);

        boolean running = window.launch.isRunning(inst);
        LaunchController.State st = window.launch.state(inst);
        int tx = 16 + icon + 14;
        int maxText = w - tx - 70;
        g2.setFont(Theme.font(14.5f, Theme.Weight.SEMIBOLD));
        g2.setColor(Theme.TEXT);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(Ui.ellipsize(inst.name, fm, maxText), tx, h / 2 - 8);

        g2.setFont(Theme.font(12.5f, Theme.Weight.MEDIUM));
        fm = g2.getFontMetrics();
        String ver = inst.mcVersion;
        g2.setColor(Theme.TEXT_2);
        g2.drawString(ver, tx, h / 2 + 10);
        int vx = tx + fm.stringWidth(ver);
        if (inst.loader.isModded()) {
            g2.setColor(Theme.TEXT_3);
            g2.drawString("  ·  ", vx, h / 2 + 10);
            vx += fm.stringWidth("  ·  ");
            g2.setColor(inst.loader.color);
            g2.drawString(inst.loader.display, vx, h / 2 + 10);
        }

        g2.setFont(Theme.font(11.5f));
        fm = g2.getFontMetrics();
        String sub;
        Color subColor = Theme.TEXT_3;
        if (st != null) {
            sub = st.stage;
            subColor = Theme.CYAN;
        } else if (running) {
            sub = "● " + I18n.tr("instance.running");
            subColor = Theme.GREEN;
        } else {
            sub = UiText.lastPlayed(inst.lastPlayed);
        }
        g2.setColor(subColor);
        g2.drawString(Ui.ellipsize(sub, fm, maxText), tx, h / 2 + 27);

        if (st != null) {
            // progress bar along the bottom edge
            int bw = w - 32;
            g2.setColor(Theme.alpha(Color.WHITE, 0.07));
            g2.fill(new RoundRectangle2D.Float(16, h - 9, bw, 3, 3, 3));
            g2.setColor(Theme.CYAN);
            if (st.fraction >= 0) g2.fill(new RoundRectangle2D.Float(16, h - 9, (float) (bw * st.fraction), 3, 3, 3));
            else {
                float ph = (System.currentTimeMillis() % 1400) / 1400f;
                g2.fill(new RoundRectangle2D.Float(16 + ph * bw * 0.7f, h - 9, bw * 0.3f, 3, 3, 3));
                repaint(60);
            }
        }

        // play / cancel / running button
        Rectangle pb = playBounds();
        boolean show = isHovered() || playHover || st != null || running;
        if (show) {
            Color c = st != null ? Theme.SURFACE_3 : running ? Theme.alpha(Theme.GREEN, 0.18) : playHover ? new Color(0x2FD866) : Theme.GREEN;
            g2.setColor(c);
            g2.fill(new Ellipse2D.Float(pb.x, pb.y, pb.width, pb.height));
            Icons.Name n = st != null ? Icons.Name.X : running ? Icons.Name.TERMINAL : Icons.Name.PLAY;
            Color ic = st != null ? Theme.TEXT : running ? Theme.GREEN : Color.WHITE;
            int is = n == Icons.Name.PLAY ? 18 : 16;
            Icons.get(n, is, ic).paintIcon(this, g2, pb.x + (pb.width - is) / 2 + (n == Icons.Name.PLAY ? 1 : 0), pb.y + (pb.height - is) / 2);
        }
        g2.dispose();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (playBounds().contains(e.getPoint())) {
            if (window.launch.isPreparing(inst)) return I18n.tr("common.cancel");
            if (window.launch.isRunning(inst)) return I18n.tr("launch.console");
            return I18n.tr("instance.play");
        }
        return null;
    }
}
