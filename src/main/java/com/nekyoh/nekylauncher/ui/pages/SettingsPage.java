package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.java.JavaInstall;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Global launcher settings. Changes are saved immediately. */
public final class SettingsPage extends JPanel {
    private final MainWindow window;
    private final Stack javaList = Stack.v(8);
    private final JLabel memLabel = Ui.muted("");

    public SettingsPage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);
        Settings s = App.get().settings;

        // general
        NCombo<I18n.Lang> lang = new NCombo<>(Arrays.asList(I18n.Lang.values()));
        lang.setSelectedItem(I18n.current());
        lang.onSelect(l -> {
            if (l == null || l == I18n.current()) return;
            s.language = l.code;
            s.save();
            I18n.set(l);
            SwingUtilities.invokeLater(window::rebuild);
        });
        lang.setPreferredSize(new Dimension(220, 38));
        NCombo<Settings.AfterLaunch> after = new NCombo<>(Arrays.asList(Settings.AfterLaunch.values()));
        after.display(a -> I18n.tr("settings.after." + a.name().toLowerCase()));
        after.setSelectedItem(s.afterLaunch);
        after.onSelect(a -> { s.afterLaunch = a; s.save(); });
        after.setPreferredSize(new Dimension(220, 38));
        Controls.Toggle console = new Controls.Toggle(s.showConsoleOnLaunch).onChange(v -> { s.showConsoleOnLaunch = v; s.save(); });
        Controls.Toggle frame = new Controls.Toggle(s.systemWindowFrame).onChange(v -> {
            s.systemWindowFrame = v;
            s.save();
            Toast.show(I18n.tr("settings.restartNeeded"), Toast.Kind.INFO);
        });

        // memory & game
        int total = (int) Math.max(2048, OS.totalMemoryMb());
        JSlider minMem = Controls.slider(256, Math.min(total, 8192), s.minMemoryMb);
        JSlider maxMem = Controls.slider(1024, total, s.maxMemoryMb);
        Runnable memUpdate = () -> {
            if (minMem.getValue() > maxMem.getValue()) minMem.setValue(maxMem.getValue());
            s.minMemoryMb = minMem.getValue();
            s.maxMemoryMb = maxMem.getValue();
            memLabel.setText(I18n.tr("settings.memoryValue", s.minMemoryMb, s.maxMemoryMb) + "   ·   " + I18n.tr("settings.totalMemory", total));
        };
        minMem.addChangeListener(e -> { memUpdate.run(); if (!minMem.getValueIsAdjusting()) s.save(); });
        maxMem.addChangeListener(e -> { memUpdate.run(); if (!maxMem.getValueIsAdjusting()) s.save(); });
        memUpdate.run();
        NTextField jvm = new NTextField(I18n.tr("settings.jvmArgsPlaceholder"));
        jvm.setText(s.jvmArgs);
        jvm.onChange(t -> { s.jvmArgs = t.trim(); s.save(); });
        NTextField w = new NTextField("854");
        w.setText(Integer.toString(s.gameWidth));
        w.onChange(t -> { s.gameWidth = parse(t, 854); s.save(); });
        NTextField h = new NTextField("480");
        h.setText(Integer.toString(s.gameHeight));
        h.onChange(t -> { s.gameHeight = parse(t, 480); s.save(); });
        JPanel wh = Ui.transparent(new GridLayout(1, 2, 10, 0));
        wh.add(Ui.field(I18n.tr("settings.width"), null, w));
        wh.add(Ui.field(I18n.tr("settings.height"), null, h));

        // java
        Controls.Toggle autoJava = new Controls.Toggle(s.autoDownloadJava).onChange(v -> { s.autoDownloadJava = v; s.save(); });
        NButton refreshJava = new NButton(I18n.tr("settings.java.scan"), Icons.Name.REFRESH, NButton.Variant.SECONDARY, NButton.Size.SM);
        refreshJava.onClick(() -> loadJava(true));
        NButton addJava = new NButton(I18n.tr("settings.java.add"), Icons.Name.PLUS, NButton.Variant.SECONDARY, NButton.Size.SM);
        addJava.onClick(() -> {
            Path p = Pickers.openFile(I18n.tr("java.pick"), OS.isWindows() ? new String[]{"exe"} : new String[0]);
            if (p == null) return;
            Tasks.async(() -> App.get().java.probe(p, JavaInstall.Source.CUSTOM), j -> {
                if (j == null) {
                    Modal.error(I18n.tr("common.error"), I18n.tr("error.javaInvalid", p));
                    return;
                }
                if (!s.extraJavaPaths.contains(p.toString())) s.extraJavaPaths.add(p.toString());
                s.save();
                loadJava(true);
            }, null);
        });
        Stack javaButtons = Stack.h(8);
        javaButtons.with(refreshJava, addJava);

        // downloads
        JSlider conc = Controls.slider(1, 64, s.concurrentDownloads);
        JLabel concLabel = Ui.muted(Integer.toString(s.concurrentDownloads));
        conc.addChangeListener(e -> {
            s.concurrentDownloads = conc.getValue();
            concLabel.setText(Integer.toString(conc.getValue()));
            if (!conc.getValueIsAdjusting()) s.save();
        });
        JPanel concRow = Ui.transparent(new BorderLayout(12, 0));
        concRow.add(conc, BorderLayout.CENTER);
        concRow.add(concLabel, BorderLayout.EAST);


        // folders
        com.nekyoh.nekylauncher.core.Dirs dirs = App.get().dirs;
        NTextField dataDir = new NTextField("");
        dataDir.setText(dirs.root.toString());
        dataDir.setEditable(false);
        NButton change = new NButton(I18n.tr("settings.dataDirChange"), Icons.Name.EDIT, NButton.Variant.PRIMARY);
        change.onClick(() -> com.nekyoh.nekylauncher.ui.dialogs.DataFolderDialog.pick(window));
        change.setEnabled(dirs.isChangeable());
        Stack fb = Stack.h(8);
        fb.with(change,
                new NButton(I18n.tr("common.open"), Icons.Name.FOLDER, NButton.Variant.SECONDARY).onClick(() -> OS.openFolder(dirs.root)),
                new NButton(I18n.tr("settings.logs"), Icons.Name.TERMINAL, NButton.Variant.GHOST).onClick(() -> OS.openFolder(dirs.logs())));
        if (dirs.source == com.nekyoh.nekylauncher.core.Dirs.Source.CUSTOM) {
            fb.with(new NButton(I18n.tr("settings.dataDirReset"), Icons.Name.REFRESH, NButton.Variant.GHOST)
                    .onClick(() -> com.nekyoh.nekylauncher.ui.dialogs.DataFolderDialog.confirm(window, com.nekyoh.nekylauncher.core.Dirs.defaultRoot())));
        }
        Stack folderRow = Stack.v(10);
        folderRow.with(dataDir, Ui.left(fb));
        String sourceHint = switch (dirs.source) {
            case CUSTOM -> I18n.tr("settings.dataDirCustom");
            case PORTABLE -> I18n.tr("settings.dataDirPortable");
            case OVERRIDE -> I18n.tr("settings.dataDirOverride");
            default -> I18n.tr("settings.dataDirDefault");
        };
        folderRow.with(Ui.paragraph(sourceHint, Theme.TEXT_3, 12));

        // updates
        Controls.Toggle autoUpdate = new Controls.Toggle(s.checkUpdates).onChange(v -> { s.checkUpdates = v; s.save(); });
        NButton checkNow = new NButton(I18n.tr("update.checkNow"), Icons.Name.REFRESH, NButton.Variant.SECONDARY);
        checkNow.onClick(() -> com.nekyoh.nekylauncher.ui.dialogs.UpdateDialog.checkNow(window, checkNow));
        NButton releases = new NButton(I18n.tr("update.allReleases"), Icons.Name.EXTERNAL, NButton.Variant.GHOST)
                .onClick(() -> OS.openUrl("https://github.com/" + AppInfo.GITHUB_REPO + "/releases"));
        Stack updButtons = Stack.h(8);
        updButtons.with(checkNow, releases);
        String distName = I18n.tr("update.dist." + com.nekyoh.nekylauncher.update.Updater.dist().name().toLowerCase());

        Stack content = Stack.v(16);
        content.with(Ui.h1(I18n.tr("settings.title")));
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.general"),
                Ui.settingRow(I18n.tr("settings.language"), I18n.tr("settings.languageHint"), lang),
                Ui.settingRow(I18n.tr("settings.afterLaunch"), I18n.tr("settings.afterLaunchHint"), after),
                Ui.settingRow(I18n.tr("settings.showConsole"), I18n.tr("settings.showConsoleHint"), console),
                Ui.settingRow(I18n.tr("settings.systemFrame"), I18n.tr("settings.systemFrameHint"), frame)));
        Stack memBox = Stack.v(10);
        memBox.with(row(I18n.tr("settings.minMemory"), minMem), row(I18n.tr("settings.maxMemory"), maxMem), memLabel,
                Ui.field(I18n.tr("settings.jvmArgs"), I18n.tr("settings.jvmArgsHint"), jvm), wh);
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.game"), memBox));
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.java"),
                Ui.settingRow(I18n.tr("settings.java.auto"), I18n.tr("settings.java.autoHint"), autoJava),
                javaButtons, javaList));
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.downloads"),
                Ui.field(I18n.tr("settings.concurrent"), I18n.tr("settings.concurrentHint"), concRow)));
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.storage"),
                Ui.field(I18n.tr("settings.dataDir"), I18n.tr("settings.dataDirHint"), folderRow)));
        content.with(InstanceSettingsTab.section(I18n.tr("settings.section.updates"),
                Ui.settingRow(I18n.tr("update.auto"), I18n.tr("update.autoHint"), autoUpdate),
                Ui.left(updButtons),
                Ui.paragraph(I18n.tr("update.current", AppInfo.VERSION, distName), Theme.TEXT_3, 12)));
        content.with(about());
        content.pad(26, 28, 32, 28);
        add(NScroll.vertical(content, Theme.BG), BorderLayout.CENTER);
        loadJava(false);
    }

    private static JComponent row(String label, JComponent c) {
        JPanel p = Ui.transparent(new BorderLayout(12, 0));
        JLabel l = Ui.muted(label);
        l.setPreferredSize(new Dimension(130, 20));
        p.add(l, BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JComponent about() {
        Card c = new Card(new BorderLayout(20, 0));
        c.pad(20, 20, 20, 20);
        BufferedImage logo = Images.resource("/nekylauncher/img/logo-512.png");
        JComponent img = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                if (logo != null) Theme.hq(g).drawImage(Images.scaledCached(logo, 192), 0, 0, 96, 96, null);
            }
        };
        img.setPreferredSize(new Dimension(96, 96));
        c.add(img, BorderLayout.WEST);
        Stack t = Stack.v(4);
        t.with(Ui.label(AppInfo.NAME + "  " + AppInfo.VERSION, 17, Theme.Weight.BOLD, Theme.TEXT),
                Ui.label("by " + AppInfo.AUTHOR, 13, Theme.Weight.MEDIUM, Theme.CYAN),
                Ui.paragraph(I18n.tr("about.license"), Theme.TEXT_2, 12.5f),
                Ui.paragraph(I18n.tr("about.disclaimer"), Theme.TEXT_3, 12));
        c.add(t, BorderLayout.CENTER);
        return c;
    }

    private void loadJava(boolean refresh) {
        javaList.removeAll();
        javaList.with(Ui.small(I18n.tr("settings.java.scanning")));
        javaList.revalidate();
        Tasks.async(() -> App.get().java.detect(refresh), this::showJava, err -> { });
    }

    private void showJava(List<JavaInstall> list) {
        javaList.removeAll();
        if (list.isEmpty()) javaList.with(Ui.small(I18n.tr("settings.java.none")));
        for (JavaInstall j : list) {
            Card r = new Card(new BorderLayout(12, 0));
            r.colors(Theme.SURFACE_2, Theme.BORDER);
            r.pad(8, 12, 8, 12);
            r.add(Ui.center(Ui.chip("Java " + j.major(), j.major() >= 21 ? Theme.CYAN : j.major() >= 17 ? Theme.GREEN : Theme.AMBER)), BorderLayout.WEST);
            Stack t = Stack.v(2);
            t.with(Ui.label(j.version() + "  ·  " + InstanceSettingsTab.sourceName(j), 12.5f, Theme.Weight.MEDIUM, Theme.TEXT), Ui.small(j.executable().toString()));
            r.add(t, BorderLayout.CENTER);
            javaList.with(r);
        }
        javaList.revalidate();
        javaList.repaint();
    }

    public void refresh() { }

    private static int parse(String s, int def) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
