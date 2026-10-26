package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.ui.kit.Images;
import com.nekyoh.nekylauncher.util.Tasks;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Procedural artwork: instance block icons and player heads (no bundled Mojang assets). */
public final class Art {
    private Art() {}

    // ------------------------------------------------------------------ instance icons

    public static void instanceIcon(Graphics2D g0, Instance inst, int x, int y, int size) {
        BufferedImage custom = inst.iconFile() != null ? Images.file(inst.iconFile()) : null;
        if (custom != null) {
            Images.drawRounded(g0, custom, x, y, size, size / 4);
            return;
        }
        BufferedImage logo = loaderLogo(inst.loader);
        if (logo != null) {
            Graphics2D g = Theme.hq(g0.create());
            g.setColor(Theme.SURFACE_2);
            g.fill(new RoundRectangle2D.Float(x, y, size, size, size / 2f, size / 2f));
            g.dispose();
            Images.drawRounded(g0, logo, x, y, size, size / 4);
            return;
        }
        blockTile(g0, inst.loader, inst.name, x, y, size);
    }

    private static final java.util.Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

    /**
     * Default instance icon for modded instances: the loader project's own logo (downloaded once,
     * then cached). Returns null until it is available, so the procedural tile is shown meanwhile/offline.
     */
    public static BufferedImage loaderLogo(LoaderType loader) {
        String url = loader.iconUrl();
        if (url == null) return null;
        BufferedImage img = Images.cached(url);
        if (img == null && REQUESTED.add(url)) {
            Images.url(url, loaded -> {
                for (java.awt.Window w : java.awt.Window.getWindows()) if (w.isShowing()) w.repaint();
            });
        }
        return img;
    }

    /** Rounded tile with an isometric pixel block tinted by mod loader. */
    public static void blockTile(Graphics2D g0, LoaderType loader, String seed, int x, int y, int size) {
        Graphics2D g = Theme.hq(g0.create());
        g.translate(x, y);
        Color base = loader.color;
        g.setPaint(new GradientPaint(0, 0, Theme.mix(base, Theme.SURFACE_2, 0.78), size, size, Theme.mix(base, Theme.BG, 0.88)));
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, size / 2f, size / 2f));
        g.setColor(Theme.alpha(base, 0.28));
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, size - 1, size - 1, size / 2f, size / 2f));

        double s = size * 0.56;
        double cx = size / 2.0, top = size * 0.2;
        double hw = s / 2, qh = s / 4;
        Color topC, leftC, rightC;
        if (loader == LoaderType.VANILLA) {
            topC = new Color(0x5BBF3A);
            leftC = new Color(0x8A5A35);
            rightC = new Color(0x6E4628);
        } else {
            topC = Theme.mix(base, Color.WHITE, 0.12);
            leftC = Theme.mix(base, Color.BLACK, 0.28);
            rightC = Theme.mix(base, Color.BLACK, 0.45);
        }
        Path2D topFace = poly(cx, top, cx + hw, top + qh, cx, top + 2 * qh, cx - hw, top + qh);
        Path2D left = poly(cx - hw, top + qh, cx, top + 2 * qh, cx, top + 2 * qh + hw, cx - hw, top + qh + hw);
        Path2D right = poly(cx + hw, top + qh, cx, top + 2 * qh, cx, top + 2 * qh + hw, cx + hw, top + qh + hw);
        g.setColor(topC);
        g.fill(topFace);
        g.setColor(leftC);
        g.fill(left);
        g.setColor(rightC);
        g.fill(right);
        // pixel texture
        Random r = new Random(seed == null ? 7 : seed.hashCode());
        Shape oldClip = g.getClip();
        for (Path2D face : new Path2D[]{topFace, left, right}) {
            g.setClip(face);
            Rectangle b = face.getBounds();
            double px = Math.max(2, s / 8);
            for (double yy = b.y; yy < b.y + b.height; yy += px) {
                for (double xx = b.x; xx < b.x + b.width; xx += px) {
                    int v = r.nextInt(10);
                    if (v < 3) {
                        g.setColor(v == 0 ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 30));
                        g.fill(new Rectangle.Double(xx, yy, px, px));
                    }
                }
            }
        }
        if (loader == LoaderType.VANILLA) {
            // grass overhang on the sides
            g.setClip(new Rectangle(0, 0, size, size));
            g.setColor(new Color(0x4CA62E));
            g.fill(poly(cx - hw, top + qh, cx, top + 2 * qh, cx, top + 2 * qh + s * 0.16, cx - hw, top + qh + s * 0.16));
            g.setColor(new Color(0x3F8F26));
            g.fill(poly(cx + hw, top + qh, cx, top + 2 * qh, cx, top + 2 * qh + s * 0.16, cx + hw, top + qh + s * 0.16));
        }
        g.setClip(oldClip);
        g.setColor(new Color(255, 255, 255, 35));
        g.setStroke(new BasicStroke(Math.max(1f, size / 64f)));
        g.draw(topFace);
        g.dispose();
    }

    private static Path2D poly(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    // ------------------------------------------------------------------ player heads

    private static final Map<String, BufferedImage> HEADS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();

    /** Paints the account's head (skin face + hat layer); falls back to a generated pixel face. */
    public static void avatar(Graphics2D g0, Account a, int x, int y, int size, Component repaintTarget) {
        BufferedImage head = a == null ? null : HEADS.get(a.uuid);
        if (head == null && a != null && a.skinUrl != null && !a.skinUrl.isBlank() && LOADING.putIfAbsent(a.uuid, true) == null) {
            Images.url(a.skinUrl, skin -> {
                BufferedImage h = headFromSkin(skin);
                if (h != null) {
                    HEADS.put(a.uuid, h);
                    if (repaintTarget != null) Tasks.ui(repaintTarget::repaint);
                }
            });
        }
        Graphics2D g = Theme.hq(g0.create());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        RoundRectangle2D clip = new RoundRectangle2D.Float(x, y, size, size, size / 3f, size / 3f);
        g.setClip(clip);
        if (head == null) head = generatedFace(a == null ? "?" : a.uuid);
        g.drawImage(head, x, y, size, size, null);
        g.setClip(null);
        g.setColor(new Color(255, 255, 255, 30));
        g.draw(clip);
        g.dispose();
    }

    /** Forget the cached head so the next paint downloads the account's new skin. */
    public static void invalidate(Account a) {
        if (a == null) return;
        HEADS.remove(a.uuid);
        LOADING.remove(a.uuid);
    }

    // ------------------------------------------------------------------ full skin preview

    /**
     * Flat front or back view of a skin, 16×32 skin pixels scaled by {@code px}. Handles the classic
     * (4px) and slim (3px) arm models, the legacy 64×32 layout (left limbs mirror the right ones),
     * the second layer and, on the back view, the cape.
     */
    public static BufferedImage renderSkin(BufferedImage skin, boolean slim, BufferedImage cape, boolean back, int px) {
        BufferedImage out = new BufferedImage(16 * px, 32 * px, BufferedImage.TYPE_INT_ARGB);
        if (skin == null || skin.getWidth() < 64) return out;
        int s = skin.getWidth() / 64;
        boolean legacy = skin.getHeight() * 2 == skin.getWidth();
        int aw = slim ? 3 : 4;
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        Part p = new Part(g, skin, s, px);

        if (!back) {
            p.face(8, 8, 8, 8, 4, 0, false);          // head
            p.face(20, 20, 8, 12, 4, 8, false);       // body
            p.face(44, 20, aw, 12, 4 - aw, 8, false); // right arm (viewer's left)
            p.face(4, 20, 4, 12, 4, 20, false);       // right leg
            if (legacy) {
                p.face(44, 20, aw, 12, 12, 8, true);
                p.face(4, 20, 4, 12, 8, 20, true);
            } else {
                p.face(36, 52, aw, 12, 12, 8, false);
                p.face(20, 52, 4, 12, 8, 20, false);
                p.face(20, 36, 8, 12, 4, 8, false);
                p.face(44, 36, aw, 12, 4 - aw, 8, false);
                p.face(52, 52, aw, 12, 12, 8, false);
                p.face(4, 36, 4, 12, 4, 20, false);
                p.face(4, 52, 4, 12, 8, 20, false);
            }
            p.face(40, 8, 8, 8, 4, 0, false);         // hat
        } else {
            int rb = 44 + aw + 4;                     // right arm back u
            int lb = 36 + aw + 4;                     // left arm back u
            p.face(24, 8, 8, 8, 4, 0, false);
            p.face(32, 20, 8, 12, 4, 8, false);
            p.face(rb, 20, aw, 12, 12, 8, false);     // right arm now on viewer's right
            p.face(12, 20, 4, 12, 8, 20, false);      // right leg
            if (legacy) {
                p.face(rb, 20, aw, 12, 4 - aw, 8, true);
                p.face(12, 20, 4, 12, 4, 20, true);
            } else {
                p.face(lb, 52, aw, 12, 4 - aw, 8, false);
                p.face(28, 52, 4, 12, 4, 20, false);
                p.face(32, 36, 8, 12, 4, 8, false);
                p.face(rb, 36, aw, 12, 12, 8, false);
                p.face(lb + 16, 52, aw, 12, 4 - aw, 8, false);
                p.face(12, 36, 4, 12, 8, 20, false);
                p.face(12, 52, 4, 12, 4, 20, false);
            }
            p.face(56, 8, 8, 8, 4, 0, false);
            if (cape != null && cape.getWidth() >= 22) {
                int cs = Math.max(1, cape.getWidth() / 64);
                g.drawImage(cape, 3 * px, 8 * px, 13 * px, 24 * px, cs, cs, 11 * cs, 17 * cs, null);
            }
        }
        g.dispose();
        return out;
    }

    private record Part(Graphics2D g, BufferedImage skin, int s, int px) {
        /** Copies the w×h skin region at (u,v) to (x,y) on the 16×32 canvas, optionally mirrored. */
        void face(int u, int v, int w, int h, int x, int y, boolean mirror) {
            int sx1 = u * s, sx2 = (u + w) * s;
            if (mirror) { int t = sx1; sx1 = sx2; sx2 = t; }
            g.drawImage(skin, x * px, y * px, (x + w) * px, (y + h) * px, sx1, v * s, sx2, (v + h) * s, null);
        }
    }

    static BufferedImage headFromSkin(BufferedImage skin) {
        if (skin == null || skin.getWidth() < 64) return null;
        int scale = skin.getWidth() / 64;
        BufferedImage h = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = h.createGraphics();
        g.drawImage(skin, 0, 0, 8, 8, 8 * scale, 8 * scale, 16 * scale, 16 * scale, null);
        g.drawImage(skin, 0, 0, 8, 8, 40 * scale, 8 * scale, 48 * scale, 16 * scale, null);
        g.dispose();
        return h;
    }

    /** Deterministic symmetric 8×8 pixel face in the brand palette (offline accounts). */
    public static BufferedImage generatedFace(String seed) {
        return HEADS.computeIfAbsent("gen:" + seed, k -> {
            Random r = new Random(seed.hashCode() * 31L + 17);
            Color[] palette = {Theme.CYAN, Theme.RED, Theme.GREEN, Theme.AMBER, new Color(0xA78BFA), new Color(0x60A5FA)};
            Color main = palette[r.nextInt(palette.length)];
            Color skin = Theme.mix(main, new Color(0x1B202B), 0.55);
            Color dark = Theme.mix(main, Color.BLACK, 0.55);
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int yy = 0; yy < 8; yy++) {
                for (int xx = 0; xx < 4; xx++) {
                    Color c;
                    if (yy < 2) c = r.nextInt(4) == 0 ? skin : dark; // hair
                    else c = r.nextInt(6) == 0 ? Theme.mix(skin, main, 0.3) : skin;
                    img.setRGB(xx, yy, c.getRGB());
                    img.setRGB(7 - xx, yy, c.getRGB());
                }
            }
            // eyes + mouth
            img.setRGB(1, 4, Color.WHITE.getRGB());
            img.setRGB(2, 4, main.getRGB());
            img.setRGB(6, 4, Color.WHITE.getRGB());
            img.setRGB(5, 4, main.getRGB());
            img.setRGB(3, 6, dark.getRGB());
            img.setRGB(4, 6, dark.getRGB());
            return img;
        });
    }
}
