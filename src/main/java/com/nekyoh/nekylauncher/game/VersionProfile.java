package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A version JSON with its {@code inheritsFrom} chain already merged. */
public final class VersionProfile {
    public String id;
    public String type = "release";
    public String mainClass;
    /** Pre-1.13 single-string game arguments. */
    public String minecraftArguments;
    public JArray gameArgs;
    public JArray jvmArgs;
    public List<Library> libraries = new ArrayList<>();
    public JObject assetIndex;
    public String assets;
    public JObject clientDownload;
    /** Version id whose jar is the game client (usually the vanilla root). */
    public String clientJarId;
    public String javaComponent;
    public int javaMajor;
    public JObject loggingClient;
    public String inheritsFrom;
    /** Child-to-root list of ids that make up this profile. */
    public List<String> chain = new ArrayList<>();

    public String rootId() { return chain.isEmpty() ? id : chain.get(chain.size() - 1); }

    public static VersionProfile fromJson(JObject j) {
        VersionProfile p = new VersionProfile();
        p.id = j.str("id");
        p.type = j.str("type", "release");
        p.mainClass = j.str("mainClass");
        p.minecraftArguments = j.str("minecraftArguments");
        JObject args = j.obj("arguments");
        if (args != null) {
            p.gameArgs = args.arr("game");
            p.jvmArgs = args.arr("jvm");
        }
        for (JObject lib : j.arrOrEmpty("libraries").objects()) p.libraries.add(new Library(lib));
        p.assetIndex = j.obj("assetIndex");
        p.assets = j.str("assets");
        JObject downloads = j.obj("downloads");
        p.clientDownload = downloads != null ? downloads.obj("client") : null;
        if (p.clientDownload != null) p.clientJarId = p.id;
        else if (j.str("jar") != null) p.clientJarId = j.str("jar");
        JObject java = j.obj("javaVersion");
        if (java != null) {
            p.javaComponent = java.str("component");
            p.javaMajor = java.integer("majorVersion", 0);
        }
        JObject logging = j.obj("logging");
        if (logging != null) p.loggingClient = logging.obj("client");
        p.inheritsFrom = j.str("inheritsFrom");
        p.chain.add(p.id);
        return p;
    }

    /** Child values win; libraries are child-first and de-duplicated by group:artifact[:classifier]. */
    public static VersionProfile merge(VersionProfile child, VersionProfile parent) {
        VersionProfile m = new VersionProfile();
        m.id = child.id;
        m.type = child.type != null ? child.type : parent.type;
        m.mainClass = child.mainClass != null ? child.mainClass : parent.mainClass;
        m.minecraftArguments = child.minecraftArguments != null ? child.minecraftArguments : parent.minecraftArguments;
        m.gameArgs = concat(parent.gameArgs, child.gameArgs);
        m.jvmArgs = concat(parent.jvmArgs, child.jvmArgs);

        Map<String, Library> parentByKey = new LinkedHashMap<>();
        for (Library l : parent.libraries) parentByKey.putIfAbsent(l.key(), l);
        Set<String> seen = new HashSet<>();
        for (Library l : child.libraries) {
            if (!seen.add(l.key())) continue;
            Library pl = parentByKey.get(l.key());
            // legacy Forge re-lists vanilla libs without download info: keep the richer vanilla entry
            if (pl != null && !l.json.has("downloads") && pl.json.has("downloads")
                    && l.maven != null && pl.maven != null && l.maven.version().equals(pl.maven.version())) {
                m.libraries.add(pl);
            } else {
                m.libraries.add(l);
            }
        }
        for (Library l : parent.libraries) if (seen.add(l.key())) m.libraries.add(l);

        m.assetIndex = child.assetIndex != null ? child.assetIndex : parent.assetIndex;
        m.assets = child.assets != null ? child.assets : parent.assets;
        m.clientDownload = child.clientDownload != null ? child.clientDownload : parent.clientDownload;
        m.clientJarId = child.clientDownload != null ? child.clientJarId
                : (child.clientJarId != null ? child.clientJarId : parent.clientJarId);
        if (child.javaComponent != null || child.javaMajor > 0) {
            m.javaComponent = child.javaComponent;
            m.javaMajor = child.javaMajor;
        } else {
            m.javaComponent = parent.javaComponent;
            m.javaMajor = parent.javaMajor;
        }
        m.loggingClient = child.loggingClient != null ? child.loggingClient : parent.loggingClient;
        m.inheritsFrom = parent.inheritsFrom;
        m.chain = new ArrayList<>(child.chain);
        m.chain.addAll(parent.chain);
        return m;
    }

    private static JArray concat(JArray a, JArray b) {
        if (a == null && b == null) return null;
        JArray out = new JArray();
        if (a != null) for (Object o : a.raw()) out.add(o);
        if (b != null) for (Object o : b.raw()) out.add(o);
        return out;
    }

    /** Loads {@code versions/<id>/<id>.json} and every parent, merging them. */
    public static VersionProfile resolve(Dirs dirs, String id) throws IOException {
        VersionProfile p = read(dirs, id);
        Set<String> visited = new HashSet<>();
        visited.add(id);
        while (p.inheritsFrom != null && !p.inheritsFrom.isBlank()) {
            String parentId = p.inheritsFrom;
            if (!visited.add(parentId)) throw new IOException("Circular inheritsFrom at " + parentId);
            p = merge(p, read(dirs, parentId));
        }
        if (p.clientJarId == null) p.clientJarId = p.rootId();
        if (p.mainClass == null) throw new IOException("Version " + id + " has no mainClass");
        return p;
    }

    public static VersionProfile read(Dirs dirs, String id) throws IOException {
        Path f = dirs.versionJson(id);
        if (!Files.exists(f)) throw new IOException("Missing version file " + f);
        return fromJson(JObject.parse(FileUtil.read(f)));
    }

    /** Required Java major version, defaulting to 8 for old profiles without javaVersion. */
    public int requiredJava() { return javaMajor > 0 ? javaMajor : 8; }

    /** Mojang runtime component, defaulting to the legacy Java 8 runtime. */
    public String runtimeComponent() { return javaComponent != null ? javaComponent : "jre-legacy"; }
}
