package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.ContentItem;
import com.nekyoh.nekylauncher.instance.ContentScanner;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.modrinth.Modrinth;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.UiText;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/** Browse Modrinth and install mods / packs / shaders (with dependencies) or whole modpacks. */
public final class ModrinthDialog {
    private final MainWindow window;
    private final Instance inst; // null = modpack mode
    private final Runnable onInstalled;
    private Modrinth.ProjectType type;
    private final NTextField query = new NTextField(I18n.tr("modrinth.search"), Icons.Name.SEARCH);
    private final Stack results = Stack.v(8);
    private final JLabel status = Ui.small("");
    private final Timer debounce;
    private int offset;
    private int searchSeq;

    private ModrinthDialog(MainWindow window, Instance inst, Modrinth.ProjectType type, Runnable onInstalled) {
        this.window = window;
        this.inst = inst;
        this.type = type;
        this.onInstalled = onInstalled;
        debounce = new Timer(350, e -> search(true));
        debounce.setRepeats(false);
    }

    public static void open(MainWindow window, Instance inst, Modrinth.ProjectType type, Runnable onInstalled) {
        new ModrinthDialog(window, inst, type, onInstalled).show();
    }

    public static void openModpacks(MainWindow window) {
        new ModrinthDialog(window, null, Modrinth.ProjectType.MODPACK, null).show();
    }

    private void show() {
        JPanel top = Ui.transparent(new BorderLayout(10, 0));
        top.add(query, BorderLayout.CENTER);
        query.onChange(t -> debounce.restart());
        if (inst != null) {
            List<Modrinth.ProjectType> types = List.of(Modrinth.ProjectType.MOD, Modrinth.ProjectType.RESOURCEPACK, Modrinth.ProjectType.SHADER);
            Controls.Segmented seg = new Controls.Segmented(List.of(I18n.tr("tab.mods"), I18n.tr("tab.resourcepacks"), I18n.tr("tab.shaderpacks")), types.indexOf(type));
            seg.onChange(i -> {
                type = types.get(i);
                search(true);
            });
            top.add(seg, BorderLayout.EAST);
        }
        JPanel info = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        info.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
        if (inst != null) {
            info.add(Ui.chip(I18n.tr("modrinth.for", inst.name), Theme.CYAN));
            info.add(Ui.chip("Minecraft " + inst.mcVersion, Theme.GREEN));
            if (inst.loader.isModded()) info.add(Ui.chip(inst.loader.display, inst.loader.color));
        }
        info.add(status);

        JScrollPane sp = NScroll.vertical(results, Theme.SURFACE);
        sp.setPreferredSize(new Dimension(820, 440));
        Stack body = Stack.v(12);
        body.with(top, info, sp);
        String title = inst == null ? I18n.tr("modrinth.modpacksTitle") : I18n.tr("modrinth.title");
        Modal m = Modal.open(title, body, 880);
        m.footer(Ui.small(I18n.tr("modrinth.credit")), new NButton(I18n.tr("common.close"), NButton.Variant.GHOST).onClick(m::close));
        m.onClose(debounce::stop);
        search(true);
    }

    private void search(boolean reset) {
        if (reset) offset = 0;
        int seq = ++searchSeq;
        status.setText(I18n.tr("common.loading"));
        String q = query.getText().trim();
        String mc = inst != null ? inst.mcVersion : null;
        LoaderType loader = inst != null ? inst.loader : null;
        Modrinth.ProjectType t = type;
        Tasks.async(() -> Modrinth.search(q, t, mc, loader, offset, 20), res -> {
            if (seq != searchSeq) return;
            if (reset) results.removeAll();
            else if (results.getComponentCount() > 0) results.remove(results.getComponentCount() - 1); // "load more"
            for (Modrinth.Hit h : res.hits()) results.with(row(h));
            offset += res.hits().size();
            status.setText(I18n.tr("modrinth.results", res.total()));
            if (offset < res.total()) {
                NButton more = new NButton(I18n.tr("modrinth.loadMore"), NButton.Variant.SECONDARY);
                more.onClick(() -> search(false));
                results.with(Ui.center(more));
            }
            if (res.hits().isEmpty() && reset) {
                JLabel none = Ui.muted(I18n.tr("modrinth.none"));
                none.setHorizontalAlignment(SwingConstants.CENTER);
                none.setBorder(BorderFactory.createEmptyBorder(40, 0, 40, 0));
                results.with(none);
            }
            results.revalidate();
            results.repaint();
        }, err -> {
            if (seq == searchSeq) status.setText(I18n.tr("modrinth.error", Tasks.message(err)));
        });
    }

    private JComponent row(Modrinth.Hit h) {
        Card c = new Card(new BorderLayout(14, 0));
        c.colors(Theme.SURFACE_2, Theme.BORDER);
        c.pad(12, 12, 12, 14);
        BufferedImage[] icon = {null};
        JComponent ic = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.hq(g.create());
                g2.setColor(Theme.SURFACE_3);
                g2.fill(new RoundRectangle2D.Float(0, 0, 52, 52, 20, 20));
                if (icon[0] != null) Images.drawRounded(g2, icon[0], 0, 0, 52, 10);
                else Icons.get(Icons.Name.CUBE, 22, Theme.TEXT_3).paintIcon(this, g2, 15, 15);
                g2.dispose();
            }
        };
        ic.setPreferredSize(new Dimension(52, 52));
        Images.url(h.iconUrl(), img -> {
            icon[0] = img;
            ic.repaint();
        });
        c.add(Ui.center(ic), BorderLayout.WEST);
        Stack t = Stack.v(4);
        JPanel titleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.add(Ui.label(h.title(), 14.5f, Theme.Weight.SEMIBOLD, Theme.TEXT));
        titleRow.add(Ui.small("   " + I18n.tr("modrinth.by", h.author())));
        t.with(titleRow, Ui.paragraph(h.description(), Theme.TEXT_2, 12.5f),
                Ui.small("↓ " + UiText.count(h.downloads()) + "   ·   " + String.join(", ", h.categories().stream().limit(4).toList())));
        c.add(t, BorderLayout.CENTER);
        NButton install = new NButton(I18n.tr("modrinth.install"), Icons.Name.DOWNLOAD, NButton.Variant.PRIMARY, NButton.Size.SM);
        install.onClick(() -> install(h, install));
        NButton page = NButton.icon(Icons.Name.EXTERNAL, I18n.tr("modrinth.openPage"), NButton.Variant.GHOST, NButton.Size.SM);
        page.onClick(() -> com.nekyoh.nekylauncher.util.OS.openUrl("https://modrinth.com/" + h.type() + "/" + h.slug()));
        Stack actions = Stack.h(6);
        actions.with(page, install);
        c.add(Ui.center(actions), BorderLayout.EAST);
        return c;
    }

    private void install(Modrinth.Hit h, NButton btn) {
        btn.setEnabled(false);
        btn.setText(I18n.tr("modrinth.installing"));
        if (inst == null) {
            installModpack(h, btn);
            return;
        }
        Modrinth.ProjectType t = type;
        Tasks.async(() -> {
            List<String> loaders = Modrinth.loaderFilter(t, inst.loader);
            Modrinth.Version v = Modrinth.best(Modrinth.versions(h.id(), loaders, inst.mcVersion));
            if (v == null) throw new java.io.IOException(I18n.tr("modrinth.noVersion", h.title(), inst.describe()));
            Path dir = ContentScanner.folder(inst, kindFor(t));
            return Modrinth.install(v, dir, t, inst.loader, inst.mcVersion, true, Progress.NONE, null);
        }, written -> {
            btn.setText(I18n.tr("modrinth.installed"));
            btn.setIconName(Icons.Name.CHECK);
            int deps = Math.max(0, written.size() - 1);
            Toast.show(deps > 0 ? I18n.tr("modrinth.installedWithDeps", h.title(), deps) : I18n.tr("modrinth.installedOne", h.title()), Toast.Kind.SUCCESS);
            if (onInstalled != null) onInstalled.run();
        }, err -> {
            btn.setEnabled(true);
            btn.setText(I18n.tr("modrinth.install"));
            Toast.show(Tasks.message(err), Toast.Kind.ERROR);
        });
    }

    private void installModpack(Modrinth.Hit h, NButton btn) {
        Tasks.async(() -> {
            Modrinth.Version v = Modrinth.best(Modrinth.versions(h.id(), List.of(), null));
            if (v == null || v.primary() == null) throw new java.io.IOException(I18n.tr("modrinth.noVersion", h.title(), ""));
            Path file = App.get().dirs.cache().resolve("modpacks").resolve(v.primary().filename());
            Http.download(v.primary().url(), file, v.primary().sha1(), null, null);
            return file;
        }, file -> {
            btn.setText(I18n.tr("modrinth.installed"));
            ImportFlow.importFile(window, file);
        }, err -> {
            btn.setEnabled(true);
            btn.setText(I18n.tr("modrinth.install"));
            Toast.show(Tasks.message(err), Toast.Kind.ERROR);
        });
    }

    private static ContentItem.Kind kindFor(Modrinth.ProjectType t) {
        return switch (t) {
            case RESOURCEPACK -> ContentItem.Kind.RESOURCEPACK;
            case SHADER -> ContentItem.Kind.SHADERPACK;
            default -> ContentItem.Kind.MOD;
        };
    }

    /** One-click install of a well-known project (e.g. Iris for shaders) with its dependencies. */
    public static void installBySlug(MainWindow window, Instance inst, String slug, Runnable onDone) {
        Toast.show(I18n.tr("modrinth.installing") + " " + slug, Toast.Kind.INFO);
        Tasks.async(() -> {
            List<String> loaders = Modrinth.loaderFilter(Modrinth.ProjectType.MOD, inst.loader);
            Modrinth.Version v = Modrinth.best(Modrinth.versions(slug, loaders, inst.mcVersion));
            if (v == null) throw new java.io.IOException(I18n.tr("modrinth.noVersion", slug, inst.describe()));
            return Modrinth.install(v, inst.modsDir(), Modrinth.ProjectType.MOD, inst.loader, inst.mcVersion, true, Progress.NONE, null);
        }, written -> {
            Toast.show(I18n.tr("modrinth.installedWithDeps", slug, Math.max(0, written.size() - 1)), Toast.Kind.SUCCESS);
            if (onDone != null) onDone.run();
        }, err -> Toast.show(Tasks.message(err), Toast.Kind.ERROR));
    }
}
