package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.dialogs.ExportDialog;
import com.nekyoh.nekylauncher.ui.kit.Modal;
import com.nekyoh.nekylauncher.ui.kit.Toast;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;

/** Context menu shared by instance cards and the instance page. */
public final class InstanceMenu {
    private InstanceMenu() {}

    public static void show(MainWindow w, Instance inst, Component invoker, int x, int y) {
        JPopupMenu m = new JPopupMenu();
        item(m, I18n.tr("instance.play"), Icons.Name.PLAY, () -> w.launch.play(inst));
        item(m, I18n.tr("instance.open"), Icons.Name.GRID, () -> w.openInstance(inst));
        item(m, I18n.tr("instance.openFolder"), Icons.Name.FOLDER, () -> OS.openFolder(inst.gameDir()));
        m.addSeparator();
        item(m, I18n.tr("instance.export"), Icons.Name.EXPORT, () -> ExportDialog.open(inst));
        item(m, I18n.tr("instance.duplicate"), Icons.Name.COPY, () -> duplicate(inst));
        m.addSeparator();
        item(m, I18n.tr("instance.delete"), Icons.Name.TRASH, () -> delete(w, inst));
        m.show(invoker, x, y);
    }

    private static void item(JPopupMenu m, String text, Icons.Name icon, Runnable r) {
        JMenuItem it = new JMenuItem(text, Icons.get(icon, 15, Theme.TEXT_2));
        it.setIconTextGap(10);
        it.addActionListener(e -> r.run());
        m.add(it);
    }

    public static void duplicate(Instance inst) {
        String name = I18n.tr("instance.copyName", inst.name);
        Tasks.async(() -> App.get().instances.duplicate(inst, name),
                copy -> Toast.show(I18n.tr("instance.duplicated", copy.name), Toast.Kind.SUCCESS),
                err -> Modal.error(I18n.tr("common.error"), Tasks.message(err)));
    }

    public static void delete(MainWindow w, Instance inst) {
        if (w.launch.isRunning(inst) || w.launch.isPreparing(inst)) {
            Toast.show(I18n.tr("instance.deleteRunning"), Toast.Kind.WARNING);
            return;
        }
        Modal.confirm(I18n.tr("instance.deleteTitle"), I18n.tr("instance.deleteMessage", inst.name), I18n.tr("instance.delete"), true, () ->
                Tasks.async(() -> App.get().instances.delete(inst), () -> {
                    Toast.show(I18n.tr("instance.deleted", inst.name), Toast.Kind.SUCCESS);
                    if (w.currentPage() == MainWindow.Page.INSTANCE) w.showPage(MainWindow.Page.INSTANCES);
                }, err -> Modal.error(I18n.tr("common.error"), Tasks.message(err))));
    }
}
