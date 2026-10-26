package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.kit.Modal;
import com.nekyoh.nekylauncher.ui.kit.Toast;
import com.nekyoh.nekylauncher.ui.pages.*;
import com.nekyoh.nekylauncher.ui.window.NFrame;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** The launcher window: sidebar navigation + pages. */
public final class MainWindow extends NFrame {
    public enum Page { HOME, INSTANCES, INSTANCE, SKINS, ACCOUNTS, SETTINGS }

    public final LaunchController launch;
    private final JPanel pages = new JPanel(new CardLayout());
    private Sidebar sidebar;
    private HomePage home;
    private InstancesPage instancesPage;
    private InstancePage instancePage;
    private SkinsPage skinsPage;
    private AccountsPage accountsPage;
    private SettingsPage settingsPage;
    private Page current = Page.HOME;

    public MainWindow() {
        super(AppInfo.NAME, App.get().settings.systemWindowFrame);
        Settings s = App.get().settings;
        setMinimumSize(new Dimension(1000, 640));
        setSize(Math.max(1000, s.windowWidth), Math.max(640, s.windowHeight));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        Modal.init(getRootPane());
        Toast.init(getRootPane());
        launch = new LaunchController(this);
        pages.setBackground(Theme.BG);
        build();
        App.get().instances.addListener(() -> Tasks.ui(this::refreshAll));
        App.get().accounts.addListener(() -> Tasks.ui(this::refreshAll));
        launch.addListener(this::refreshLive);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { requestClose(); }
        });
        if (s.windowMaximized) SwingUtilities.invokeLater(this::toggleMaximize);
        String lost = com.nekyoh.nekylauncher.core.Dirs.pointerProblem;
        if (lost != null) {
            SwingUtilities.invokeLater(() -> Toast.show(I18n.tr("datadir.unavailable", lost), Toast.Kind.WARNING,
                    I18n.tr("nav.settings"), () -> showPage(Page.SETTINGS)));
        }
    }

    private void build() {
        body.removeAll();
        pages.removeAll();
        sidebar = new Sidebar(this);
        home = new HomePage(this);
        instancesPage = new InstancesPage(this);
        instancePage = new InstancePage(this);
        skinsPage = new SkinsPage(this);
        accountsPage = new AccountsPage(this);
        settingsPage = new SettingsPage(this);
        pages.add(home, Page.HOME.name());
        pages.add(instancesPage, Page.INSTANCES.name());
        pages.add(instancePage, Page.INSTANCE.name());
        pages.add(skinsPage, Page.SKINS.name());
        pages.add(accountsPage, Page.ACCOUNTS.name());
        pages.add(settingsPage, Page.SETTINGS.name());
        body.add(sidebar, BorderLayout.WEST);
        body.add(pages, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    /** Rebuilds every page (used after a language change). */
    public void rebuild() {
        Instance open = instancePage.instance();
        Page p = current;
        build();
        if (p == Page.INSTANCE && open != null) openInstance(open);
        else showPage(p);
    }

    public void showPage(Page p) {
        current = p;
        ((CardLayout) pages.getLayout()).show(pages, p.name());
        sidebar.setActive(p == Page.INSTANCE ? Page.INSTANCES : p);
        switch (p) {
            case HOME -> home.refresh();
            case INSTANCES -> instancesPage.refresh();
            case SKINS -> skinsPage.refresh();
            case ACCOUNTS -> accountsPage.refresh();
            case SETTINGS -> settingsPage.refresh();
            default -> { }
        }
    }

    public void openInstance(Instance i) {
        instancePage.show(i);
        showPage(Page.INSTANCE);
    }

    public Page currentPage() { return current; }

    private void refreshAll() {
        sidebar.refresh();
        home.refresh();
        instancesPage.refresh();
        accountsPage.refresh();
        if (current == Page.SKINS) skinsPage.refresh();
        instancePage.refreshHeader();
    }

    /** Cheap refresh for launch progress ticks. */
    private void refreshLive() {
        sidebar.refresh();
        home.refreshLive();
        instancesPage.refreshLive();
        instancePage.refreshLive();
    }

    private void requestClose() {
        int running = App.get().launcher.allRunning().size();
        Runnable exit = () -> {
            Settings s = App.get().settings;
            s.windowMaximized = isMaximized();
            if (!isMaximized()) {
                s.windowWidth = getWidth();
                s.windowHeight = getHeight();
            }
            s.save();
            dispose();
            System.exit(0);
        };
        if (running > 0) {
            Modal.confirm(I18n.tr("app.closeTitle"), I18n.tr("app.closeRunning", running), I18n.tr("app.closeConfirm"), false, exit);
        } else {
            exit.run();
        }
    }
}
