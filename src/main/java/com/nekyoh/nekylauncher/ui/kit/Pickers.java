package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.util.OS;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native file dialogs (Explorer on Windows, GTK on Linux). */
public final class Pickers {
    private Pickers() {}

    private static Frame owner() {
        for (Frame f : Frame.getFrames()) if (f.isVisible() && f.isActive()) return f;
        for (Frame f : Frame.getFrames()) if (f.isVisible()) return f;
        return null;
    }

    /** @param extensions lower-case extensions without dot; empty = any */
    public static List<Path> openFiles(String title, boolean multiple, String... extensions) {
        FileDialog d = new FileDialog(owner(), title, FileDialog.LOAD);
        d.setMultipleMode(multiple);
        if (extensions.length > 0) {
            d.setFilenameFilter((dir, name) -> {
                String n = name.toLowerCase(Locale.ROOT);
                for (String e : extensions) if (n.endsWith("." + e)) return true;
                return false;
            });
            if (OS.isWindows()) {
                StringBuilder sb = new StringBuilder();
                for (String e : extensions) sb.append(sb.isEmpty() ? "" : ";").append("*.").append(e);
                d.setFile(sb.toString());
            }
        }
        d.setVisible(true);
        List<Path> out = new ArrayList<>();
        for (File f : d.getFiles()) out.add(f.toPath());
        return out;
    }

    public static Path openFile(String title, String... extensions) {
        List<Path> l = openFiles(title, false, extensions);
        return l.isEmpty() ? null : l.get(0);
    }

    public static Path saveFile(String title, String suggestedName) {
        FileDialog d = new FileDialog(owner(), title, FileDialog.SAVE);
        d.setFile(suggestedName);
        d.setVisible(true);
        if (d.getFile() == null) return null;
        String name = d.getFile();
        if (suggestedName.contains(".") && !name.contains(".")) name += suggestedName.substring(suggestedName.lastIndexOf('.'));
        return Path.of(d.getDirectory(), name);
    }

    public static Path openDirectory(String title) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return fc.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().toPath() : null;
    }
}
