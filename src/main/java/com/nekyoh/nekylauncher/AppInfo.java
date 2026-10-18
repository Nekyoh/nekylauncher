package com.nekyoh.nekylauncher;

public final class AppInfo {
    private AppInfo() {}

    public static final String NAME = "NekyLauncher";
    public static final String AUTHOR = "Nekyoh";
    public static final String VERSION = readVersion();
    /** Modrinth asks for a uniquely identifying user agent: author/project/version. */
    public static final String USER_AGENT = "Nekyoh/NekyLauncher/" + VERSION + " (github.com/nekyoh/nekylauncher)";

    /**
     * Microsoft (Azure) client ID compiled into this build, or "" when the build has none.
     * With it, users sign in with one click and never have to configure anything.
     */
    public static final String BUILTIN_MS_CLIENT_ID = readBuildProperty("msClientId");

    private static String readBuildProperty(String key) {
        try (var in = AppInfo.class.getResourceAsStream("/nekylauncher/build.properties")) {
            if (in == null) return "";
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty(key, "").trim();
            return v.startsWith("${") ? "" : v;
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static String readVersion() {
        String v = AppInfo.class.getPackage() != null ? AppInfo.class.getPackage().getImplementationVersion() : null;
        return v != null ? v : "1.0.0";
    }
}
