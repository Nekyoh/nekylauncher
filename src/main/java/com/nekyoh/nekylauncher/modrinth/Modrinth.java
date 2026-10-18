package com.nekyoh.nekylauncher.modrinth;

import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Modrinth API v2 client: search, version resolution and download with dependencies. */
public final class Modrinth {
    private Modrinth() {}

    public static final String API = "https://api.modrinth.com/v2";

    public enum ProjectType {
        MOD("mod"), RESOURCEPACK("resourcepack"), SHADER("shader"), MODPACK("modpack");

        public final String api;

        ProjectType(String api) { this.api = api; }
    }

    public record Hit(String id, String slug, String title, String description, String author, long downloads,
                      String iconUrl, String type, List<String> categories) {}

    public record SearchResult(List<Hit> hits, int total) {}

    public record FileRef(String url, String filename, String sha1, long size) {}

    public record Version(String id, String projectId, String name, String number, List<String> gameVersions,
                          List<String> loaders, FileRef primary, List<JObject> dependencies, String type) {}

    public static SearchResult search(String query, ProjectType type, String gameVersion, LoaderType loader, int offset, int limit) throws IOException {
        JArray facets = new JArray();
        facets.add(new JArray().add("project_type:" + type.api));
        if (gameVersion != null && !gameVersion.isBlank()) facets.add(new JArray().add("versions:" + gameVersion));
        if (type == ProjectType.MOD && loader != null && loader.isModded()) {
            JArray l = new JArray().add("categories:" + loader.modrinth);
            // Quilt can load most Fabric mods
            if (loader == LoaderType.QUILT) l.add("categories:fabric");
            facets.add(l);
        }
        String url = API + "/search?limit=" + limit + "&offset=" + offset
                + "&index=" + (query == null || query.isBlank() ? "downloads" : "relevance")
                + "&query=" + Http.enc(query == null ? "" : query)
                + "&facets=" + Http.enc(facets.toJson(false));
        JObject j = Http.getJson(url);
        List<Hit> hits = new ArrayList<>();
        for (JObject h : j.arrOrEmpty("hits").objects()) {
            hits.add(new Hit(h.str("project_id"), h.str("slug"), h.str("title"), h.str("description", ""),
                    h.str("author", ""), h.lng("downloads", 0), h.str("icon_url"), h.str("project_type"),
                    h.arrOrEmpty("categories").strings()));
        }
        return new SearchResult(hits, j.integer("total_hits", hits.size()));
    }

    /** Loader names Modrinth accepts for files of this type on this instance. */
    public static List<String> loaderFilter(ProjectType type, LoaderType loader) {
        return switch (type) {
            case MOD -> loader == LoaderType.QUILT ? List.of("quilt", "fabric") : List.of(loader.modrinth);
            case RESOURCEPACK -> List.of("minecraft");
            case SHADER -> List.of(); // iris / optifine / canvas: any
            case MODPACK -> List.of();
        };
    }

    public static List<Version> versions(String projectId, List<String> loaders, String gameVersion) throws IOException {
        StringBuilder url = new StringBuilder(API + "/project/" + Http.enc(projectId) + "/version?");
        if (loaders != null && !loaders.isEmpty()) {
            JArray l = new JArray();
            loaders.forEach(l::add);
            url.append("loaders=").append(Http.enc(l.toJson(false))).append('&');
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("game_versions=").append(Http.enc(new JArray().add(gameVersion).toJson(false)));
        }
        List<Version> out = new ArrayList<>();
        for (JObject v : Http.getJsonArray(url.toString()).objects()) out.add(parseVersion(v));
        return out;
    }

    public static Version version(String versionId) throws IOException {
        return parseVersion(Http.getJson(API + "/version/" + Http.enc(versionId)));
    }

    static Version parseVersion(JObject v) {
        FileRef primary = null;
        List<JObject> files = v.arrOrEmpty("files").objects();
        for (JObject f : files) {
            if (f.bool("primary", false)) { primary = file(f); break; }
        }
        if (primary == null && !files.isEmpty()) primary = file(files.get(0));
        return new Version(v.str("id"), v.str("project_id"), v.str("name", ""), v.str("version_number", ""),
                v.arrOrEmpty("game_versions").strings(), v.arrOrEmpty("loaders").strings(), primary,
                v.arrOrEmpty("dependencies").objects(), v.str("version_type", "release"));
    }

    private static FileRef file(JObject f) {
        return new FileRef(f.str("url"), f.str("filename"), f.objOrEmpty("hashes").str("sha1"), f.lng("size", 0));
    }

    /** Best version: prefer release over beta/alpha, newest first (API order). */
    public static Version best(List<Version> list) {
        for (Version v : list) if ("release".equals(v.type())) return v;
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Downloads a version's primary file into {@code dir}, plus required dependencies (mods only).
     * Returns the files written.
     */
    public static List<Path> install(Version version, Path dir, ProjectType type, LoaderType loader, String gameVersion,
                                     boolean withDependencies, Progress progress, CancelToken cancel) throws IOException {
        List<Path> written = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Version> queue = new ArrayDeque<>();
        queue.add(version);
        Files.createDirectories(dir);
        Map<String, Path> installed = installedProjects(dir);
        boolean first = true;
        while (!queue.isEmpty()) {
            if (cancel != null) cancel.check();
            Version v = queue.poll();
            if (!visited.add(v.projectId())) continue;
            if (v.primary() == null) continue;
            Path existing = installed.get(v.projectId());
            // dependencies that are already present (any version) are left alone
            if (!first && existing != null) continue;
            first = false;
            String name = v.primary().filename();
            Path target = FileUtil.safeResolve(dir, name);
            if (!Hashes.matches(target, v.primary().sha1(), v.primary().size())) {
                progress.stage(name);
                Http.download(v.primary().url(), target, v.primary().sha1(), null, cancel);
                written.add(target);
            }
            // installing another version of the same project replaces the old file
            if (existing != null && !existing.getFileName().toString().equals(name)) Files.deleteIfExists(existing);
            installed.put(v.projectId(), target);
            if (!withDependencies || type != ProjectType.MOD) continue;
            for (JObject dep : v.dependencies()) {
                if (!"required".equals(dep.str("dependency_type"))) continue;
                String pid = dep.str("project_id");
                if (pid != null && (visited.contains(pid) || installed.containsKey(pid))) continue;
                try {
                    Version dv = null;
                    if (dep.str("version_id") != null) dv = version(dep.str("version_id"));
                    else if (pid != null) dv = best(versions(pid, loaderFilter(type, loader), gameVersion));
                    if (dv != null) queue.add(dv);
                } catch (IOException e) {
                    Log.warn("Dependency " + pid + " not resolved: " + e.getMessage());
                }
            }
        }
        return written;
    }

    /** Identifies files already in {@code dir} by hash (one API call): projectId -> file. */
    public static Map<String, Path> installedProjects(Path dir) {
        Map<String, Path> out = new HashMap<>();
        Map<String, Path> byHash = new HashMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".disabled")) byHash.put(Hashes.sha1(p), p);
            }
        } catch (IOException e) {
            return out;
        }
        if (byHash.isEmpty()) return out;
        try {
            JArray hashes = new JArray();
            byHash.keySet().forEach(hashes::add);
            Http.Response r = Http.postJson(API + "/version_files", new JObject().put("hashes", hashes).put("algorithm", "sha1"));
            if (r.status() != 200) return out;
            JObject map = r.json();
            for (String h : map.keys()) {
                JObject v = map.obj(h);
                if (v != null && v.str("project_id") != null && byHash.containsKey(h)) out.put(v.str("project_id"), byHash.get(h));
            }
        } catch (Exception e) {
            Log.warn("Could not identify installed files: " + e.getMessage());
        }
        return out;
    }
}
