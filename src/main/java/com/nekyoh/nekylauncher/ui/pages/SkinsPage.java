package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.skins.SkinApi;
import com.nekyoh.nekylauncher.skins.SkinLibrary;
import com.nekyoh.nekylauncher.ui.*;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin changer, like the official launcher: upload a PNG, pick classic/slim arms, choose one of the
 * account's capes and apply it to the Minecraft profile. Combinations are kept as presets.
 */
public final class SkinsPage extends JPanel {
    private final MainWindow window;
    private final Stack content = Stack.v(22);

    // state of the account currently shown
    private String loadedFor;
    private SkinApi.Profile profile;
    private String loadError;
    private boolean loading;
    private boolean busy;

    // editor state
    private byte[] png;
    private BufferedImage skin;
    private boolean slim;
    private String capeId = "";
    private boolean skinChanged;
    private String sourceName;

    private Preview preview;
    private Controls.Segmented model;
    private JPanel capeRow;
    private NTextField presetName;
    private NButton applyBtn, saveBtn;
    private JLabel fileLabel;
    private JPanel presetGrid;

    private static final Map<String, BufferedImage> PRESET_THUMBS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> CAPE_REQUESTED = ConcurrentHashMap.newKeySet();

    public SkinsPage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);
        content.pad(26, 28, 28, 28);
        add(NScroll.vertical(content, Theme.BG), BorderLayout.CENTER);
        build();
    }

    /** Called when the page is shown or accounts change: reloads the profile when the account differs. */
    public void refresh() {
        Account a = App.get().accounts.active();
        String id = a == null ? null : a.id;
        if (!Objects.equals(id, loadedFor)) {
            loadedFor = id;
            profile = null;
            loadError = null;
            png = null;
            skin = null;
            skinChanged = false;
            capeId = "";
            if (a != null) load(a);
            build();
        }
    }

    private void load(Account a) {
        loading = true;
        Tasks.async(() -> {
            App.get().launcher.refreshAccount(a, Progress.NONE);
            SkinApi.Profile p = SkinApi.fetch(a.accessToken);
            SkinApi.Skin s = p.activeSkin();
            byte[] data = s == null || s.url() == null ? null : Http.getBytes(s.url());
            return new Loaded(p, data);
        }, l -> {
            if (!Objects.equals(loadedFor, a.id)) return;
            loading = false;
            profile = l.profile;
            SkinApi.Skin s = profile.activeSkin();
            if (l.png != null && !skinChanged) {
                setSkin(l.png, s != null && s.slim(), null);
                skinChanged = false;
            }
            SkinApi.Cape c = profile.activeCape();
            capeId = c == null ? "" : c.id();
            syncAccountSkin(a);
            build();
        }, err -> {
            if (!Objects.equals(loadedFor, a.id)) return;
            loading = false;
            loadError = Tasks.message(err);
            build();
        });
    }

    private record Loaded(SkinApi.Profile profile, byte[] png) {}

    // ------------------------------------------------------------------ layout

    private void build() {
        content.removeAll();
        Stack head = Stack.v(4);
        head.with(Ui.h1(I18n.tr("skins.title")), Ui.muted(I18n.tr("skins.subtitle")));
        content.with(head);

        Account a = App.get().accounts.active();
        if (a == null) {
            Card c = new Card(new BorderLayout(16, 0));
            c.pad(20, 22, 20, 22);
            c.add(Ui.paragraph(I18n.tr("skins.needAccount"), Theme.TEXT_2, 13), BorderLayout.CENTER);
            c.add(Ui.center(new NButton(I18n.tr("accounts.ms.login"), Icons.Name.USER, NButton.Variant.PRIMARY)
                    .onClick(() -> window.showPage(MainWindow.Page.ACCOUNTS))), BorderLayout.EAST);
            content.with(c);
        } else {
            if (loadError != null) content.with(errorBanner(a));
            content.with(editor(a));
        }
        content.with(presetsSection(a));
        content.revalidate();
        content.repaint();
    }

    private JComponent errorBanner(Account a) {
        Card c = new Card(new BorderLayout(12, 0)).colors(Theme.alpha(Theme.RED, 0.08), Theme.alpha(Theme.RED, 0.35));
        c.pad(12, 16, 12, 16);
        c.add(Ui.icon(Icons.Name.WARNING, 18, Theme.RED), BorderLayout.WEST);
        c.add(Ui.paragraph(I18n.tr("skins.loadFailed", loadError), Theme.TEXT_2, 12.5f), BorderLayout.CENTER);
        c.add(Ui.center(new NButton(I18n.tr("skins.retry"), Icons.Name.REFRESH, NButton.Variant.SECONDARY).onClick(() -> {
            loadError = null;
            load(a);
            build();
        })), BorderLayout.EAST);
        return c;
    }

    private JComponent editor(Account a) {
        Card card = new Card(new BorderLayout(26, 0));
        card.pad(20, 22, 20, 22);

        preview = new Preview();
        JPanel left = Ui.transparent(new BorderLayout(0, 8));
        left.add(preview, BorderLayout.CENTER);
        JLabel hint = Ui.small(I18n.tr("skins.dropHint"));
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        left.add(hint, BorderLayout.SOUTH);
        card.add(left, BorderLayout.WEST);

        Stack form = Stack.v(18);

        // skin file
        fileLabel = Ui.small(sourceLabel());
        NButton choose = new NButton(I18n.tr("skins.choose"), Icons.Name.UPLOAD, NButton.Variant.SECONDARY).onClick(this::pickFile);
        Stack fileRow = Stack.h(12);
        fileRow.with(choose, fileLabel);
        form.with(Ui.field(I18n.tr("skins.file"), null, Ui.left(fileRow)));

        // arm model
        model = new Controls.Segmented(List.of(I18n.tr("skins.model.classic"), I18n.tr("skins.model.slim")), slim ? 1 : 0);
        model.onChange(i -> {
            boolean s = i == 1;
            if (s != slim) {
                slim = s;
                skinChanged = png != null;
                preview.update();
                updateButtons();
            }
        });
        form.with(Ui.field(I18n.tr("skins.model"), I18n.tr("skins.model.hint"), Ui.left(model)));

        // capes
        capeRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        capeRow.setBorder(BorderFactory.createEmptyBorder(0, -10, 0, 0));
        fillCapes();
        form.with(Ui.field(I18n.tr("skins.cape"), null, capeRow));

        // actions
        presetName = new NTextField(I18n.tr("skins.presetName"), Icons.Name.EDIT);
        presetName.setPreferredSize(new Dimension(220, 38));
        applyBtn = new NButton(I18n.tr("skins.apply"), Icons.Name.CHECK, NButton.Variant.PRIMARY).onClick(() -> apply(a, png, slim, capeId, null));
        saveBtn = new NButton(I18n.tr("skins.savePreset"), Icons.Name.STAR, NButton.Variant.SECONDARY).onClick(this::savePreset);
        Stack actions = Stack.h(10);
        actions.with(applyBtn, presetName, saveBtn);
        form.with(Ui.left(actions));
        updateButtons();

        JPanel fw = Ui.transparent(new BorderLayout());
        fw.add(form, BorderLayout.NORTH);
        card.add(fw, BorderLayout.CENTER);

        FileDrop.install(card, new FileDrop.Listener() {
            @Override public void dropped(List<Path> files) { loadFile(files.get(0)); }

            @Override public void hover(boolean active) { preview.setDropping(active); }
        });
        return card;
    }

    private String sourceLabel() {
        if (loading && png == null) return I18n.tr("skins.loading");
        if (sourceName != null) return sourceName;
        return png != null ? I18n.tr("skins.current") : I18n.tr("skins.noFile");
    }

    private void fillCapes() {
        capeRow.removeAll();
        if (profile == null) {
            capeRow.add(Ui.small(loading ? I18n.tr("skins.loading") : "—"));
        } else {
            capeRow.add(new CapeTile(null));
            for (SkinApi.Cape c : profile.capes()) capeRow.add(new CapeTile(c));
            if (profile.capes().isEmpty()) capeRow.add(Ui.small(I18n.tr("skins.noCapes")));
        }
        capeRow.revalidate();
        capeRow.repaint();
    }

    private void updateButtons() {
        if (applyBtn == null) return;
        boolean ready = profile != null && !busy;
        SkinApi.Cape active = profile == null ? null : profile.activeCape();
        boolean capeChanged = profile != null && !Objects.equals(capeId, active == null ? "" : active.id());
        applyBtn.setEnabled(ready && ((skinChanged && png != null) || capeChanged));
        saveBtn.setEnabled(png != null && !busy);
        if (fileLabel != null) fileLabel.setText(sourceLabel());
    }

    // ------------------------------------------------------------------ actions

    private void pickFile() {
        Path p = Pickers.openFile(I18n.tr("skins.choose"), "png");
        if (p != null) loadFile(p);
    }

    private void loadFile(Path p) {
        try {
            byte[] data = Files.readAllBytes(p);
            BufferedImage img = SkinLibrary.validate(data);
            String name = p.getFileName().toString();
            setSkin(data, SkinLibrary.looksSlim(img), name);
            skinChanged = true;
            if (presetName != null && presetName.getText().isBlank()) presetName.setText(name.replaceFirst("(?i)\\.png$", ""));
            updateButtons();
        } catch (IOException e) {
            Toast.show(e.getMessage(), Toast.Kind.ERROR);
        }
    }

    private void setSkin(byte[] data, boolean isSlim, String name) {
        png = data;
        try {
            skin = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            skin = null;
        }
        slim = isSlim;
        sourceName = name;
        if (model != null) model.setSelected(slim ? 1 : 0, false);
        if (preview != null) preview.update();
    }

    private void savePreset() {
        if (png == null) return;
        try {
            App.get().skins.add(presetName.getText(), png, slim, capeId);
            presetName.setText("");
            Toast.show(I18n.tr("skins.presetSaved"), Toast.Kind.SUCCESS);
            rebuildPresets();
        } catch (IOException e) {
            Toast.show(e.getMessage(), Toast.Kind.ERROR);
        }
    }

    /**
     * Applies a skin and/or cape to the Minecraft profile. Uploads only what actually changed, so a
     * cape switch does not spend a skin upload (Mojang rate-limits skin changes).
     */
    private void apply(Account a, byte[] data, boolean isSlim, String wantCape, String presetLabel) {
        if (busy || profile == null) return;
        boolean uploadSkin = data != null && (presetLabel != null || skinChanged);
        SkinApi.Cape active = profile.activeCape();
        String currentCape = active == null ? "" : active.id();
        // a preset made with another account may reference a cape this account doesn't own
        boolean capeOwned = wantCape == null || wantCape.isEmpty() || profile.capes().stream().anyMatch(c -> c.id().equals(wantCape));
        boolean changeCape = capeOwned && wantCape != null && !wantCape.equals(currentCape);
        if (!uploadSkin && !changeCape) return;
        busy = true;
        updateButtons();
        Toast.show(I18n.tr("skins.applying"), Toast.Kind.INFO);
        Tasks.async(() -> {
            App.get().launcher.refreshAccount(a, Progress.NONE);
            SkinApi.Profile p = profile;
            if (uploadSkin) p = SkinApi.uploadSkin(a.accessToken, data, isSlim);
            if (changeCape) p = SkinApi.setCape(a.accessToken, wantCape);
            return p;
        }, p -> {
            busy = false;
            profile = p;
            skinChanged = false;
            SkinApi.Cape c = p.activeCape();
            capeId = c == null ? "" : c.id();
            if (presetLabel != null) {
                setSkin(data, isSlim, presetLabel);
            }
            syncAccountSkin(a);
            Toast.show(I18n.tr("skins.applied"), Toast.Kind.SUCCESS);
            if (!capeOwned) Toast.show(I18n.tr("skins.capeNotOwned"), Toast.Kind.WARNING);
            build();
        }, err -> {
            busy = false;
            updateButtons();
            Toast.show(Tasks.message(err), Toast.Kind.ERROR);
        });
    }

    /** Keeps the head shown in the sidebar/accounts list in sync with the profile's active skin. */
    private void syncAccountSkin(Account a) {
        if (profile == null) return;
        SkinApi.Skin s = profile.activeSkin();
        String url = s == null || s.url() == null ? "" : s.url();
        if (!url.equals(a.skinUrl)) {
            a.skinUrl = url;
            Art.invalidate(a);
            App.get().accounts.update(a);
        }
    }

    // ------------------------------------------------------------------ presets

    private JComponent presetsSection(Account a) {
        Stack s = Stack.v(12);
        s.with(Ui.h2(I18n.tr("skins.presets")));
        presetGrid = Ui.transparent(new GridFlow(170, 252, 14));
        s.with(presetGrid);
        fillPresets(a);
        return s;
    }

    private void rebuildPresets() {
        fillPresets(App.get().accounts.active());
        presetGrid.revalidate();
        presetGrid.repaint();
    }

    private void fillPresets(Account a) {
        presetGrid.removeAll();
        List<SkinLibrary.Preset> all = App.get().skins.all();
        if (all.isEmpty()) {
            presetGrid.setLayout(new BorderLayout());
            Card empty = new Card(new BorderLayout());
            empty.pad(20, 22, 20, 22);
            empty.add(Ui.muted(I18n.tr("skins.noPresets")), BorderLayout.CENTER);
            presetGrid.add(empty, BorderLayout.CENTER);
            return;
        }
        presetGrid.setLayout(new GridFlow(170, 252, 14));
        for (SkinLibrary.Preset p : all) presetGrid.add(new PresetCard(a, p));
    }

    private BufferedImage thumb(SkinLibrary.Preset p) {
        return PRESET_THUMBS.computeIfAbsent(p.id + (p.slim ? ":s" : ":c"), k -> {
            try {
                BufferedImage img = ImageIO.read(p.file(App.get().skins.dir()).toFile());
                return Art.renderSkin(img, p.slim, null, false, 5);
            } catch (Exception e) {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
        });
    }

    private final class PresetCard extends Card {
        PresetCard(Account a, SkinLibrary.Preset p) {
            super(new BorderLayout(0, 8));
            pad(14, 14, 12, 14);
            JComponent img = new JComponent() {
                @Override protected void paintComponent(Graphics g) {
                    BufferedImage t = thumb(p);
                    int x = (getWidth() - t.getWidth()) / 2;
                    g.drawImage(t, x, 0, null);
                }
            };
            img.setPreferredSize(new Dimension(80, 160));
            add(img, BorderLayout.CENTER);

            Stack info = Stack.v(6);
            JLabel name = Ui.label(p.name, 13.5f, Theme.Weight.SEMIBOLD, Theme.TEXT);
            Stack chips = Stack.h(6);
            chips.with(Ui.chip(I18n.tr(p.slim ? "skins.model.slim" : "skins.model.classic"), Theme.CYAN));
            if (!p.capeId.isEmpty()) chips.with(Ui.chip(I18n.tr("skins.withCape"), Theme.AMBER));
            JPanel bottom = Ui.transparent(new BorderLayout(6, 0));
            NButton use = new NButton(I18n.tr("skins.use"), null, NButton.Variant.SECONDARY, NButton.Size.SM);
            use.setEnabled(a != null && profile != null && !busy);
            use.onClick(() -> {
                try {
                    apply(a, App.get().skins.read(p), p.slim, p.capeId, p.name);
                } catch (IOException e) {
                    Toast.show(e.getMessage(), Toast.Kind.ERROR);
                }
            });
            NButton edit = NButton.icon(Icons.Name.EDIT, I18n.tr("skins.edit"), NButton.Variant.GHOST, NButton.Size.SM).onClick(() -> {
                try {
                    setSkin(App.get().skins.read(p), p.slim, p.name);
                    capeId = p.capeId;
                    skinChanged = true;
                    fillCapes();
                    updateButtons();
                } catch (IOException e) {
                    Toast.show(e.getMessage(), Toast.Kind.ERROR);
                }
            });
            NButton del = NButton.icon(Icons.Name.TRASH, I18n.tr("skins.delete"), NButton.Variant.GHOST, NButton.Size.SM).onClick(() ->
                    Modal.confirm(I18n.tr("skins.deleteTitle"), I18n.tr("skins.deleteMessage", p.name), I18n.tr("skins.delete"), true, () -> {
                        try {
                            App.get().skins.remove(p);
                            rebuildPresets();
                        } catch (IOException e) {
                            Toast.show(e.getMessage(), Toast.Kind.ERROR);
                        }
                    }));
            Stack icons = Stack.h(2);
            icons.with(edit, del);
            bottom.add(use, BorderLayout.CENTER);
            bottom.add(icons, BorderLayout.EAST);
            info.with(name, chips, bottom);
            add(info, BorderLayout.SOUTH);
        }
    }

    // ------------------------------------------------------------------ components

    /** Front + back view with the selected model and cape; also the drop target highlight. */
    private final class Preview extends JComponent {
        private static final int PX = 7;
        private BufferedImage front, back;
        private boolean dropping;

        Preview() {
            setPreferredSize(new Dimension(16 * PX * 2 + 64, 32 * PX + 40));
            update();
        }

        void setDropping(boolean d) {
            dropping = d;
            repaint();
        }

        void update() {
            BufferedImage cape = capeImage(capeId);
            front = Art.renderSkin(skin, slim, null, false, PX);
            back = Art.renderSkin(skin, slim, cape, true, PX);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, Theme.SURFACE_2, 0, h, Theme.mix(Theme.CYAN, Theme.SURFACE, 0.9)));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 22, 22));
            if (dropping) {
                g2.setColor(Theme.alpha(Theme.CYAN, 0.12));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 22, 22));
                g2.setColor(Theme.CYAN);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 5}, 0));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 22, 22));
            }
            if (skin == null) {
                g2.setFont(Theme.font(12.5f, Theme.Weight.MEDIUM));
                g2.setColor(Theme.TEXT_3);
                String t = loading ? I18n.tr("skins.loading") : I18n.tr("skins.dropHere");
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2);
            } else {
                int y = (h - front.getHeight()) / 2;
                int gap = 24;
                int x = (w - front.getWidth() * 2 - gap) / 2;
                // soft floor shadow
                g2.setColor(new Color(0, 0, 0, 60));
                g2.fillOval(x + 10, y + front.getHeight() - 6, front.getWidth() - 20, 12);
                g2.fillOval(x + front.getWidth() + gap + 10, y + front.getHeight() - 6, front.getWidth() - 20, 12);
                g2.drawImage(front, x, y, null);
                g2.drawImage(back, x + front.getWidth() + gap, y, null);
            }
            g2.dispose();
        }
    }

    private BufferedImage capeImage(String id) {
        if (profile == null || id == null || id.isEmpty()) return null;
        for (SkinApi.Cape c : profile.capes()) {
            if (c.id().equals(id)) {
                BufferedImage img = Images.cached(c.url());
                if (img == null && CAPE_REQUESTED.add(c.url())) {
                    Images.url(c.url(), loaded -> {
                        if (preview != null) preview.update();
                        if (capeRow != null) capeRow.repaint();
                    });
                }
                return img;
            }
        }
        return null;
    }

    /** Selectable cape: shows the cape's outer face; the first tile means "no cape". */
    private final class CapeTile extends JComponent {
        private final SkinApi.Cape cape;
        private boolean hover;

        CapeTile(SkinApi.Cape cape) {
            this.cape = cape;
            setPreferredSize(new Dimension(54, 76));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(cape == null ? I18n.tr("skins.noCape") : cape.alias());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    capeId = cape == null ? "" : cape.id();
                    capeRow.repaint();
                    preview.update();
                    updateButtons();
                }
            });
        }

        private boolean selected() { return Objects.equals(capeId, cape == null ? "" : cape.id()); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            boolean sel = selected();
            g2.setColor(sel ? Theme.alpha(Theme.CYAN, 0.14) : hover ? Theme.SURFACE_3 : Theme.SURFACE_2);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            g2.setColor(sel ? Theme.CYAN : Theme.BORDER);
            g2.setStroke(new BasicStroke(sel ? 2f : 1f));
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 14, 14));
            if (cape == null) {
                Icons.get(Icons.Name.X, 20, Theme.TEXT_3).paintIcon(this, g2, (w - 20) / 2, (h - 20) / 2);
            } else {
                BufferedImage img = capeImage(cape.id());
                if (img != null) {
                    int cs = Math.max(1, img.getWidth() / 64);
                    int cw = 30, ch = 48;
                    int x = (w - cw) / 2, y = (h - ch) / 2;
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(img, x, y, x + cw, y + ch, cs, cs, 11 * cs, 17 * cs, null);
                }
            }
            g2.dispose();
        }
    }
}
