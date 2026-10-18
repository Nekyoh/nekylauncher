package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.util.Hashes;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.Log;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Image loading (resources, files, URLs with disk cache) and high-quality scaling. */
public final class Images {
    private Images() {}

    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
    private static Path diskCache;

    public static void init(Path cacheDir) { diskCache = cacheDir.resolve("images"); }

    public static BufferedImage resource(String path) {
        return CACHE.computeIfAbsent("res:" + path, k -> {
            try (InputStream in = Images.class.getResourceAsStream(path)) {
                return in == null ? null : ImageIO.read(in);
            } catch (Exception e) {
                Log.warn("Missing image " + path);
                return null;
            }
        });
    }

    /** Multi-step downscale for smooth results; nearest-neighbour when {@code pixelArt}. */
    public static BufferedImage scale(BufferedImage src, int w, int h, boolean pixelArt) {
        if (src == null) return null;
        if (pixelArt) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return out;
        }
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = draw(cur, cw, ch);
        }
        return (cw == w && ch == h) ? cur : draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Draws {@code img} into a rounded square, scaled to fit. Small images are treated as pixel art. */
    public static void drawRounded(Graphics2D g, BufferedImage img, int x, int y, int size, int radius) {
        if (img == null) return;
        boolean pixel = img.getWidth() <= 64;
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.hq(g2);
        if (pixel) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setClip(new RoundRectangle2D.Float(x, y, size, size, radius * 2, radius * 2));
        if (pixel) g2.drawImage(img, x, y, size, size, null);
        else g2.drawImage(scaledCached(img, size * 2), x, y, size, size, null);
        g2.dispose();
    }

    private static final Map<BufferedImage, Map<Integer, BufferedImage>> SCALED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Square downscale cached per source image (cheap repaints). */
    public static BufferedImage scaledCached(BufferedImage img, int size) {
        Map<Integer, BufferedImage> m = SCALED.computeIfAbsent(img, k -> new ConcurrentHashMap<>());
        return m.computeIfAbsent(size, s -> img.getWidth() <= s ? img : scale(img, s, s * img.getHeight() / Math.max(1, img.getWidth()), false));
    }

    /** Loads an image from a URL (memory + disk cache) and calls back on the UI thread. */
    public static void url(String url, Consumer<BufferedImage> onLoaded) {
        if (url == null || url.isBlank()) return;
        BufferedImage hit = CACHE.get(url);
        if (hit != null) {
            onLoaded.accept(hit);
            return;
        }
        Tasks.background(() -> {
            try {
                Path f = diskCache != null ? diskCache.resolve(Hashes.sha1(url.getBytes(StandardCharsets.UTF_8))) : null;
                byte[] data;
                if (f != null && Files.exists(f)) data = Files.readAllBytes(f);
                else {
                    data = Http.getBytes(url);
                    if (f != null) {
                        Files.createDirectories(f.getParent());
                        Files.write(f, data);
                    }
                }
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img == null) return; // e.g. WebP/SVG: unsupported, keep placeholder
                if (img.getWidth() > 256) img = scale(img, 128, 128 * img.getHeight() / img.getWidth(), false);
                CACHE.put(url, img);
                BufferedImage fin = img;
                Tasks.ui(() -> onLoaded.accept(fin));
            } catch (Exception e) {
                // icons are decorative: ignore failures
            }
        });
    }

    /** Image already loaded by {@link #url}, or null (no I/O). */
    public static BufferedImage cached(String url) { return url == null ? null : CACHE.get(url); }

    public static BufferedImage file(Path p) {
        if (p == null || !Files.exists(p)) return null;
        try {
            String key = "file:" + p + ":" + Files.getLastModifiedTime(p).toMillis();
            BufferedImage hit = CACHE.get(key);
            if (hit != null) return hit;
            BufferedImage img = ImageIO.read(p.toFile());
            if (img != null) CACHE.put(key, img);
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
