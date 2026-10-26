package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.game.GameProcess;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.kit.Images;
import com.nekyoh.nekylauncher.ui.kit.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Left navigation: brand, pages, running games, account switcher. */
final class Sidebar extends JPanel {
    private final MainWindow window;
    private final List<NavItem> items = new ArrayList<>();
    private final AccountChip accountChip;
    private final RunningChip runningChip;

    Sidebar(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.SIDEBAR);
        setPreferredSize(new Dimension(236, 100));

        JPanel top = Ui.transparent(new BorderLayout());
        top.add(new Brand(), BorderLayout.NORTH);
        JPanel nav = Ui.transparent(new GridLayout(0, 1, 0, 4));
        nav.setBorder(BorderFactory.createEmptyBorder(10, 14, 0, 14));
        add(nav, MainWindow.Page.HOME, Icons.Name.HOME, I18n.tr("nav.home"));
        add(nav, MainWindow.Page.INSTANCES, Icons.Name.GRID, I18n.tr("nav.instances"));
        add(nav, MainWindow.Page.SKINS, Icons.Name.SHIRT, I18n.tr("nav.skins"));
        add(nav, MainWindow.Page.ACCOUNTS, Icons.Name.USER, I18n.tr("nav.accounts"));
        add(nav, MainWindow.Page.SETTINGS, Icons.Name.SETTINGS, I18n.tr("nav.settings"));
        top.add(nav, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JPanel bottom = Ui.transparent(new BorderLayout(0, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 14, 14, 14));
        runningChip = new RunningChip();
        accountChip = new AccountChip();
        JPanel above = Ui.transparent(new BorderLayout(0, 8));
        above.add(links(), BorderLayout.NORTH);
        above.add(runningChip, BorderLayout.SOUTH);
        bottom.add(above, BorderLayout.NORTH);
        bottom.add(accountChip, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        setActive(MainWindow.Page.HOME);
        refresh();
    }

    private void add(JPanel nav, MainWindow.Page page, Icons.Name icon, String text) {
        NavItem it = new NavItem(page, icon, text);
        items.add(it);
        nav.add(it);
    }

    /** Official channels and sites, right above the player card. */
    private JComponent links() {
        JPanel p = Ui.transparent(new BorderLayout(0, 6));
        JLabel head = Ui.caps(I18n.tr("sidebar.links"));
        head.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        p.add(head, BorderLayout.NORTH);
        JPanel yt = Ui.transparent(new GridLayout(1, 2, 6, 0));
        yt.add(new LinkItem(Icons.Name.VIDEO, "Nezoru", com.nekyoh.nekylauncher.AppInfo.YOUTUBE_NEZORU, new Color(0xF87171), true));
        yt.add(new LinkItem(Icons.Name.VIDEO, "Tekyoh", com.nekyoh.nekylauncher.AppInfo.YOUTUBE_TEKYOH, new Color(0xF87171), true));
        JPanel sites = Ui.transparent(new GridLayout(0, 1, 0, 2));
        sites.add(new LinkItem(Icons.Name.GLOBE, "nekyoh.tech", com.nekyoh.nekylauncher.AppInfo.SITE, Theme.CYAN, false));
        sites.add(new LinkItem(Icons.Name.GLOBE, I18n.tr("sidebar.launcherSite"), com.nekyoh.nekylauncher.AppInfo.LAUNCHER_SITE, Theme.CYAN, false));
        sites.add(new LinkItem(Icons.Name.CUBE, I18n.tr("sidebar.epiccraft"), com.nekyoh.nekylauncher.AppInfo.EPICCRAFT_SITE, Theme.GREEN, false));
        JPanel body = Ui.transparent(new BorderLayout(0, 6));
        body.add(yt, BorderLayout.NORTH);
        body.add(sites, BorderLayout.CENTER);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** Small clickable link: pill (YouTube channels) or compact row (sites). */
    private static final class LinkItem extends JComponent {
        private final Icons.Name icon;
        private final String text;
        private final Color accent;
        private final boolean pill;
        private boolean hover;

        LinkItem(Icons.Name icon, String text, String url, Color accent, boolean pill) {
            this.icon = icon;
            this.text = text;
            this.accent = accent;
            this.pill = pill;
            setPreferredSize(new Dimension(100, pill ? 34 : 28));
            setToolTipText(url);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { com.nekyoh.nekylauncher.util.OS.openUrl(url); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            if (pill) {
                g2.setColor(hover ? Theme.alpha(accent, 0.16) : Theme.SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));
                g2.setColor(hover ? Theme.alpha(accent, 0.45) : Theme.BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 16, 16));
            } else if (hover) {
                g2.setColor(Theme.SURFACE_2);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            }
            int is = 15;
            Icons.get(icon, is, accent).paintIcon(this, g2, pill ? 10 : 8, (h - is) / 2);
            g2.setFont(Theme.font(12.5f, Theme.Weight.MEDIUM));
            g2.setColor(hover ? Theme.TEXT : Theme.TEXT_2);
            FontMetrics fm = g2.getFontMetrics();
            int tx = (pill ? 10 : 8) + is + 8;
            g2.drawString(Ui.ellipsize(text, fm, w - tx - 18), tx, (h - fm.getHeight()) / 2 + fm.getAscent());
            if (hover) Icons.get(Icons.Name.EXTERNAL, 12, Theme.TEXT_3).paintIcon(this, g2, w - 18, (h - 12) / 2);
            g2.dispose();
        }
    }

    void setActive(MainWindow.Page p) {
        for (NavItem i : items) i.setActive(i.page == p);
    }

    void refresh() {
        accountChip.repaint();
        Collection<GameProcess> running = App.get().launcher.allRunning();
        runningChip.setVisible(!running.isEmpty());
        runningChip.repaint();
        revalidate();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(Theme.BORDER);
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
    }

    /** Logo + glitch wordmark. */
    private static final class Brand extends JComponent {
        private final BufferedImage mark = Images.resource("/nekylauncher/img/mark.png");

        Brand() { setPreferredSize(new Dimension(236, 86)); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            if (mark != null) g2.drawImage(Images.scaledCached(mark, 84), 20, 22, 42, 42, null);
            g2.setFont(Theme.font(17.5f, Theme.Weight.BOLD));
            g2.setColor(Theme.TEXT);
            Theme.glitchText(g2, "NekyLauncher", 72, 43, 1.2f);
            g2.setFont(Theme.font(11.5f, Theme.Weight.MEDIUM));
            g2.setColor(Theme.TEXT_3);
            g2.drawString("by Nekyoh", 73, 60);
            g2.dispose();
        }
    }

    private final class NavItem extends JComponent {
        final MainWindow.Page page;
        private final Icons.Name icon;
        private final String text;
        private boolean active, hover;

        NavItem(MainWindow.Page page, Icons.Name icon, String text) {
            this.page = page;
            this.icon = icon;
            this.text = text;
            setPreferredSize(new Dimension(200, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { window.showPage(page); }
            });
        }

        void setActive(boolean a) {
            active = a;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            if (active) {
                g2.setPaint(new GradientPaint(0, 0, Theme.alpha(Theme.CYAN, 0.16), w, 0, Theme.alpha(Theme.CYAN, 0.02)));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 20, 20));
                g2.setColor(Theme.CYAN);
                g2.fill(new RoundRectangle2D.Float(0, 11, 3, h - 22, 3, 3));
            } else if (hover) {
                g2.setColor(Theme.SURFACE_2);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 20, 20));
            }
            Icons.get(icon, 19, active ? Theme.CYAN : hover ? Theme.TEXT : Theme.TEXT_2).paintIcon(this, g2, 16, (h - 19) / 2);
            g2.setFont(Theme.font(14, active ? Theme.Weight.SEMIBOLD : Theme.Weight.MEDIUM));
            g2.setColor(active || hover ? Theme.TEXT : Theme.TEXT_2);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 48, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Shows running games; click opens the console of the first one. */
    private final class RunningChip extends JComponent {
        private final Timer pulse = new Timer(90, e -> repaint());

        RunningChip() {
            setPreferredSize(new Dimension(200, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(I18n.tr("launch.console"));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    for (GameProcess gp : App.get().launcher.allRunning()) {
                        window.launch.openConsole(gp.instance);
                        break;
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.alpha(Theme.GREEN, 0.10));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
            g2.setColor(Theme.alpha(Theme.GREEN, 0.35));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 18, 18));
            float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 300.0));
            g2.setColor(Theme.alpha(Theme.GREEN, pulse));
            g2.fillOval(14, h / 2 - 4, 8, 8);
            Collection<GameProcess> running = App.get().launcher.allRunning();
            String label = running.size() == 1 ? running.iterator().next().instance.name : I18n.tr("sidebar.running", running.size());
            g2.setFont(Theme.font(12.5f, Theme.Weight.SEMIBOLD));
            g2.setColor(Theme.TEXT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(Ui.ellipsize(label, fm, w - 70), 32, (h - fm.getHeight()) / 2 + fm.getAscent());
            Icons.get(Icons.Name.TERMINAL, 16, Theme.TEXT_2).paintIcon(this, g2, w - 28, (h - 16) / 2);
            g2.dispose();
        }

        @Override
        public void setVisible(boolean v) {
            super.setVisible(v);
            if (v) pulse.start(); else pulse.stop();
        }
    }

    /** Active account; click to switch. */
    private final class AccountChip extends JComponent {
        private boolean hover;

        AccountChip() {
            setPreferredSize(new Dimension(200, 58));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { showMenu(); }
            });
        }

        private void showMenu() {
            JPopupMenu menu = new JPopupMenu();
            for (Account a : App.get().accounts.all()) {
                JMenuItem it = new JMenuItem(a.username + "  ·  " + typeName(a));
                it.addActionListener(e -> App.get().accounts.setActive(a));
                menu.add(it);
            }
            if (!App.get().accounts.all().isEmpty()) menu.addSeparator();
            JMenuItem manage = new JMenuItem(I18n.tr("sidebar.manageAccounts"));
            manage.addActionListener(e -> window.showPage(MainWindow.Page.ACCOUNTS));
            menu.add(manage);
            menu.show(this, 0, -menu.getPreferredSize().height - 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(hover ? Theme.SURFACE_2 : Theme.SURFACE);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 22, 22));
            g2.setColor(Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 22, 22));
            Account a = App.get().accounts.active();
            if (a == null) {
                Icons.get(Icons.Name.USER, 20, Theme.TEXT_2).paintIcon(this, g2, 16, (h - 20) / 2);
                g2.setFont(Theme.font(13, Theme.Weight.SEMIBOLD));
                g2.setColor(Theme.TEXT);
                g2.drawString(I18n.tr("sidebar.noAccount"), 48, h / 2 - 2);
                g2.setFont(Theme.font(11.5f));
                g2.setColor(Theme.CYAN);
                g2.drawString(I18n.tr("sidebar.addAccount"), 48, h / 2 + 14);
            } else {
                Art.avatar(g2, a, 12, (h - 34) / 2, 34, this);
                g2.setFont(Theme.font(13.5f, Theme.Weight.SEMIBOLD));
                g2.setColor(Theme.TEXT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(Ui.ellipsize(a.username, fm, w - 86), 56, h / 2 - 2);
                g2.setFont(Theme.font(11.5f));
                g2.setColor(Theme.GREEN);
                g2.drawString(typeName(a), 56, h / 2 + 14);
            }
            Icons.get(Icons.Name.CHEVRON_DOWN, 15, Theme.TEXT_3).paintIcon(this, g2, w - 28, (h - 15) / 2);
            g2.dispose();
        }
    }

    private static String typeName(Account a) { return UiText.accountType(a); }
}
