package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.update.Updater;
import com.nekyoh.nekylauncher.util.*;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/** "New version available" flow: startup check, release notes, download and restart into the update. */
public final class UpdateDialog {
    private UpdateDialog() {}

    /** Quiet check on startup: only a toast when there is something new (and not skipped). */
    public static void checkOnStartup(MainWindow window) {
        Settings s = App.get().settings;
        if (!s.checkUpdates) return;
        Tasks.async(Updater::latest, r -> {
            if (!Updater.isNewer(r.version(), AppInfo.VERSION) || r.version().equals(s.skippedVersion)) return;
            Toast.show(I18n.tr("update.available", r.version()), Toast.Kind.INFO, I18n.tr("update.see"), () -> show(window, r));
        }, err -> Log.info("Update check skipped: " + Tasks.message(err)));
    }

    /** Manual check from Settings: always answers. */
    public static void checkNow(MainWindow window, NButton btn) {
        btn.setEnabled(false);
        Tasks.async(Updater::latest, r -> {
            btn.setEnabled(true);
            if (Updater.isNewer(r.version(), AppInfo.VERSION)) show(window, r);
            else Toast.show(I18n.tr("update.upToDate", AppInfo.VERSION), Toast.Kind.SUCCESS);
        }, err -> {
            btn.setEnabled(true);
            if (err instanceof Http.HttpException he && he.status == 404) {
                Toast.show(I18n.tr("update.upToDate", AppInfo.VERSION), Toast.Kind.SUCCESS); // no release published yet
            } else {
                Toast.show(I18n.tr("update.checkFailed", Tasks.message(err)), Toast.Kind.ERROR);
            }
        });
    }

    public static void show(MainWindow window, Updater.Release r) {
        Updater.Dist dist = Updater.dist();
        Updater.Asset asset = Updater.assetFor(r, dist);
        boolean self = Updater.canSelfUpdate(r, App.get().dirs.root);

        Stack body = Stack.v(14);
        JPanel versions = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        versions.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        versions.add(Ui.chip(AppInfo.VERSION, Theme.TEXT_3));
        versions.add(Ui.icon(Icons.Name.CHEVRON_RIGHT, 14, Theme.TEXT_3));
        versions.add(Ui.chip(r.version(), Theme.GREEN));
        if (asset != null && asset.size() > 0) versions.add(Ui.small("  " + FileUtil.humanSize(asset.size())));
        body.with(versions);

        String notes = r.notes() == null || r.notes().isBlank() ? I18n.tr("update.noNotes") : cleanNotes(r.notes());
        JTextArea text = Ui.paragraph(notes, Theme.TEXT_2, 13);
        JScrollPane sp = NScroll.vertical(text, Theme.SURFACE);
        sp.setPreferredSize(new Dimension(520, Math.min(260, text.getPreferredSize().height + 20)));
        body.with(sp);
        if (!self) body.with(Ui.paragraph(I18n.tr(dist == Updater.Dist.DEV ? "update.manualDev" : "update.manual"), Theme.TEXT_3, 12));
        else if (dist == Updater.Dist.WINDOWS_INSTALLER) body.with(Ui.paragraph(I18n.tr("update.installerNote"), Theme.TEXT_3, 12));
        else if (dist == Updater.Dist.LINUX_DEB) body.with(Ui.paragraph(I18n.tr("update.debNote"), Theme.TEXT_3, 12));

        Modal m = Modal.open(I18n.tr("update.title", r.version()), body, 600);
        NButton skip = new NButton(I18n.tr("update.skip"), NButton.Variant.GHOST).onClick(() -> {
            App.get().settings.skippedVersion = r.version();
            App.get().settings.save();
            m.close();
        });
        NButton later = new NButton(I18n.tr("update.later"), NButton.Variant.GHOST).onClick(m::close);
        NButton go = self
                ? new NButton(I18n.tr("update.now"), Icons.Name.DOWNLOAD, NButton.Variant.PRIMARY)
                : new NButton(I18n.tr("update.openPage"), Icons.Name.EXTERNAL, NButton.Variant.PRIMARY);
        go.onClick(() -> {
            if (!self) {
                OS.openUrl(r.pageUrl());
                m.close();
            } else if (!App.get().launcher.allRunning().isEmpty()) {
                Toast.show(I18n.tr("update.closeGames"), Toast.Kind.WARNING);
            } else {
                runUpdate(window, m, body, asset, dist);
            }
        });
        m.footer(skip, later, go);
    }

    /** GitHub release notes are Markdown: keep them readable as plain text. */
    static String cleanNotes(String md) {
        return md.replace("\r", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("\\[([^]]+)]\\((https?://[^)]+)\\)", "$1 ($2)")
                .trim();
    }

    private static void runUpdate(MainWindow window, Modal m, Stack body, Updater.Asset asset, Updater.Dist dist) {
        CancelToken cancel = new CancelToken();
        Controls.Progress bar = new Controls.Progress();
        bar.setValue(0);
        JLabel status = Ui.muted(I18n.tr("update.downloading"));
        body.removeAll();
        body.with(status, bar);
        body.revalidate();
        m.relayout();
        m.escClosable(false);
        NButton cancelBtn = new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(() -> {
            cancel.cancel();
            m.close();
        });
        m.footer(cancelBtn);
        Path work = App.get().dirs.cache().resolve("update");
        Progress p = new Progress() {
            @Override public void stage(String text) { }

            @Override public void fraction(double v) { Tasks.ui(() -> bar.setValue(v)); }
        };
        Tasks.async(() -> {
            Path file = Updater.download(asset, work, p, cancel);
            cancel.check();
            Tasks.ui(() -> {
                status.setText(I18n.tr("update.installing"));
                bar.setValue(-1);
            });
            Updater.install(file, dist, work);
            return file;
        }, file -> {
            App.get().settings.skippedVersion = "";
            App.get().settings.save();
            status.setText(I18n.tr("update.restarting"));
            m.footer();
            // the helper waits for this process to end before touching any file
            Timer t = new Timer(900, e -> {
                window.dispose();
                System.exit(0);
            });
            t.setRepeats(false);
            t.start();
        }, err -> {
            if (err instanceof CancelToken.CancelledException) return;
            m.close();
            Modal.error(I18n.tr("update.failed"), Tasks.message(err));
        });
    }
}
