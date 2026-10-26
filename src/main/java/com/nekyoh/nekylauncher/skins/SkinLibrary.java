package com.nekyoh.nekylauncher.skins;

import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Saved skin presets (PNG + model + cape) for one-click switching. Stored in &lt;data&gt;/skins. */
public final class SkinLibrary {
    public static final class Preset {
        public String id;
        public String name;
        public boolean slim;
        /** Cape to show with this skin; "" = no cape. */
        public String capeId = "";
        public long created;

        public Path file(Path dir) { return dir.resolve(id + ".png"); }
    }

    private final Path dir;
    private final List<Preset> presets = new ArrayList<>();

    public SkinLibrary(Path dir) {
        this.dir = dir;
        load();
    }

    public Path dir() { return dir; }

    private Path index() { return dir.resolve("presets.json"); }

    private void load() {
        presets.clear();
        if (!Files.exists(index())) return;
        try {
            for (JObject o : JArray.parse(FileUtil.read(index())).objects()) {
                Preset p = new Preset();
                p.id = o.str("id");
                p.name = o.str("name", "Skin");
                p.slim = o.bool("slim", false);
                p.capeId = o.str("capeId", "");
                p.created = o.lng("created", 0);
                if (p.id != null && Files.exists(p.file(dir))) presets.add(p);
            }
        } catch (Exception e) {
            Log.error("Could not read skin presets", e);
        }
    }

    private void save() throws IOException {
        JArray a = new JArray();
        for (Preset p : presets) {
            a.add(new JObject().put("id", p.id).put("name", p.name).put("slim", p.slim).put("capeId", p.capeId).put("created", p.created));
        }
        FileUtil.write(index(), a.toJson(true));
    }

    public synchronized List<Preset> all() { return new ArrayList<>(presets); }

    public synchronized Preset add(String name, byte[] png, boolean slim, String capeId) throws IOException {
        validate(png);
        Preset p = new Preset();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = name == null || name.isBlank() ? "Skin " + (presets.size() + 1) : name.trim();
        p.slim = slim;
        p.capeId = capeId == null ? "" : capeId;
        p.created = System.currentTimeMillis();
        Files.createDirectories(dir);
        Files.write(p.file(dir), png);
        presets.add(0, p);
        save();
        return p;
    }

    public synchronized void update(Preset p) throws IOException { save(); }

    public synchronized void remove(Preset p) throws IOException {
        presets.removeIf(x -> x.id.equals(p.id));
        Files.deleteIfExists(p.file(dir));
        save();
    }

    public byte[] read(Preset p) throws IOException { return Files.readAllBytes(p.file(dir)); }

    /** Minecraft accepts PNG skins of 64×64 or the legacy 64×32 layout (HD skins are rejected). */
    public static BufferedImage validate(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException(com.nekyoh.nekylauncher.i18n.I18n.tr("skins.error.notPng"));
        if (img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) {
            throw new IOException(com.nekyoh.nekylauncher.i18n.I18n.tr("skins.error.size", img.getWidth(), img.getHeight()));
        }
        return img;
    }

    /** Guess the arm model: slim skins leave the 4th arm column transparent. */
    public static boolean looksSlim(BufferedImage skin) {
        if (skin == null || skin.getHeight() < 64) return false;
        int px = skin.getRGB(54, 20);
        int px2 = skin.getRGB(54, 31);
        return ((px >>> 24) == 0) && ((px2 >>> 24) == 0);
    }

    public static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }
}
