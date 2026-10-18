package com.nekyoh.nekylauncher.instance;

import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One isolated Minecraft setup: version + loader + its own game folder (mods, packs, worlds...). */
public final class Instance {
    public static final String FILE = "instance.json";

    public final Path dir;
    public String name;
    public String mcVersion;
    public LoaderType loader = LoaderType.VANILLA;
    public String loaderVersion = "";
    /** Id of the installed version profile to launch; empty until the loader has been installed. */
    public String versionId = "";
    public String icon = "";
    public String group = "";
    public String javaPath = "";
    public boolean overrideMemory;
    public int minMemoryMb = 512;
    public int maxMemoryMb = 4096;
    public String jvmArgs = "";
    public boolean overrideResolution;
    public int width = 854;
    public int height = 480;
    public boolean fullscreen;
    public String joinServer = "";
    public long created = System.currentTimeMillis();
    public long lastPlayed;
    public long playTimeSeconds;

    public Instance(Path dir) { this.dir = dir; }

    public String id() { return dir.getFileName().toString(); }

    /** The .minecraft-equivalent folder of this instance. */
    public Path gameDir() { return dir.resolve("minecraft"); }

    public Path modsDir() { return gameDir().resolve("mods"); }
    public Path resourcePacksDir() { return gameDir().resolve("resourcepacks"); }
    public Path shaderPacksDir() { return gameDir().resolve("shaderpacks"); }
    public Path savesDir() { return gameDir().resolve("saves"); }
    public Path screenshotsDir() { return gameDir().resolve("screenshots"); }
    public Path nativesDir() { return dir.resolve("natives"); }

    public Path iconFile() { return icon == null || icon.isBlank() ? null : dir.resolve(icon); }

    /** The version profile id this instance needs (computed when not yet installed). */
    public String expectedVersionId() {
        if (versionId != null && !versionId.isBlank()) return versionId;
        return loader == LoaderType.VANILLA ? mcVersion : null;
    }

    public String describe() {
        return loader == LoaderType.VANILLA ? mcVersion : mcVersion + " · " + loader.display;
    }

    public static Instance load(Path dir) throws IOException {
        JObject o = JObject.parse(FileUtil.read(dir.resolve(FILE)));
        Instance i = new Instance(dir);
        i.fromJson(o);
        return i;
    }

    public void fromJson(JObject o) {
        name = o.str("name", id());
        mcVersion = o.str("mcVersion", "");
        loader = LoaderType.of(o.str("loader", "VANILLA"));
        loaderVersion = o.str("loaderVersion", "");
        versionId = o.str("versionId", "");
        icon = o.str("icon", "");
        group = o.str("group", "");
        javaPath = o.str("javaPath", "");
        overrideMemory = o.bool("overrideMemory", false);
        minMemoryMb = o.integer("minMemoryMb", minMemoryMb);
        maxMemoryMb = o.integer("maxMemoryMb", maxMemoryMb);
        jvmArgs = o.str("jvmArgs", "");
        overrideResolution = o.bool("overrideResolution", false);
        width = o.integer("width", width);
        height = o.integer("height", height);
        fullscreen = o.bool("fullscreen", false);
        joinServer = o.str("joinServer", "");
        created = o.lng("created", created);
        lastPlayed = o.lng("lastPlayed", 0);
        playTimeSeconds = o.lng("playTimeSeconds", 0);
    }

    public JObject toJson() {
        return new JObject()
                .put("formatVersion", 1)
                .put("name", name)
                .put("mcVersion", mcVersion)
                .put("loader", loader.name())
                .put("loaderVersion", loaderVersion)
                .put("versionId", versionId)
                .put("icon", icon)
                .put("group", group)
                .put("javaPath", javaPath)
                .put("overrideMemory", overrideMemory)
                .put("minMemoryMb", minMemoryMb)
                .put("maxMemoryMb", maxMemoryMb)
                .put("jvmArgs", jvmArgs)
                .put("overrideResolution", overrideResolution)
                .put("width", width)
                .put("height", height)
                .put("fullscreen", fullscreen)
                .put("joinServer", joinServer)
                .put("created", created)
                .put("lastPlayed", lastPlayed)
                .put("playTimeSeconds", playTimeSeconds);
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(gameDir());
        FileUtil.write(dir.resolve(FILE), toJson().toJson(true));
    }
}
