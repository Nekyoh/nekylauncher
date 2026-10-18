package com.nekyoh.nekylauncher.core;

import com.nekyoh.nekylauncher.util.OS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Launcher data layout. Libraries, assets, versions and runtimes are shared by every instance
 * (like Prism/XMCL); each instance only owns its own game directory.
 */
public final class Dirs {
    /** How the data folder was chosen. Only DEFAULT and CUSTOM can be changed from the UI. */
    public enum Source { DEFAULT, CUSTOM, PORTABLE, OVERRIDE }

    /** Small text file in the default folder that points to a user-chosen data folder. */
    public static final String POINTER = "datadir.txt";

    public final Path root;
    public final Source source;
    /** Set when a custom folder was configured but could not be used at startup (shown to the user). */
    public static volatile String pointerProblem;

    public Dirs(Path root) { this(root, Source.OVERRIDE); }

    public Dirs(Path root, Source source) {
        this.root = root.toAbsolutePath().normalize();
        this.source = source;
    }

    /**
     * Resolution order: -Dnekylauncher.dir / NEKYLAUNCHER_DIR, portable mode ("portable.txt" next to
     * the launcher), a custom folder chosen in Settings (pointer file), then the platform default
     * (%APPDATA%\.nekylauncher on Windows, $XDG_DATA_HOME/nekylauncher on Linux).
     */
    public static Dirs detect() {
        String override = System.getProperty("nekylauncher.dir", System.getenv("NEKYLAUNCHER_DIR"));
        if (override != null && !override.isBlank()) return new Dirs(Path.of(override), Source.OVERRIDE);
        Path appDir = appLocation();
        if (appDir != null && Files.exists(appDir.resolve("portable.txt"))) return new Dirs(appDir.resolve("data"), Source.PORTABLE);
        Path def = defaultRoot();
        Path custom = readPointer(def);
        if (custom != null) return new Dirs(custom, Source.CUSTOM);
        return new Dirs(def, Source.DEFAULT);
    }

    /** The platform default data folder (where the pointer file lives). */
    public static Path defaultRoot() {
        if (OS.isWindows()) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null ? Path.of(appData) : OS.home().resolve("AppData/Roaming");
            return base.resolve(".nekylauncher");
        }
        if (OS.isMac()) return OS.home().resolve("Library/Application Support/NekyLauncher");
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : OS.home().resolve(".local/share");
        return base.resolve("nekylauncher");
    }

    static Path readPointer(Path defaultRoot) {
        Path f = defaultRoot.resolve(POINTER);
        if (!Files.isRegularFile(f)) return null;
        try {
            String s = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return null;
            Path p = Path.of(s);
            if (!p.isAbsolute()) return null;
            Files.createDirectories(p); // e.g. external drive re-mounted: fails if it is gone
            return p;
        } catch (Exception e) {
            // folder unreachable (unplugged drive, bad path): fall back to the default
            pointerProblem = readQuietly(f);
            return null;
        }
    }

    private static String readQuietly(Path f) {
        try {
            return Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return f.toString();
        }
    }

    /** Points future launches at {@code target}; {@code null} (or the default itself) restores the default. */
    public static void writePointer(Path target) throws IOException {
        Path def = defaultRoot();
        Path f = def.resolve(POINTER);
        if (target == null || target.toAbsolutePath().normalize().equals(def.toAbsolutePath().normalize())) {
            Files.deleteIfExists(f);
            return;
        }
        Files.createDirectories(def);
        Files.writeString(f, target.toAbsolutePath().normalize().toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isChangeable() { return source == Source.DEFAULT || source == Source.CUSTOM; }

    private static Path appLocation() {
        try {
            Path p = Path.of(Dirs.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }

    public void ensure() throws IOException {
        for (Path p : new Path[]{root, instances(), libraries(), assets(), versions(), runtimes(), cache(), logs()}) {
            Files.createDirectories(p);
        }
    }

    public Path instances() { return root.resolve("instances"); }
    public Path libraries() { return root.resolve("libraries"); }
    public Path assets() { return root.resolve("assets"); }
    public Path versions() { return root.resolve("versions"); }
    public Path runtimes() { return root.resolve("runtimes"); }
    public Path cache() { return root.resolve("cache"); }
    public Path logs() { return root.resolve("logs"); }
    public Path settingsFile() { return root.resolve("settings.json"); }
    public Path accountsFile() { return root.resolve("accounts.json"); }

    public Path versionJson(String id) { return versions().resolve(id).resolve(id + ".json"); }
    public Path versionJar(String id) { return versions().resolve(id).resolve(id + ".jar"); }
}
