package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.ContentItem;
import com.nekyoh.nekylauncher.instance.ContentScanner;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.modrinth.Modrinth;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.dialogs.BrowseDialog;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mods / resource packs / shader packs / worlds of an instance. Files dropped anywhere on the tab
 * are copied into the right folder automatically.
 */
final class ContentTab extends JPanel {
    private final MainWindow window;
    private final Instance inst;
    private final ContentItem.Kind kind;
    private final JPanel list = Ui.transparent(null);
    private final NTextField filter = new NTextField(I18n.tr("content.filter"), Icons.Name.SEARCH);
    private final JLabel summary = Ui.small("");
    private List<ContentItem> items = new ArrayList<>();
    private boolean dropping;
    private boolean loading;

    ContentTab(MainWindow window, Instance inst, ContentItem.Kind kind) {
        super(new BorderLayout(0, 14));
        this.window = window;
        this.inst = inst;
        this.kind = kind;
        setOpaque(false);

        filter.setPreferredSize(new Dimension(240, 38));
        filter.onChange(t -> render());
        Stack bar = Stack.h(10);
        bar.with(filter, summary).glue();
        if (kind != ContentItem.Kind.WORLD) {
            NButton browse = new NButton(I18n.tr("content.browse"), Icons.Name.SEARCH, NButton.Variant.PRIMARY);
            browse.onClick(() -> BrowseDialog.open(window, inst, projectType(), this::reload));
            bar.with(browse);
        }
        NButton add = new NButton(I18n.tr("content.addFiles"), Icons.Name.PLUS, NButton.Variant.SECONDARY);
        add.onClick(this::pickFiles);
        NButton folder = NButton.icon(Icons.Name.FOLDER, I18n.tr("content.openFolder"), NButton.Variant.SECONDARY, NButton.Size.MD);
        folder.onClick(() -> OS.openFolder(ContentScanner.folder(inst, kind)));
        NButton refresh = NButton.icon(Icons.Name.REFRESH, I18n.tr("common.refresh"), NButton.Variant.GHOST, NButton.Size.MD);
        refresh.onClick(this::reload);
        bar.with(add, folder, refresh);

        JPanel top = Ui.transparent(new BorderLayout(0, 12));
        top.add(bar, BorderLayout.NORTH);
        JComponent banner = banner();
        if (banner != null) top.add(banner, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        list.setLayout(new StackListLayout());
        add(NScroll.vertical(list, Theme.BG), BorderLayout.CENTER);

        FileDrop.install(this, new FileDrop.Listener() {
            @Override public void dropped(List<Path> files) { addFiles(files); }

            @Override public void hover(boolean active) {
                dropping = active;
                repaint();
            }
        });
    }

    private Modrinth.ProjectType projectType() {
        return switch (kind) {
            case RESOURCEPACK -> Modrinth.ProjectType.RESOURCEPACK;
            case SHADERPACK -> Modrinth.ProjectType.SHADER;
            default -> Modrinth.ProjectType.MOD;
        };
    }

    private JComponent banner() {
        if (kind == ContentItem.Kind.MOD && inst.loader == LoaderType.VANILLA) {
            return infoBanner(I18n.tr("content.vanillaWarning"), Theme.AMBER, null, null);
        }
        if (kind == ContentItem.Kind.SHADERPACK) {
            boolean irisCapable = inst.loader == LoaderType.FABRIC || inst.loader == LoaderType.QUILT || inst.loader == LoaderType.NEOFORGE;
            if (irisCapable) {
                return infoBanner(I18n.tr("content.shaderHint"), Theme.CYAN, I18n.tr("content.installIris"),
                        () -> BrowseDialog.installBySlug(window, inst, "iris", this::reload));
            }
            return infoBanner(I18n.tr("content.shaderHintOther"), Theme.CYAN, null, null);
        }
        return null;
    }

    private JComponent infoBanner(String text, Color color, String action, Runnable r) {
        Card c = new Card(new BorderLayout(12, 0));
        c.colors(Theme.alpha(color, 0.07), Theme.alpha(color, 0.3));
        c.pad(10, 14, 10, 12);
        c.add(Ui.icon(Icons.Name.INFO, 18, color), BorderLayout.WEST);
        c.add(Ui.paragraph(text, Theme.TEXT_2, 12.5f), BorderLayout.CENTER);
        if (action != null) c.add(new NButton(action, Icons.Name.DOWNLOAD, NButton.Variant.OUTLINE, NButton.Size.SM).onClick(r), BorderLayout.EAST);
        return c;
    }

    private void pickFiles() {
        String[] ext = switch (kind) {
            case MOD -> new String[]{"jar"};
            case RESOURCEPACK, SHADERPACK, WORLD -> new String[]{"zip"};
        };
        List<Path> files = Pickers.openFiles(I18n.tr("content.addFiles"), true, ext);
        if (!files.isEmpty()) addFiles(files);
    }

    private void addFiles(List<Path> files) {
        List<Path> accepted = files.stream().filter(p -> ContentScanner.accepts(kind, p)).toList();
        if (accepted.isEmpty()) {
            Toast.show(I18n.tr("content.unsupported"), Toast.Kind.WARNING);
            return;
        }
        Tasks.async(() -> ContentScanner.addFiles(inst, kind, accepted), n -> {
            Toast.show(I18n.tr("content.added", n), Toast.Kind.SUCCESS);
            reload();
        }, err -> Modal.error(I18n.tr("common.error"), Tasks.message(err)));
    }

    void reload() {
        if (loading) return;
        loading = true;
        Tasks.async(() -> ContentScanner.scan(inst, kind), result -> {
            loading = false;
            items = result;
            render();
        }, err -> loading = false);
    }

    private void render() {
        list.removeAll();
        String q = filter.getText().trim().toLowerCase(Locale.ROOT);
        int disabled = 0;
        for (ContentItem it : items) {
            if (!it.enabled) disabled++;
            if (!q.isEmpty() && !(it.title.toLowerCase(Locale.ROOT).contains(q) || it.fileName.toLowerCase(Locale.ROOT).contains(q))) continue;
            list.add(new Row(it));
        }
        String noun = I18n.tr("content.count." + kind.name().toLowerCase(Locale.ROOT), items.size());
        summary.setText(disabled > 0 ? noun + "  ·  " + I18n.tr("content.disabledCount", disabled) : noun);
        if (items.isEmpty()) list.add(new DropZone());
        list.revalidate();
        list.repaint();
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (!dropping) return;
        Graphics2D g2 = Theme.hq(g.create());
        g2.setColor(Theme.alpha(Theme.BG, 0.86));
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 24, 24));
        g2.setColor(Theme.CYAN);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{8f, 8f}, 0));
        g2.draw(new RoundRectangle2D.Float(2, 2, getWidth() - 4, getHeight() - 4, 24, 24));
        Icons.get(Icons.Name.DOWNLOAD, 34, Theme.CYAN).paintIcon(this, g2, getWidth() / 2 - 17, getHeight() / 2 - 50);
        g2.setFont(Theme.font(17, Theme.Weight.SEMIBOLD));
        String t = I18n.tr("content.dropHere." + kind.name().toLowerCase(Locale.ROOT));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Theme.TEXT);
        g2.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, getHeight() / 2 + 8);
        g2.dispose();
    }

    /** Vertical list layout: rows at full width, preferred height, 8px gap. */
    private static final class StackListLayout implements LayoutManager {
        public void addLayoutComponent(String n, Component c) { }
        public void removeLayoutComponent(Component c) { }

        public Dimension preferredLayoutSize(Container p) {
            int h = 0;
            for (Component c : p.getComponents()) h += c.getPreferredSize().height + 8;
            return new Dimension(300, Math.max(0, h - 8));
        }

        public Dimension minimumLayoutSize(Container p) { return preferredLayoutSize(p); }

        public void layoutContainer(Container p) {
            int y = 0, w = p.getWidth();
            for (Component c : p.getComponents()) {
                int h = c.getPreferredSize().height;
                c.setBounds(0, y, w, h);
                y += h + 8;
            }
        }
    }

    private final class DropZone extends JComponent {
        DropZone() { setPreferredSize(new Dimension(300, 220)); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.SURFACE);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 24, 24));
            g2.setColor(Theme.BORDER_STRONG);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{7f, 7f}, 0));
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 24, 24));
            Icons.Name ic = switch (kind) {
                case MOD -> Icons.Name.CUBE;
                case RESOURCEPACK -> Icons.Name.IMAGE;
                case SHADERPACK -> Icons.Name.SPARKLES;
                case WORLD -> Icons.Name.GLOBE;
            };
            Icons.get(ic, 34, Theme.TEXT_3).paintIcon(this, g2, w / 2 - 17, h / 2 - 62);
            g2.setFont(Theme.font(16, Theme.Weight.SEMIBOLD));
            g2.setColor(Theme.TEXT);
            String t = I18n.tr("content.empty." + kind.name().toLowerCase(Locale.ROOT));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2 + 4);
            g2.setFont(Theme.font(13));
            g2.setColor(Theme.TEXT_2);
            String d = I18n.tr("content.emptyHint");
            fm = g2.getFontMetrics();
            g2.drawString(d, (w - fm.stringWidth(d)) / 2, h / 2 + 30);
            g2.dispose();
        }
    }

    /** One item: icon, title, version, description, enable toggle and actions. */
    private final class Row extends Card {
        Row(ContentItem it) {
            super(new BorderLayout(14, 0));
            pad(10, 12, 10, 12);
            JComponent icon = new JComponent() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = Theme.hq(g.create());
                    if (it.icon != null) {
                        g2.setColor(Theme.SURFACE_2);
                        g2.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 18, 18));
                        Images.drawRounded(g2, it.icon, 0, 0, 44, 9);
                    } else {
                        g2.setColor(Theme.SURFACE_3);
                        g2.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 18, 18));
                        Icons.Name n = switch (kind) {
                            case MOD -> Icons.Name.CUBE;
                            case RESOURCEPACK -> Icons.Name.IMAGE;
                            case SHADERPACK -> Icons.Name.SPARKLES;
                            case WORLD -> Icons.Name.GLOBE;
                        };
                        Icons.get(n, 20, Theme.TEXT_3).paintIcon(this, g2, 12, 12);
                    }
                    if (!it.enabled) {
                        g2.setColor(new Color(11, 13, 18, 150));
                        g2.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 18, 18));
                    }
                    g2.dispose();
                }
            };
            icon.setPreferredSize(new Dimension(44, 44));
            add(icon, BorderLayout.WEST);

            Stack text = Stack.v(3);
            JPanel titleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JLabel title = Ui.label(it.title, 14, Theme.Weight.SEMIBOLD, it.enabled ? Theme.TEXT : Theme.TEXT_3);
            titleRow.add(title);
            if (it.version != null && !it.version.isBlank()) {
                JLabel v = Ui.small("   " + it.version);
                titleRow.add(v);
            }
            text.with(titleRow);
            String desc = it.description == null || it.description.isBlank() ? it.fileName : it.description.replace('\n', ' ');
            String extra = kind == ContentItem.Kind.WORLD ? "" : "  ·  " + FileUtil.humanSize(it.size);
            JLabel d = Ui.small(desc + (desc.equals(it.fileName) ? extra : ""));
            d.setToolTipText(it.fileName + (it.authors.isBlank() ? "" : "  —  " + it.authors));
            text.with(d);
            JPanel textWrap = Ui.transparent(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            textWrap.add(text, gc);
            add(textWrap, BorderLayout.CENTER);

            Stack actions = Stack.h(6);
            if (kind != ContentItem.Kind.WORLD) {
                Controls.Toggle toggle = new Controls.Toggle(it.enabled);
                toggle.setToolTipText(I18n.tr(it.enabled ? "content.disable" : "content.enable"));
                toggle.onChange(on -> Tasks.async(() -> ContentScanner.setEnabled(it, on), p -> reload(),
                        err -> Modal.error(I18n.tr("common.error"), Tasks.message(err))));
                actions.with(toggle).space(6);
            }
            NButton reveal = NButton.icon(Icons.Name.FOLDER, I18n.tr("content.showFile"), NButton.Variant.GHOST, NButton.Size.SM);
            reveal.onClick(() -> {
                if (kind == ContentItem.Kind.WORLD) OS.openFolder(it.path);
                else OS.revealFile(it.path);
            });
            NButton del = NButton.icon(Icons.Name.TRASH, I18n.tr("common.delete"), NButton.Variant.GHOST, NButton.Size.SM);
            del.onClick(() -> Modal.confirm(I18n.tr("content.deleteTitle"), I18n.tr("content.deleteMessage", it.title),
                    I18n.tr("common.delete"), true, () -> Tasks.async(() -> FileUtil.deleteRecursive(it.path), this::done,
                            err -> Modal.error(I18n.tr("common.error"), Tasks.message(err)))));
            actions.with(reveal, del);
            JPanel actionsWrap = Ui.transparent(new GridBagLayout());
            actionsWrap.add(actions);
            add(actionsWrap, BorderLayout.EAST);
            setPreferredSize(new Dimension(300, 66));
        }

        private void done() { reload(); }
    }
}
