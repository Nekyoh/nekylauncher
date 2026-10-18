package com.nekyoh.nekylauncher.util;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Platform detection and desktop integration. */
public final class OS {
    private OS() {}

    public enum Platform { WINDOWS, LINUX, MACOS, OTHER }

    public static final Platform PLATFORM = detect();
    public static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    public static final String VERSION = System.getProperty("os.version", "");

    private static Platform detect() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return Platform.WINDOWS;
        if (n.contains("mac") || n.contains("darwin")) return Platform.MACOS;
        if (n.contains("linux") || n.contains("nux") || n.contains("bsd")) return Platform.LINUX;
        return Platform.OTHER;
    }

    public static boolean isWindows() { return PLATFORM == Platform.WINDOWS; }

    public static boolean isLinux() { return PLATFORM == Platform.LINUX; }

    public static boolean isMac() { return PLATFORM == Platform.MACOS; }

    public static boolean is64Bit() { return ARCH.contains("64"); }

    public static boolean isArm() { return ARCH.startsWith("arm") || ARCH.startsWith("aarch"); }

    /** OS name as used in Mojang version JSON rules. */
    public static String mojangName() {
        return switch (PLATFORM) {
            case WINDOWS -> "windows";
            case MACOS -> "osx";
            default -> "linux";
        };
    }

    /** Arch as used in Mojang rules ("x86" means 32-bit Intel). */
    public static String mojangArch() {
        if (isArm()) return is64Bit() ? "arm64" : "arm32";
        return is64Bit() ? "x86_64" : "x86";
    }

    /** Platform key in Mojang's java-runtime index (all.json). */
    public static String javaRuntimePlatform() {
        return switch (PLATFORM) {
            case WINDOWS -> isArm() ? "windows-arm64" : (is64Bit() ? "windows-x64" : "windows-x86");
            case MACOS -> isArm() ? "mac-os-arm64" : "mac-os";
            default -> is64Bit() ? (isArm() ? "linux-arm64" : "linux") : "linux-i386";
        };
    }

    public static String exe(String name) { return isWindows() ? name + ".exe" : name; }

    public static String classpathSeparator() { return File.pathSeparator; }

    public static Path home() { return Path.of(System.getProperty("user.home")); }

    /** Default location of the official launcher's .minecraft folder. */
    public static Path officialMinecraftDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home().resolve("AppData/Roaming").toString(), ".minecraft");
        }
        if (isMac()) return home().resolve("Library/Application Support/minecraft");
        return home().resolve(".minecraft");
    }

    public static long totalMemoryMb() {
        try {
            var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) { }
        return 8192;
    }

    /** Opens a folder in the system file manager, creating it if needed. */
    public static void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) { }
        String p = dir.toAbsolutePath().toString();
        try {
            if (isWindows()) { new ProcessBuilder("explorer.exe", p).start(); return; }
            if (isMac()) { new ProcessBuilder("open", p).start(); return; }
            new ProcessBuilder("xdg-open", p).start();
            return;
        } catch (IOException e) {
            Log.warn("Native open failed, trying Desktop API: " + e.getMessage());
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            Log.error("Could not open folder " + p, e);
        }
    }

    /** Selects a file in the file manager when possible, otherwise opens its folder. */
    public static void revealFile(Path file) {
        if (isWindows()) {
            try {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
                return;
            } catch (IOException ignored) { }
        }
        Path parent = file.getParent();
        if (parent != null) openFolder(parent);
    }

    public static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            Log.warn("Desktop browse failed: " + e.getMessage());
        }
        try {
            if (isWindows()) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (isMac()) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (IOException e) {
            Log.error("Could not open " + url, e);
        }
    }

    public static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            Log.warn("Clipboard unavailable: " + e.getMessage());
        }
    }
}
