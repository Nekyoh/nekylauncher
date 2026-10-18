package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.*;
import com.nekyoh.nekylauncher.ui.dialogs.ExportDialog;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.OS;

import javax.swing.*;
import java.awt.*;

/** Detail view of one instance: header with play controls + content and settings tabs. */
public final class InstancePage extends JPanel {
    private final MainWindow window;
    private Instance inst;
    private final JPanel holder = Ui.transparent(new BorderLayout());
    private Header header;
    private Tabs tabs;
    private ContentTab mods, packs, shaders, worlds;

    public InstancePage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);
        holder.setBorder(BorderFactory.createEmptyBorder(22, 28, 20, 28));
        add(holder, BorderLayout.CENTER);
    }

    public Instance instance() { return inst; }

    public void show(Instance i) {
        String keepTab = tabs != null && inst != null && inst.id().equals(i.id()) ? tabs.selectedKey() : null;
        inst = i;
        holder.removeAll();
        header = new Header();
        holder.add(header, BorderLayout.NORTH);
        tabs = new Tabs();
        mods = new ContentTab(window, i, com.nekyoh.nekylauncher.instance.ContentItem.Kind.MOD);
        packs = new ContentTab(window, i, com.nekyoh.nekylauncher.instance.ContentItem.Kind.RESOURCEPACK);
        shaders = new ContentTab(window, i, com.nekyoh.nekylauncher.instance.ContentItem.Kind.SHADERPACK);
        worlds = new ContentTab(window, i, com.nekyoh.nekylauncher.instance.ContentItem.Kind.WORLD);
        tabs.addTab("mods", I18n.tr("tab.mods"), Icons.Name.CUBE, mods);
        tabs.addTab("resourcepacks", I18n.tr("tab.resourcepacks"), Icons.Name.IMAGE, packs);
        tabs.addTab("shaderpacks", I18n.tr("tab.shaderpacks"), Icons.Name.SPARKLES, shaders);
        tabs.addTab("worlds", I18n.tr("tab.worlds"), Icons.Name.GLOBE, worlds);
        tabs.addTab("settings", I18n.tr("tab.settings"), Icons.Name.SETTINGS, new InstanceSettingsTab(window, i, this::refreshHeader));
        tabs.onChange(k -> {
            switch (k) {
                case "mods" -> mods.reload();
                case "resourcepacks" -> packs.reload();
                case "shaderpacks" -> shaders.reload();
                case "worlds" -> worlds.reload();
                default -> { }
            }
        });
        JPanel tabsWrap = Ui.transparent(new BorderLayout());
        tabsWrap.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        tabsWrap.add(tabs, BorderLayout.CENTER);
        holder.add(tabsWrap, BorderLayout.CENTER);
        tabs.select(keepTab != null ? keepTab : (i.loader.isModded() ? "mods" : "resourcepacks"));
        holder.revalidate();
        holder.repaint();
    }

    public void refreshHeader() {
        if (inst == null || header == null) return;
        // the instance may have been deleted or replaced
        if (App.get().instances.byId(inst.id()).isEmpty()) return;
        header.update();
    }

    public void refreshLive() {
        if (header != null) header.update();
    }

    private final class Header extends JPanel {
        private final JLabel name = Ui.h1("");
        private final JPanel chips = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final JLabel meta = Ui.small("");
        private final NButton play = new NButton(I18n.tr("instance.play"), Icons.Name.PLAY, NButton.Variant.PLAY, NButton.Size.LG);
        private final Controls.Progress progress = new Controls.Progress();
        private final JLabel status = Ui.small("");
        private final JPanel progressRow = Ui.transparent(new BorderLayout(0, 6));
        private final JComponent icon;

        Header() {
            super(new BorderLayout(18, 0));
            setOpaque(false);
            NButton back = NButton.icon(Icons.Name.CHEVRON_LEFT, I18n.tr("common.back"), NButton.Variant.GHOST, NButton.Size.MD);
            back.onClick(() -> window.showPage(MainWindow.Page.INSTANCES));
            icon = new JComponent() {
                @Override protected void paintComponent(Graphics g) { Art.instanceIcon((Graphics2D) g, inst, 0, 0, 72); }
            };
            icon.setPreferredSize(new Dimension(72, 72));
            JPanel left = Ui.transparent(new BorderLayout(14, 0));
            JPanel backWrap = Ui.transparent(new GridBagLayout());
            backWrap.add(back);
            left.add(backWrap, BorderLayout.WEST);
            left.add(icon, BorderLayout.EAST);
            add(left, BorderLayout.WEST);

            Stack info = Stack.v(6);
            chips.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
            info.with(name, chips, meta);
            JPanel infoWrap = Ui.transparent(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            infoWrap.add(info, gc);
            add(infoWrap, BorderLayout.CENTER);

            NButton folder = NButton.icon(Icons.Name.FOLDER, I18n.tr("instance.openFolder"), NButton.Variant.SECONDARY, NButton.Size.LG);
            folder.onClick(() -> OS.openFolder(inst.gameDir()));
            NButton export = NButton.icon(Icons.Name.EXPORT, I18n.tr("instance.export"), NButton.Variant.SECONDARY, NButton.Size.LG);
            export.onClick(() -> ExportDialog.open(inst));
            NButton more = NButton.icon(Icons.Name.MORE, I18n.tr("common.more"), NButton.Variant.SECONDARY, NButton.Size.LG);
            more.onClick(() -> InstanceMenu.show(window, inst, more, 0, more.getHeight() + 4));
            play.setPreferredSize(new Dimension(150, 46));
            play.onClick(() -> {
                if (window.launch.isPreparing(inst)) window.launch.cancel(inst);
                else if (window.launch.isRunning(inst)) window.launch.stop(inst);
                else window.launch.play(inst);
            });
            Stack actions = Stack.h(10);
            actions.with(folder, export, more, play);
            progress.setPreferredSize(new Dimension(300, 5));
            progressRow.add(status, BorderLayout.NORTH);
            progressRow.add(progress, BorderLayout.CENTER);
            Stack right = Stack.v(10);
            right.with(actions, progressRow);
            JPanel rightWrap = Ui.transparent(new GridBagLayout());
            rightWrap.add(right);
            add(rightWrap, BorderLayout.EAST);
            update();
        }

        void update() {
            name.setText(inst.name);
            chips.removeAll();
            chips.add(Ui.chip("Minecraft " + inst.mcVersion, Theme.GREEN));
            if (inst.loader.isModded()) {
                String lv = inst.loaderVersion == null || inst.loaderVersion.isBlank() ? "" : " " + inst.loaderVersion;
                chips.add(Ui.chip(inst.loader.display + lv, inst.loader.color));
            }
            if (window.launch.isRunning(inst)) chips.add(Ui.chip(I18n.tr("instance.running"), Theme.GREEN, Icons.Name.PLAY));
            chips.revalidate();
            meta.setText(I18n.tr("instance.meta", UiText.lastPlayed(inst.lastPlayed), UiText.playTime(inst.playTimeSeconds)));
            boolean preparing = window.launch.isPreparing(inst);
            boolean running = window.launch.isRunning(inst);
            if (preparing) {
                play.setText(I18n.tr("common.cancel"));
                play.setIconName(Icons.Name.X);
                play.setVariant(NButton.Variant.SECONDARY);
            } else if (running) {
                play.setText(I18n.tr("launch.stop"));
                play.setIconName(Icons.Name.STOP);
                play.setVariant(NButton.Variant.DANGER);
            } else {
                play.setText(I18n.tr("instance.play"));
                play.setIconName(Icons.Name.PLAY);
                play.setVariant(NButton.Variant.PLAY);
            }
            LaunchController.State st = window.launch.state(inst);
            progressRow.setVisible(st != null);
            if (st != null) {
                status.setText(Ui.ellipsize(st.stage + (st.detail == null || st.detail.isBlank() ? "" : "  ·  " + st.detail),
                        status.getFontMetrics(status.getFont()), 300));
                progress.setValue(st.fraction);
            }
            icon.repaint();
            revalidate();
            repaint();
        }
    }
}
