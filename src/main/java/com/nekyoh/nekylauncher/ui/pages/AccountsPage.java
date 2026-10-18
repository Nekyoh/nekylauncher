package com.nekyoh.nekylauncher.ui.pages;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.*;
import com.nekyoh.nekylauncher.ui.dialogs.AccountDialogs;
import com.nekyoh.nekylauncher.ui.kit.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/** Add Microsoft / offline / Ely.by accounts and pick the active one. */
public final class AccountsPage extends JPanel {
    private final MainWindow window;
    private final Stack list = Stack.v(10);

    public AccountsPage(MainWindow window) {
        super(new BorderLayout());
        this.window = window;
        setBackground(Theme.BG);

        Stack head = Stack.v(4);
        head.with(Ui.h1(I18n.tr("accounts.title")), Ui.muted(I18n.tr("accounts.subtitle")));

        JComponent add = msCard();

        Stack content = Stack.v(22);
        content.with(head, add, Ui.h2(I18n.tr("accounts.yours")), list);
        content.pad(26, 28, 28, 28);
        add(NScroll.vertical(content, Theme.BG), BorderLayout.CENTER);
        refresh();
    }

    private static JComponent msLogo() {
        return new JLabel(Icons.custom(22, g -> {
            g.setColor(new Color(0xF25022)); g.fillRect(2, 2, 9, 9);
            g.setColor(new Color(0x7FBA00)); g.fillRect(13, 2, 9, 9);
            g.setColor(new Color(0x00A4EF)); g.fillRect(2, 13, 9, 9);
            g.setColor(new Color(0xFFB900)); g.fillRect(13, 13, 9, 9);
        }));
    }

    /** The only way in: the original game with a Microsoft account. */
    private JComponent msCard() {
        Card c = new Card(new BorderLayout(18, 0));
        c.pad(18, 20, 18, 20);
        JPanel iconBox = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.hq(g.create());
                g2.setColor(Theme.alpha(Theme.GREEN, 0.12));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 22, 22));
                g2.dispose();
            }
        };
        iconBox.setOpaque(false);
        iconBox.setPreferredSize(new Dimension(52, 52));
        iconBox.add(msLogo());
        c.add(Ui.center(iconBox), BorderLayout.WEST);
        Stack t = Stack.v(4);
        t.with(Ui.label(I18n.tr("accounts.ms.title"), 15.5f, Theme.Weight.SEMIBOLD, Theme.TEXT), Ui.paragraph(I18n.tr("accounts.ms.desc"), Theme.TEXT_2, 12.5f));
        JPanel tw = Ui.transparent(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        tw.add(t, gc);
        c.add(tw, BorderLayout.CENTER);
        NButton login = new NButton(I18n.tr("accounts.ms.login"), Icons.Name.USER, NButton.Variant.PRIMARY, NButton.Size.LG);
        login.onClick(() -> AccountDialogs.microsoft(window));
        c.add(Ui.center(login), BorderLayout.EAST);
        return c;
    }

    public void refresh() {
        list.removeAll();
        List<Account> all = App.get().accounts.all();
        Account active = App.get().accounts.active();
        if (all.isEmpty()) {
            Card empty = new Card(new BorderLayout());
            empty.pad(22, 22, 22, 22);
            empty.add(Ui.muted(I18n.tr("accounts.none")), BorderLayout.CENTER);
            list.with(empty);
        }
        for (Account a : all) list.with(row(a, active != null && active.id.equals(a.id)));
        list.revalidate();
        list.repaint();
    }

    private JComponent row(Account a, boolean isActive) {
        Card c = new Card(new BorderLayout(14, 0));
        c.pad(12, 14, 12, 14);
        c.setSelected(isActive);
        JComponent avatar = new JComponent() {
            @Override protected void paintComponent(Graphics g) { Art.avatar((Graphics2D) g, a, 0, 0, 46, this); }
        };
        avatar.setPreferredSize(new Dimension(46, 46));
        c.add(avatar, BorderLayout.WEST);
        Stack info = Stack.v(5);
        JPanel nameRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nameRow.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        nameRow.add(Ui.label(a.username, 15, Theme.Weight.SEMIBOLD, Theme.TEXT));
        Color tc = Theme.GREEN;
        nameRow.add(Ui.chip(UiText.accountType(a), tc));
        if (isActive) nameRow.add(Ui.chip(I18n.tr("accounts.active"), Theme.CYAN, Icons.Name.CHECK));
        info.with(nameRow, Ui.small("UUID " + a.dashedUuid()));
        JPanel infoWrap = Ui.transparent(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        infoWrap.add(info, gc);
        c.add(infoWrap, BorderLayout.CENTER);
        Stack actions = Stack.h(8);
        if (!isActive) actions.with(new NButton(I18n.tr("accounts.use"), NButton.Variant.SECONDARY).onClick(() -> App.get().accounts.setActive(a)));
        actions.with(NButton.icon(Icons.Name.TRASH, I18n.tr("accounts.remove"), NButton.Variant.GHOST, NButton.Size.MD).onClick(() ->
                Modal.confirm(I18n.tr("accounts.removeTitle"), I18n.tr("accounts.removeMessage", a.username), I18n.tr("accounts.remove"), true,
                        () -> App.get().accounts.remove(a))));
        JPanel aw = Ui.transparent(new GridBagLayout());
        aw.add(actions);
        c.add(aw, BorderLayout.EAST);
        return c;
    }
}
