package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Mojang's version_manifest_v2.json, cached on disk for offline use. */
public final class VersionManifest {
    public static final String URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record Entry(String id, String type, String url, String sha1, String releaseTime) {
        public boolean isRelease() { return "release".equals(type); }
        public boolean isSnapshot() { return "snapshot".equals(type); }
        public boolean isOld() { return "old_beta".equals(type) || "old_alpha".equals(type); }
    }

    public final String latestRelease;
    public final String latestSnapshot;
    public final List<Entry> versions;

    private VersionManifest(JObject j) {
        JObject latest = j.objOrEmpty("latest");
        latestRelease = latest.str("release");
        latestSnapshot = latest.str("snapshot");
        List<Entry> list = new ArrayList<>();
        for (JObject v : j.arrOrEmpty("versions").objects()) {
            list.add(new Entry(v.str("id"), v.str("type"), v.str("url"), v.str("sha1"), v.str("releaseTime", "")));
        }
        versions = Collections.unmodifiableList(list);
    }

    private static volatile VersionManifest cached;

    /** Network first (once per session unless {@code refresh}), falling back to the disk cache. */
    public static synchronized VersionManifest get(Dirs dirs, boolean refresh) throws IOException {
        if (cached != null && !refresh) return cached;
        Path cacheFile = dirs.cache().resolve("version_manifest_v2.json");
        try {
            String body = Http.getString(URL);
            VersionManifest m = new VersionManifest(JObject.parse(body));
            FileUtil.write(cacheFile, body);
            cached = m;
            return m;
        } catch (IOException e) {
            if (Files.exists(cacheFile)) {
                Log.warn("Using cached version manifest: " + e.getMessage());
                cached = new VersionManifest(JObject.parse(FileUtil.read(cacheFile)));
                return cached;
            }
            throw e;
        }
    }

    /** Returns the cached manifest if already loaded (no I/O). */
    public static VersionManifest peek() { return cached; }

    public Optional<Entry> find(String id) {
        return versions.stream().filter(v -> v.id().equals(id)).findFirst();
    }
}
