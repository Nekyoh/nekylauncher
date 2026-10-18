package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.*;
import com.nekyoh.nekylauncher.ui.dialogs.ImportFlow;
import com.nekyoh.nekylauncher.ui.dialogs.ModrinthDialog;
import com.nekyoh.nekylauncher.ui.dialogs.NewInstanceDialog;
import com.nekyoh.nekylauncher.ui.kit.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/** Landing page: hero with quick play, shortcuts and recent instances. */
public final class HomePage extends JPanel {
    private final MainWindow window;
    private final Hero hero = new Hero();
    private final JPanel recentGrid = Ui.transparent(new GridFlow(300, 96, 14));
    private final JPanel recentWrap = Ui.transparent(new BorderLayout(0, 12));
    private final JLabel greeting = Ui.label("", 30, Theme.Weight.BOLD, Theme.TEXT);
    private final InstancePicker picker = new InstancePicker();
    private final NButton play = new NButton(I18n.tr("home.play"), Icons.Name.PLAY, NButton.Variant.PLAY, NButton.Size.XL);
    private final Controls.Progress progress = new Controls.Progress();
    private final JLabel status = Ui.small("");
    private final JPanel progressRow = Ui.transparent(new BorderLayout(0, 6));
    private Instance selected;

    public HomePage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);

        // hero content
        Stack heroText = Stack.v(6);
        heroText.with(Ui.label("NEKYLAUNCHER  ·  BY NEKYOH", 11.5f, Theme.Weight.BOLD, Theme.CYAN), greeting,
                Ui.label(I18n.tr("home.subtitle"), 14, Theme.Weight.REGULAR, Theme.TEXT_2));
        play.setPreferredSize(new Dimension(190, 58));
        play.onClick(() -> {
            if (selected == null) {
                NewInstanceDialog.open(window);
                return;
            }
            if (window.launch.isPreparing(selected)) window.launch.cancel(selected);
            else window.launch.play(selected);
        });
        Stack playRow = Stack.h(12);
        playRow.with(picker, play);
        progress.setPreferredSize(new Dimension(380, 6));
        status.setForeground(Theme.TEXT_2);
        progressRow.add(status, BorderLayout.NORTH);
        progressRow.add(progress, BorderLayout.CENTER);
        progressRow.setVisible(false);
        progressRow.setPreferredSize(new Dimension(420, 32));
        Stack heroLeft = Stack.v(22);
        heroLeft.with(heroText, playRow, progressRow);
        hero.setLayout(new BorderLayout());
        JPanel heroInner = Ui.transparent(new BorderLayout());
        heroInner.setBorder(BorderFactory.createEmptyBorder(38, 40, 30, 300));
        heroInner.add(heroLeft, BorderLayout.NORTH);
        hero.add(heroInner, BorderLayout.CENTER);
        hero.setPreferredSize(new Dimension(800, 300));

        // quick actions
        JPanel quick = Ui.transparent(new GridLayout(1, 3, 14, 0));
        quick.add(action(Icons.Name.PLUS, I18n.tr("home.quick.new"), I18n.tr("home.quick.newDesc"), Theme.CYAN, () -> NewInstanceDialog.open(window)));
        quick.add(action(Icons.Name.DOWNLOAD, I18n.tr("home.quick.import"), I18n.tr("home.quick.importDesc"), Theme.AMBER, () -> ImportFlow.pickAndImport(window)));
        quick.add(action(Icons.Name.PACKAGE, I18n.tr("home.quick.modpacks"), I18n.tr("home.quick.modpacksDesc"), Theme.GREEN,
                () -> ModrinthDialog.openModpacks(window)));
        quick.setPreferredSize(new Dimension(600, 76));

        JPanel recentHead = Ui.transparent(new BorderLayout());
        recentHead.add(Ui.h2(I18n.tr("home.recent")), BorderLayout.WEST);
        recentHead.add(Ui.link(I18n.tr("home.seeAll"), () -> window.showPage(MainWindow.Page.INSTANCES)), BorderLayout.EAST);
        recentWrap.add(recentHead, BorderLayout.NORTH);
        recentWrap.add(recentGrid, BorderLayout.CENTER);

        Stack content = Stack.v(22);
        content.with(hero, quick, recentWrap);
        content.pad(26, 28, 28, 28);
        add(NScroll.vertical(content, Theme.BG), BorderLayout.CENTER);
        refresh();
    }

    private JComponent action(Icons.Name icon, String title, String desc, Color color, Runnable r) {
        Card c = new Card(new BorderLayout(14, 0));
        c.pad(14, 16, 14, 16).onClick(r);
        JComponent ic = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.hq(g.create());
                g2.setColor(Theme.alpha(color, 0.14));
                g2.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 22, 22));
                Icons.get(icon, 20, color).paintIcon(this, g2, 12, 12);
                g2.dispose();
            }
        };
        ic.setPreferredSize(new Dimension(44, 44));
        c.add(ic, BorderLayout.WEST);
        Stack t = Stack.v(2);
        t.with(Ui.label(title, 14, Theme.Weight.SEMIBOLD, Theme.TEXT), Ui.small(desc));
        JPanel center = Ui.transparent(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        center.add(t, gc);
        c.add(center, BorderLayout.CENTER);
        c.bindChild(center);
        c.bindChild(t);
        c.bindChild(ic);
        return c;
    }

    public void refresh() {
        App app = App.get();
        Account acc = app.accounts.active();
        greeting.setText(acc != null ? I18n.tr("home.hello", acc.username) : I18n.tr("home.welcome"));
        List<Instance> all = app.instances.all();
        selected = null;
        for (Instance i : all) if (i.id().equals(app.settings.lastInstance)) selected = i;
        if (selected == null && !all.isEmpty()) selected = all.get(0);

        recentGrid.removeAll();
        if (all.isEmpty()) {
            recentGrid.setLayout(new BorderLayout());
            recentGrid.add(emptyState(), BorderLayout.CENTER);
        } else {
            recentGrid.setLayout(new GridFlow(300, 96, 14));
            for (Instance i : all.subList(0, Math.min(6, all.size()))) recentGrid.add(new InstanceCard(window, i));
        }
        recentGrid.revalidate();
        recentGrid.repaint();
        refreshLive();
    }

    public void refreshLive() {
        boolean preparing = selected != null && window.launch.isPreparing(selected);
        boolean running = selected != null && window.launch.isRunning(selected);
        if (selected == null) {
            play.setText(I18n.tr("home.create"));
            play.setIconName(Icons.Name.PLUS);
            play.setVariant(NButton.Variant.PRIMARY);
        } else if (preparing) {
            play.setText(I18n.tr("common.cancel"));
            play.setIconName(Icons.Name.X);
            play.setVariant(NButton.Variant.SECONDARY);
        } else if (running) {
            play.setText(I18n.tr("home.running"));
            play.setIconName(Icons.Name.TERMINAL);
            play.setVariant(NButton.Variant.SECONDARY);
        } else {
            play.setText(I18n.tr("home.play"));
            play.setIconName(Icons.Name.PLAY);
            play.setVariant(NButton.Variant.PLAY);
        }
        LaunchController.State st = selected != null ? window.launch.state(selected) : null;
        progressRow.setVisible(st != null);
        if (st != null) {
            String d = st.detail == null || st.detail.isBlank() ? "" : "  ·  " + st.detail;
            status.setText(st.stage + d);
            progress.setValue(st.fraction);
        }
        picker.repaint();
        for (Component c : recentGrid.getComponents()) c.repaint();
        revalidate();
    }

    private JComponent emptyState() {
        Card c = new Card(new BorderLayout(0, 10));
        c.pad(30, 30, 30, 30);
        Stack s = Stack.v(10);
        JLabel icon = Ui.icon(Icons.Name.CUBE, 34, Theme.CYAN);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel t = Ui.h2(I18n.tr("home.empty.title"));
        t.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel d = Ui.muted(I18n.tr("home.empty.desc"));
        d.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel btn = Ui.transparent(new FlowLayout(FlowLayout.CENTER));
        btn.add(new NButton(I18n.tr("home.create"), Icons.Name.PLUS, NButton.Variant.PRIMARY).onClick(() -> NewInstanceDialog.open(window)));
        s.with(icon, t, d, btn);
        c.add(s, BorderLayout.CENTER);
        c.setPreferredSize(new Dimension(400, 210));
        return c;
    }

    /** Selected-instance chip inside the hero; click to pick another. */
    private final class InstancePicker extends JComponent {
        private boolean hover;

        InstancePicker() {
            setPreferredSize(new Dimension(300, 58));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { showMenu(); }
            });
        }

        private void showMenu() {
            List<Instance> all = App.get().instances.all();
            if (all.isEmpty()) {
                NewInstanceDialog.open(window);
                return;
            }
            JPopupMenu m = new JPopupMenu();
            for (Instance i : all) {
                JMenuItem it = new JMenuItem(i.name + "   " + i.describe());
                it.addActionListener(e -> {
                    selected = i;
                    App.get().settings.lastInstance = i.id();
                    App.get().settings.save();
                    refreshLive();
                });
                m.add(it);
            }
            m.addSeparator();
            JMenuItem create = new JMenuItem(I18n.tr("home.quick.new"));
            create.addActionListener(e -> NewInstanceDialog.open(window));
            m.add(create);
            m.show(this, 0, getHeight() + 4);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(hover ? new Color(0x1E2430) : new Color(0x161B24));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 30, 30));
            g2.setColor(hover ? Theme.BORDER_STRONG : Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 30, 30));
            if (selected != null) {
                Art.instanceIcon(g2, selected, 10, 9, 40);
                g2.setFont(Theme.font(14, Theme.Weight.SEMIBOLD));
                g2.setColor(Theme.TEXT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(Ui.ellipsize(selected.name, fm, w - 100), 62, h / 2 - 3);
                g2.setFont(Theme.font(12));
                g2.setColor(Theme.TEXT_2);
                g2.drawString(selected.describe(), 62, h / 2 + 14);
            } else {
                Icons.get(Icons.Name.CUBE, 22, Theme.TEXT_3).paintIcon(this, g2, 18, (h - 22) / 2);
                g2.setFont(Theme.font(13.5f, Theme.Weight.MEDIUM));
                g2.setColor(Theme.TEXT_2);
                g2.drawString(I18n.tr("home.noInstance"), 54, h / 2 + 5);
            }
            Icons.get(Icons.Name.CHEVRON_DOWN, 16, Theme.TEXT_2).paintIcon(this, g2, w - 30, (h - 16) / 2);
            g2.dispose();
        }
    }

    /** Painted hero background: gradient, brand glows, floating pixels and the logo. */
    private static final class Hero extends JPanel {
        private final BufferedImage logo = Images.resource("/nekylauncher/img/logo-512.png");

        Hero() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, 28, 28);
            g2.setClip(shape);
            g2.setPaint(new GradientPaint(0, 0, new Color(0x121926), w, h, new Color(0x0C0F15)));
            g2.fill(shape);
            g2.setPaint(new RadialGradientPaint(w - 150f, 40f, 360f, new float[]{0f, 1f},
                    new Color[]{Theme.alpha(Theme.CYAN, 0.20), Theme.alpha(Theme.CYAN, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.setPaint(new RadialGradientPaint(w * 0.55f, h + 60f, 340f, new float[]{0f, 1f},
                    new Color[]{Theme.alpha(Theme.RED, 0.13), Theme.alpha(Theme.RED, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.setPaint(new RadialGradientPaint(60f, 0f, 300f, new float[]{0f, 1f},
                    new Color[]{Theme.alpha(Theme.GREEN, 0.07), Theme.alpha(Theme.GREEN, 0)}));
            g2.fillRect(0, 0, w, h);
            // floating pixels like the logo's glitch squares
            Random r = new Random(42);
            for (int i = 0; i < 26; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * h;
                int s = 3 + r.nextInt(7);
                Color c = r.nextBoolean() ? Theme.CYAN : r.nextInt(3) == 0 ? Theme.RED : Color.WHITE;
                g2.setColor(Theme.alpha(c, 0.05 + r.nextFloat() * 0.12));
                g2.fillRect((int) x, (int) y, s, s);
            }
            // subtle grid
            g2.setColor(new Color(255, 255, 255, 7));
            for (int x = 0; x < w; x += 32) g2.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 32) g2.drawLine(0, y, w, y);
            if (logo != null && w > 760) {
                int ls = Math.min(h + 10, 300);
                int lx = w - ls - 18, ly = (h - ls) / 2;
                g2.setColor(Theme.alpha(Color.BLACK, 0.25));
                g2.fill(new Ellipse2D.Float(lx + ls * 0.09f, ly + ls * 0.09f, ls * 0.82f, ls * 0.82f));
                g2.drawImage(Images.scaledCached(logo, ls * 2), lx, ly, ls, ls, null);
            }
            g2.setClip(null);
            g2.setColor(Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 28, 28));
            g2.dispose();
        }
    }

}
