package com.nekyoh.nekylauncher.instance;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** A mod, resource pack, shader pack or world found in an instance folder. */
public final class ContentItem {
    public enum Kind { MOD, RESOURCEPACK, SHADERPACK, WORLD }

    public final Kind kind;
    public final Path path;
    public final String fileName;
    public final boolean enabled;
    public final long size;
    public final long modified;
    public String title;
    public String version = "";
    public String description = "";
    public String authors = "";
    public BufferedImage icon;

    public ContentItem(Kind kind, Path path, String fileName, boolean enabled, long size, long modified) {
        this.kind = kind;
        this.path = path;
        this.fileName = fileName;
        this.enabled = enabled;
        this.size = size;
        this.modified = modified;
        this.title = displayFromFile(fileName);
    }

    static String displayFromFile(String fn) {
        String s = fn;
        if (s.endsWith(".disabled")) s = s.substring(0, s.length() - 9);
        int dot = s.lastIndexOf('.');
        if (dot > 0 && (s.endsWith(".jar") || s.endsWith(".zip"))) s = s.substring(0, dot);
        return s;
    }
}
