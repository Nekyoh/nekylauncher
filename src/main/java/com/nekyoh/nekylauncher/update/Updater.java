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
                // installed copies are updated by swapping files too (no installer UI, any drive)
                case WINDOWS_INSTALLER, WINDOWS_PORTABLE -> n.endsWith("windows-x64.zip");
                case LINUX_DEB -> n.endsWith("_amd64.deb") || n.endsWith(".deb");
                case LINUX_PORTABLE -> n.endsWith("linux-x64.tar.gz");
                case DEV -> false;
            };
            if (ok && a.url() != null) return a;
        }
        return null;
    }

    /**
     * Whether this copy can update itself: a packaged build, a matching file in the release and a
     * data folder that the file swap would not touch. Where the launcher is installed (C:, D:, a
     * protected folder…) doesn't matter: protected folders just ask for administrator rights.
     */
    public static boolean canSelfUpdate(Release r, Path dataRoot) {
        Dist d = dist();
        if (d == Dist.DEV || assetFor(r, d) == null) return false;
        if (d == Dist.LINUX_DEB) return true;
        Path app = Dirs.appLocation();
        if (app == null) return false;
        for (String part : replacedParts(d)) {
            if (dataRoot != null && dataRoot.toAbsolutePath().normalize().startsWith(app.resolve(part).toAbsolutePath().normalize())) return false;
        }
        return true;
    }

    /** Folders of the program that are replaced; everything else (data/, portable.txt) is kept. */
    static List<String> replacedParts(Dist d) {
        return d.windows() ? List.of("app", "runtime") : List.of("bin", "lib");
    }

    /**
     * True when the program folder can't be written by this user (e.g. Program Files, /opt, a folder
     * created by another account): the swap then runs with administrator rights.
     */
    public static boolean needsAdmin(Dist d) {
        if (d == Dist.LINUX_DEB) return true;
        Path app = Dirs.appLocation();
        if (app == null) return true;
        for (Path dir : List.of(app, app.resolve(replacedParts(d).get(1)))) {
            // Files.isWritable is unreliable with Windows ACLs: actually try
            try {
                Path probe = Files.createTempFile(dir, ".neky-write", ".tmp");
                Files.deleteIfExists(probe);
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
        return false;
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
        boolean admin = needsAdmin(d);
        switch (d) {
            case WINDOWS_INSTALLER, WINDOWS_PORTABLE -> {
                Path staging = cleanDir(workDir.resolve("staging"));
                try (InputStream in = Files.newInputStream(file)) {
                    FileUtil.unzip(in, staging, null, null);
                }
                Path src = findRoot(staging, "NekyLauncher.exe");
                writePowerShell(workDir.resolve("swap.ps1"), windowsSwapScript(src, app, d == Dist.WINDOWS_INSTALLER));
                runPowerShell(workDir, log, windowsUpdateScript(pid, workDir.resolve("swap.ps1"), app, admin,
                        d == Dist.WINDOWS_INSTALLER ? versionOf(file) : null));
            }
            case LINUX_DEB -> runShell(workDir, log, linuxDebScript(pid, file, exe));
            case LINUX_PORTABLE -> {
                Path staging = cleanDir(workDir.resolve("staging"));
                try (InputStream in = Files.newInputStream(file)) {
                    Tar.extractTarGz(in, staging, 0);
                }
                Path src = findRoot(staging, "bin/NekyLauncher");
                runShell(workDir, log, linuxPortableScript(pid, src, app, admin));
            }
            case DEV -> throw new IOException("Not a packaged build");
        }
    }

    /** Version of the downloaded update, from the build.properties / manifest inside its jar ("" if unknown). */
    private static String versionOf(Path zip) {
        try (var zf = new java.util.zip.ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                if (!e.getName().replace('\\', '/').endsWith("app/NekyLauncher.jar")) continue;
                try (var jar = new java.util.jar.JarInputStream(zf.getInputStream(e))) {
                    var mf = jar.getManifest();
                    String v = mf == null ? null : mf.getMainAttributes().getValue("Implementation-Version");
                    return v == null ? "" : v;
                }
            }
        } catch (IOException ignored) { }
        return "";
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

    /**
     * Replaces app/ and runtime/ (and the .exe) with the new ones. Data, portable.txt and anything
     * else in the folder stay. An installed copy keeps its "installed" marker so it stays recognised.
     */
    public static String windowsSwapScript(Path src, Path app, boolean installedCopy) {
        List<String> l = new ArrayList<>(List.of(
                "$ErrorActionPreference = 'Continue'",
                "$app = " + ps(app),
                "$src = " + ps(src),
                "foreach ($n in 'app', 'runtime') {",
                "  $p = Join-Path $app $n",
                "  for ($i = 0; $i -lt 20 -and (Test-Path -LiteralPath $p); $i++) {",
                "    Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue",
                "    if (Test-Path -LiteralPath $p) { Start-Sleep -Milliseconds 500 }",
                "  }",
                "}",
                "Get-ChildItem -LiteralPath $src -Force | Copy-Item -Destination $app -Recurse -Force"));
        if (installedCopy) {
            l.add("$cfg = Join-Path $app 'app\\NekyLauncher.cfg'");
            // rewrite without a BOM: the jpackage launcher reads this file as plain text
            l.add("if (Test-Path -LiteralPath $cfg) { $t = [IO.File]::ReadAllText($cfg).Replace('neky.dist=windows-portable', 'neky.dist=windows-installer'); [IO.File]::WriteAllText($cfg, $t, (New-Object Text.UTF8Encoding $false)) }");
        }
        l.add("");
        return String.join("\r\n", l);
    }

    /**
     * Runs as the user: waits for the launcher to exit, runs the swap (elevated through UAC when the
     * folder is protected), refreshes the version shown in Windows "Apps" and reopens the launcher
     * (never elevated). If the UAC prompt is refused nothing changes and the old version reopens.
     */
    public static String windowsUpdateScript(long pid, Path swap, Path app, boolean admin, String newVersion) {
        List<String> l = new ArrayList<>(List.of(
                "$ErrorActionPreference = 'Continue'",
                "Wait-Process -Id " + pid + " -Timeout 120 -ErrorAction SilentlyContinue",
                "Start-Sleep -Milliseconds 300",
                "$swap = " + ps(swap),
                "$ok = $true"));
        if (admin) {
            l.add("try {");
            l.add("  Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -WindowStyle Hidden -ErrorAction Stop -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('\"' + $swap + '\"'))");
            l.add("} catch { $ok = $false; Write-Output \"Elevation refused: $_\" }");
        } else {
            l.add("& $swap");
        }
        if (newVersion != null && !newVersion.isBlank()) {
            l.add("if ($ok) {");
            l.add("Get-ChildItem 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall', 'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall' -ErrorAction SilentlyContinue |");
            l.add("  Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue).DisplayName -eq 'NekyLauncher' } |");
            l.add("  ForEach-Object { Set-ItemProperty -LiteralPath $_.PSPath -Name DisplayVersion -Value " + ps(newVersion) + " -ErrorAction SilentlyContinue }");
            l.add("}");
        }
        l.add("Start-Process -FilePath (Join-Path " + ps(app) + " 'NekyLauncher.exe')");
        l.add("");
        return String.join("\r\n", l);
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

    public static String linuxPortableScript(long pid, Path src, Path app, boolean admin) {
        String swap = "rm -rf \"$1/bin\" \"$1/lib\" && cp -a \"$2/.\" \"$1/\" && chmod +x \"$1/bin/NekyLauncher\"";
        return String.join("\n",
                "#!/bin/sh",
                "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done",
                "APP=" + sh(app),
                "SRC=" + sh(src),
                (admin ? "pkexec " : "") + "sh -c " + sh(swap) + " sh \"$APP\" \"$SRC\"",
                "nohup \"$APP/bin/NekyLauncher\" >/dev/null 2>&1 &",
                "");
    }

    /** UTF-8 with BOM: Windows PowerShell 5 reads BOM-less scripts as ANSI and breaks accented paths. */
    private static void writePowerShell(Path f, String script) throws IOException {
        Files.writeString(f, "\uFEFF" + script, StandardCharsets.UTF_8);
    }

    private static void runPowerShell(Path workDir, Path log, String script) throws IOException {
        Path f = workDir.resolve("update.ps1");
        writePowerShell(f, script);
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
