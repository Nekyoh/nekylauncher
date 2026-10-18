package com.nekyoh.nekylauncher.java;

import java.nio.file.Path;

/**
 * A Java runtime on disk.
 *
 * @param executable java(.exe) or javaw.exe used to start the game
 * @param version    full version string, e.g. "17.0.8" or "1.8.0_51"
 * @param major      feature version (8, 17, 21...)
 * @param arch       os.arch reported by the runtime, may be empty
 * @param source     where it was found
 */
public record JavaInstall(Path executable, String version, int major, String arch, Source source) {
    public enum Source { MANAGED, SYSTEM, OFFICIAL_LAUNCHER, CUSTOM }

    public Path home() {
        Path bin = executable.getParent();
        return bin != null && bin.getParent() != null ? bin.getParent() : executable;
    }

    public boolean is64Bit() { return arch == null || arch.isEmpty() || arch.contains("64"); }

    public static int parseMajor(String version) {
        if (version == null || version.isBlank()) return 0;
        String v = version.trim();
        if (v.startsWith("1.")) v = v.substring(2);
        StringBuilder num = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (Character.isDigit(c)) num.append(c);
            else break;
        }
        try {
            return num.isEmpty() ? 0 : Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
