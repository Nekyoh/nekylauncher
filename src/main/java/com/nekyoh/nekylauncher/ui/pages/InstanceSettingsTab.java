package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.game.VersionManifest;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.java.JavaInstall;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.loader.Loaders;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Per-instance settings: name/icon, version & loader, Java, memory, window, JVM args. */
final class InstanceSettingsTab extends JPanel {
    private final MainWindow window;
    private final Instance inst;
    private final Runnable onSaved;

    private final NTextField name = new NTextField(I18n.tr("instance.namePlaceholder"));
    private final NCombo<String> mcVersion = new NCombo<>();
    private final Controls.Segmented loader;
    private final NCombo<Loaders.LoaderVersion> loaderVersion = new NCombo<>();
    private final JLabel loaderStatus = Ui.small("");
    private final Controls.Segmented javaMode;
    private final NCombo<JavaInstall> javaInstalled = new NCombo<>();
    private final NTextField javaCustom = new NTextField(OS.isWindows() ? "C:\\...\\bin\\javaw.exe" : "/usr/lib/jvm/.../bin/java");
    private final JPanel javaDetail = Ui.transparent(new CardLayout());
    private final Controls.Toggle memOverride;
    private final JSlider minMem, maxMem;
    private final JLabel memLabel = Ui.muted("");
    private final NTextField jvmArgs = new NTextField(I18n.tr("settings.jvmArgsPlaceholder"));
    private final Controls.Toggle resOverride;
    private final NTextField width = new NTextField("854");
    private final NTextField height = new NTextField("480");
    private final JCheckBox fullscreen;
    private final NTextField server = new NTextField(I18n.tr("instance.serverPlaceholder"));

    InstanceSettingsTab(MainWindow window, Instance inst, Runnable onSaved) {
        super(new BorderLayout());
        this.window = window;
        this.inst = inst;
        this.onSaved = onSaved;
        setOpaque(false);

        name.setText(inst.name);
        loader = new Controls.Segmented(Arrays.stream(LoaderType.values()).map(l -> l.display).toList(), inst.loader.ordinal());
        loader.onChange(i -> loadLoaderVersions());
        loaderVersion.display(v -> v.id() + (v.recommended() ? "  ★" : ""));
        mcVersion.setItems(List.of(inst.mcVersion));
        mcVersion.setSelectedItem(inst.mcVersion);
        mcVersion.onSelect(v -> loadLoaderVersions());
        loadMcVersions();
        loadLoaderVersions();

        // java
        boolean custom = inst.javaPath != null && !inst.javaPath.isBlank();
        javaMode = new Controls.Segmented(List.of(I18n.tr("java.auto"), I18n.tr("java.installed"), I18n.tr("java.custom")), 0);
        javaInstalled.display(j -> "Java " + j.major() + "  ·  " + sourceName(j) + "  ·  " + j.executable());
        javaCustom.setText(custom ? inst.javaPath : "");
        NButton browse = new NButton(I18n.tr("common.browse"), Icons.Name.FOLDER, NButton.Variant.SECONDARY);
        browse.onClick(() -> {
            Path p = Pickers.openFile(I18n.tr("java.pick"), OS.isWindows() ? new String[]{"exe"} : new String[0]);
            if (p != null) javaCustom.setText(p.toString());
        });
        JPanel customRow = Ui.transparent(new BorderLayout(10, 0));
        customRow.add(javaCustom, BorderLayout.CENTER);
        customRow.add(browse, BorderLayout.EAST);
        JPanel autoInfo = Ui.transparent(new BorderLayout());
        autoInfo.add(Ui.paragraph(I18n.tr("java.autoHint"), Theme.TEXT_3, 12.5f), BorderLayout.CENTER);
        javaDetail.add(autoInfo, "auto");
        javaDetail.add(javaInstalled, "installed");
        javaDetail.add(customRow, "custom");
        javaMode.onChange(i -> ((CardLayout) javaDetail.getLayout()).show(javaDetail, i == 0 ? "auto" : i == 1 ? "installed" : "custom"));
        Tasks.async(() -> App.get().java.detect(false), list -> {
            javaInstalled.setItems(list);
            if (custom) {
                for (JavaInstall j : list) {
                    if (j.executable().toString().equals(inst.javaPath)) {
                        javaInstalled.setSelectedItem(j);
                        javaMode.setSelected(1, true);
                        return;
                    }
                }
                javaMode.setSelected(2, true);
            }
        }, null);

        // memory
        int total = (int) Math.max(2048, OS.totalMemoryMb());
        memOverride = new Controls.Toggle(inst.overrideMemory);
        minMem = Controls.slider(256, Math.min(total, 8192), inst.overrideMemory ? inst.minMemoryMb : App.get().settings.minMemoryMb);
        maxMem = Controls.slider(1024, total, inst.overrideMemory ? inst.maxMemoryMb : App.get().settings.maxMemoryMb);
        minMem.addChangeListener(e -> updateMemLabel());
        maxMem.addChangeListener(e -> updateMemLabel());
        memOverride.onChange(on -> updateMemEnabled());
        updateMemLabel();
        updateMemEnabled();

        jvmArgs.setText(inst.jvmArgs);
        resOverride = new Controls.Toggle(inst.overrideResolution);
        width.setText(Integer.toString(inst.width));
        height.setText(Integer.toString(inst.height));
        fullscreen = Controls.checkbox(I18n.tr("instance.fullscreen"), inst.fullscreen);
        server.setText(inst.joinServer);

        // layout
        Stack s = Stack.v(16);
        s.with(section(I18n.tr("instance.section.general"),
                Ui.field(I18n.tr("instance.name"), null, name),
                iconRow()));
        Stack loaderBox = Stack.v(10);
        loaderBox.with(Ui.left(loader));
        JPanel lvRow = Ui.transparent(new BorderLayout(10, 0));
        lvRow.add(loaderVersion, BorderLayout.CENTER);
        lvRow.add(loaderStatus, BorderLayout.EAST);
        loaderBox.with(lvRow);
        s.with(section(I18n.tr("instance.section.version"),
                Ui.field(I18n.tr("instance.mcVersion"), I18n.tr("instance.versionHint"), mcVersion),
                Ui.field(I18n.tr("instance.loader"), null, loaderBox)));
        Stack javaBox = Stack.v(10);
        javaBox.with(Ui.left(javaMode), javaDetail);
        s.with(section(I18n.tr("instance.section.java"), javaBox));
        Stack memBox = Stack.v(10);
        memBox.with(Ui.settingRow(I18n.tr("instance.memOverride"), I18n.tr("instance.memOverrideHint"), memOverride));
        memBox.with(labeled(I18n.tr("settings.minMemory"), minMem), labeled(I18n.tr("settings.maxMemory"), maxMem), memLabel);
        memBox.with(Ui.field(I18n.tr("settings.jvmArgs"), I18n.tr("settings.jvmArgsHint"), jvmArgs));
        s.with(section(I18n.tr("instance.section.memory"), memBox));
        Stack winBox = Stack.v(10);
        JPanel wh = Ui.transparent(new GridLayout(1, 2, 10, 0));
        wh.add(Ui.field(I18n.tr("settings.width"), null, width));
        wh.add(Ui.field(I18n.tr("settings.height"), null, height));
        winBox.with(Ui.settingRow(I18n.tr("instance.resOverride"), null, resOverride), wh, fullscreen,
                Ui.field(I18n.tr("instance.server"), I18n.tr("instance.serverHint"), server));
        s.with(section(I18n.tr("instance.section.window"), winBox));

        NButton save = new NButton(I18n.tr("common.saveChanges"), Icons.Name.CHECK, NButton.Variant.PRIMARY);
        save.onClick(this::save);
        JPanel saveRow = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        saveRow.add(save);
        s.with(saveRow);
        s.pad(4, 0, 24, 6);
        add(NScroll.vertical(s, Theme.BG), BorderLayout.CENTER);
    }

    private JComponent iconRow() {
        JComponent preview = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                com.nekyoh.nekylauncher.ui.Art.instanceIcon((Graphics2D) g, inst, 0, 0, 48);
            }
        };
        preview.setPreferredSize(new Dimension(48, 48));
        NButton change = new NButton(I18n.tr("instance.changeIcon"), Icons.Name.IMAGE, NButton.Variant.SECONDARY);
        change.onClick(() -> {
            Path p = Pickers.openFile(I18n.tr("instance.changeIcon"), "png", "jpg", "jpeg");
            if (p == null) return;
            try {
                String fn = "icon" + p.getFileName().toString().substring(p.getFileName().toString().lastIndexOf('.'));
                Files.copy(p, inst.dir.resolve(fn), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                inst.icon = fn;
                App.get().instances.save(inst);
                preview.repaint();
                onSaved.run();
            } catch (Exception e) {
                Modal.error(I18n.tr("common.error"), Tasks.message(e));
            }
        });
        NButton reset = new NButton(I18n.tr("instance.resetIcon"), NButton.Variant.GHOST);
        reset.onClick(() -> {
            inst.icon = "";
            App.get().instances.save(inst);
            preview.repaint();
            onSaved.run();
        });
        Stack row = Stack.h(12);
        row.with(preview, change, reset);
        return Ui.field(I18n.tr("instance.icon"), null, row);
    }

    private static JComponent labeled(String label, JComponent c) {
        JPanel p = Ui.transparent(new BorderLayout(12, 0));
        JLabel l = Ui.muted(label);
        l.setPreferredSize(new Dimension(130, 20));
        p.add(l, BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    static JComponent section(String title, JComponent... rows) {
        Card c = new Card(new BorderLayout(0, 14));
        c.pad(18, 20, 20, 20);
        c.add(Ui.h3(title), BorderLayout.NORTH);
        Stack s = Stack.v(14);
        s.with(rows);
        c.add(s, BorderLayout.CENTER);
        return c;
    }

    static String sourceName(JavaInstall j) {
        return switch (j.source()) {
            case MANAGED -> I18n.tr("java.source.managed");
            case OFFICIAL_LAUNCHER -> I18n.tr("java.source.official");
            case CUSTOM -> I18n.tr("java.source.custom");
            default -> I18n.tr("java.source.system");
        };
    }

    private void updateMemLabel() {
        if (minMem.getValue() > maxMem.getValue()) minMem.setValue(maxMem.getValue());
        memLabel.setText(I18n.tr("settings.memoryValue", minMem.getValue(), maxMem.getValue()));
    }

    private void updateMemEnabled() {
        minMem.setEnabled(memOverride.isOn());
        maxMem.setEnabled(memOverride.isOn());
    }

    private void loadMcVersions() {
        Tasks.async(() -> VersionManifest.get(App.get().dirs, false), m -> {
            List<String> ids = new ArrayList<>();
            for (VersionManifest.Entry e : m.versions) if (e.isRelease() || e.id().equals(inst.mcVersion)) ids.add(e.id());
            if (!ids.contains(inst.mcVersion)) ids.add(0, inst.mcVersion);
            Object sel = mcVersion.getSelectedItem();
            mcVersion.setItems(ids);
            mcVersion.setSelectedItem(sel != null ? sel : inst.mcVersion);
        }, err -> { });
    }

    private void loadLoaderVersions() {
        LoaderType type = LoaderType.values()[loader.getSelected()];
        String mc = mcVersion.selected();
        loaderVersion.setItems(List.of());
        loaderVersion.setVisible(type != LoaderType.VANILLA);
        if (type == LoaderType.VANILLA || mc == null) {
            loaderVersion.setEnabled(false);
            loaderStatus.setText("");
            return;
        }
        loaderVersion.setEnabled(false);
        loaderStatus.setText(I18n.tr("common.loading"));
        Tasks.async(() -> Loaders.versions(type, mc), list -> {
            if (LoaderType.values()[loader.getSelected()] != type || !mc.equals(mcVersion.selected())) return;
            loaderVersion.setItems(list);
            loaderVersion.setEnabled(!list.isEmpty());
            loaderStatus.setText(list.isEmpty() ? I18n.tr("newInstance.noLoaderVersions") : "");
            String want = type == inst.loader && mc.equals(inst.mcVersion) ? inst.loaderVersion : Loaders.pickDefault(list);
            for (Loaders.LoaderVersion v : list) if (v.id().equals(want)) loaderVersion.setSelectedItem(v);
        }, err -> loaderStatus.setText(I18n.tr("error.network")));
    }

    private void save() {
        if (window.launch.isRunning(inst) || window.launch.isPreparing(inst)) {
            Toast.show(I18n.tr("instance.saveRunning"), Toast.Kind.WARNING);
            return;
        }
        String newName = name.getText().trim();
        if (!newName.isEmpty()) inst.name = newName;

        LoaderType type = LoaderType.values()[loader.getSelected()];
        String mc = mcVersion.selected();
        Loaders.LoaderVersion lv = loaderVersion.selected();
        String lvId = type == LoaderType.VANILLA ? "" : lv != null ? lv.id() : "";
        boolean versionChanged = mc != null && (!mc.equals(inst.mcVersion) || type != inst.loader || !lvId.equals(inst.loaderVersion == null ? "" : inst.loaderVersion));
        if (versionChanged) {
            if (type != LoaderType.VANILLA && lv == null) {
                Toast.show(I18n.tr("newInstance.pickLoaderVersion"), Toast.Kind.WARNING);
                return;
            }
            inst.mcVersion = mc;
            inst.loader = type;
            inst.loaderVersion = lvId;
            inst.versionId = type == LoaderType.VANILLA ? mc : "";
            if (type != LoaderType.VANILLA) {
                try { Files.createDirectories(inst.modsDir()); } catch (Exception ignored) { }
            }
        }

        switch (javaMode.getSelected()) {
            case 1 -> {
                JavaInstall j = javaInstalled.selected();
                inst.javaPath = j != null ? j.executable().toString() : "";
            }
            case 2 -> inst.javaPath = javaCustom.getText().trim();
            default -> inst.javaPath = "";
        }
        inst.overrideMemory = memOverride.isOn();
        inst.minMemoryMb = minMem.getValue();
        inst.maxMemoryMb = maxMem.getValue();
        inst.jvmArgs = jvmArgs.getText().trim();
        inst.overrideResolution = resOverride.isOn();
        inst.width = parse(width.getText(), 854);
        inst.height = parse(height.getText(), 480);
        inst.fullscreen = fullscreen.isSelected();
        inst.joinServer = server.getText().trim();
        App.get().instances.save(inst);
        onSaved.run();
        Toast.show(versionChanged ? I18n.tr("instance.savedReinstall") : I18n.tr("instance.saved"), Toast.Kind.SUCCESS);
        if (versionChanged) window.openInstance(inst);
    }

    private static int parse(String s, int def) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

}
