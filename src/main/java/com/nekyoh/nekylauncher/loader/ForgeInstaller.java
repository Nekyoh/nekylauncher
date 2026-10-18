package com.nekyoh.nekylauncher.loader;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.game.Downloader;
import com.nekyoh.nekylauncher.game.Library;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.java.JavaInstall;
import com.nekyoh.nekylauncher.util.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs Forge / NeoForge installers headlessly, like the official installer's "client" mode:
 * extracts the version profile, fetches libraries and executes the install processors
 * (binary patching, mappings) with the game's Java runtime.
 */
final class ForgeInstaller {
    private final Dirs dirs;
    private final JavaInstall java;
    private final int concurrency;
    private final Progress progress;
    private final CancelToken cancel;

    ForgeInstaller(Dirs dirs, JavaInstall java, int concurrency, Progress progress, CancelToken cancel) {
        this.dirs = dirs;
        this.java = java;
        this.concurrency = concurrency;
        this.progress = progress;
        this.cancel = cancel;
    }

    String install(String installerUrl, String mc) throws IOException {
        String fileName = installerUrl.substring(installerUrl.lastIndexOf('/') + 1);
        Path installer = dirs.cache().resolve("installers").resolve(fileName);
        if (!Files.exists(installer)) {
            String sha1 = null;
            try {
                sha1 = Http.getString(installerUrl + ".sha1").trim();
                if (!sha1.matches("[0-9a-fA-F]{40}")) sha1 = null;
            } catch (IOException ignored) { }
            Http.download(installerUrl, installer, sha1, null, cancel);
        }
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            JObject profile = JObject.parse(readEntry(zip, "install_profile.json"));
            if (profile.has("versionInfo")) return installLegacy(zip, profile, mc);
            return installModern(zip, installer, profile, mc);
        } catch (IOException e) {
            // a corrupt cached installer would fail forever otherwise
            if (!(e instanceof CancelToken.CancelledException)) Files.deleteIfExists(installer);
            throw e;
        }
    }

    // ------------------------------------------------------------------ legacy (1.5 - 1.12.2 old installers)

    private String installLegacy(ZipFile zip, JObject profile, String mc) throws IOException {
        JObject install = profile.objOrEmpty("install");
        JObject versionInfo = profile.obj("versionInfo");
        String id = versionInfo.str("id", install.str("target"));
        if (!versionInfo.has("inheritsFrom")) versionInfo.put("inheritsFrom", install.str("minecraft", mc));
        if (!versionInfo.has("jar")) versionInfo.put("jar", install.str("minecraft", mc));

        // the universal jar ships inside the installer
        String path = install.str("path");
        String filePath = install.str("filePath");
        if (path != null && filePath != null) {
            Path target = dirs.libraries().resolve(Maven.parse(path).path());
            extract(zip, filePath, target);
        }

        List<Downloader.Task> tasks = new ArrayList<>();
        for (JObject lj : versionInfo.arrOrEmpty("libraries").objects()) {
            Library lib = new Library(lj);
            if (!lib.isAllowed(Map.of())) continue;
            Library.Artifact a = lib.artifact();
            if (a == null) continue;
            Path target = dirs.libraries().resolve(a.path());
            if (Files.exists(target)) continue;
            if (path != null && lib.maven != null && lib.maven.key().equals(Maven.parse(path).key())) continue;
            tasks.add(new Downloader.Task(a.url(), target, null, 0, false));
        }
        progress.stage(I18n.tr("progress.downloadingLibraries"));
        Downloader.run(tasks, concurrency, progress, cancel);
        FileUtil.write(dirs.versionJson(id), versionInfo.toJson(true));
        return id;
    }

    // ------------------------------------------------------------------ modern (1.12.2 new installers, 1.13+)

    private String installModern(ZipFile zip, Path installerPath, JObject profile, String mc) throws IOException {
        String jsonEntry = profile.str("json", "/version.json");
        JObject version = JObject.parse(readEntry(zip, jsonEntry.startsWith("/") ? jsonEntry.substring(1) : jsonEntry));
        String id = version.str("id", profile.str("version"));
        String minecraft = profile.str("minecraft", mc);

        // 1. libraries from both the install profile (processor tools) and the game profile
        List<Downloader.Task> tasks = new ArrayList<>();
        List<JObject> libs = new ArrayList<>(profile.arrOrEmpty("libraries").objects());
        libs.addAll(version.arrOrEmpty("libraries").objects());
        for (JObject lj : libs) {
            Library lib = new Library(lj);
            Library.Artifact a = lib.artifact();
            if (a == null || a.path() == null) continue;
            Path target = dirs.libraries().resolve(a.path());
            if (a.downloadable()) {
                tasks.add(Downloader.Task.of(a.url(), target, a.sha1(), a.size()));
            } else if (!Hashes.matches(target, a.sha1(), a.size())) {
                if (!extract(zip, "maven/" + a.path(), target)) {
                    throw new IOException("Installer is missing bundled library " + lib.name);
                }
            }
        }
        String ownPath = profile.str("path");
        if (ownPath != null) {
            Path target = dirs.libraries().resolve(Maven.parse(ownPath).path());
            if (!Files.exists(target)) extract(zip, "maven/" + Maven.parse(ownPath).path(), target);
        }
        progress.stage(I18n.tr("progress.downloadingLibraries"));
        Downloader.run(tasks, concurrency, progress, cancel);

        // 2. data tokens
        Path tmp = Files.createTempDirectory(dirs.cache(), "forge-install-");
        try {
            Map<String, String> data = new HashMap<>();
            JObject dataObj = profile.objOrEmpty("data");
            for (String key : dataObj.keys()) {
                JObject entry = dataObj.obj(key);
                String value = entry != null ? entry.str("client") : null;
                if (value == null) continue;
                if (value.startsWith("[") && value.endsWith("]")) {
                    data.put(key, artifactPath(value.substring(1, value.length() - 1)));
                } else if (value.startsWith("'") && value.endsWith("'")) {
                    data.put(key, value.substring(1, value.length() - 1));
                } else if (value.startsWith("/")) {
                    Path out = FileUtil.safeResolve(tmp, value.substring(1));
                    if (!extract(zip, value.substring(1), out)) throw new IOException("Installer is missing " + value);
                    data.put(key, out.toString());
                } else {
                    data.put(key, value);
                }
            }
            data.put("SIDE", "client");
            data.put("MINECRAFT_JAR", dirs.versionJar(minecraft).toString());
            data.put("MINECRAFT_VERSION", minecraft);
            data.put("ROOT", dirs.root.toString());
            data.put("INSTALLER", installerPath.toString());
            data.put("LIBRARY_DIR", dirs.libraries().toString());
            if (!Files.exists(dirs.versionJar(minecraft))) throw new IOException("Vanilla client jar missing: " + dirs.versionJar(minecraft));

            // 3. processors
            List<JObject> processors = profile.arrOrEmpty("processors").objects();
            List<JObject> clientProcs = new ArrayList<>();
            for (JObject p : processors) {
                JArray sides = p.arr("sides");
                if (sides == null || sides.strings().contains("client")) clientProcs.add(p);
            }
            int n = 0;
            for (JObject p : clientProcs) {
                cancel.check();
                n++;
                progress.stage(I18n.tr("progress.forgeProcessor", n, clientProcs.size()));
                progress.fraction((double) (n - 1) / Math.max(1, clientProcs.size()));
                runProcessor(p, data);
            }
        } finally {
            try { FileUtil.deleteRecursive(tmp); } catch (IOException ignored) { }
        }

        FileUtil.write(dirs.versionJson(id), version.toJson(true));
        return id;
    }

    private void runProcessor(JObject proc, Map<String, String> data) throws IOException {
        String jarCoord = proc.str("jar");
        Path jar = Path.of(artifactPath(jarCoord));
        // outputs already valid? skip (re-installs, interrupted installs)
        JObject outputs = proc.obj("outputs");
        if (outputs != null && outputs.size() > 0) {
            boolean allOk = true;
            for (String k : outputs.keys()) {
                Path f = Path.of(replace(k, data));
                String sha = replace(outputs.str(k), data);
                if (!Hashes.matches(f, sha, 0)) { allOk = false; break; }
            }
            if (allOk) return;
        }
        String mainClass;
        try (JarFile jf = new JarFile(jar.toFile())) {
            mainClass = jf.getManifest() != null ? jf.getManifest().getMainAttributes().getValue("Main-Class") : null;
        }
        if (mainClass == null) throw new IOException("Processor " + jarCoord + " has no Main-Class");
        List<String> cp = new ArrayList<>();
        cp.add(jar.toString());
        for (String c : proc.arrOrEmpty("classpath").strings()) cp.add(artifactPath(c));
        List<String> cmd = new ArrayList<>();
        Path javaExe = java.executable();
        Path console = javaExe.resolveSibling(OS.exe("java"));
        cmd.add(Files.exists(console) ? console.toString() : javaExe.toString());
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));
        cmd.add(mainClass);
        for (String a : proc.arrOrEmpty("args").strings()) cmd.add(replace(a, data));

        Log.info("Forge processor: " + jarCoord + " " + proc.arrOrEmpty("args").strings());
        Process pr = new ProcessBuilder(cmd).directory(dirs.root.toFile()).redirectErrorStream(true).start();
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancel.isCancelled()) { pr.destroyForcibly(); cancel.check(); }
                tail.addLast(line);
                if (tail.size() > 30) tail.removeFirst();
                progress.detail(line.length() > 120 ? line.substring(0, 120) + "…" : line);
            }
        }
        int code;
        try {
            code = pr.waitFor();
        } catch (InterruptedException e) {
            pr.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (code != 0) {
            Log.warn("Processor output:\n" + String.join("\n", tail));
            throw new IOException("Forge processor " + jarCoord + " failed (exit " + code + "): " + (tail.isEmpty() ? "" : tail.peekLast()));
        }
        if (outputs != null) {
            for (String k : outputs.keys()) {
                Path f = Path.of(replace(k, data));
                String sha = replace(outputs.str(k), data);
                if (!Hashes.matches(f, sha, 0)) {
                    Files.deleteIfExists(f);
                    throw new IOException("Processor output checksum mismatch: " + f.getFileName());
                }
            }
        }
    }

    /** Forge token rules: whole "[maven]" -> library path, whole "'literal'", otherwise {KEY} substitution. */
    String replace(String s, Map<String, String> data) throws IOException {
        if (s == null) return "";
        if (s.length() > 1 && s.startsWith("[") && s.endsWith("]")) return artifactPath(s.substring(1, s.length() - 1));
        if (s.length() > 1 && s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            } else if (c == '{') {
                int end = s.indexOf('}', i);
                if (end < 0) { out.append(c); continue; }
                String key = s.substring(i + 1, end);
                String v = data.get(key);
                if (v == null) throw new IOException("Unknown installer token {" + key + "}");
                out.append(v);
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String artifactPath(String coord) {
        return dirs.libraries().resolve(Maven.parse(coord).path()).toString();
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("Not a Forge/NeoForge installer (missing " + name + ")");
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean extract(ZipFile zip, String name, Path target) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return false;
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(e)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
