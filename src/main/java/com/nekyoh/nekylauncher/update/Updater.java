package com.nekyoh.nekylauncher.update;

import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.loader.Loaders;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Self-update from GitHub Releases. The release assets built by the CI are:
 * {@code NekyLauncher-<ver>.exe} (Windows installer), {@code NekyLauncher-windows-x64.zip} (Windows portable),
 * {@code nekylauncher_<ver>_amd64.deb} (Linux package) and {@code NekyLauncher-linux-x64.tar.gz} (Linux portable).
 * Each build knows which one it came from ({@code -Dneky.dist=...} set by the packaging scripts) and
 * updates itself with the same kind of file.
 *
 * <p>Files of a running program can't be replaced on Windows, so the last step is a small script
 * that waits for the launcher to close, swaps the files (or runs the installer) and opens the new version.
 */
public final class Updater {
    private Updater() {}

    public enum Dist {
        WINDOWS_INSTALLER, WINDOWS_PORTABLE, LINUX_DEB, LINUX_PORTABLE, /** running from an IDE / plain jar */ DEV;

        public boolean windows() { return this == WINDOWS_INSTALLER || this == WINDOWS_PORTABLE; }
    }

    public record Asset(String name, String url, long size, String sha256) {}

    public record Release(String version, String tag, String title, String notes, String pageUrl, List<Asset> assets) {}

    private static volatile String api = "https://api.github.com/repos/" + AppInfo.GITHUB_REPO + "/releases/latest";

    /** Tests only. */
    public static void setApiForTests(String url) {
        api = url == null ? "https://api.github.com/repos/" + AppInfo.GITHUB_REPO + "/releases/latest" : url;
    }

    // ------------------------------------------------------------------ what is running

    public static Dist dist() {
        String d = System.getProperty("neky.dist", "").trim().toLowerCase(Locale.ROOT);
        switch (d) {
            case "windows-installer": return Dist.WINDOWS_INSTALLER;
            case "windows-portable": return Dist.WINDOWS_PORTABLE;
            case "linux-deb": return Dist.LINUX_DEB;
            case "linux-portable": return Dist.LINUX_PORTABLE;
            default: break;
        }
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null || exe.isBlank()) return Dist.DEV;
        // packaged build without the marker: guess from where it lives
        if (OS.isWindows()) return Dist.WINDOWS_PORTABLE;
        return exe.startsWith("/opt/") ? Dist.LINUX_DEB : Dist.LINUX_PORTABLE;
    }

    // ------------------------------------------------------------------ check

    /** Latest published release (drafts and pre-releases are ignored by GitHub's "latest"). */
    public static Release latest() throws IOException {
        return parse(Http.getJson(api, Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28")));
    }

    static Release parse(JObject j) {
        List<Asset> assets = new ArrayList<>();
        for (JObject a : j.arrOrEmpty("assets").objects()) {
            String digest = a.str("digest", "");
            String sha = digest.toLowerCase(Locale.ROOT).startsWith("sha256:") ? digest.substring(7) : null;
            assets.add(new Asset(a.str("name", ""), a.str("browser_download_url"), a.lng("size", 0), sha));
        }
        String tag = j.str("tag_name", "");
        String ver = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String title = j.str("name", "");
        return new Release(ver, tag, title.isBlank() ? tag : title, j.str("body", ""),
                j.str("html_url", AppInfo.RELEASES_PAGE), assets);
    }

    public static boolean isNewer(String remote, String current) {
        if (remote == null || remote.isBlank() || !Character.isDigit(remote.charAt(0))) return false;
        return Loaders.compareVersions(remote, current) > 0;
    }

    /** The release file that updates this kind of build, or null. */
    public static Asset assetFor(Release r, Dist d) {
        for (Asset a : r.assets()) {
            String n = a.name().toLowerCase(Locale.ROOT);
            boolean ok = switch (d) {
                case WINDOWS_INSTALLER -> n.endsWith(".exe") || n.endsWith(".msi");
                case WINDOWS_PORTABLE -> n.endsWith("windows-x64.zip");
                case LINUX_DEB -> n.endsWith("_amd64.deb") || n.endsWith(".deb");
                case LINUX_PORTABLE -> n.endsWith("linux-x64.tar.gz");
                case DEV -> false;
            };
            if (ok && a.url() != null) return a;
        }
        return null;
    }

    /**
     * Whether this copy can update itself: a packaged build, a matching file in the release and,
     * for portable copies, a writable folder whose data would not be touched by the swap.
     */
    public static boolean canSelfUpdate(Release r, Path dataRoot) {
        Dist d = dist();
        if (d == Dist.DEV || assetFor(r, d) == null) return false;
        if (d == Dist.WINDOWS_PORTABLE || d == Dist.LINUX_PORTABLE) {
            Path app = Dirs.appLocation();
            if (app == null || !Files.isWritable(app)) return false;
            for (String part : replacedParts(d)) {
                if (dataRoot != null && dataRoot.toAbsolutePath().normalize().startsWith(app.resolve(part).toAbsolutePath().normalize())) return false;
            }
        }
        return true;
    }

    /** Folders/files of a portable copy that are replaced; everything else (data/, portable.txt) is kept. */
    static List<String> replacedParts(Dist d) {
        return d == Dist.WINDOWS_PORTABLE ? List.of("app", "runtime") : List.of("bin", "lib");
    }

    // ------------------------------------------------------------------ download

    public static Path download(Asset a, Path dir, Progress progress, CancelToken cancel) throws IOException {
        Files.createDirectories(dir);
        Path target = FileUtil.safeResolve(dir, a.name());
        long total = Math.max(1, a.size());
        long[] got = {0};
        Http.download(a.url(), target, null, n -> {
            got[0] += n;
            progress.fraction(Math.min(1, (double) got[0] / total));
        }, cancel);
        if (a.size() > 0 && Files.size(target) != a.size()) {
            Files.deleteIfExists(target);
            throw new IOException("Size mismatch for " + a.name());
        }
        if (a.sha256() != null && !a.sha256().isBlank()) {
            String sha = Hashes.file(target, "SHA-256");
            if (!sha.equalsIgnoreCase(a.sha256())) {
                Files.deleteIfExists(target);
                throw new IOException("Checksum mismatch for " + a.name());
            }
        }
        return target;
    }

    // ------------------------------------------------------------------ install

    /**
     * Prepares the update and starts the helper script. The caller must exit the launcher right
     * after this returns so the script can do its job.
     */
    public static void install(Path file, Dist d, Path workDir) throws IOException {
        long pid = ProcessHandle.current().pid();
        Path app = Dirs.appLocation();
        String exe = System.getProperty("jpackage.app-path", "");
        Files.createDirectories(workDir);
        Path log = workDir.resolve("update.log");
        switch (d) {
            case WINDOWS_INSTALLER -> runPowerShell(workDir, log, windowsInstallerScript(pid, file, exe));
            case WINDOWS_PORTABLE -> {
                Path staging = cleanDir(workDir.resolve("staging"));
                try (InputStream in = Files.newInputStream(file)) {
                    FileUtil.unzip(in, staging, null, null);
                }
                Path src = findRoot(staging, "NekyLauncher.exe");
                runPowerShell(workDir, log, windowsPortableScript(pid, src, app));
            }
            case LINUX_DEB -> runShell(workDir, log, linuxDebScript(pid, file, exe));
            case LINUX_PORTABLE -> {
                Path staging = cleanDir(workDir.resolve("staging"));
                try (InputStream in = Files.newInputStream(file)) {
                    Tar.extractTarGz(in, staging, 0);
                }
                Path src = findRoot(staging, "bin/NekyLauncher");
                runShell(workDir, log, linuxPortableScript(pid, src, app));
            }
            case DEV -> throw new IOException("Not a packaged build");
        }
    }

    private static Path cleanDir(Path p) throws IOException {
        FileUtil.deleteRecursive(p);
        Files.createDirectories(p);
        return p;
    }

    /** The folder inside the extracted archive that contains {@code marker} (the archive root or one level down). */
    public static Path findRoot(Path staging, String marker) throws IOException {
        if (Files.exists(staging.resolve(marker))) return staging;
        try (var s = Files.list(staging)) {
            for (Path p : s.filter(Files::isDirectory).toList()) if (Files.exists(p.resolve(marker))) return p;
        }
        throw new IOException("Unexpected update package: " + marker + " not found");
    }

    // ------------------------------------------------------------------ helper scripts

    static String ps(Object s) { return "'" + String.valueOf(s).replace("'", "''") + "'"; }

    static String sh(Object s) { return "'" + String.valueOf(s).replace("'", "'\\''") + "'"; }

    public static String windowsInstallerScript(long pid, Path installer, String exe) {
        return String.join("\r\n",
                "$ErrorActionPreference = 'Continue'",
                "Wait-Process -Id " + pid + " -Timeout 120 -ErrorAction SilentlyContinue",
                "Start-Process -FilePath " + ps(installer) + " -Wait",
                "$exe = " + ps(exe),
                "if ($exe -and (Test-Path $exe)) { Start-Process -FilePath $exe }",
                "");
    }

    public static String windowsPortableScript(long pid, Path src, Path app) {
        return String.join("\r\n",
                "$ErrorActionPreference = 'Continue'",
                "Wait-Process -Id " + pid + " -Timeout 120 -ErrorAction SilentlyContinue",
                "$app = " + ps(app),
                "$src = " + ps(src),
                "foreach ($n in 'app', 'runtime') {",
                "  $p = Join-Path $app $n",
                "  for ($i = 0; $i -lt 20 -and (Test-Path $p); $i++) {",
                "    Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue",
                "    if (Test-Path $p) { Start-Sleep -Milliseconds 500 }",
                "  }",
                "}",
                "Copy-Item -Path (Join-Path $src '*') -Destination $app -Recurse -Force",
                "Start-Process -FilePath (Join-Path $app 'NekyLauncher.exe')",
                "");
    }

    public static String linuxDebScript(long pid, Path deb, String exe) {
        return String.join("\n",
                "#!/bin/sh",
                "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done",
                "DEB=" + sh(deb),
                "if command -v pkexec >/dev/null 2>&1; then",
                "  pkexec sh -c 'apt-get install -y \"$1\" || dpkg -i \"$1\"' sh \"$DEB\" || exit 1",
                "else",
                "  xdg-open \"$DEB\"",
                "  exit 0",
                "fi",
                "EXE=" + sh(exe.isBlank() ? "/opt/nekylauncher/bin/NekyLauncher" : exe),
                "[ -x \"$EXE\" ] && nohup \"$EXE\" >/dev/null 2>&1 &",
                "");
    }

    public static String linuxPortableScript(long pid, Path src, Path app) {
        return String.join("\n",
                "#!/bin/sh",
                "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done",
                "APP=" + sh(app),
                "SRC=" + sh(src),
                "rm -rf \"$APP/bin\" \"$APP/lib\"",
                "cp -a \"$SRC/.\" \"$APP/\"",
                "chmod +x \"$APP/bin/NekyLauncher\"",
                "nohup \"$APP/bin/NekyLauncher\" >/dev/null 2>&1 &",
                "");
    }

    private static void runPowerShell(Path workDir, Path log, String script) throws IOException {
        Path f = workDir.resolve("update.ps1");
        Files.writeString(f, script, StandardCharsets.UTF_8);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", f.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void runShell(Path workDir, Path log, String script) throws IOException {
        Path f = workDir.resolve("update.sh");
        Files.writeString(f, script, StandardCharsets.UTF_8);
        Tar.makeExecutable(f);
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(Path.of("/usr/bin/setsid")) || Files.isExecutable(Path.of("/bin/setsid"))) cmd.add("setsid");
        cmd.add("sh");
        cmd.add(f.toString());
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
}
