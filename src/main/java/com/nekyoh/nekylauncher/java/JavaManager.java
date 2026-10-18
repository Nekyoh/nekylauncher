package com.nekyoh.nekylauncher.java;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.game.Downloader;
import com.nekyoh.nekylauncher.game.VersionProfile;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Finds installed Java runtimes (system, official Minecraft launcher, custom) and installs the exact
 * runtime each Minecraft version asks for, from Mojang's own runtime index (same as the official
 * launcher), falling back to Eclipse Temurin when Mojang has no build for this platform.
 */
public final class JavaManager {
    public static final String MOJANG_RUNTIME_INDEX =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private static final String MARKER = ".neky-runtime.json";

    private final Dirs dirs;
    private final Settings settings;
    private volatile List<JavaInstall> detected;

    public JavaManager(Dirs dirs, Settings settings) {
        this.dirs = dirs;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ detection

    public synchronized List<JavaInstall> detect(boolean refresh) {
        if (detected != null && !refresh) return detected;
        Map<Path, JavaInstall.Source> candidates = new LinkedHashMap<>();
        for (Path p : findExecutables(dirs.runtimes(), 6)) candidates.putIfAbsent(p, JavaInstall.Source.MANAGED);
        for (Path root : officialLauncherRuntimeRoots()) {
            for (Path p : findExecutables(root, 7)) candidates.putIfAbsent(p, JavaInstall.Source.OFFICIAL_LAUNCHER);
        }
        for (String s : settings.extraJavaPaths) {
            try {
                Path p = Path.of(s);
                if (Files.isRegularFile(p)) candidates.putIfAbsent(p, JavaInstall.Source.CUSTOM);
            } catch (Exception ignored) { }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path p = Path.of(javaHome, "bin", OS.exe("java"));
            if (Files.isRegularFile(p)) candidates.putIfAbsent(p, JavaInstall.Source.SYSTEM);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) {
                try {
                    Path p = Path.of(d.trim().replace("\"", ""), OS.exe("java"));
                    if (Files.isRegularFile(p)) candidates.putIfAbsent(p, JavaInstall.Source.SYSTEM);
                } catch (Exception ignored) { }
            }
        }
        for (Path root : systemRoots()) {
            for (Path p : findExecutables(root, 4)) candidates.putIfAbsent(p, JavaInstall.Source.SYSTEM);
        }

        Map<Path, JavaInstall> byReal = new LinkedHashMap<>();
        List<Thread> threads = new ArrayList<>();
        List<JavaInstall> results = Collections.synchronizedList(new ArrayList<>());
        for (var e : candidates.entrySet()) {
            threads.add(Thread.ofVirtual().start(() -> {
                JavaInstall j = probe(e.getKey(), e.getValue());
                if (j != null) results.add(j);
            }));
        }
        for (Thread t : threads) {
            try { t.join(15000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        // keep candidate order (managed first), dedupe by real path
        List<JavaInstall> ordered = new ArrayList<>(results);
        List<Path> order = new ArrayList<>(candidates.keySet());
        ordered.sort(Comparator.comparingInt(j -> order.indexOf(j.executable())));
        for (JavaInstall j : ordered) {
            Path real;
            try { real = j.executable().toRealPath().getParent(); } catch (IOException ex) { real = j.home(); }
            byReal.putIfAbsent(real, j);
        }
        List<JavaInstall> list = new ArrayList<>(byReal.values());
        list.sort(Comparator.comparing((JavaInstall j) -> j.source().ordinal()).thenComparing(j -> -j.major()));
        detected = Collections.unmodifiableList(list);
        Log.info("Detected " + list.size() + " Java runtime(s)");
        return detected;
    }

    private static List<Path> officialLauncherRuntimeRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(OS.officialMinecraftDir().resolve("runtime"));
        if (OS.isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) roots.add(Path.of(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"));
            String pf86 = System.getenv("ProgramFiles(x86)");
            if (pf86 != null) roots.add(Path.of(pf86, "Minecraft Launcher", "runtime"));
            roots.add(Path.of("C:\\XboxGames\\Minecraft Launcher\\Content\\runtime"));
        }
        return roots;
    }

    private static List<Path> systemRoots() {
        List<Path> roots = new ArrayList<>();
        Path home = OS.home();
        roots.add(home.resolve(".jdks"));
        roots.add(home.resolve(".gradle").resolve("jdks"));
        if (OS.isWindows()) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
                String pf = System.getenv(env);
                if (pf == null) continue;
                try (Stream<Path> s = Files.list(Path.of(pf))) {
                    s.filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .matches(".*(java|jdk|jre|adopt|zulu|corretto|microsoft|bellsoft|semeru|liberica|openjdk|graalvm|temurin|redhat|eclipse).*"))
                            .forEach(roots::add);
                } catch (IOException ignored) { }
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/usr/lib64/jvm"));
            roots.add(Path.of("/usr/java"));
            roots.add(Path.of("/opt"));
            roots.add(home.resolve(".sdkman/candidates/java"));
        }
        return roots;
    }

    private static List<Path> findExecutables(Path root, int depth) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        String exe = OS.exe("java");
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && p.getFileName().toString().equals(exe)
                            && p.getParent() != null && p.getParent().getFileName() != null
                            && p.getParent().getFileName().toString().equals("bin")) {
                        found.add(p);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE; // unreadable folder: skip it
                }
            });
        } catch (IOException ignored) { }
        return found;
    }

    /** Reads the runtime's version (release file first, then by running it). Accepts java, java.exe or javaw.exe. */
    public JavaInstall probe(Path executable, JavaInstall.Source source) {
        try {
            Path exe = executable;
            String fn = exe.getFileName().toString().toLowerCase(Locale.ROOT);
            Path console = fn.startsWith("javaw") ? exe.resolveSibling(OS.exe("java")) : exe;
            Path home = exe.getParent() != null ? exe.getParent().getParent() : null;
            String version = null, arch = "";
            if (home != null && Files.isRegularFile(home.resolve("release"))) {
                for (String line : Files.readAllLines(home.resolve("release"), StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim().replace("\"", "");
                    if (k.equals("JAVA_VERSION")) version = v;
                    if (k.equals("OS_ARCH")) arch = v;
                }
            }
            if (version == null && Files.isRegularFile(console)) {
                Process pr = new ProcessBuilder(console.toString(), "-XshowSettings:properties", "-version")
                        .redirectErrorStream(true).start();
                String out;
                try (InputStream in = pr.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!pr.waitFor(10, TimeUnit.SECONDS)) pr.destroyForcibly();
                for (String line : out.split("\\R")) {
                    String t = line.trim();
                    if (t.startsWith("java.version =")) version = t.substring(t.indexOf('=') + 1).trim();
                    if (t.startsWith("os.arch =")) arch = t.substring(t.indexOf('=') + 1).trim();
                }
            }
            if (version == null) return null;
            return new JavaInstall(preferredLauncher(exe), version, JavaInstall.parseMajor(version), arch, source);
        } catch (Exception e) {
            Log.warn("Could not probe Java at " + executable + ": " + e.getMessage());
            return null;
        }
    }

    /** On Windows use javaw.exe so no console window pops up next to the game. */
    public static Path preferredLauncher(Path exe) {
        if (!OS.isWindows()) return exe;
        Path w = exe.resolveSibling("javaw.exe");
        return Files.isRegularFile(w) ? w : exe;
    }

    // ------------------------------------------------------------------ managed runtimes

    public Path managedDir(String component) { return dirs.runtimes().resolve(component); }

    public JavaInstall findManaged(String component) {
        Path dir = managedDir(component);
        if (!Files.isRegularFile(dir.resolve(MARKER))) return null;
        List<Path> exes = findExecutables(dir, 6);
        if (exes.isEmpty()) return null;
        return probe(exes.get(0), JavaInstall.Source.MANAGED);
    }

    public JavaInstall installManaged(String component, int major, Progress progress, CancelToken cancel) throws IOException {
        progress.stage(I18n.tr("progress.downloadingJava", major));
        progress.fraction(-1);
        JObject index;
        Path cache = dirs.cache().resolve("java_runtimes.json");
        try {
            String body = Http.getString(MOJANG_RUNTIME_INDEX);
            FileUtil.write(cache, body);
            index = JObject.parse(body);
        } catch (IOException e) {
            if (!Files.exists(cache)) throw e;
            index = JObject.parse(FileUtil.read(cache));
        }
        JObject platform = index.obj(OS.javaRuntimePlatform());
        JArray builds = platform != null ? platform.arr(component) : null;
        if (builds == null || builds.isEmpty()) {
            Log.info("Mojang has no " + component + " for " + OS.javaRuntimePlatform() + ", using Temurin " + major);
            return installTemurin(major, progress, cancel);
        }
        JObject build = builds.obj(0);
        JObject manifestRef = build.objOrEmpty("manifest");
        JObject manifest = JObject.parse(Http.getString(manifestRef.str("url")));
        Path dir = managedDir(component);
        FileUtil.deleteRecursive(dir);
        Files.createDirectories(dir);

        JObject files = manifest.objOrEmpty("files");
        List<Downloader.Task> tasks = new ArrayList<>();
        List<Path> executables = new ArrayList<>();
        Map<Path, String> links = new LinkedHashMap<>();
        for (String rel : files.keys()) {
            JObject f = files.obj(rel);
            Path target = FileUtil.safeResolve(dir, rel);
            switch (f.str("type", "file")) {
                case "directory" -> Files.createDirectories(target);
                case "link" -> links.put(target, f.str("target"));
                default -> {
                    JObject raw = f.objOrEmpty("downloads").obj("raw");
                    if (raw == null) continue;
                    tasks.add(Downloader.Task.of(raw.str("url"), target, raw.str("sha1"), raw.lng("size", 0)));
                    if (f.bool("executable", false)) executables.add(target);
                }
            }
        }
        Downloader.run(tasks, settings.concurrentDownloads, progress, cancel);
        for (Path p : executables) Tar.makeExecutable(p);
        if (!OS.isWindows()) {
            for (var l : links.entrySet()) {
                try {
                    Files.createDirectories(l.getKey().getParent());
                    Files.deleteIfExists(l.getKey());
                    Files.createSymbolicLink(l.getKey(), Path.of(l.getValue()));
                } catch (Exception e) {
                    Log.warn("Runtime link failed " + l.getKey() + ": " + e.getMessage());
                }
            }
        }
        FileUtil.write(dir.resolve(MARKER), new JObject()
                .put("component", component)
                .put("version", build.objOrEmpty("version").str("name", ""))
                .put("manifestSha1", manifestRef.str("sha1", "")).toJson(true));
        detected = null;
        JavaInstall j = findManaged(component);
        if (j == null) throw new IOException("Java runtime installed but no executable found in " + dir);
        return j;
    }

    /** Eclipse Temurin JRE from the Adoptium API (used for platforms Mojang does not cover). */
    public JavaInstall installTemurin(int major, Progress progress, CancelToken cancel) throws IOException {
        int feature = major == 16 ? 17 : Math.max(8, major);
        String os = OS.isWindows() ? "windows" : OS.isMac() ? "mac" : "linux";
        String arch = OS.isArm() ? (OS.is64Bit() ? "aarch64" : "arm") : (OS.is64Bit() ? "x64" : "x32");
        String component = "temurin-" + feature;
        Path dir = managedDir(component);
        if (findManaged(component) != null) return findManaged(component);
        String url = "https://api.adoptium.net/v3/binary/latest/" + feature + "/ga/" + os + "/" + arch + "/jre/hotspot/normal/eclipse?project=jdk";
        Path archive = dirs.cache().resolve(component + (OS.isWindows() ? ".zip" : ".tar.gz"));
        progress.stage(I18n.tr("progress.downloadingJava", feature));
        Http.download(url, archive, null, null, cancel);
        progress.stage(I18n.tr("progress.extracting"));
        FileUtil.deleteRecursive(dir);
        Files.createDirectories(dir);
        try (InputStream in = Files.newInputStream(archive)) {
            if (OS.isWindows()) {
                FileUtil.unzip(in, dir, null, null);
                // strip single top-level folder
                try (Stream<Path> s = Files.list(dir)) {
                    List<Path> top = s.toList();
                    if (top.size() == 1 && Files.isDirectory(top.get(0))) {
                        Path inner = top.get(0);
                        FileUtil.copyRecursive(inner, dir);
                        FileUtil.deleteRecursive(inner);
                    }
                }
            } else {
                Tar.extractTarGz(in, dir, 1);
            }
        }
        Files.deleteIfExists(archive);
        FileUtil.write(dir.resolve(MARKER), new JObject().put("component", component).put("version", "temurin-" + feature).toJson(true));
        detected = null;
        JavaInstall j = findManaged(component);
        if (j == null) throw new IOException("Temurin installed but no executable found");
        return j;
    }

    // ------------------------------------------------------------------ selection

    /**
     * Picks the Java for a profile: the instance's explicit choice, else the managed runtime
     * (installing it when allowed), else the best installed match.
     */
    public JavaInstall resolveFor(VersionProfile profile, String customPath, Progress progress, CancelToken cancel) throws IOException {
        if (customPath != null && !customPath.isBlank()) {
            Path p = Path.of(customPath.trim());
            if (Files.isDirectory(p)) p = p.resolve("bin").resolve(OS.exe("java"));
            JavaInstall j = probe(p, JavaInstall.Source.CUSTOM);
            if (j == null) throw new IOException(I18n.tr("error.javaInvalid", customPath));
            return j;
        }
        String component = profile.runtimeComponent();
        int major = profile.requiredJava();
        JavaInstall managed = findManaged(component);
        if (managed != null) return managed;
        if (settings.autoDownloadJava) {
            try {
                return installManaged(component, major, progress, cancel);
            } catch (CancelToken.CancelledException e) {
                throw e;
            } catch (IOException e) {
                // Mojang's runtime service unreachable or broken: try Temurin, then anything installed
                Log.warn("Mojang Java runtime failed (" + e.getMessage() + "), trying Temurin " + major);
                try {
                    return installTemurin(major, progress, cancel);
                } catch (CancelToken.CancelledException ce) {
                    throw ce;
                } catch (IOException e2) {
                    JavaInstall fallback = pickInstalled(major);
                    if (fallback != null) return fallback;
                    throw e;
                }
            }
        }
        JavaInstall best = pickInstalled(major);
        if (best == null) throw new IOException(I18n.tr("error.noJava", major));
        return best;
    }

    public JavaInstall pickInstalled(int major) {
        JavaInstall best = null;
        for (JavaInstall j : detect(false)) {
            if (!j.is64Bit() && OS.is64Bit()) continue;
            if (j.major() == major) return j;
            boolean acceptable = major <= 8 ? j.major() == 8 : j.major() > major;
            if (acceptable && (best == null || j.major() < best.major())) best = j;
        }
        return best;
    }
}
