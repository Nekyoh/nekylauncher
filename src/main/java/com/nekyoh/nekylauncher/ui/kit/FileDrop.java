package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.util.Log;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts files dragged from Explorer / Nautilus / Dolphin. Handles both the Windows file-list
 * flavor and the text/uri-list flavor used by Linux desktops.
 */
public final class FileDrop {
    private FileDrop() {}

    private static DataFlavor uriList;

    static {
        try {
            uriList = new DataFlavor("text/uri-list;class=java.lang.String");
        } catch (ClassNotFoundException ignored) { }
    }

    public interface Listener {
        void dropped(List<Path> files);

        default void hover(boolean active) { }
    }

    public static void install(JComponent c, Listener l) {
        new DropTarget(c, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override public void dragEnter(DropTargetDragEvent e) {
                if (supported(e.getCurrentDataFlavors())) {
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                    l.hover(true);
                } else e.rejectDrag();
            }

            @Override public void dragExit(DropTargetEvent e) { l.hover(false); }

            @Override public void drop(DropTargetDropEvent e) {
                l.hover(false);
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    List<Path> files = extract(e.getTransferable());
                    e.dropComplete(true);
                    if (!files.isEmpty()) l.dropped(files);
                } catch (Exception ex) {
                    Log.warn("Drop failed: " + ex.getMessage());
                    e.dropComplete(false);
                }
            }
        }, true);
    }

    private static boolean supported(DataFlavor[] flavors) {
        for (DataFlavor f : flavors) {
            if (f.isFlavorJavaFileListType() || (uriList != null && f.equals(uriList)) || "text/uri-list".equals(f.getMimeType().split(";")[0])) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Path> extract(Transferable t) throws Exception {
        List<Path> out = new ArrayList<>();
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            for (File f : (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor)) out.add(f.toPath());
            return out;
        }
        if (uriList != null && t.isDataFlavorSupported(uriList)) {
            String data = (String) t.getTransferData(uriList);
            for (String line : data.split("\r?\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    out.add(Path.of(new URI(line)));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }
}
