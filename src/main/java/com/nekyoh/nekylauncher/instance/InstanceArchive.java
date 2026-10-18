package com.nekyoh.nekylauncher.instance;

import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Instance export/import as .zip.
 * Import understands: NekyLauncher zips, Modrinth .mrpack, Prism/MultiMC exports and CurseForge modpack zips.
 */
public final class InstanceArchive {
    private InstanceArchive() {}

    public static final String MANIFEST = "nekylauncher-instance.json";
    /** Folders that are machine-specific or regenerated; unchecked by default on export. */
    public static final Set<String> DEFAULT_EXCLUDED = Set.of("logs", "crash-reports", ".cache", ".fabric", ".quilt",
            "debug", "downloads", "natives", ".mixin.out", "command_history.txt", "usercache.json", "usernamecache.json");

    public record Entry(String name, boolean directory, long size) {}

    public record ImportResult(Instance instance, List<String> warnings) {}

    // ------------------------------------------------------------------ export

    public static List<Entry> exportables(Instance inst) {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(inst.gameDir())) {
            for (Path p : s.sorted().toList()) {
                boolean dir = Files.isDirectory(p);
                out.add(new Entry(p.getFileName().toString(), dir, dir ? FileUtil.sizeOf(p) : Files.size(p)));
            }
        } catch (IOException e) {
            Log.warn("Could not list " + inst.gameDir() + ": " + e.getMessage());
        }
        return out;
    }

    public static void export(Instance inst, Path zipFile, Set<String> include, Progress progress) throws IOException {
        Files.createDirectories(zipFile.toAbsolutePath().getParent());
        Path tmp = zipFile.resolveSibling(zipFile.getFileName() + ".part");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zos = new ZipOutputStream(os)) {
            JObject meta = inst.toJson().copy();
            meta.remove("javaPath").remove("lastPlayed").remove("playTimeSeconds");
            meta.put("exportedBy", AppInfo.NAME + " " + AppInfo.VERSION);
            zos.putNextEntry(new ZipEntry(MANIFEST));
            zos.write(meta.toJson(true).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            Path icon = inst.iconFile();
            if (icon != null && Files.exists(icon)) FileUtil.zipAdd(zos, icon, icon.getFileName().toString(), p -> true);
            int i = 0;
            List<String> names = new ArrayList<>(include);
            Collections.sort(names);
            for (String name : names) {
                progress.stage(I18n.tr("progress.exporting", name));
                progress.fraction((double) i++ / Math.max(1, names.size()));
                Path src = FileUtil.safeResolve(inst.gameDir(), name);
                if (!Files.exists(src)) continue;
                FileUtil.zipAdd(zos, src, "minecraft/" + name, p -> !p.getFileName().toString().equals("session.lock"));
            }
        }
        Files.move(tmp, zipFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        progress.fraction(1);
    }

    // ------------------------------------------------------------------ import

    public static ImportResult importArchive(Path zip, InstanceManager manager, Progress progress, CancelToken cancel) throws IOException {
        progress.stage(I18n.tr("progress.importing"));
        progress.fraction(-1);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (zf.getEntry(MANIFEST) != null) return importNeky(zf, manager, progress);
            if (zf.getEntry("modrinth.index.json") != null) return importMrpack(zf, manager, progress, cancel);
            String prismRoot = findPrismRoot(zf);
            if (prismRoot != null) return importPrism(zf, prismRoot, manager, progress);
            ZipEntry cf = zf.getEntry("manifest.json");
            if (cf != null) {
                JObject m = JObject.parse(read(zf, cf));
                if (m.has("minecraft")) return importCurseForge(zf, m, manager, progress, cancel);
            }
        }
        throw new IOException(I18n.tr("import.unknownFormat"));
    }

    private static ImportResult importNeky(ZipFile zf, InstanceManager manager, Progress progress) throws IOException {
        JObject meta = JObject.parse(read(zf, zf.getEntry(MANIFEST)));
        String name = meta.str("name", "Imported");
        Path dir = manager.newInstanceDir(name);
        Files.createDirectories(dir);
        try {
            extractPrefix(zf, "minecraft/", dir.resolve("minecraft"), progress);
            Instance inst = new Instance(dir);
            inst.fromJson(meta);
            inst.name = name;
            inst.created = System.currentTimeMillis();
            inst.lastPlayed = 0;
            inst.javaPath = "";
            String icon = meta.str("icon", "");
            if (!icon.isBlank() && zf.getEntry(icon) != null) {
                try (InputStream in = zf.getInputStream(zf.getEntry(icon))) {
                    Files.copy(in, FileUtil.safeResolve(dir, icon), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            manager.register(inst);
            return new ImportResult(inst, List.of());
        } catch (IOException | RuntimeException e) {
            FileUtil.deleteRecursive(dir);
            throw e;
        }
    }

    private static ImportResult importMrpack(ZipFile zf, InstanceManager manager, Progress progress, CancelToken cancel) throws IOException {
        JObject index = JObject.parse(read(zf, zf.getEntry("modrinth.index.json")));
        JObject deps = index.objOrEmpty("dependencies");
        String mc = deps.str("minecraft");
        if (mc == null) throw new IOException("Modpack does not declare a Minecraft version");
        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = "";
        if (deps.has("fabric-loader")) { loader = LoaderType.FABRIC; loaderVersion = deps.str("fabric-loader"); }
        else if (deps.has("quilt-loader")) { loader = LoaderType.QUILT; loaderVersion = deps.str("quilt-loader"); }
        else if (deps.has("neoforge")) { loader = LoaderType.NEOFORGE; loaderVersion = deps.str("neoforge"); }
        else if (deps.has("forge")) { loader = LoaderType.FORGE; loaderVersion = deps.str("forge"); }
        String name = index.str("name", "Modpack");
        Path dir = manager.newInstanceDir(name);
        Files.createDirectories(dir);
        List<String> warnings = new ArrayList<>();
        try {
            Path game = dir.resolve("minecraft");
            List<JObject> files = index.arrOrEmpty("files").objects();
            int i = 0;
            for (JObject f : files) {
                if (cancel != null) cancel.check();
                i++;
                if ("unsupported".equals(f.objOrEmpty("env").str("client"))) continue;
                String path = f.str("path");
                if (path == null) continue;
                Path target = FileUtil.safeResolve(game, path);
                String sha1 = f.objOrEmpty("hashes").str("sha1");
                progress.stage(I18n.tr("progress.downloadingFile", target.getFileName(), i, files.size()));
                progress.fraction((double) i / files.size());
                IOException last = null;
                boolean ok = false;
                for (String url : f.arrOrEmpty("downloads").strings()) {
                    try {
                        Http.download(url, target, sha1, null, cancel);
                        ok = true;
                        break;
                    } catch (CancelToken.CancelledException ce) {
                        throw ce;
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (!ok) warnings.add(path + (last != null ? " (" + last.getMessage() + ")" : ""));
            }
            extractPrefix(zf, "overrides/", game, progress);
            extractPrefix(zf, "client-overrides/", game, progress);
            Instance inst = new Instance(dir);
            inst.name = name;
            inst.mcVersion = mc;
            inst.loader = loader;
            inst.loaderVersion = loaderVersion;
            inst.versionId = loader == LoaderType.VANILLA ? mc : "";
            manager.register(inst);
            return new ImportResult(inst, warnings);
        } catch (IOException | RuntimeException e) {
            FileUtil.deleteRecursive(dir);
            throw e;
        }
    }

    private static String findPrismRoot(ZipFile zf) {
        var en = zf.entries();
        String best = null;
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName().replace('\\', '/');
            if (n.endsWith("mmc-pack.json")) {
                String root = n.substring(0, n.length() - "mmc-pack.json".length());
                if (best == null || root.length() < best.length()) best = root;
            }
        }
        return best;
    }

    private static ImportResult importPrism(ZipFile zf, String root, InstanceManager manager, Progress progress) throws IOException {
        JObject pack = JObject.parse(read(zf, zf.getEntry(root + "mmc-pack.json")));
        String mc = null, loaderVersion = "";
        LoaderType loader = LoaderType.VANILLA;
        for (JObject c : pack.arrOrEmpty("components").objects()) {
            String uid = c.str("uid", "");
            String v = c.str("version", c.str("cachedVersion", ""));
            switch (uid) {
                case "net.minecraft" -> mc = v;
                case "net.fabricmc.fabric-loader" -> { loader = LoaderType.FABRIC; loaderVersion = v; }
                case "org.quiltmc.quilt-loader" -> { loader = LoaderType.QUILT; loaderVersion = v; }
                case "net.minecraftforge" -> { loader = LoaderType.FORGE; loaderVersion = v; }
                case "net.neoforged" -> { loader = LoaderType.NEOFORGE; loaderVersion = v; }
                default -> { }
            }
        }
        if (mc == null) throw new IOException("Prism instance without Minecraft component");
        String name = "Prism instance";
        ZipEntry cfg = zf.getEntry(root + "instance.cfg");
        if (cfg != null) {
            for (String line : read(zf, cfg).split("\\R")) {
                if (line.startsWith("name=")) name = line.substring(5).trim();
            }
        }
        Path dir = manager.newInstanceDir(name);
        Files.createDirectories(dir);
        try {
            Path game = dir.resolve("minecraft");
            String gameRoot = zf.getEntry(root + ".minecraft/") != null || hasPrefix(zf, root + ".minecraft/") ? root + ".minecraft/" : root + "minecraft/";
            extractPrefix(zf, gameRoot, game, progress);
            Instance inst = new Instance(dir);
            inst.name = name;
            inst.mcVersion = mc;
            inst.loader = loader;
            inst.loaderVersion = loaderVersion;
            inst.versionId = loader == LoaderType.VANILLA ? mc : "";
            manager.register(inst);
            return new ImportResult(inst, List.of());
        } catch (IOException | RuntimeException e) {
            FileUtil.deleteRecursive(dir);
            throw e;
        }
    }

    private static ImportResult importCurseForge(ZipFile zf, JObject m, InstanceManager manager, Progress progress, CancelToken cancel) throws IOException {
        JObject minecraft = m.objOrEmpty("minecraft");
        String mc = minecraft.str("version");
        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = "";
        for (JObject l : minecraft.arrOrEmpty("modLoaders").objects()) {
            String id = l.str("id", "");
            int dash = id.indexOf('-');
            if (dash < 0) continue;
            LoaderType t = LoaderType.of(id.substring(0, dash));
            if (t == LoaderType.VANILLA) continue;
            loader = t;
            loaderVersion = id.substring(dash + 1);
            if (t == LoaderType.NEOFORGE && mc != null && mc.equals("1.20.1") && loaderVersion.startsWith("1.20.1-")) loaderVersion = loaderVersion.substring(7);
            if (l.bool("primary", false)) break;
        }
        String name = m.str("name", "CurseForge pack");
        Path dir = manager.newInstanceDir(name);
        Files.createDirectories(dir);
        List<String> warnings = new ArrayList<>();
        try {
            Path game = dir.resolve("minecraft");
            Path mods = game.resolve("mods");
            Files.createDirectories(mods);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
            List<JObject> files = m.arrOrEmpty("files").objects();
            int i = 0;
            for (JObject f : files) {
                if (cancel != null) cancel.check();
                i++;
                long project = f.lng("projectID", 0), file = f.lng("fileID", 0);
                progress.stage(I18n.tr("progress.downloadingFile", project + "/" + file, i, files.size()));
                progress.fraction((double) i / files.size());
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.curseforge.com/api/v1/mods/" + project + "/files/" + file + "/download"))
                            .header("User-Agent", AppInfo.USER_AGENT).timeout(Duration.ofMinutes(3)).GET().build();
                    HttpResponse<InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (r.statusCode() / 100 != 2) {
                        r.body().close();
                        throw new IOException("HTTP " + r.statusCode());
                    }
                    String path = r.uri().getPath();
                    String fn = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                    if (fn.isBlank()) fn = project + "-" + file + ".jar";
                    Path target = FileUtil.safeResolve(mods, fn);
                    try (InputStream in = r.body()) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelToken.CancelledException();
                } catch (IOException e) {
                    warnings.add("CurseForge " + project + "/" + file + " (" + e.getMessage() + ")");
                }
            }
            extractPrefix(zf, m.str("overrides", "overrides") + "/", game, progress);
            Instance inst = new Instance(dir);
            inst.name = name;
            inst.mcVersion = mc;
            inst.loader = loader;
            inst.loaderVersion = loaderVersion;
            inst.versionId = loader == LoaderType.VANILLA ? mc : "";
            manager.register(inst);
            return new ImportResult(inst, warnings);
        } catch (IOException | RuntimeException e) {
            FileUtil.deleteRecursive(dir);
            throw e;
        }
    }

    private static boolean hasPrefix(ZipFile zf, String prefix) {
        var en = zf.entries();
        while (en.hasMoreElements()) if (en.nextElement().getName().replace('\\', '/').startsWith(prefix)) return true;
        return false;
    }

    private static void extractPrefix(ZipFile zf, String prefix, Path dest, Progress progress) throws IOException {
        Files.createDirectories(dest);
        var en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName().replace('\\', '/');
            if (!n.startsWith(prefix)) continue;
            String rel = n.substring(prefix.length());
            if (rel.isEmpty()) continue;
            Path out = FileUtil.safeResolve(dest, rel);
            if (e.isDirectory()) {
                Files.createDirectories(out);
                continue;
            }
            Files.createDirectories(out.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String read(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Suggested export file name. */
    public static String exportFileName(Instance inst) {
        return FileUtil.safeName(inst.name).replace(' ', '_') + ".zip";
    }
}
