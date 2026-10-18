package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Maven;
import com.nekyoh.nekylauncher.util.OS;

import java.util.List;
import java.util.Map;

/** A library entry from a version JSON (vanilla, Fabric-style maven, or legacy Forge). */
public final class Library {
    public static final String MOJANG_LIBRARIES = "https://libraries.minecraft.net/";

    public record Artifact(String path, String url, String sha1, long size) {
        /** Empty url means the file is provided locally (e.g. extracted from a Forge installer). */
        public boolean downloadable() { return url != null && !url.isBlank(); }
    }

    public final JObject json;
    public final String name;
    public final Maven maven;

    public Library(JObject json) {
        this.json = json;
        this.name = json.str("name", "");
        Maven m;
        try {
            m = Maven.parse(name);
        } catch (IllegalArgumentException e) {
            m = null;
        }
        this.maven = m;
    }

    public String key() { return maven != null ? maven.key() : name; }

    public boolean isAllowed(Map<String, Boolean> features) {
        if (!Rules.allowed(json.arr("rules"), features)) return false;
        // legacy Forge installers mark server-only libraries
        if (json.raw().containsKey("clientreq") && !json.bool("clientreq", true)) return false;
        if (json.raw().containsKey("serverreq") && !json.raw().containsKey("clientreq")) return false;
        return true;
    }

    public boolean hasNatives() { return json.has("natives"); }

    /** The jar that goes on the classpath, or null for natives-only entries. */
    public Artifact artifact() {
        JObject downloads = json.obj("downloads");
        if (downloads != null) {
            JObject a = downloads.obj("artifact");
            if (a != null) {
                String path = a.str("path");
                if (path == null && maven != null) path = maven.path();
                return new Artifact(path, a.str("url", ""), a.str("sha1"), a.lng("size", 0));
            }
            return null;
        }
        if (hasNatives() || maven == null) return null;
        String path = maven.path();
        // Fabric/Quilt meta may include top-level sha1/size
        return new Artifact(path, baseUrl() + path, json.str("sha1"), json.lng("size", 0));
    }

    /** Native classifier jar for the current OS (legacy LWJGL 2 / LWJGL 3 pre-1.19 layout), or null. */
    public Artifact nativeArtifact() {
        JObject natives = json.obj("natives");
        if (natives == null) return null;
        String classifier = natives.str(OS.mojangName());
        if (classifier == null) return null;
        classifier = classifier.replace("${arch}", OS.is64Bit() ? "64" : "32");
        JObject downloads = json.obj("downloads");
        if (downloads != null) {
            JObject cls = downloads.obj("classifiers");
            JObject a = cls != null ? cls.obj(classifier) : null;
            if (a != null) {
                String path = a.str("path");
                if (path == null && maven != null) path = maven.withClassifier(classifier).path();
                return new Artifact(path, a.str("url", ""), a.str("sha1"), a.lng("size", 0));
            }
        }
        if (maven == null) return null;
        String path = maven.withClassifier(classifier).path();
        return new Artifact(path, baseUrl() + path, null, 0);
    }

    public List<String> extractExcludes() {
        JObject ex = json.obj("extract");
        return ex == null ? List.of() : ex.arrOrEmpty("exclude").strings();
    }

    private String baseUrl() {
        String u = json.str("url");
        if (u == null || u.isBlank()) return MOJANG_LIBRARIES;
        u = u.replace("http://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/")
                .replace("https://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/");
        if (u.startsWith("http://")) u = "https://" + u.substring(7);
        return u.endsWith("/") ? u : u + "/";
    }

    @Override
    public String toString() { return name; }
}
