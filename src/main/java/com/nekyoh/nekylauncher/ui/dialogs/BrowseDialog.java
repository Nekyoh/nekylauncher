package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.curseforge.CurseForge;
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
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browse Modrinth or CurseForge and install mods / resource packs / shaders (with required
 * dependencies) into an instance, or whole modpacks as new instances.
 */
public final class BrowseDialog {
    public enum Source {
        MODRINTH("Modrinth", new Color(0x1BD96A)), CURSEFORGE("CurseForge", new Color(0xF16436));

        public final String display;
        public final Color color;

        Source(String display, Color color) {
            this.display = display;
            this.color = color;
        }

        static Source of(String s) { return "curseforge".equalsIgnoreCase(s) ? CURSEFORGE : MODRINTH; }
    }

    /** One search result, whatever the source. */
    private record Item(Source source, String id, String title, String description, String author, long downloads,
                        String iconUrl, List<String> categories, String pageUrl, CurseForge.Hit cf) {}

    private record Page(List<Item> items, int total) {}

    private final MainWindow window;
    private final Instance inst; // null = modpack mode
    private final Runnable onInstalled;
    private Modrinth.ProjectType type;
    private Source source;
    private final NTextField query = new NTextField("", Icons.Name.SEARCH);
    private final Stack results = Stack.v(8);
    private final JLabel status = Ui.small("");
    private final JLabel credit = Ui.small("");
    private final Timer debounce;
    private int offset;
    private int searchSeq;
    private Modal modal;
    /** Installed project ids per source+type folder, computed lazily (hash / fingerprint lookup). */
    private final Map<String, Set<String>> installed = new ConcurrentHashMap<>();

    private BrowseDialog(MainWindow window, Instance inst, Modrinth.ProjectType type, Runnable onInstalled) {
        this.window = window;
        this.inst = inst;
        this.type = type;
        this.onInstalled = onInstalled;
        this.source = Source.of(App.get().settings.browseSource);
        debounce = new Timer(350, e -> search(true));
        debounce.setRepeats(false);
    }

    public static void open(MainWindow window, Instance inst, Modrinth.ProjectType type, Runnable onInstalled) {
        new BrowseDialog(window, inst, type, onInstalled).show();
    }

    public static void openModpacks(MainWindow window) {
        new BrowseDialog(window, null, Modrinth.ProjectType.MODPACK, null).show();
    }

    private void show() {
        Controls.Segmented src = new Controls.Segmented(List.of(Source.MODRINTH.display, Source.CURSEFORGE.display), source.ordinal());
        src.onChange(i -> {
            source = Source.values()[i];
            App.get().settings.browseSource = source.name().toLowerCase(Locale.ROOT);
            App.get().settings.save();
            updateTexts();
            search(true);
        });
        JPanel top = Ui.transparent(new BorderLayout(10, 0));
        top.add(src, BorderLayout.WEST);
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
        sp.setPreferredSize(new Dimension(860, 440));
        Stack body = Stack.v(12);
        body.with(top, info, sp);
        modal = Modal.open("", body, 920);
        modal.footer(credit, new NButton(I18n.tr("common.close"), NButton.Variant.GHOST).onClick(modal::close));
        modal.onClose(debounce::stop);
        updateTexts();
        search(true);
    }

    private void updateTexts() {
        modal.setTitle(inst == null ? I18n.tr("browse.modpacksTitle", source.display) : I18n.tr("browse.title"));
        query.setPlaceholder(I18n.tr("browse.search", source.display));
        credit.setText(I18n.tr("browse.credit", source.display));
    }

    // ------------------------------------------------------------------ search

    private Page fetch(Source s, String q, Modrinth.ProjectType t, int off) throws IOException {
        String mc = inst != null ? inst.mcVersion : null;
        LoaderType loader = inst != null ? inst.loader : null;
        List<Item> items = new ArrayList<>();
        if (s == Source.MODRINTH) {
            Modrinth.SearchResult r = Modrinth.search(q, t, mc, loader, off, 20);
            for (Modrinth.Hit h : r.hits()) {
                items.add(new Item(s, h.id(), h.title(), h.description(), h.author(), h.downloads(), h.iconUrl(), h.categories(),
                        "https://modrinth.com/" + h.type() + "/" + h.slug(), null));
            }
            return new Page(items, r.total());
        }
        CurseForge.SearchResult r = CurseForge.search(q, cfType(t), mc, loader, off, 20);
        for (CurseForge.Hit h : r.hits()) {
            items.add(new Item(s, String.valueOf(h.id()), h.title(), h.summary(), h.author(), h.downloads(), h.iconUrl(), h.categories(), h.pageUrl(), h));
        }
        return new Page(items, r.total());
    }

    static CurseForge.ClassType cfType(Modrinth.ProjectType t) {
        return switch (t) {
            case MOD -> CurseForge.ClassType.MOD;
            case RESOURCEPACK -> CurseForge.ClassType.RESOURCEPACK;
            case SHADER -> CurseForge.ClassType.SHADER;
            case MODPACK -> CurseForge.ClassType.MODPACK;
        };
    }

    private void search(boolean reset) {
        if (reset) offset = 0;
        int seq = ++searchSeq;
        if (source == Source.CURSEFORGE && !CurseForge.available()) {
            results.removeAll();
            results.with(noKeyCard());
            status.setText("");
            results.revalidate();
            results.repaint();
            return;
        }
        status.setText(I18n.tr("common.loading"));
        String q = query.getText().trim();
        Modrinth.ProjectType t = type;
        Source s = source;
        Tasks.async(() -> {
            Page p = fetch(s, q, t, offset);
            if (inst != null) installedIds(s, t); // warm up "installed" marks off the UI thread
            return p;
        }, res -> {
            if (seq != searchSeq) return;
            if (reset) results.removeAll();
            else if (results.getComponentCount() > 0) results.remove(results.getComponentCount() - 1); // "load more"
            Set<String> have = inst == null ? Set.of() : installed.getOrDefault(key(s, t), Set.of());
            for (Item h : res.items()) results.with(row(h, have.contains(h.id())));
            offset += res.items().size();
            status.setText(I18n.tr("modrinth.results", res.total()));
            if (offset < res.total() && !res.items().isEmpty()) {
                NButton more = new NButton(I18n.tr("modrinth.loadMore"), NButton.Variant.SECONDARY);
                more.onClick(() -> search(false));
                results.with(Ui.center(more));
            }
            if (res.items().isEmpty() && reset) {
                JLabel none = Ui.muted(I18n.tr("modrinth.none"));
                none.setHorizontalAlignment(SwingConstants.CENTER);
                none.setBorder(BorderFactory.createEmptyBorder(40, 0, 40, 0));
                results.with(none);
            }
            results.revalidate();
            results.repaint();
        }, err -> {
            if (seq == searchSeq) status.setText(I18n.tr("browse.error", s.display, Tasks.message(err)));
        });
    }

    private JComponent noKeyCard() {
        Card c = new Card(new BorderLayout(14, 0));
        c.colors(Theme.alpha(Source.CURSEFORGE.color, 0.07), Theme.alpha(Source.CURSEFORGE.color, 0.3));
        c.pad(18, 18, 18, 18);
        c.add(Ui.icon(Icons.Name.INFO, 20, Source.CURSEFORGE.color), BorderLayout.WEST);
        c.add(Ui.paragraph(I18n.tr("cf.noKeyLong"), Theme.TEXT_2, 12.5f), BorderLayout.CENTER);
        return c;
    }

    private static String key(Source s, Modrinth.ProjectType t) { return s + ":" + t; }

    private Set<String> installedIds(Source s, Modrinth.ProjectType t) {
        return installed.computeIfAbsent(key(s, t), k -> {
            Path dir = ContentScanner.folder(inst, kindFor(t));
            Set<String> ids = ConcurrentHashMap.newKeySet();
            if (s == Source.MODRINTH) ids.addAll(Modrinth.installedProjects(dir).keySet());
            else CurseForge.installedProjects(dir).keySet().forEach(id -> ids.add(String.valueOf(id)));
            return ids;
        });
    }

    // ------------------------------------------------------------------ rows

    private JComponent row(Item h, boolean isInstalled) {
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
        if (!h.author().isBlank()) titleRow.add(Ui.small("   " + I18n.tr("modrinth.by", h.author())));
        if (isInstalled) {
            titleRow.add(Box.createHorizontalStrut(8));
            titleRow.add(Ui.chip(I18n.tr("modrinth.installed"), Theme.GREEN, Icons.Name.CHECK));
        }
        t.with(titleRow, Ui.paragraph(h.description(), Theme.TEXT_2, 12.5f),
                Ui.small("↓ " + UiText.count(h.downloads()) + "   ·   " + String.join(", ", h.categories().stream().limit(4).toList())));
        c.add(t, BorderLayout.CENTER);
        NButton install = new NButton(I18n.tr(isInstalled ? "browse.reinstall" : "modrinth.install"), Icons.Name.DOWNLOAD,
                isInstalled ? NButton.Variant.SECONDARY : NButton.Variant.PRIMARY, NButton.Size.SM);
        install.onClick(() -> install(h, install));
        NButton page = NButton.icon(Icons.Name.EXTERNAL, I18n.tr("browse.openPage", h.source().display), NButton.Variant.GHOST, NButton.Size.SM);
        page.onClick(() -> OS.openUrl(h.pageUrl()));
        Stack actions = Stack.h(6);
        actions.with(page, install);
        c.add(Ui.center(actions), BorderLayout.EAST);
        return c;
    }

    // ------------------------------------------------------------------ install

    private record Installed(int files, List<CurseForge.Blocked> blocked) {}

    private void install(Item h, NButton btn) {
        btn.setEnabled(false);
        btn.setText(I18n.tr("modrinth.installing"));
        if (inst == null) {
            installModpack(h, btn);
            return;
        }
        Modrinth.ProjectType t = type;
        Tasks.async(() -> {
            Path dir = ContentScanner.folder(inst, kindFor(t));
            if (h.source() == Source.MODRINTH) {
                List<String> loaders = Modrinth.loaderFilter(t, inst.loader, inst.mcVersion);
                Modrinth.Version v = Modrinth.best(Modrinth.versions(h.id(), loaders, inst.mcVersion));
                if (v == null) throw new IOException(I18n.tr("modrinth.noVersion", h.title(), inst.describe()));
                return new Installed(Modrinth.install(v, dir, t, inst.loader, inst.mcVersion, true, Progress.NONE, null).size(), List.of());
            }
            CurseForge.ClassType ct = cfType(t);
            CurseForge.FileInfo f = CurseForge.best(CurseForge.projectFiles(h.cf().id(), ct, inst.mcVersion, inst.loader));
            if (f == null) throw new IOException(I18n.tr("modrinth.noVersion", h.title(), inst.describe()));
            CurseForge.InstallResult r = CurseForge.install(h.cf(), f, dir, ct, inst.loader, inst.mcVersion, true, Progress.NONE, null);
            return new Installed(r.written().size(), r.blocked());
        }, res -> {
            btn.setText(I18n.tr("modrinth.installed"));
            btn.setIconName(Icons.Name.CHECK);
            // the folder changed (dependencies too): recompute "installed" marks on the next search
            installed.keySet().removeIf(k -> k.endsWith(":" + t));
            int deps = Math.max(0, res.files() - 1);
            Toast.show(deps > 0 ? I18n.tr("modrinth.installedWithDeps", h.title(), deps) : I18n.tr("modrinth.installedOne", h.title()), Toast.Kind.SUCCESS);
            if (!res.blocked().isEmpty()) showBlocked(res.blocked());
            if (onInstalled != null) onInstalled.run();
        }, err -> {
            btn.setEnabled(true);
            btn.setText(I18n.tr("modrinth.install"));
            if (err instanceof CurseForge.DistributionBlockedException b) showBlocked(List.of(b.blocked));
            else Toast.show(Tasks.message(err), Toast.Kind.ERROR);
        });
    }

    private void installModpack(Item h, NButton btn) {
        Tasks.async(() -> {
            Path dir = App.get().dirs.cache().resolve("modpacks");
            if (h.source() == Source.MODRINTH) {
                Modrinth.Version v = Modrinth.best(Modrinth.versions(h.id(), List.of(), null));
                if (v == null || v.primary() == null) throw new IOException(I18n.tr("modrinth.noVersion", h.title(), ""));
                Path file = dir.resolve(v.primary().filename());
                Http.download(v.primary().url(), file, v.primary().sha1(), null, null);
                return file;
            }
            CurseForge.FileInfo f = CurseForge.best(CurseForge.projectFiles(h.cf().id(), CurseForge.ClassType.MODPACK, null, null));
            if (f == null) throw new IOException(I18n.tr("modrinth.noVersion", h.title(), ""));
            if (f.downloadUrl() == null) throw new CurseForge.DistributionBlockedException(new CurseForge.Blocked(h.title(), h.pageUrl()));
            Path file = com.nekyoh.nekylauncher.util.FileUtil.safeResolve(dir, f.fileName());
            Http.download(f.downloadUrl(), file, f.sha1(), null, null);
            return file;
        }, file -> {
            btn.setText(I18n.tr("modrinth.installed"));
            ImportFlow.importFile(window, file);
        }, err -> {
            btn.setEnabled(true);
            btn.setText(I18n.tr("modrinth.install"));
            if (err instanceof CurseForge.DistributionBlockedException b) showBlocked(List.of(b.blocked));
            else Toast.show(Tasks.message(err), Toast.Kind.ERROR);
        });
    }

    /** Files the author only allows from curseforge.com: link to each so the user can get them by hand. */
    public static void showBlocked(List<CurseForge.Blocked> blocked) {
        Stack s = Stack.v(10);
        s.with(Ui.paragraph(I18n.tr("cf.blocked"), Theme.TEXT_2, 13));
        for (CurseForge.Blocked b : blocked) {
            JPanel row = Ui.transparent(new BorderLayout(10, 0));
            row.add(Ui.label(b.name(), 13, Theme.Weight.MEDIUM, Theme.TEXT), BorderLayout.CENTER);
            row.add(new NButton(I18n.tr("cf.openPage"), Icons.Name.EXTERNAL, NButton.Variant.SECONDARY, NButton.Size.SM)
                    .onClick(() -> OS.openUrl(b.pageUrl())), BorderLayout.EAST);
            s.with(row);
        }
        Modal m = Modal.open(I18n.tr("cf.blockedTitle"), s, 560);
        m.footer(new NButton(I18n.tr("common.close"), NButton.Variant.GHOST).onClick(m::close));
    }

    private static ContentItem.Kind kindFor(Modrinth.ProjectType t) {
        return switch (t) {
            case RESOURCEPACK -> ContentItem.Kind.RESOURCEPACK;
            case SHADER -> ContentItem.Kind.SHADERPACK;
            default -> ContentItem.Kind.MOD;
        };
    }

    /** One-click install of a well-known Modrinth project (e.g. Iris for shaders) with its dependencies. */
    public static void installBySlug(MainWindow window, Instance inst, String slug, Runnable onDone) {
        Toast.show(I18n.tr("modrinth.installing") + " " + slug, Toast.Kind.INFO);
        Tasks.async(() -> {
            List<String> loaders = Modrinth.loaderFilter(Modrinth.ProjectType.MOD, inst.loader, inst.mcVersion);
            Modrinth.Version v = Modrinth.best(Modrinth.versions(slug, loaders, inst.mcVersion));
            if (v == null) throw new IOException(I18n.tr("modrinth.noVersion", slug, inst.describe()));
            return Modrinth.install(v, inst.modsDir(), Modrinth.ProjectType.MOD, inst.loader, inst.mcVersion, true, Progress.NONE, null);
        }, written -> {
            Toast.show(I18n.tr("modrinth.installedWithDeps", slug, Math.max(0, written.size() - 1)), Toast.Kind.SUCCESS);
            if (onDone != null) onDone.run();
        }, err -> Toast.show(Tasks.message(err), Toast.Kind.ERROR));
    }
}
