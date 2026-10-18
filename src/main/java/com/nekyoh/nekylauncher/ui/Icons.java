package com.nekyoh.nekylauncher.ui;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.*;
import java.util.function.Consumer;

/** Resolution-independent stroke icons drawn on a 24×24 grid (no image assets, crisp at any DPI). */
public final class Icons {
    private Icons() {}

    public enum Name {
        HOME, GRID, USER, SETTINGS, PLAY, PLUS, FOLDER, DOWNLOAD, EXPORT, TRASH, SEARCH, X, MINUS, SQUARE, RESTORE,
        CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_DOWN, CHECK, CUBE, IMAGE, SPARKLES, GLOBE, TERMINAL, COFFEE, MORE,
        COPY, EXTERNAL, REFRESH, STOP, INFO, WARNING, CLOCK, EDIT, MICROSOFT, KEY, CHIP, MONITOR, UPLOAD, STAR, PACKAGE
    }

    public static Icon get(Name n, int size, Color color) { return new VIcon(n, size, color); }

    public static final class VIcon implements Icon {
        private final Name name;
        private final int size;
        private Color color;

        VIcon(Name name, int size, Color color) {
            this.name = name;
            this.size = size;
            this.color = color;
        }

        public void setColor(Color c) { this.color = c; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Theme.hq(g.create());
            g2.translate(x, y);
            g2.scale(size / 24.0, size / 24.0);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g2, name);
            g2.dispose();
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    /** Paints icon {@code n} into a 24×24 space on {@code g} (stroke and color already set). */
    public static void draw(Graphics2D g, Name n) {
        switch (n) {
            case HOME -> {
                poly(g, false, 3, 11, 12, 3.5, 21, 11);
                poly(g, false, 5.5, 9.5, 5.5, 20, 18.5, 20, 18.5, 9.5);
                poly(g, false, 10, 20, 10, 14.5, 14, 14.5, 14, 20);
            }
            case GRID -> {
                rr(g, 3.5, 3.5, 7, 7, 2);
                rr(g, 13.5, 3.5, 7, 7, 2);
                rr(g, 3.5, 13.5, 7, 7, 2);
                rr(g, 13.5, 13.5, 7, 7, 2);
            }
            case USER -> {
                g.draw(new Ellipse2D.Double(8, 3.5, 8, 8));
                Path2D p = new Path2D.Double();
                p.moveTo(4.5, 20.5);
                p.curveTo(4.5, 16.5, 8, 14, 12, 14);
                p.curveTo(16, 14, 19.5, 16.5, 19.5, 20.5);
                g.draw(p);
            }
            case SETTINGS -> {
                g.draw(gear());
                g.draw(new Ellipse2D.Double(9, 9, 6, 6));
            }
            case PLAY -> {
                Path2D p = new Path2D.Double();
                p.moveTo(7.5, 4.8);
                p.lineTo(19, 12);
                p.lineTo(7.5, 19.2);
                p.closePath();
                g.fill(p);
                g.draw(p);
            }
            case PLUS -> {
                line(g, 12, 5, 12, 19);
                line(g, 5, 12, 19, 12);
            }
            case FOLDER -> poly(g, true, 3, 6, 9.5, 6, 11.5, 8.5, 21, 8.5, 21, 19, 3, 19);
            case DOWNLOAD -> {
                line(g, 12, 4, 12, 15);
                poly(g, false, 7, 10.5, 12, 15.5, 17, 10.5);
                line(g, 5, 20, 19, 20);
            }
            case UPLOAD -> {
                line(g, 12, 16, 12, 4.5);
                poly(g, false, 7, 9, 12, 4, 17, 9);
                line(g, 5, 20, 19, 20);
            }
            case EXPORT -> {
                line(g, 12, 14.5, 12, 3.5);
                poly(g, false, 7.5, 8, 12, 3.5, 16.5, 8);
                poly(g, false, 5, 12.5, 5, 20, 19, 20, 19, 12.5);
            }
            case PACKAGE -> {
                poly(g, true, 3.5, 7.5, 12, 3.5, 20.5, 7.5, 20.5, 16.5, 12, 20.5, 3.5, 16.5);
                poly(g, false, 3.5, 7.5, 12, 11.5, 20.5, 7.5);
                line(g, 12, 11.5, 12, 20.5);
                line(g, 7.8, 5.5, 16.2, 9.5);
            }
            case TRASH -> {
                line(g, 4, 7, 20, 7);
                poly(g, false, 6, 7, 7, 20, 17, 20, 18, 7);
                poly(g, false, 9, 7, 9, 4, 15, 4, 15, 7);
                line(g, 10, 11, 10, 16);
                line(g, 14, 11, 14, 16);
            }
            case SEARCH -> {
                g.draw(new Ellipse2D.Double(4.5, 4.5, 13, 13));
                line(g, 16, 16, 20.5, 20.5);
            }
            case X -> {
                line(g, 6, 6, 18, 18);
                line(g, 18, 6, 6, 18);
            }
            case MINUS -> line(g, 5, 12, 19, 12);
            case SQUARE -> rr(g, 5, 5, 14, 14, 2);
            case RESTORE -> {
                rr(g, 4, 8.5, 11.5, 11.5, 2);
                poly(g, false, 8.5, 8.5, 8.5, 4, 20, 4, 20, 15.5, 15.5, 15.5);
            }
            case CHEVRON_LEFT -> poly(g, false, 15, 5, 8, 12, 15, 19);
            case CHEVRON_RIGHT -> poly(g, false, 9, 5, 16, 12, 9, 19);
            case CHEVRON_DOWN -> poly(g, false, 6, 9, 12, 15, 18, 9);
            case CHECK -> poly(g, false, 5, 12.5, 10, 17.5, 19, 7);
            case CUBE -> {
                poly(g, true, 12, 3, 20, 7.5, 20, 16.5, 12, 21, 4, 16.5, 4, 7.5);
                poly(g, false, 4, 7.5, 12, 12, 20, 7.5);
                line(g, 12, 12, 12, 21);
            }
            case IMAGE -> {
                rr(g, 3, 4, 18, 16, 2.5);
                g.draw(new Ellipse2D.Double(7, 8, 3.5, 3.5));
                poly(g, false, 21, 15, 16, 10, 5, 20);
            }
            case SPARKLES -> {
                Path2D p = new Path2D.Double();
                p.moveTo(10.5, 4);
                p.quadTo(11.5, 11, 18, 12.5);
                p.quadTo(11.5, 14, 10.5, 21);
                p.quadTo(9.5, 14, 3, 12.5);
                p.quadTo(9.5, 11, 10.5, 4);
                p.closePath();
                g.draw(p);
                line(g, 19, 3, 19, 7);
                line(g, 17, 5, 21, 5);
            }
            case GLOBE -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new Ellipse2D.Double(8, 3, 8, 18));
                line(g, 3.5, 12, 20.5, 12);
            }
            case TERMINAL -> {
                rr(g, 3, 4, 18, 16, 2.5);
                poly(g, false, 7, 9, 10, 12, 7, 15);
                line(g, 12.5, 15, 16.5, 15);
            }
            case COFFEE -> {
                Path2D p = new Path2D.Double();
                p.moveTo(4.5, 9);
                p.lineTo(16, 9);
                p.lineTo(16, 15);
                p.quadTo(16, 19.5, 11.5, 19.5);
                p.lineTo(9, 19.5);
                p.quadTo(4.5, 19.5, 4.5, 15);
                p.closePath();
                g.draw(p);
                Path2D h = new Path2D.Double();
                h.moveTo(16, 10.5);
                h.curveTo(20.5, 10.5, 20.5, 15, 16, 15);
                g.draw(h);
                line(g, 8, 3, 8, 5.5);
                line(g, 11, 3, 11, 5.5);
                line(g, 14, 3, 14, 5.5);
            }
            case MORE -> {
                dot(g, 6, 12, 1.7);
                dot(g, 12, 12, 1.7);
                dot(g, 18, 12, 1.7);
            }
            case COPY -> {
                rr(g, 8.5, 8.5, 12, 12, 2);
                poly(g, false, 15.5, 8.5, 15.5, 3.5, 3.5, 3.5, 3.5, 15.5, 8.5, 15.5);
            }
            case EXTERNAL -> {
                poly(g, false, 14, 4, 20, 4, 20, 10);
                line(g, 20, 4, 11, 13);
                poly(g, false, 18, 14, 18, 20, 4, 20, 4, 6, 10, 6);
            }
            case REFRESH -> {
                g.draw(new Arc2D.Double(4.5, 4.5, 15, 15, 40, 290, Arc2D.OPEN));
                poly(g, false, 20, 4, 20, 9, 15, 9);
            }
            case STOP -> {
                RoundRectangle2D r = new RoundRectangle2D.Double(6, 6, 12, 12, 3, 3);
                g.fill(r);
                g.draw(r);
            }
            case INFO -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                line(g, 12, 11, 12, 16.5);
                dot(g, 12, 7.8, 1.2);
            }
            case WARNING -> {
                poly(g, true, 12, 3.5, 21.5, 20, 2.5, 20);
                line(g, 12, 9.5, 12, 14);
                dot(g, 12, 17, 1.2);
            }
            case CLOCK -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                poly(g, false, 12, 7, 12, 12, 15.5, 14);
            }
            case EDIT -> {
                poly(g, true, 4, 20, 4.5, 16, 15.5, 5, 19, 8.5, 8, 19.5);
                line(g, 13, 7.5, 16.5, 11);
            }
            case MICROSOFT -> {
                g.fill(new Rectangle2D.Double(3, 3, 8.5, 8.5));
                g.fill(new Rectangle2D.Double(12.5, 3, 8.5, 8.5));
                g.fill(new Rectangle2D.Double(3, 12.5, 8.5, 8.5));
                g.fill(new Rectangle2D.Double(12.5, 12.5, 8.5, 8.5));
            }
            case KEY -> {
                g.draw(new Ellipse2D.Double(3.5, 11, 9, 9));
                line(g, 11, 12.5, 20, 3.5);
                line(g, 16.5, 7, 19.5, 10);
                line(g, 14, 9.5, 16.5, 12);
            }
            case CHIP -> {
                rr(g, 6, 6, 12, 12, 2);
                rr(g, 9.5, 9.5, 5, 5, 1);
                for (double v : new double[]{9.5, 14.5}) {
                    line(g, v, 2.5, v, 6);
                    line(g, v, 18, v, 21.5);
                    line(g, 2.5, v, 6, v);
                    line(g, 18, v, 21.5, v);
                }
            }
            case MONITOR -> {
                rr(g, 3, 4, 18, 12, 2);
                line(g, 8, 20, 16, 20);
                line(g, 12, 16, 12, 20);
            }
            case STAR -> poly(g, true, 12, 3, 14.6, 8.9, 21, 9.5, 16.2, 13.8, 17.6, 20.2, 12, 16.9, 6.4, 20.2, 7.8, 13.8, 3, 9.5, 9.4, 8.9);
        }
    }

    private static Shape gear() {
        Path2D p = new Path2D.Double();
        int teeth = 8;
        double ro = 10, ri = 7.6;
        for (int i = 0; i < teeth; i++) {
            double a = Math.PI * 2 * i / teeth;
            double w1 = Math.toRadians(13), w2 = Math.toRadians(21);
            double[][] pts = {
                    {ri, a - w2}, {ro, a - w1}, {ro, a + w1}, {ri, a + w2}
            };
            for (int k = 0; k < pts.length; k++) {
                double x = 12 + pts[k][0] * Math.cos(pts[k][1]);
                double y = 12 + pts[k][0] * Math.sin(pts[k][1]);
                if (i == 0 && k == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            double next = Math.PI * 2 * (i + 1) / teeth - Math.toRadians(21);
            p.append(new Arc2D.Double(12 - ri, 12 - ri, ri * 2, ri * 2, -Math.toDegrees(a + Math.toRadians(21)),
                    -Math.toDegrees(next - (a + Math.toRadians(21))), Arc2D.OPEN), true);
        }
        p.closePath();
        return p;
    }

    static void line(Graphics2D g, double x1, double y1, double x2, double y2) { g.draw(new Line2D.Double(x1, y1, x2, y2)); }

    static void rr(Graphics2D g, double x, double y, double w, double h, double r) {
        g.draw(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }

    static void dot(Graphics2D g, double x, double y, double r) { g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2)); }

    static void poly(Graphics2D g, boolean closed, double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        if (closed) p.closePath();
        g.draw(p);
    }

    /** Convenience for one-off custom icons. */
    public static Icon custom(int size, Consumer<Graphics2D> painter) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.hq(g.create());
                g2.translate(x, y);
                g2.scale(size / 24.0, size / 24.0);
                painter.accept(g2);
                g2.dispose();
            }
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
        };
    }
}
