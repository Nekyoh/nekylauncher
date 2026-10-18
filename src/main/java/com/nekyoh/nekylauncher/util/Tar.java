package com.nekyoh.nekylauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Minimal .tar.gz extractor (ustar, GNU long names, pax paths, symlinks, exec bits). */
public final class Tar {
    private Tar() {}

    public static void extractTarGz(InputStream raw, Path dest, int stripComponents) throws IOException {
        try (InputStream in = new GZIPInputStream(raw, 64 * 1024)) {
            byte[] header = new byte[512];
            String longName = null;
            String longLink = null;
            while (true) {
                if (!readFully(in, header)) break;
                if (isZero(header)) break;
                String name = cstr(header, 0, 100);
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty() && longName == null) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                char type = (char) header[156];
                String link = cstr(header, 157, 100);

                if (type == 'L' || type == 'K') {
                    String s = new String(readBytes(in, size), StandardCharsets.UTF_8);
                    s = s.replace("\0", "");
                    if (type == 'L') longName = s; else longLink = s;
                    skipPadding(in, size);
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    String pax = new String(readBytes(in, size), StandardCharsets.UTF_8);
                    skipPadding(in, size);
                    if (type == 'x') {
                        for (String line : pax.split("\n")) {
                            int sp = line.indexOf(' ');
                            if (sp < 0) continue;
                            String kv = line.substring(sp + 1);
                            if (kv.startsWith("path=")) longName = kv.substring(5);
                            else if (kv.startsWith("linkpath=")) longLink = kv.substring(9);
                        }
                    }
                    continue;
                }
                if (longName != null) { name = longName; longName = null; }
                if (longLink != null) { link = longLink; longLink = null; }

                String rel = strip(name, stripComponents);
                if (rel == null || rel.isEmpty()) {
                    skip(in, size);
                    skipPadding(in, size);
                    continue;
                }
                Path out = FileUtil.safeResolve(dest, rel);
                switch (type) {
                    case '5' -> Files.createDirectories(out);
                    case '2' -> {
                        Files.createDirectories(out.getParent());
                        try {
                            Files.deleteIfExists(out);
                            Files.createSymbolicLink(out, Path.of(link));
                        } catch (UnsupportedOperationException | IOException e) {
                            Log.warn("Skipping symlink " + rel + ": " + e.getMessage());
                        }
                    }
                    case '0', '\0', '7' -> {
                        Files.createDirectories(out.getParent());
                        try (var os = Files.newOutputStream(out)) {
                            long left = size;
                            byte[] buf = new byte[64 * 1024];
                            while (left > 0) {
                                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                                if (n < 0) throw new IOException("Truncated tar");
                                os.write(buf, 0, n);
                                left -= n;
                            }
                        }
                        skipPadding(in, size);
                        if ((mode & 0111) != 0) makeExecutable(out);
                        continue;
                    }
                    default -> { }
                }
                skip(in, size);
                skipPadding(in, size);
            }
        }
    }

    public static void makeExecutable(Path p) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(p));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            p.toFile().setExecutable(true, false);
        }
    }

    private static String strip(String name, int n) {
        String s = name;
        while (s.startsWith("./")) s = s.substring(2);
        for (int i = 0; i < n; i++) {
            int slash = s.indexOf('/');
            if (slash < 0) return null;
            s = s.substring(slash + 1);
        }
        return s;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        if (size > 1 << 20) throw new IOException("Tar header too large");
        byte[] b = new byte[(int) size];
        if (!readFully(in, b)) throw new IOException("Truncated tar");
        return b;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("Truncated tar");
                s = 1;
            }
            left -= s;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skip(in, pad);
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256 (GNU) for big files
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
