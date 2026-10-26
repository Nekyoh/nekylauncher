package com.nekyoh.nekylauncher.curseforge;

import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CurseForge Core API v1 client (api.curseforge.com). Needs an API key from the CurseForge console,
 * compiled into the build like the Microsoft client ID.
 *
 * <p>Files whose author disabled third-party distribution come back without a {@code downloadUrl};
 * those are never fetched behind the author's back: the user gets a link to download them manually.
 */
public final class CurseForge {
    private CurseForge() {}

    public static final String API = "https://api.curseforge.com/v1";
    private static volatile String base = API;
    public static final int MINECRAFT = 432;

    public enum ClassType {
        MOD(6, "mc-mods"), RESOURCEPACK(12, "texture-packs"), SHADER(6552, "shaders"), MODPACK(4471, "modpacks");

        public final int id;
        public final String path;

        ClassType(int id, String path) {
            this.id = id;
            this.path = path;
        }

        public static ClassType of(int classId) {
            for (ClassType t : values()) if (t.id == classId) return t;
            return null;
        }
    }

    /** CurseForge modLoaderType ids. */
    static int loaderId(LoaderType l) {
        return switch (l) {
            case FORGE -> 1;
            case FABRIC, QUILT -> 4; // Quilt runs Fabric mods; Quilt-only mods are matched by file tags
            case NEOFORGE -> 6;
            default -> 0;
        };
    }

    public record Hit(long id, String slug, String title, String summary, String author, long downloads,
                      String iconUrl, List<String> categories, String pageUrl, int classId) {}

    public record SearchResult(List<Hit> hits, int total) {}

    public record FileInfo(long id, long modId, String displayName, String fileName, String downloadUrl, String sha1,
                           long size, List<String> gameVersions, int releaseType, List<Long> requiredMods) {}

    /** A file that must be downloaded by hand (author opted out of third-party downloads). */
    public record Blocked(String name, String pageUrl) {}

    public record InstallResult(List<Path> written, List<Blocked> blocked) {}

    public static final class DistributionBlockedException extends IOException {
        public final Blocked blocked;

        public DistributionBlockedException(Blocked b) {
            super(b.name());
            this.blocked = b;
        }
    }

    // ------------------------------------------------------------------ key & transport

    private static volatile String keyOverride;

    /** Tests only: key and API base (a local server). */
    public static void setForTests(String key, String apiBase) {
        keyOverride = key;
        base = apiBase == null ? API : apiBase;
    }

    static String key() {
        if (keyOverride != null) return keyOverride;
        if (!AppInfo.BUILTIN_CF_API_KEY.isBlank()) return AppInfo.BUILTIN_CF_API_KEY;
        String env = System.getenv("NEKY_CF_API_KEY");
        return env == null ? "" : env.trim();
    }

    public static boolean available() { return !key().isBlank(); }

    private static Map<String, String> headers() {
        String k = key();
        if (k.isBlank()) return Map.of();
        return Map.of("x-api-key", k);
    }

    private static JObject get(String path) throws IOException {
        requireKey();
        return Http.getJson(base + path, headers());
    }

    private static JObject post(String path, JObject body) throws IOException {
        requireKey();
        Http.Response r = Http.post(base + path, "application/json", body.toJson(false), headers());
        if (r.status() / 100 != 2) throw new Http.HttpException(base + path, r.status(), r.body());
        return r.json();
    }

    private static void requireKey() throws IOException {
        if (!available()) throw new IOException(com.nekyoh.nekylauncher.i18n.I18n.tr("cf.noKey"));
    }

    // ------------------------------------------------------------------ search & files

    public static SearchResult search(String query, ClassType type, String gameVersion, LoaderType loader, int offset, int limit) throws IOException {
        StringBuilder u = new StringBuilder("/mods/search?gameId=" + MINECRAFT + "&classId=" + type.id
                + "&sortField=2&sortOrder=desc" // popularity
                + "&index=" + offset + "&pageSize=" + Math.min(50, limit));
        if (query != null && !query.isBlank()) u.append("&searchFilter=").append(Http.enc(query.trim()));
        if (gameVersion != null && !gameVersion.isBlank()) u.append("&gameVersion=").append(Http.enc(gameVersion));
        if (type == ClassType.MOD && loader != null && loaderId(loader) > 0) u.append("&modLoaderType=").append(loaderId(loader));
        return parseSearch(get(u.toString()));
    }

    static SearchResult parseSearch(JObject j) {
        List<Hit> hits = new ArrayList<>();
        for (JObject m : j.arrOrEmpty("data").objects()) hits.add(parseHit(m));
        int total = (int) Math.min(10_000, j.objOrEmpty("pagination").lng("totalCount", hits.size()));
        return new SearchResult(hits, total);
    }

    static Hit parseHit(JObject m) {
        List<String> cats = new ArrayList<>();
        for (JObject c : m.arrOrEmpty("categories").objects()) if (c.str("name") != null) cats.add(c.str("name"));
        List<JObject> authors = m.arrOrEmpty("authors").objects();
        String author = authors.isEmpty() ? "" : authors.get(0).str("name", "");
        JObject logo = m.objOrEmpty("logo");
        String icon = logo.str("thumbnailUrl", logo.str("url"));
        int classId = m.integer("classId", 6);
        String page = m.objOrEmpty("links").str("websiteUrl");
        if (page == null || page.isBlank()) {
            ClassType t = ClassType.of(classId);
            page = "https://www.curseforge.com/minecraft/" + (t == null ? "mc-mods" : t.path) + "/" + m.str("slug", "");
        }
        return new Hit(m.lng("id", 0), m.str("slug", ""), m.str("name", ""), m.str("summary", ""), author,
                m.lng("downloadCount", 0), icon, cats, page, classId);
    }

    public static Hit mod(long modId) throws IOException { return parseHit(get("/mods/" + modId).objOrEmpty("data")); }

    /** Batch project lookup. */
    public static Map<Long, Hit> mods(Collection<Long> ids) throws IOException {
        Map<Long, Hit> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        JArray a = new JArray();
        ids.forEach(a::add);
        for (JObject m : post("/mods", new JObject().put("modIds", a)).arrOrEmpty("data").objects()) {
            Hit h = parseHit(m);
            out.put(h.id(), h);
        }
        return out;
    }

    /** Batch file lookup (used by modpack import). */
    public static Map<Long, FileInfo> files(Collection<Long> fileIds) throws IOException {
        Map<Long, FileInfo> out = new HashMap<>();
        List<Long> all = new ArrayList<>(fileIds);
        for (int i = 0; i < all.size(); i += 500) {
            JArray a = new JArray();
            all.subList(i, Math.min(all.size(), i + 500)).forEach(a::add);
            for (JObject f : post("/mods/files", new JObject().put("fileIds", a)).arrOrEmpty("data").objects()) {
                FileInfo fi = parseFile(f);
                out.put(fi.id(), fi);
            }
        }
        return out;
    }

    /** Files of a project usable on this game version / loader, newest first. */
    public static List<FileInfo> projectFiles(long modId, ClassType type, String gameVersion, LoaderType loader) throws IOException {
        StringBuilder u = new StringBuilder("/mods/" + modId + "/files?pageSize=50");
        if (gameVersion != null && !gameVersion.isBlank()) u.append("&gameVersion=").append(Http.enc(gameVersion));
        boolean filterLoader = type == ClassType.MOD && loader != null && loader.isModded();
        // Quilt and NeoForge 1.20.1 also accept other loaders' files: filter those locally
        if (filterLoader && loader != LoaderType.QUILT && !neoForgeLegacy(loader, gameVersion)) u.append("&modLoaderType=").append(loaderId(loader));
        List<FileInfo> out = new ArrayList<>();
        for (JObject f : get(u.toString()).arrOrEmpty("data").objects()) {
            if (!f.bool("isAvailable", true)) continue;
            FileInfo fi = parseFile(f);
            if (filterLoader && !matchesLoader(fi, loader, gameVersion)) continue;
            out.add(fi);
        }
        out.sort(Comparator.comparingLong(FileInfo::id).reversed()); // ids grow with upload time
        return out;
    }

    static boolean neoForgeLegacy(LoaderType l, String mc) { return l == LoaderType.NEOFORGE && "1.20.1".equals(mc); }

    /** Loader tags a file may carry to run on this loader (CurseForge lists them in gameVersions). */
    public static List<String> acceptedLoaders(LoaderType l, String mc) {
        return switch (l) {
            case QUILT -> List.of("quilt", "fabric");
            case NEOFORGE -> neoForgeLegacy(l, mc) ? List.of("neoforge", "forge") : List.of("neoforge");
            case FORGE -> List.of("forge");
            case FABRIC -> List.of("fabric");
            default -> List.of();
        };
    }

    public static boolean matchesLoader(FileInfo f, LoaderType l, String mc) {
        List<String> ok = acceptedLoaders(l, mc);
        boolean anyLoaderTag = false;
        for (String gv : f.gameVersions()) {
            String g = gv.toLowerCase(Locale.ROOT);
            if (List.of("forge", "neoforge", "fabric", "quilt").contains(g)) {
                anyLoaderTag = true;
                if (ok.contains(g)) return true;
            }
        }
        return !anyLoaderTag; // untagged (old) files: trust the server-side filter
    }

    static FileInfo parseFile(JObject f) {
        String sha1 = null;
        for (JObject h : f.arrOrEmpty("hashes").objects()) if (h.integer("algo", 0) == 1) sha1 = h.str("value");
        List<Long> req = new ArrayList<>();
        for (JObject d : f.arrOrEmpty("dependencies").objects()) if (d.integer("relationType", 0) == 3) req.add(d.lng("modId", 0));
        String url = f.str("downloadUrl");
        return new FileInfo(f.lng("id", 0), f.lng("modId", 0), f.str("displayName", ""), f.str("fileName", ""),
                url == null || url.isBlank() ? null : url, sha1, f.lng("fileLength", 0),
                f.arrOrEmpty("gameVersions").strings(), f.integer("releaseType", 1), req);
    }

    /** Best file: newest release, else newest beta/alpha. */
    public static FileInfo best(List<FileInfo> files) {
        for (FileInfo f : files) if (f.releaseType() == 1) return f;
        return files.isEmpty() ? null : files.get(0);
    }

    // ------------------------------------------------------------------ install

    /**
     * Downloads {@code file} into {@code dir} plus its required dependencies (mods only). Dependencies
     * already present (matched by CurseForge fingerprint) are skipped. Throws
     * {@link DistributionBlockedException} if the main file can't be downloaded by third parties;
     * blocked dependencies are reported in the result.
     */
    public static InstallResult install(Hit project, FileInfo file, Path dir, ClassType type, LoaderType loader, String gameVersion,
                                        boolean withDependencies, Progress progress, CancelToken cancel) throws IOException {
        if (file.downloadUrl() == null) throw new DistributionBlockedException(new Blocked(project.title() + " — " + file.fileName(), project.pageUrl()));
        Files.createDirectories(dir);
        Map<Long, Path> installed = installedProjects(dir);
        List<Path> written = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<FileInfo> queue = new ArrayDeque<>();
        queue.add(file);
        boolean first = true;
        while (!queue.isEmpty()) {
            if (cancel != null) cancel.check();
            FileInfo f = queue.poll();
            if (!visited.add(f.modId())) continue;
            Path existing = installed.get(f.modId());
            if (!first && existing != null) continue;
            first = false;
            if (f.downloadUrl() == null) {
                Hit h = safeMod(f.modId());
                blocked.add(new Blocked((h == null ? "#" + f.modId() : h.title()) + " — " + f.fileName(),
                        h == null ? "https://www.curseforge.com/projects/" + f.modId() : h.pageUrl()));
                continue;
            }
            Path target = FileUtil.safeResolve(dir, f.fileName());
            if (!Hashes.matches(target, f.sha1(), f.size())) {
                progress.stage(f.fileName());
                Http.download(f.downloadUrl(), target, f.sha1(), null, cancel);
                written.add(target);
            }
            if (existing != null && !existing.equals(target)) Files.deleteIfExists(existing);
            installed.put(f.modId(), target);
            if (!withDependencies || type != ClassType.MOD) continue;
            for (long dep : f.requiredMods()) {
                if (dep <= 0 || visited.contains(dep) || installed.containsKey(dep)) continue;
                try {
                    FileInfo df = best(projectFiles(dep, ClassType.MOD, gameVersion, loader));
                    if (df != null) queue.add(df);
                    else Log.warn("CurseForge dependency " + dep + " has no file for " + gameVersion + " " + loader);
                } catch (IOException e) {
                    Log.warn("CurseForge dependency " + dep + " not resolved: " + e.getMessage());
                }
            }
        }
        return new InstallResult(written, blocked);
    }

    private static Hit safeMod(long id) {
        try {
            return mod(id);
        } catch (IOException e) {
            return null;
        }
    }

    /** Identifies files in {@code dir} by CurseForge fingerprint: modId -> file. */
    public static Map<Long, Path> installedProjects(Path dir) {
        Map<Long, Path> out = new HashMap<>();
        Map<Long, Path> byPrint = new HashMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".disabled")) byPrint.put(fingerprint(p), p);
            }
        } catch (IOException e) {
            return out;
        }
        if (byPrint.isEmpty() || !available()) return out;
        try {
            JArray a = new JArray();
            byPrint.keySet().forEach(a::add);
            JObject data = post("/fingerprints/" + MINECRAFT, new JObject().put("fingerprints", a)).objOrEmpty("data");
            for (JObject m : data.arrOrEmpty("exactMatches").objects()) {
                JObject file = m.objOrEmpty("file");
                long print = file.lng("fileFingerprint", -1);
                Path p = byPrint.get(print);
                if (p != null) out.put(m.lng("id", file.lng("modId", 0)), p);
            }
        } catch (Exception e) {
            Log.warn("Could not identify installed CurseForge files: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ fingerprint

    /** CurseForge file fingerprint: MurmurHash2 (seed 1) over the bytes, ignoring 9, 10, 13 and 32. */
    public static long fingerprint(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return fingerprint(in.readAllBytes());
        }
    }

    public static long fingerprint(byte[] data) {
        int len = 0;
        byte[] buf = new byte[data.length];
        for (byte b : data) if (b != 9 && b != 10 && b != 13 && b != 32) buf[len++] = b;
        final int m = 0x5bd1e995;
        int h = 1 ^ len;
        int i = 0;
        while (len - i >= 4) {
            int k = (buf[i] & 0xff) | (buf[i + 1] & 0xff) << 8 | (buf[i + 2] & 0xff) << 16 | (buf[i + 3] & 0xff) << 24;
            k *= m;
            k ^= k >>> 24;
            k *= m;
            h *= m;
            h ^= k;
            i += 4;
        }
        int rem = len - i;
        if (rem == 3) h ^= (buf[i + 2] & 0xff) << 16;
        if (rem >= 2) h ^= (buf[i + 1] & 0xff) << 8;
        if (rem >= 1) {
            h ^= buf[i] & 0xff;
            h *= m;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xffffffffL;
    }
}
