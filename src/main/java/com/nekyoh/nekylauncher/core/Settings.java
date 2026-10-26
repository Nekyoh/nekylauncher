package com.nekyoh.nekylauncher.core;

import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;
import com.nekyoh.nekylauncher.util.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Global launcher preferences (settings.json). */
public final class Settings {
    public enum AfterLaunch { KEEP_OPEN, MINIMIZE, HIDE }

    public String language = defaultLanguage();
    public AfterLaunch afterLaunch = AfterLaunch.MINIMIZE;
    public boolean showConsoleOnLaunch = false;
    public boolean systemWindowFrame = false;
    public boolean autoDownloadJava = true;
    public int minMemoryMb = 512;
    public int maxMemoryMb = defaultMaxMemory();
    public String jvmArgs = "";
    public int concurrentDownloads = 16;
    public String msClientId = "";
    public int gameWidth = 854;
    public int gameHeight = 480;
    public List<String> extraJavaPaths = new ArrayList<>();
    public String lastInstance = "";
    public int windowWidth = 1200;
    public int windowHeight = 740;
    public boolean windowMaximized = false;
    /** Last content source picked in the browser: "modrinth" or "curseforge". */
    public String browseSource = "modrinth";
    /** Look for a new NekyLauncher release on startup. */
    public boolean checkUpdates = true;
    /** Release the user chose to skip ("" = none). */
    public String skippedVersion = "";

    private transient Path file;

    /** Client ID to use: the user's override from Settings, else the one compiled into the build. */
    public String effectiveMsClientId() {
        if (msClientId != null && !msClientId.isBlank()) return msClientId.trim();
        return com.nekyoh.nekylauncher.AppInfo.BUILTIN_MS_CLIENT_ID;
    }

    public static String defaultLanguage() {
        String l = Locale.getDefault().getLanguage();
        if (l.equals("pt")) return "pt";
        if (l.equals("es")) return "es";
        return "en";
    }

    static int defaultMaxMemory() {
        long total = OS.totalMemoryMb();
        if (total >= 16000) return 6144;
        if (total >= 8000) return 4096;
        if (total >= 6000) return 3072;
        return 2048;
    }

    public static Settings load(Path file) {
        Settings s = new Settings();
        s.file = file;
        if (!Files.exists(file)) return s;
        try {
            JObject o = JObject.parse(FileUtil.read(file));
            s.language = o.str("language", s.language);
            try { s.afterLaunch = AfterLaunch.valueOf(o.str("afterLaunch", s.afterLaunch.name())); } catch (IllegalArgumentException ignored) { }
            s.showConsoleOnLaunch = o.bool("showConsoleOnLaunch", s.showConsoleOnLaunch);
            s.systemWindowFrame = o.bool("systemWindowFrame", s.systemWindowFrame);
            s.autoDownloadJava = o.bool("autoDownloadJava", s.autoDownloadJava);
            s.minMemoryMb = o.integer("minMemoryMb", s.minMemoryMb);
            s.maxMemoryMb = o.integer("maxMemoryMb", s.maxMemoryMb);
            s.jvmArgs = o.str("jvmArgs", s.jvmArgs);
            s.concurrentDownloads = Math.max(1, Math.min(64, o.integer("concurrentDownloads", s.concurrentDownloads)));
            s.msClientId = o.str("msClientId", s.msClientId);
            s.gameWidth = o.integer("gameWidth", s.gameWidth);
            s.gameHeight = o.integer("gameHeight", s.gameHeight);
            s.extraJavaPaths = new ArrayList<>(o.arrOrEmpty("extraJavaPaths").strings());
            s.lastInstance = o.str("lastInstance", s.lastInstance);
            s.windowWidth = o.integer("windowWidth", s.windowWidth);
            s.windowHeight = o.integer("windowHeight", s.windowHeight);
            s.windowMaximized = o.bool("windowMaximized", s.windowMaximized);
            s.browseSource = o.str("browseSource", s.browseSource);
            s.checkUpdates = o.bool("checkUpdates", s.checkUpdates);
            s.skippedVersion = o.str("skippedVersion", s.skippedVersion);
        } catch (Exception e) {
            Log.error("Could not read settings, using defaults", e);
        }
        return s;
    }

    public synchronized void save() {
        if (file == null) return;
        JObject o = new JObject()
                .put("language", language)
                .put("afterLaunch", afterLaunch.name())
                .put("showConsoleOnLaunch", showConsoleOnLaunch)
                .put("systemWindowFrame", systemWindowFrame)
                .put("autoDownloadJava", autoDownloadJava)
                .put("minMemoryMb", minMemoryMb)
                .put("maxMemoryMb", maxMemoryMb)
                .put("jvmArgs", jvmArgs)
                .put("concurrentDownloads", concurrentDownloads)
                .put("msClientId", msClientId)
                .put("gameWidth", gameWidth)
                .put("gameHeight", gameHeight)
                .put("lastInstance", lastInstance)
                .put("windowWidth", windowWidth)
                .put("windowHeight", windowHeight)
                .put("windowMaximized", windowMaximized)
                .put("browseSource", browseSource)
                .put("checkUpdates", checkUpdates)
                .put("skippedVersion", skippedVersion);
        JArray j = new JArray();
        extraJavaPaths.forEach(j::add);
        o.put("extraJavaPaths", j);
        try {
            FileUtil.write(file, o.toJson(true));
        } catch (Exception e) {
            Log.error("Could not save settings", e);
        }
    }
}
