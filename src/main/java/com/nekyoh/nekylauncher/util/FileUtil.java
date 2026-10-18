package com.nekyoh.nekylauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class FileUtil {
    private FileUtil() {}

    public static String read(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8); }

    /** Atomic-ish text write (temp + move) so a crash never leaves half-written config. */
    public static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) {
            Files.delete(p);
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    public static void copyRecursive(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            Files.createDirectories(dst.toAbsolutePath().getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long sizeOf(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(x -> {
                try { return Files.size(x); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    /** Folder-name-safe version of an arbitrary display name. */
    public static String safeName(String name) {
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ");
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "instance";
        if (s.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)")) s = "_" + s;
        return s.length() > 64 ? s.substring(0, 64).trim() : s;
    }

    /** Resolves a zip/archive entry name under {@code root}, rejecting path traversal ("zip slip"). */
    public static Path safeResolve(Path root, String entryName) throws IOException {
        String n = entryName.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        Path r = root.toAbsolutePath().normalize();
        Path out = r.resolve(n).normalize();
        if (!out.startsWith(r) || n.contains(":")) throw new IOException("Unsafe path in archive: " + entryName);
        return out;
    }

    /** Extracts every entry under {@code prefix} (stripping it) into {@code dest}. */
    public static int unzip(InputStream raw, Path dest, String prefix, Predicate<String> filter) throws IOException {
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (prefix != null) {
                    if (!name.startsWith(prefix)) continue;
                    name = name.substring(prefix.length());
                }
                if (name.isEmpty() || (filter != null && !filter.test(name))) continue;
                Path out = safeResolve(dest, name);
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        }
        return count;
    }

    /** Adds a file or folder tree to an open zip stream at {@code base}. */
    public static void zipAdd(ZipOutputStream zos, Path src, String base, Predicate<Path> include) throws IOException {
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.sorted().toList()) {
                    if (!include.test(p)) continue;
                    String rel = src.relativize(p).toString().replace('\\', '/');
                    String name = rel.isEmpty() ? base : base + "/" + rel;
                    if (Files.isDirectory(p)) {
                        zos.putNextEntry(new ZipEntry(name + "/"));
                        zos.closeEntry();
                    } else if (Files.isRegularFile(p)) {
                        putFile(zos, p, name);
                    }
                }
            }
        } else if (Files.isRegularFile(src) && include.test(src)) {
            putFile(zos, src, base);
        }
    }

    private static void putFile(ZipOutputStream zos, Path p, String name) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(Files.getLastModifiedTime(p).toMillis());
        zos.putNextEntry(ze);
        try (InputStream in = Files.newInputStream(p)) {
            in.transferTo(zos);
        } catch (FileSystemException locked) {
            // e.g. session.lock held by a running game: skip silently
            Log.warn("Skipped locked file " + p);
        }
        zos.closeEntry();
    }

    public static void copyStream(InputStream in, OutputStream out) throws IOException { in.transferTo(out); }

    public static String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Picks "name", "name (2)", ... until the path does not exist. */
    public static Path uniquePath(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return p;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && !Files.isDirectory(p)) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; ; i++) {
            Path c = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(c)) return c;
        }
    }
}
