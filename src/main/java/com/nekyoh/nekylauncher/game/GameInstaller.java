package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.Hashes;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;
import com.nekyoh.nekylauncher.util.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads everything a resolved profile needs: client jar, libraries, natives, assets, logging config. */
public final class GameInstaller {
    public static final String RESOURCES_URL = "https://resources.download.minecraft.net/";

    private final Dirs dirs;
    private final int concurrency;

    public GameInstaller(Dirs dirs, int concurrency) {
        this.dirs = dirs;
        this.concurrency = concurrency;
    }

    /** Makes sure versions/&lt;id&gt;/&lt;id&gt;.json exists and matches the manifest (refreshes updated JSONs). */
    public void ensureVanillaJson(String id, VersionManifest manifest) throws IOException {
        Path f = dirs.versionJson(id);
        VersionManifest.Entry e = manifest != null ? manifest.find(id).orElse(null) : null;
        if (e == null) {
            if (Files.exists(f)) return;
            throw new IOException(I18n.tr("error.versionNotFound", id));
        }
        if (Hashes.matches(f, e.sha1(), 0)) return;
        Http.download(e.url(), f, e.sha1(), null, null);
    }

    public record Result(Path assetsRoot, Path gameAssets, String assetIndexName) {}

    public Result install(VersionProfile p, Path gameDir, Map<String, Boolean> features, Progress progress, CancelToken cancel) throws IOException {
        List<Downloader.Task> tasks = new ArrayList<>();

        progress.stage(I18n.tr("progress.checkingFiles"));
        progress.fraction(-1);
        // client jar
        Path clientJar = dirs.versionJar(p.clientJarId);
        if (p.clientDownload != null && p.clientDownload.str("url") != null) {
            tasks.add(Downloader.Task.of(p.clientDownload.str("url"), clientJar, p.clientDownload.str("sha1"), p.clientDownload.lng("size", 0)));
        } else if (!Files.exists(clientJar)) {
            throw new IOException("Missing client jar " + clientJar);
        }

        // libraries + native classifier jars
        for (Library lib : p.libraries) {
            if (!lib.isAllowed(features)) continue;
            addLibrary(tasks, lib, lib.artifact());
            addLibrary(tasks, lib, lib.nativeArtifact());
        }

        // logging configuration (log4j hardening, same as the official launcher)
        if (p.loggingClient != null) {
            JObject file = p.loggingClient.obj("file");
            if (file != null && file.str("url") != null) {
                tasks.add(Downloader.Task.of(file.str("url"), dirs.assets().resolve("log_configs").resolve(file.str("id")),
                        file.str("sha1"), file.lng("size", 0)));
            }
        }

        progress.stage(I18n.tr("progress.downloadingLibraries"));
        Downloader.run(tasks, concurrency, progress, cancel);

        // assets
        Result r = installAssets(p, gameDir, progress, cancel);
        progress.fraction(1);
        return r;
    }

    private void addLibrary(List<Downloader.Task> tasks, Library lib, Library.Artifact a) throws IOException {
        if (a == null || a.path() == null) return;
        Path target = dirs.libraries().resolve(a.path());
        if (a.downloadable()) {
            tasks.add(Downloader.Task.of(a.url(), target, a.sha1(), a.size()));
        } else if (!Files.exists(target)) {
            throw new IOException("Library " + lib.name + " is missing and has no download URL. Reinstall the mod loader.");
        }
    }

    private Result installAssets(VersionProfile p, Path gameDir, Progress progress, CancelToken cancel) throws IOException {
        Path assetsRoot = dirs.assets();
        if (p.assetIndex == null) return new Result(assetsRoot, assetsRoot, p.assets != null ? p.assets : "legacy");
        String indexId = p.assetIndex.str("id", p.assets);
        Path indexFile = assetsRoot.resolve("indexes").resolve(indexId + ".json");
        String sha1 = p.assetIndex.str("sha1");
        if (!Hashes.matches(indexFile, sha1, 0)) {
            progress.stage(I18n.tr("progress.downloadingAssets"));
            Http.download(p.assetIndex.str("url"), indexFile, sha1, null, cancel);
        }
        JObject index = JObject.parse(FileUtil.read(indexFile));
        JObject objects = index.objOrEmpty("objects");
        List<Downloader.Task> tasks = new ArrayList<>(objects.size());
        for (String name : objects.keys()) {
            JObject o = objects.obj(name);
            String hash = o.str("hash");
            if (hash == null || hash.length() < 2) continue;
            String rel = hash.substring(0, 2) + "/" + hash;
            tasks.add(new Downloader.Task(RESOURCES_URL + rel, assetsRoot.resolve("objects").resolve(rel), hash, o.lng("size", 0), false));
        }
        progress.stage(I18n.tr("progress.downloadingAssets"));
        progress.fraction(0);
        Downloader.run(tasks, concurrency, progress, cancel);

        boolean virtual = index.bool("virtual", false);
        boolean mapToResources = index.bool("map_to_resources", false);
        Path gameAssets = assetsRoot;
        if (virtual || mapToResources) {
            Path target = mapToResources ? gameDir.resolve("resources") : assetsRoot.resolve("virtual").resolve(indexId);
            progress.stage(I18n.tr("progress.preparingAssets"));
            for (String name : objects.keys()) {
                String hash = objects.obj(name).str("hash");
                if (hash == null) continue;
                Path src = assetsRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                Path dst = FileUtil.safeResolve(target, name);
                if (Files.exists(dst) && Files.size(dst) == Files.size(src)) continue;
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            gameAssets = target;
        }
        return new Result(assetsRoot, gameAssets, indexId);
    }

    /** Extracts native classifier jars into a fresh directory. */
    public void extractNatives(VersionProfile p, Map<String, Boolean> features, Path nativesDir) throws IOException {
        FileUtil.deleteRecursive(nativesDir);
        Files.createDirectories(nativesDir);
        for (Library lib : p.libraries) {
            if (!lib.isAllowed(features)) continue;
            Library.Artifact a = lib.nativeArtifact();
            if (a == null) continue;
            Path jar = dirs.libraries().resolve(a.path());
            if (!Files.exists(jar)) continue;
            List<String> excludes = lib.extractExcludes();
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || name.startsWith("META-INF/")) continue;
                    if (excludes.stream().anyMatch(name::startsWith)) continue;
                    Path out = FileUtil.safeResolve(nativesDir, name);
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException ex) {
                Log.warn("Could not extract natives from " + jar + ": " + ex.getMessage());
            }
        }
    }
}
