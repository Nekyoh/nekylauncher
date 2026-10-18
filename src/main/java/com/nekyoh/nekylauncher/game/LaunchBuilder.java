package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.OS;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a resolved profile + account + settings into the java command line. */
public final class LaunchBuilder {
    private LaunchBuilder() {}

    /** Same defaults the official launcher uses. */
    public static final List<String> DEFAULT_JVM_TUNING = List.of(
            "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M");

    public static final class Options {
        public VersionProfile profile;
        public Account account;
        public Path javaExe;
        public Path librariesDir;
        public Path clientJar;
        public Path nativesDir;
        public Path gameDir;
        public Path assetsRoot;
        public Path gameAssets;
        public String assetIndexName;
        public Path loggingConfig;
        public int minMemoryMb = 512;
        public int maxMemoryMb = 2048;
        public String extraJvmArgs = "";
        public int width;
        public int height;
        public boolean fullscreen;
        public String joinServer = "";
    }

    public static Map<String, Boolean> features(Options o) {
        Map<String, Boolean> f = new HashMap<>();
        f.put("is_demo_user", false);
        f.put("has_custom_resolution", o.width > 0 && o.height > 0);
        f.put("has_quick_plays_support", false);
        f.put("is_quick_play_singleplayer", false);
        f.put("is_quick_play_realms", false);
        f.put("is_quick_play_multiplayer", !o.joinServer.isBlank() && supportsQuickPlay(o.profile));
        return f;
    }

    static boolean supportsQuickPlay(VersionProfile p) {
        return p.gameArgs != null && p.gameArgs.toJson(false).contains("is_quick_play_multiplayer");
    }

    public static List<String> classpath(VersionProfile p, Map<String, Boolean> features, Path librariesDir, Path clientJar) {
        Set<String> cp = new LinkedHashSet<>();
        for (Library lib : p.libraries) {
            if (!lib.isAllowed(features)) continue;
            Library.Artifact a = lib.artifact();
            if (a == null || a.path() == null) continue;
            cp.add(librariesDir.resolve(a.path()).toString());
        }
        cp.add(clientJar.toString());
        return new ArrayList<>(cp);
    }

    public static List<String> build(Options o) {
        VersionProfile p = o.profile;
        Map<String, Boolean> features = features(o);
        Account acc = o.account;

        Map<String, String> v = new HashMap<>();
        v.put("auth_player_name", acc.username);
        v.put("auth_uuid", acc.uuid);
        v.put("auth_access_token", acc.accessToken == null || acc.accessToken.isBlank() ? "0" : acc.accessToken);
        v.put("auth_session", "token:" + v.get("auth_access_token") + ":" + acc.uuid);
        v.put("auth_xuid", acc.xuid == null ? "" : acc.xuid);
        v.put("clientid", "");
        v.put("user_type", acc.userType());
        v.put("user_properties", "{}");
        v.put("version_name", p.id);
        v.put("version_type", p.type == null ? "release" : p.type);
        v.put("game_directory", o.gameDir.toString());
        v.put("assets_root", o.assetsRoot.toString());
        v.put("game_assets", o.gameAssets.toString());
        v.put("assets_index_name", o.assetIndexName == null ? "legacy" : o.assetIndexName);
        v.put("natives_directory", o.nativesDir.toString());
        v.put("library_directory", o.librariesDir.toString());
        v.put("classpath_separator", File.pathSeparator);
        v.put("launcher_name", AppInfo.NAME);
        v.put("launcher_version", AppInfo.VERSION);
        v.put("resolution_width", Integer.toString(o.width > 0 ? o.width : 854));
        v.put("resolution_height", Integer.toString(o.height > 0 ? o.height : 480));
        v.put("quickPlayMultiplayer", o.joinServer.trim());
        v.put("quickPlayPath", "quickPlay/log.json");
        v.put("classpath", String.join(File.pathSeparator, classpath(p, features, o.librariesDir, o.clientJar)));

        List<String> cmd = new ArrayList<>();
        cmd.add(o.javaExe.toString());
        cmd.add("-Xms" + Math.min(o.minMemoryMb, o.maxMemoryMb) + "M");
        cmd.add("-Xmx" + o.maxMemoryMb + "M");
        cmd.add("-Dlog4j2.formatMsgNoLookups=true");
        List<String> extra = splitArgs(o.extraJvmArgs);
        cmd.addAll(extra.isEmpty() ? DEFAULT_JVM_TUNING : extra);
        if (o.loggingConfig != null && p.loggingClient != null) {
            String arg = p.loggingClient.str("argument", "-Dlog4j.configurationFile=${path}");
            cmd.add(arg.replace("${path}", o.loggingConfig.toString()));
        }

        if (p.jvmArgs != null) {
            cmd.addAll(expand(p.jvmArgs, features, v));
        } else {
            if (OS.isWindows()) cmd.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            if (OS.isMac()) cmd.add("-XstartOnFirstThread");
            cmd.add("-Djava.library.path=" + v.get("natives_directory"));
            cmd.add("-Dminecraft.launcher.brand=" + AppInfo.NAME);
            cmd.add("-Dminecraft.launcher.version=" + AppInfo.VERSION);
            cmd.add("-cp");
            cmd.add(v.get("classpath"));
        }

        cmd.add(p.mainClass);

        boolean modern = p.gameArgs != null;
        if (modern) {
            cmd.addAll(expand(p.gameArgs, features, v));
        } else if (p.minecraftArguments != null) {
            for (String tok : p.minecraftArguments.trim().split("\\s+")) {
                if (!tok.isEmpty()) cmd.add(substitute(tok, v));
            }
        }
        if (!modern && o.width > 0 && o.height > 0) {
            cmd.add("--width"); cmd.add(Integer.toString(o.width));
            cmd.add("--height"); cmd.add(Integer.toString(o.height));
        }
        if (o.fullscreen) cmd.add("--fullscreen");
        if (!o.joinServer.isBlank() && !features.get("is_quick_play_multiplayer")) {
            String host = o.joinServer.trim(), port = "25565";
            int c = host.lastIndexOf(':');
            if (c > 0 && host.indexOf(':') == c) {
                port = host.substring(c + 1);
                host = host.substring(0, c);
            }
            cmd.add("--server"); cmd.add(host);
            cmd.add("--port"); cmd.add(port);
        }
        return cmd;
    }

    /** Expands a modern "arguments" array: strings and {rules, value} objects. */
    static List<String> expand(JArray args, Map<String, Boolean> features, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof String s) {
                out.add(substitute(s, vars));
            } else if (a instanceof JObject obj) {
                if (!Rules.allowed(obj.arr("rules"), features)) continue;
                Object val = obj.get("value");
                if (val instanceof String s) out.add(substitute(s, vars));
                else if (val instanceof JArray arr) for (String s : arr.strings()) out.add(substitute(s, vars));
            }
        }
        return out;
    }

    public static String substitute(String s, Map<String, String> vars) {
        if (s.indexOf("${") < 0) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("${", i);
            if (start < 0) { sb.append(s, i, s.length()); break; }
            int end = s.indexOf('}', start);
            if (end < 0) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, start);
            String key = s.substring(start + 2, end);
            String val = vars.get(key);
            sb.append(val != null ? val : s.substring(start, end + 1));
            i = end + 1;
        }
        return sb.toString();
    }

    /** Splits a command-line style string, honouring single and double quotes. */
    public static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has || !cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                has = false;
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has || !cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Masks the access token for logs. */
    public static List<String> redact(List<String> cmd, String token) {
        List<String> out = new ArrayList<>(cmd.size());
        for (String a : cmd) out.add(token != null && token.length() > 4 ? a.replace(token, "********") : a);
        return out;
    }
}
