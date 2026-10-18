package com.nekyoh.nekylauncher.instance;

import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lists instance content and reads friendly metadata (names, versions, icons) from mod jars and packs. */
public final class ContentScanner {
    private ContentScanner() {}

    private record CacheKey(Path path, long modified, long size) {}

    private static final Map<CacheKey, ContentItem> CACHE = new ConcurrentHashMap<>();

    public static Path folder(Instance i, ContentItem.Kind kind) {
        return switch (kind) {
            case MOD -> i.modsDir();
            case RESOURCEPACK -> i.resourcePacksDir();
            case SHADERPACK -> i.shaderPacksDir();
            case WORLD -> i.savesDir();
        };
    }

    /** File types accepted when dropping/adding files of this kind. */
    public static boolean accepts(ContentItem.Kind kind, Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean dir = Files.isDirectory(p);
        return switch (kind) {
            case MOD -> n.endsWith(".jar") || n.endsWith(".jar.disabled") || n.endsWith(".litemod");
            case RESOURCEPACK, SHADERPACK -> n.endsWith(".zip") || n.endsWith(".zip.disabled") || dir;
            case WORLD -> dir && Files.exists(p.resolve("level.dat")) || n.endsWith(".zip");
        };
    }

    public static List<ContentItem> scan(Instance inst, ContentItem.Kind kind) {
        Path dir = folder(inst, kind);
        List<ContentItem> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String fn = p.getFileName().toString();
                if (fn.startsWith(".")) continue;
                boolean isDir = Files.isDirectory(p);
                if (kind == ContentItem.Kind.MOD && (isDir || !(fn.endsWith(".jar") || fn.endsWith(".disabled") || fn.endsWith(".litemod")))) continue;
                if ((kind == ContentItem.Kind.RESOURCEPACK || kind == ContentItem.Kind.SHADERPACK)
                        && !isDir && !(fn.endsWith(".zip") || fn.endsWith(".disabled"))) continue;
                if (kind == ContentItem.Kind.WORLD && !isDir) continue;
                long size = isDir ? 0 : Files.size(p);
                long mod = Files.getLastModifiedTime(p).toMillis();
                CacheKey key = new CacheKey(p, mod, size);
                ContentItem item = CACHE.get(key);
                if (item == null) {
                    item = new ContentItem(kind, p, fn, !fn.endsWith(".disabled"), size, mod);
                    try {
                        switch (kind) {
                            case MOD -> readMod(item);
                            case RESOURCEPACK -> readPack(item);
                            case SHADERPACK -> { }
                            case WORLD -> readWorld(item);
                        }
                    } catch (Exception e) {
                        Log.warn("Metadata unreadable for " + fn + ": " + e.getMessage());
                    }
                    CACHE.put(key, item);
                }
                out.add(item);
            }
        } catch (IOException e) {
            Log.error("Could not scan " + dir, e);
        }
        out.sort(Comparator.comparing((ContentItem c) -> c.title.toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ actions

    public static Path setEnabled(ContentItem item, boolean enabled) throws IOException {
        String fn = item.fileName;
        String target;
        if (enabled && fn.endsWith(".disabled")) target = fn.substring(0, fn.length() - ".disabled".length());
        else if (!enabled && !fn.endsWith(".disabled")) target = fn + ".disabled";
        else return item.path;
        Path dst = item.path.resolveSibling(target);
        Files.move(item.path, dst, StandardCopyOption.REPLACE_EXISTING);
        return dst;
    }

    /** Copies files/folders into the right folder for {@code kind}; returns how many were added. */
    public static int addFiles(Instance inst, ContentItem.Kind kind, List<Path> files) throws IOException {
        Path dir = folder(inst, kind);
        Files.createDirectories(dir);
        int n = 0;
        for (Path f : files) {
            if (!accepts(kind, f)) continue;
            String name = f.getFileName().toString();
            if (kind == ContentItem.Kind.WORLD && name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                importWorldZip(f, dir);
                n++;
                continue;
            }
            Path dst = dir.resolve(name);
            if (f.toAbsolutePath().normalize().equals(dst.toAbsolutePath().normalize())) continue;
            if (Files.isDirectory(f)) dst = FileUtil.uniquePath(dir, name);
            FileUtil.copyRecursive(f, dst);
            n++;
        }
        return n;
    }

    private static void importWorldZip(Path zip, Path savesDir) throws IOException {
        // find the folder containing level.dat inside the zip
        String root = null;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().replace('\\', '/');
                if (n.endsWith("level.dat") && (root == null || n.length() < root.length() + 9)) root = n.substring(0, n.length() - "level.dat".length());
            }
        }
        if (root == null) throw new IOException("No level.dat in " + zip.getFileName());
        String base = root.isEmpty() ? ContentItem.displayFromFile(zip.getFileName().toString()) : Path.of(root).getFileName().toString();
        Path dest = FileUtil.uniquePath(savesDir, base);
        try (InputStream in = Files.newInputStream(zip)) {
            FileUtil.unzip(in, dest, root.isEmpty() ? null : root, null);
        }
    }

    // ------------------------------------------------------------------ metadata readers

    private static void readMod(ContentItem item) throws IOException {
        try (ZipFile zf = new ZipFile(item.path.toFile())) {
            String json = text(zf, "fabric.mod.json");
            if (json != null) {
                JObject j = JObject.parse(json);
                item.title = j.str("name", j.str("id", item.title));
                item.version = j.str("version", "");
                item.description = j.str("description", "");
                item.authors = people(j.arr("authors"));
                item.icon = image(zf, iconPath(j.get("icon")));
                return;
            }
            json = text(zf, "quilt.mod.json");
            if (json != null) {
                JObject ql = JObject.parse(json).objOrEmpty("quilt_loader");
                JObject meta = ql.objOrEmpty("metadata");
                item.title = meta.str("name", ql.str("id", item.title));
                item.version = ql.str("version", "");
                item.description = meta.str("description", "");
                JObject contrib = meta.obj("contributors");
                if (contrib != null) item.authors = String.join(", ", contrib.keys());
                item.icon = image(zf, iconPath(meta.get("icon")));
                return;
            }
            String toml = text(zf, "META-INF/neoforge.mods.toml");
            if (toml == null) toml = text(zf, "META-INF/mods.toml");
            if (toml != null) {
                Map<String, String> top = new HashMap<>();
                Map<String, String> mod = tomlFirstMod(toml, top);
                item.title = mod.getOrDefault("displayName", mod.getOrDefault("modId", item.title));
                String ver = mod.getOrDefault("version", "");
                if (ver.contains("${")) {
                    ZipEntry mf = zf.getEntry("META-INF/MANIFEST.MF");
                    if (mf != null) {
                        try (InputStream in = zf.getInputStream(mf)) {
                            String impl = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                            ver = impl != null ? impl : "";
                        }
                    } else ver = "";
                }
                item.version = ver;
                item.description = mod.getOrDefault("description", "").trim();
                item.authors = mod.getOrDefault("authors", top.getOrDefault("authors", ""));
                String logo = mod.getOrDefault("logoFile", top.get("logoFile"));
                if (logo != null) item.icon = image(zf, logo);
                return;
            }
            json = text(zf, "mcmod.info");
            if (json != null) {
                Object root = com.nekyoh.nekylauncher.util.Json.parse(json.trim());
                JObject first = null;
                if (root instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) first = JArray.parse(json.trim()).obj(0);
                else if (root instanceof Map) {
                    JArray list = JObject.parse(json.trim()).arr("modList");
                    if (list != null && list.size() > 0) first = list.obj(0);
                }
                if (first != null) {
                    item.title = first.str("name", item.title);
                    item.version = first.str("version", "");
                    item.description = first.str("description", "");
                    item.authors = String.join(", ", first.arrOrEmpty("authorList").strings());
                    String logo = first.str("logoFile");
                    if (logo != null && !logo.isBlank()) item.icon = image(zf, logo);
                }
            }
        }
    }

    private static void readPack(ContentItem item) throws IOException {
        if (Files.isDirectory(item.path)) {
            Path meta = item.path.resolve("pack.mcmeta");
            if (Files.exists(meta)) item.description = packDescription(Files.readString(meta, StandardCharsets.UTF_8));
            Path png = item.path.resolve("pack.png");
            if (Files.exists(png)) item.icon = ImageIO.read(png.toFile());
            return;
        }
        try (ZipFile zf = new ZipFile(item.path.toFile())) {
            String meta = text(zf, "pack.mcmeta");
            if (meta != null) item.description = packDescription(meta);
            item.icon = image(zf, "pack.png");
        }
    }

    private static void readWorld(ContentItem item) throws IOException {
        Path icon = item.path.resolve("icon.png");
        if (Files.exists(icon)) item.icon = ImageIO.read(icon.toFile());
        item.description = "";
    }

    static String packDescription(String mcmeta) {
        try {
            Object d = JObject.parse(mcmeta).objOrEmpty("pack").get("description");
            return stripFormatting(flatten(d));
        } catch (Exception e) {
            return "";
        }
    }

    private static String flatten(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        if (o instanceof JObject j) {
            StringBuilder sb = new StringBuilder(j.str("text", j.str("translate", "")));
            JArray extra = j.arr("extra");
            if (extra != null) for (Object e : extra) sb.append(flatten(e));
            return sb.toString();
        }
        if (o instanceof JArray a) {
            StringBuilder sb = new StringBuilder();
            for (Object e : a) sb.append(flatten(e));
            return sb.toString();
        }
        return o.toString();
    }

    public static String stripFormatting(String s) { return s.replaceAll("§.", ""); }

    private static String people(JArray arr) {
        if (arr == null) return "";
        List<String> names = new ArrayList<>();
        for (Object o : arr) {
            if (o instanceof String s) names.add(s);
            else if (o instanceof JObject j && j.str("name") != null) names.add(j.str("name"));
        }
        return String.join(", ", names);
    }

    private static String iconPath(Object icon) {
        if (icon instanceof String s) return s;
        if (icon instanceof JObject j) {
            String best = null;
            int bestSize = -1;
            for (String k : j.keys()) {
                int size;
                try { size = Integer.parseInt(k); } catch (NumberFormatException e) { size = 0; }
                if (size >= bestSize && size <= 256) { bestSize = size; best = j.str(k); }
            }
            return best;
        }
        return null;
    }

    private static String text(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BufferedImage image(ZipFile zf, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.startsWith("/") ? name.substring(1) : name;
        ZipEntry e = zf.getEntry(n);
        if (e == null || e.getSize() > 4_000_000) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        } catch (Exception ex) {
            return null;
        }
    }

    /** Tiny TOML reader for mods.toml: top-level keys and the first [[mods]] table (strings and ''' blocks). */
    static Map<String, String> tomlFirstMod(String toml, Map<String, String> topLevel) {
        Map<String, String> mod = new HashMap<>();
        String section = "";
        boolean inFirstMod = false, seenMod = false;
        String[] lines = toml.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                int hash = line.indexOf('#');
                section = (hash >= 0 ? line.substring(0, hash) : line).trim();
                if (section.equals("[[mods]]")) {
                    inFirstMod = !seenMod;
                    seenMod = true;
                } else inFirstMod = false;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            String value;
            if (val.startsWith("'''") || val.startsWith("\"\"\"")) {
                String q = val.substring(0, 3);
                StringBuilder sb = new StringBuilder(val.substring(3));
                while (sb.indexOf(q) < 0 && i + 1 < lines.length) sb.append('\n').append(lines[++i]);
                int end = sb.indexOf(q);
                value = end >= 0 ? sb.substring(0, end) : sb.toString();
            } else if (val.startsWith("\"")) {
                int end = val.indexOf('"', 1);
                value = end > 0 ? val.substring(1, end).replace("\\n", "\n") : val.substring(1);
            } else if (val.startsWith("'")) {
                int end = val.indexOf('\'', 1);
                value = end > 0 ? val.substring(1, end) : val.substring(1);
            } else {
                int hash = val.indexOf('#');
                value = hash >= 0 ? val.substring(0, hash).trim() : val;
            }
            if (section.isEmpty()) topLevel.put(key, value);
            else if (inFirstMod) mod.put(key, value);
        }
        return mod;
    }
}
