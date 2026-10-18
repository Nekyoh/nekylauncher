package com.nekyoh.nekylauncher.core;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Changing where NekyLauncher keeps its data: validation, moving the files and restarting. */
public final class DataFolder {
    private DataFolder() {}

    public enum Problem { NONE, SAME, INSIDE_CURRENT, CONTAINS_CURRENT, NOT_WRITABLE }

    /** True if {@code dir} already holds NekyLauncher data (it will be reused instead of overwritten). */
    public static boolean hasLauncherData(Path dir) {
        return Files.isRegularFile(dir.resolve("settings.json")) || Files.isDirectory(dir.resolve("instances"));
    }

    public static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return true;
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A folder picked by the user that already has unrelated files gets a "NekyLauncher" subfolder,
     * so we never mix our data with someone's Documents or Desktop.
     */
    public static Path normalizeTarget(Path picked) {
        Path p = picked.toAbsolutePath().normalize();
        if (isEmptyDir(p) || hasLauncherData(p)) return p;
        return p.resolve("NekyLauncher");
    }

    public static Problem check(Path current, Path target) {
        Path cur = current.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (t.equals(cur)) return Problem.SAME;
        if (t.startsWith(cur)) return Problem.INSIDE_CURRENT;
        if (cur.startsWith(t)) return Problem.CONTAINS_CURRENT;
        try {
            Files.createDirectories(t);
            Path probe = Files.createTempFile(t, ".neky-write-test", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            return Problem.NOT_WRITABLE;
        }
        return Problem.NONE;
    }

    /**
     * Moves everything from {@code from} to {@code to}. Same-drive moves are instant renames;
     * across drives files are copied and then removed from the old place. The pointer file and the
     * currently open launcher log stay behind.
     */
    public static void move(Path from, Path to, Progress progress, CancelToken cancel) throws IOException {
        Files.createDirectories(to);
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(from)) {
            for (Path p : s.toList()) {
                if (p.getFileName().toString().equals(Dirs.POINTER)) continue;
                entries.add(p);
            }
        }
        long total = 0;
        for (Path p : entries) total += Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? FileUtil.sizeOf(p) : Files.size(p);
        AtomicLong done = new AtomicLong();
        final long totalBytes = Math.max(1, total);
        progress.fraction(0);
        List<Path[]> renamed = new ArrayList<>();
        List<Path[]> copied = new ArrayList<>();
        Path inProgress = null;
        try {
            for (Path src : entries) {
                if (cancel != null) cancel.check();
                String name = src.getFileName().toString();
                Path dst = to.resolve(name);
                progress.stage(I18n.tr("datadir.moving", name));
                boolean logs = name.equals("logs");
                if (!logs && !Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        long size = Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS) ? FileUtil.sizeOf(src) : Files.size(src);
                        Files.move(src, dst); // instant rename on the same drive
                        renamed.add(new Path[]{src, dst});
                        progress.fraction((double) done.addAndGet(size) / totalBytes);
                        continue;
                    } catch (IOException ignored) {
                        // another drive or a locked file: copy instead
                    }
                }
                inProgress = dst;
                copyTree(src, dst, done, totalBytes, progress, cancel);
                inProgress = null;
                if (!logs) copied.add(new Path[]{src, dst});
            }
        } catch (IOException | RuntimeException e) {
            // roll back so the current folder stays complete
            progress.stage(I18n.tr("datadir.rollback"));
            if (inProgress != null) deleteQuietly(inProgress);
            for (Path[] c : copied) deleteQuietly(c[1]);
            for (Path[] r : renamed) {
                try {
                    Files.move(r[1], r[0]);
                } catch (IOException ex) {
                    Log.error("Rollback failed for " + r[1] + " -> " + r[0], ex);
                }
            }
            throw e;
        }
        // everything is in place: now remove the copied originals
        for (Path[] c : copied) deleteQuietly(c[0]);
        progress.fraction(1);
    }

    private static void copyTree(Path src, Path dst, AtomicLong done, long total, Progress progress, CancelToken cancel) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (cancel != null) cancel.check();
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path out = dst.resolve(src.relativize(file).toString());
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                try {
                    Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                } catch (FileSystemException locked) {
                    if (!file.getFileName().toString().endsWith(".log")) throw locked;
                    Log.warn("Skipped locked file " + file);
                }
                progress.fraction((double) done.addAndGet(attrs.size()) / total);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path p) {
        try {
            FileUtil.deleteRecursive(p);
        } catch (IOException e) {
            Log.warn("Could not remove old data " + p + ": " + e.getMessage());
        }
    }

    /** Starts a new launcher process (same executable/classpath) and exits this one. */
    public static void restart() {
        List<String> cmd = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path"); // set by jpackage launchers (.exe / bin/NekyLauncher)
        if (appPath != null && Files.exists(Path.of(appPath))) {
            cmd.add(appPath);
        } else {
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path java = OS.isWindows() && Files.exists(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe") : bin.resolve(OS.exe("java"));
            cmd.add(java.toString());
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("com.nekyoh.nekylauncher.NekyLauncher");
        }
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            Log.error("Restart failed, please reopen NekyLauncher manually", e);
        }
        System.exit(0);
    }
}
