package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.core.DataFolder;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/** Lets the user move the data folder (instances, Java, libraries...) somewhere else, e.g. another drive. */
public final class DataFolderDialog {
    private DataFolderDialog() {}

    public static void pick(MainWindow window) {
        Path picked = Pickers.openDirectory(I18n.tr("datadir.pick"));
        if (picked == null) return;
        confirm(window, DataFolder.normalizeTarget(picked));
    }

    public static void confirm(MainWindow window, Path target) {
        App app = App.get();
        if (!app.launcher.allRunning().isEmpty()) {
            Toast.show(I18n.tr("datadir.gamesRunning"), Toast.Kind.WARNING);
            return;
        }
        Path current = app.dirs.root;
        switch (DataFolder.check(current, target)) {
            case SAME -> {
                Toast.show(I18n.tr("datadir.same"), Toast.Kind.INFO);
                return;
            }
            case INSIDE_CURRENT, CONTAINS_CURRENT -> {
                Modal.error(I18n.tr("datadir.title"), I18n.tr("datadir.nested"));
                return;
            }
            case NOT_WRITABLE -> {
                Modal.error(I18n.tr("datadir.title"), I18n.tr("datadir.notWritable", target));
                return;
            }
            default -> { }
        }
        boolean existing = DataFolder.hasLauncherData(target);

        Stack body = Stack.v(12);
        body.with(pathRow(I18n.tr("datadir.from"), current.toString(), Theme.TEXT_2),
                pathRow(I18n.tr("datadir.to"), target.toString(), Theme.CYAN));
        JCheckBox move = Controls.checkbox(I18n.tr("datadir.move"), !existing);
        if (existing) {
            move.setEnabled(false);
            body.with(Ui.paragraph(I18n.tr("datadir.existing"), Theme.AMBER, 12.5f));
        } else {
            body.with(move, Ui.paragraph(I18n.tr("datadir.moveHint"), Theme.TEXT_3, 12));
        }
        body.with(Ui.paragraph(I18n.tr("datadir.restartNote"), Theme.TEXT_2, 12.5f));
        Modal m = Modal.open(I18n.tr("datadir.title"), body, 560);
        NButton ok = new NButton(I18n.tr("datadir.confirm"), Icons.Name.CHECK, NButton.Variant.PRIMARY);
        ok.onClick(() -> {
            m.close();
            apply(current, target, move.isSelected() && !existing);
        });
        m.footer(new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(m::close), ok);
    }

    private static JComponent pathRow(String label, String path, Color color) {
        JPanel p = Ui.transparent(new BorderLayout(12, 0));
        JLabel l = Ui.muted(label);
        l.setPreferredSize(new Dimension(56, 20));
        p.add(l, BorderLayout.WEST);
        JLabel v = Ui.label(path, 13, Theme.Weight.MEDIUM, color);
        v.setToolTipText(path);
        p.add(v, BorderLayout.CENTER);
        return p;
    }

    private static void apply(Path current, Path target, boolean moveData) {
        App.get().settings.save();
        if (!moveData) {
            switchAndRestart(target);
            return;
        }
        JLabel stage = Ui.muted(I18n.tr("datadir.preparing"));
        Controls.Progress bar = new Controls.Progress();
        Stack body = Stack.v(12);
        body.with(stage, bar, Ui.paragraph(I18n.tr("datadir.dontClose"), Theme.TEXT_3, 12));
        Modal m = Modal.open(I18n.tr("datadir.title"), body, 480).escClosable(false);
        CancelToken cancel = new CancelToken();
        m.footer(new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(cancel::cancel));
        Progress p = new Progress() {
            public void stage(String text) { SwingUtilities.invokeLater(() -> stage.setText(text)); }
            public void fraction(double v) { SwingUtilities.invokeLater(() -> bar.setValue(v)); }
        };
        Tasks.async(() -> DataFolder.move(current, target, p, cancel), () -> {
            m.close();
            switchAndRestart(target);
        }, err -> {
            m.close();
            // DataFolder.move rolled back: the launcher keeps using the current folder
            if (!(err instanceof CancelToken.CancelledException)) Modal.error(I18n.tr("datadir.failed"), Tasks.message(err));
            else Toast.show(I18n.tr("datadir.cancelled"), Toast.Kind.INFO);
        });
    }

    private static void switchAndRestart(Path target) {
        try {
            Dirs.writePointer(target);
        } catch (Exception e) {
            Modal.error(I18n.tr("datadir.failed"), Tasks.message(e));
            return;
        }
        DataFolder.restart();
    }
}
