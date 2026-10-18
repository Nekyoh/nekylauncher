package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.game.VersionManifest;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.loader.Loaders;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Create an instance: name, Minecraft version, mod loader and loader version. */
public final class NewInstanceDialog {
    private final MainWindow window;
    private final NTextField name = new NTextField(I18n.tr("newInstance.namePlaceholder"));
    private final Controls.Segmented filter = new Controls.Segmented(
            List.of(I18n.tr("newInstance.releases"), I18n.tr("newInstance.snapshots"), I18n.tr("newInstance.old")), 0);
    private final NTextField search = new NTextField(I18n.tr("newInstance.searchVersion"), Icons.Name.SEARCH);
    private final DefaultListModel<VersionManifest.Entry> model = new DefaultListModel<>();
    private final JList<VersionManifest.Entry> versions = new JList<>(model);
    private final JLabel listStatus = Ui.small("");
    private final Controls.Segmented loader = new Controls.Segmented(Arrays.stream(LoaderType.values()).map(l -> l.display).toList(), 0);
    private final NCombo<Loaders.LoaderVersion> loaderVersion = new NCombo<>();
    private final JLabel loaderStatus = Ui.small("");
    private final NButton create = new NButton(I18n.tr("newInstance.create"), Icons.Name.PLUS, NButton.Variant.PRIMARY);
    private VersionManifest manifest;
    private boolean nameEdited;
    private boolean settingName;
    private Modal modal;

    private NewInstanceDialog(MainWindow window) { this.window = window; }

    public static void open(MainWindow window) { new NewInstanceDialog(window).show(); }

    private void show() {
        versions.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                VersionManifest.Entry e = (VersionManifest.Entry) value;
                JPanel row = new JPanel(new BorderLayout());
                row.setBorder(new EmptyBorder(8, 12, 8, 12));
                row.setBackground(sel ? Theme.SURFACE_3 : Theme.SURFACE_2);
                JLabel id = Ui.label(e.id(), 13.5f, Theme.Weight.SEMIBOLD, sel ? Theme.TEXT : Theme.TEXT);
                row.add(id, BorderLayout.WEST);
                String date = e.releaseTime().length() >= 10 ? e.releaseTime().substring(0, 10) : "";
                String type = switch (e.type()) {
                    case "release" -> I18n.tr("version.release");
                    case "snapshot" -> I18n.tr("version.snapshot");
                    default -> I18n.tr("version.old");
                };
                JLabel meta = Ui.small(type + "  ·  " + date);
                row.add(meta, BorderLayout.EAST);
                if (sel) row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.CYAN), new EmptyBorder(8, 9, 8, 12)));
                return row;
            }
        });
        versions.setBackground(Theme.SURFACE_2);
        versions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versions.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                suggestName();
                loadLoaderVersions();
            }
        });
        JScrollPane sp = NScroll.wrap(versions, Theme.SURFACE_2);
        sp.setPreferredSize(new Dimension(560, 210));
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER_STRONG));

        filter.onChange(i -> fillVersions());
        search.onChange(t -> fillVersions());
        search.setPreferredSize(new Dimension(200, 38));
        name.onChange(t -> { if (!settingName) nameEdited = true; });
        loader.onChange(i -> {
            suggestName();
            loadLoaderVersions();
        });
        loaderVersion.display(v -> v.id() + (v.recommended() ? "   ★ " + I18n.tr("newInstance.recommended") : v.stable() ? "" : "   (beta)"));

        JPanel versionTop = Ui.transparent(new BorderLayout(10, 0));
        versionTop.add(filter, BorderLayout.WEST);
        versionTop.add(search, BorderLayout.CENTER);
        Stack versionBox = Stack.v(8);
        versionBox.with(versionTop, sp, listStatus);

        Stack loaderBox = Stack.v(10);
        JPanel lvRow = Ui.transparent(new BorderLayout(10, 0));
        lvRow.add(loaderVersion, BorderLayout.CENTER);
        lvRow.add(loaderStatus, BorderLayout.EAST);
        loaderBox.with(Ui.left(loader), lvRow);

        Stack body = Stack.v(18);
        body.with(Ui.field(I18n.tr("newInstance.name"), null, name),
                Ui.field(I18n.tr("newInstance.version"), null, versionBox),
                Ui.field(I18n.tr("newInstance.loader"), I18n.tr("newInstance.loaderHint"), loaderBox));

        modal = Modal.open(I18n.tr("newInstance.title"), body, 640);
        NButton importBtn = new NButton(I18n.tr("instances.import"), Icons.Name.DOWNLOAD, NButton.Variant.GHOST);
        importBtn.onClick(() -> {
            modal.close();
            ImportFlow.pickAndImport(window);
        });
        NButton cancel = new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST);
        cancel.onClick(modal::close);
        create.onClick(this::create);
        modal.footer(importBtn, cancel, create);
        loaderVersion.setEnabled(false);
        loadManifest();
    }

    private void loadManifest() {
        listStatus.setText(I18n.tr("common.loading"));
        create.setEnabled(false);
        Tasks.async(() -> VersionManifest.get(App.get().dirs, false), m -> {
            manifest = m;
            listStatus.setText("");
            create.setEnabled(true);
            fillVersions();
        }, err -> listStatus.setText(I18n.tr("newInstance.manifestError", Tasks.message(err))));
    }

    private void fillVersions() {
        if (manifest == null) return;
        VersionManifest.Entry prev = versions.getSelectedValue();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        int f = filter.getSelected();
        for (VersionManifest.Entry e : manifest.versions) {
            boolean ok = switch (f) {
                case 0 -> e.isRelease();
                case 1 -> e.isSnapshot();
                default -> e.isOld();
            };
            if (ok && (q.isEmpty() || e.id().toLowerCase(Locale.ROOT).contains(q))) model.addElement(e);
        }
        if (prev != null && model.contains(prev)) versions.setSelectedValue(prev, true);
        else if (!model.isEmpty()) versions.setSelectedIndex(0);
    }

    private void suggestName() {
        if (nameEdited) return;
        VersionManifest.Entry e = versions.getSelectedValue();
        if (e == null) return;
        LoaderType t = LoaderType.values()[loader.getSelected()];
        settingName = true;
        name.setText(t == LoaderType.VANILLA ? e.id() : e.id() + " " + t.display);
        settingName = false;
    }

    private void loadLoaderVersions() {
        LoaderType t = LoaderType.values()[loader.getSelected()];
        VersionManifest.Entry e = versions.getSelectedValue();
        loaderVersion.setItems(List.of());
        loaderVersion.setEnabled(false);
        loaderVersion.setVisible(t != LoaderType.VANILLA);
        if (t == LoaderType.VANILLA || e == null) {
            loaderStatus.setText(t == LoaderType.VANILLA ? I18n.tr("newInstance.vanillaHint") : "");
            return;
        }
        String mc = e.id();
        loaderStatus.setText(I18n.tr("common.loading"));
        Tasks.async(() -> Loaders.versions(t, mc), list -> {
            VersionManifest.Entry now = versions.getSelectedValue();
            if (LoaderType.values()[loader.getSelected()] != t || now == null || !now.id().equals(mc)) return;
            loaderVersion.setItems(new ArrayList<>(list));
            loaderVersion.setEnabled(!list.isEmpty());
            String def = Loaders.pickDefault(list);
            for (Loaders.LoaderVersion v : list) if (v.id().equals(def)) loaderVersion.setSelectedItem(v);
            loaderStatus.setText(list.isEmpty() ? I18n.tr("newInstance.noLoaderVersions") : "");
        }, err -> loaderStatus.setText(I18n.tr("error.network")));
    }

    private void create() {
        VersionManifest.Entry e = versions.getSelectedValue();
        if (e == null) {
            Toast.show(I18n.tr("newInstance.pickVersion"), Toast.Kind.WARNING);
            return;
        }
        LoaderType t = LoaderType.values()[loader.getSelected()];
        Loaders.LoaderVersion lv = loaderVersion.selected();
        if (t != LoaderType.VANILLA && lv == null) {
            Toast.show(I18n.tr("newInstance.pickLoaderVersion"), Toast.Kind.WARNING);
            return;
        }
        String n = name.getText().trim().isEmpty() ? e.id() : name.getText().trim();
        create.setEnabled(false);
        Tasks.async(() -> App.get().instances.create(n, e.id(), t, lv != null ? lv.id() : ""), (Instance inst) -> {
            modal.close();
            Toast.show(I18n.tr("newInstance.created", inst.name), Toast.Kind.SUCCESS, I18n.tr("instance.play"), () -> window.launch.play(inst));
            window.openInstance(inst);
        }, err -> {
            create.setEnabled(true);
            Modal.error(I18n.tr("common.error"), Tasks.message(err));
        });
    }
}
