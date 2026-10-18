package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.*;
import com.nekyoh.nekylauncher.ui.dialogs.ImportFlow;
import com.nekyoh.nekylauncher.ui.dialogs.NewInstanceDialog;
import com.nekyoh.nekylauncher.ui.kit.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** All instances as a responsive grid, with search, create and import (also by drag & drop). */
public final class InstancesPage extends JPanel {
    private final MainWindow window;
    private final JPanel grid = Ui.transparent(new GridFlow(300, 96, 14));
    private final NTextField search = new NTextField(I18n.tr("instances.search"), Icons.Name.SEARCH);
    private final JLabel count = Ui.muted("");
    private boolean dropping;

    public InstancesPage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);

        JPanel head = Ui.transparent(new BorderLayout(16, 0));
        Stack title = Stack.v(4);
        title.with(Ui.h1(I18n.tr("instances.title")), count);
        head.add(title, BorderLayout.WEST);
        search.setPreferredSize(new Dimension(260, 38));
        search.onChange(t -> rebuild());
        NButton importBtn = new NButton(I18n.tr("instances.import"), Icons.Name.DOWNLOAD, NButton.Variant.SECONDARY);
        importBtn.onClick(() -> ImportFlow.pickAndImport(window));
        NButton create = new NButton(I18n.tr("instances.new"), Icons.Name.PLUS, NButton.Variant.PRIMARY);
        create.onClick(() -> NewInstanceDialog.open(window));
        Stack actions = Stack.h(10);
        actions.with(search, importBtn, create);
        JPanel actionsWrap = Ui.transparent(new GridBagLayout());
        actionsWrap.add(actions);
        head.add(actionsWrap, BorderLayout.EAST);

        Stack content = Stack.v(22);
        content.with(head, grid);
        content.pad(26, 28, 28, 28);
        JScrollPane sp = NScroll.vertical(content, Theme.BG);
        add(sp, BorderLayout.CENTER);

        FileDrop.install(this, new FileDrop.Listener() {
            @Override public void dropped(List<Path> files) {
                for (Path p : files) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".zip") || n.endsWith(".mrpack")) ImportFlow.importFile(window, p);
                }
            }

            @Override public void hover(boolean active) {
                dropping = active;
                repaint();
            }
        });
        refresh();
    }

    public void refresh() { rebuild(); }

    private void rebuild() {
        List<Instance> all = App.get().instances.all();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        grid.removeAll();
        int shown = 0;
        for (Instance i : all) {
            if (!q.isEmpty() && !(i.name.toLowerCase(Locale.ROOT).contains(q) || i.mcVersion.contains(q)
                    || i.loader.display.toLowerCase(Locale.ROOT).contains(q))) continue;
            grid.add(new InstanceCard(window, i));
            shown++;
        }
        count.setText(I18n.tr("instances.count", all.size()));
        if (all.isEmpty()) {
            grid.setLayout(new BorderLayout());
            grid.add(emptyState(), BorderLayout.CENTER);
        } else {
            grid.setLayout(new GridFlow(300, 96, 14));
            if (shown == 0) {
                grid.setLayout(new BorderLayout());
                JLabel none = Ui.muted(I18n.tr("instances.noMatch"));
                none.setHorizontalAlignment(SwingConstants.CENTER);
                grid.add(none, BorderLayout.CENTER);
            }
        }
        grid.revalidate();
        grid.repaint();
    }

    public void refreshLive() {
        for (Component c : grid.getComponents()) c.repaint();
    }

    private JComponent emptyState() {
        Card c = new Card(new BorderLayout());
        c.pad(40, 30, 40, 30);
        Stack s = Stack.v(12);
        JLabel icon = Ui.icon(Icons.Name.CUBE, 40, Theme.CYAN);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel t = Ui.h2(I18n.tr("home.empty.title"));
        t.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel d = Ui.muted(I18n.tr("instances.emptyDesc"));
        d.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel btns = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btns.add(new NButton(I18n.tr("instances.new"), Icons.Name.PLUS, NButton.Variant.PRIMARY).onClick(() -> NewInstanceDialog.open(window)));
        btns.add(new NButton(I18n.tr("instances.import"), Icons.Name.DOWNLOAD, NButton.Variant.SECONDARY).onClick(() -> ImportFlow.pickAndImport(window)));
        s.with(icon, t, d, btns);
        c.add(s, BorderLayout.CENTER);
        c.setPreferredSize(new Dimension(400, 250));
        return c;
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (dropping) {
            Graphics2D g2 = Theme.hq(g.create());
            g2.setColor(Theme.alpha(Theme.BG, 0.85));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Theme.CYAN);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{8f, 8f}, 0));
            g2.draw(new RoundRectangle2D.Float(24, 24, getWidth() - 48, getHeight() - 48, 28, 28));
            g2.setFont(Theme.font(18, Theme.Weight.SEMIBOLD));
            String t = I18n.tr("instances.dropImport");
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Theme.TEXT);
            g2.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, getHeight() / 2);
            g2.dispose();
        }
    }
}
