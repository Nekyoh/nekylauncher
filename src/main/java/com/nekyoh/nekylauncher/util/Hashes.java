package com.nekyoh.nekylauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}

    public static MessageDigest digest(String algo) {
        try {
            return MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) { return HexFormat.of().formatHex(b); }

    public static String file(Path p, String algo) throws IOException {
        MessageDigest md = digest(algo);
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(p)) {
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        return hex(md.digest());
    }

    public static String sha1(Path p) throws IOException { return file(p, "SHA-1"); }

    public static String sha1(byte[] data) { return hex(digest("SHA-1").digest(data)); }

    /** True when the file exists and matches the given size (if > 0) and sha1 (if non-null). */
    public static boolean matches(Path p, String sha1, long size) {
        try {
            if (!Files.isRegularFile(p)) return false;
            if (size > 0 && Files.size(p) != size) return false;
            if (sha1 == null || sha1.isBlank()) return true;
            return sha1.equalsIgnoreCase(sha1(p));
        } catch (IOException e) {
            return false;
        }
    }
}
