package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.MicrosoftAuth;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** Sign-in dialogs for Microsoft (device code), offline names and Ely.by. */
public final class AccountDialogs {
    private AccountDialogs() {}

    // ------------------------------------------------------------------ microsoft

    public static void microsoft(MainWindow window) {
        String clientId = App.get().settings.effectiveMsClientId();
        if (clientId == null || clientId.isBlank()) {
            Stack body = Stack.v(12);
            body.with(Ui.paragraph(I18n.tr("ms.setupNeeded"), Theme.TEXT_2, 13));
            Modal m = Modal.open(I18n.tr("ms.title"), body, 520);
            NButton go = new NButton(I18n.tr("ms.openSettings"), Icons.Name.SETTINGS, NButton.Variant.PRIMARY);
            go.onClick(() -> {
                m.close();
                window.showPage(MainWindow.Page.SETTINGS);
            });
            m.footer(new NButton(I18n.tr("common.close"), NButton.Variant.GHOST).onClick(m::close), go);
            return;
        }
        microsoftBrowser(window, clientId);
    }

    /** One click: the browser opens Microsoft's page, the user signs in, the launcher gets the result. */
    private static void microsoftBrowser(MainWindow window, String clientId) {
        CancelToken cancel = new CancelToken();
        String[] loginUrl = {null};
        JComponent logo = new JLabel(Icons.custom(44, g -> {
            g.setColor(new Color(0xF25022)); g.fillRect(1, 1, 10, 10);
            g.setColor(new Color(0x7FBA00)); g.fillRect(13, 1, 10, 10);
            g.setColor(new Color(0x00A4EF)); g.fillRect(1, 13, 10, 10);
            g.setColor(new Color(0xFFB900)); g.fillRect(13, 13, 10, 10);
        }));
        JLabel status = Ui.muted(I18n.tr("ms.browserOpening"));
        Controls.Progress spin = new Controls.Progress();
        NButton reopen = new NButton(I18n.tr("ms.browserReopen"), Icons.Name.EXTERNAL, NButton.Variant.SECONDARY);
        reopen.setEnabled(false);
        reopen.onClick(() -> { if (loginUrl[0] != null) OS.openUrl(loginUrl[0]); });
        NButton copyLink = NButton.icon(Icons.Name.COPY, I18n.tr("ms.browserCopyLink"), NButton.Variant.GHOST, NButton.Size.MD);
        copyLink.setEnabled(false);
        copyLink.onClick(() -> {
            if (loginUrl[0] == null) return;
            OS.copyToClipboard(loginUrl[0]);
            Toast.show(I18n.tr("ms.browserLinkCopied"), Toast.Kind.INFO);
        });
        Stack actions = Stack.h(8);
        actions.with(reopen, copyLink);
        Stack body = Stack.v(14);
        body.with(Ui.center(logo), Ui.paragraph(I18n.tr("ms.browserSteps"), Theme.TEXT_2, 13.5f), status, spin, Ui.center(actions));
        Modal m = Modal.open(I18n.tr("ms.title"), body, 480);
        m.onClose(cancel::cancel);
        JLabel useCode = Ui.link(I18n.tr("ms.useCode"), () -> {
            m.close();
            microsoftDeviceCode(window, clientId);
        });
        m.footer(useCode, new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(m::close));

        Tasks.async(() -> new MicrosoftAuth(clientId).loginWithBrowser(url -> SwingUtilities.invokeLater(() -> {
            loginUrl[0] = url;
            OS.openUrl(url);
            reopen.setEnabled(true);
            copyLink.setEnabled(true);
            status.setText(I18n.tr("ms.browserWaiting"));
        }), cancel), acc -> {
            App.get().accounts.add(acc);
            m.close();
            Toast.show(I18n.tr("accounts.added", acc.username), Toast.Kind.SUCCESS);
            window.toFront();
        }, err -> {
            if (err instanceof CancelToken.CancelledException) return;
            m.close();
            Modal.error(I18n.tr("ms.failed"), Tasks.message(err));
        });
    }

    /** Fallback: sign in on another device (phone/PC) with a short code at microsoft.com/link. */
    private static void microsoftDeviceCode(MainWindow window, String clientId) {
        CancelToken cancel = new CancelToken();
        JLabel status = Ui.muted(I18n.tr("ms.requesting"));
        CodeBox code = new CodeBox();
        NButton copy = new NButton(I18n.tr("ms.copyCode"), Icons.Name.COPY, NButton.Variant.SECONDARY);
        NButton openLink = new NButton(I18n.tr("ms.openPage"), Icons.Name.EXTERNAL, NButton.Variant.PRIMARY);
        copy.setEnabled(false);
        openLink.setEnabled(false);
        Controls.Progress spin = new Controls.Progress();
        Stack actions = Stack.h(10);
        actions.with(copy, openLink);
        Stack body = Stack.v(14);
        body.with(Ui.paragraph(I18n.tr("ms.steps"), Theme.TEXT_2, 13), code, Ui.center(actions), status, spin);
        Modal m = Modal.open(I18n.tr("ms.title"), body, 500);
        m.onClose(cancel::cancel);
        m.footer(new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(m::close));

        Tasks.async(() -> {
            MicrosoftAuth auth = new MicrosoftAuth(clientId);
            MicrosoftAuth.DeviceCode dc = auth.startDeviceCode();
            SwingUtilities.invokeLater(() -> {
                code.setCode(dc.userCode());
                copy.setEnabled(true);
                openLink.setEnabled(true);
                copy.onClick(() -> {
                    OS.copyToClipboard(dc.userCode());
                    Toast.show(I18n.tr("ms.copied"), Toast.Kind.INFO);
                });
                openLink.onClick(() -> {
                    OS.copyToClipboard(dc.userCode());
                    OS.openUrl(dc.verificationUri());
                });
                status.setText(I18n.tr("ms.waiting", dc.verificationUri()));
                OS.copyToClipboard(dc.userCode());
            });
            return auth.pollAndLogin(dc, cancel);
        }, acc -> {
            App.get().accounts.add(acc);
            m.close();
            Toast.show(I18n.tr("accounts.added", acc.username), Toast.Kind.SUCCESS);
        }, err -> {
            if (err instanceof CancelToken.CancelledException) return;
            m.close();
            Modal.error(I18n.tr("ms.failed"), Tasks.message(err));
        });
    }

    /** Big monospaced device code. */
    private static final class CodeBox extends JComponent {
        private String code = "········";

        CodeBox() { setPreferredSize(new Dimension(300, 74)); }

        void setCode(String c) {
            code = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.hq(g.create());
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.SURFACE_2);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 24, 24));
            g2.setColor(Theme.alpha(Theme.CYAN, 0.4));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 24, 24));
            g2.setFont(Theme.mono(30).deriveFont(Font.BOLD));
            FontMetrics fm = g2.getFontMetrics();
            StringBuilder spaced = new StringBuilder();
            for (char c : code.toCharArray()) spaced.append(c).append(' ');
            String s = spaced.toString().trim();
            g2.setColor(Theme.TEXT);
            Theme.glitchText(g2, s, (w - fm.stringWidth(s)) / 2f, (h - fm.getHeight()) / 2f + fm.getAscent(), 1.2f);
            g2.dispose();
        }
    }

}
