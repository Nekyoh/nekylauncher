package com.nekyoh.nekylauncher.ui.kit;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.util.OS;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;

/** In-window modal dialogs (dimmed backdrop + centered card), stackable, Esc to close. */
public final class Modal {
    private static JLayeredPane layers;
    private static final Deque<Modal> STACK = new ArrayDeque<>();

    public static void init(JRootPane root) {
        layers = root.getLayeredPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "neky-modal-esc");
        root.getActionMap().put("neky-modal-esc", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                Modal top = STACK.peek();
                if (top != null && top.escClosable) top.close();
            }
        });
        layers.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                for (Modal m : STACK) m.backdrop.setBounds(0, 0, layers.getWidth(), layers.getHeight());
            }
        });
    }

    public static boolean isOpen() { return !STACK.isEmpty(); }

    private final JPanel backdrop;
    private final JPanel card;
    private final JPanel footer;
    private final JLabel titleLabel;
    private Runnable onClose;
    private boolean escClosable = true;
    private boolean closed;

    private Modal(String title, JComponent body, int width) {
        backdrop = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(new Color(4, 6, 10, 170));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        backdrop.setOpaque(false);
        MouseAdapter eat = new MouseAdapter() { };
        backdrop.addMouseListener(eat);
        backdrop.addMouseMotionListener(eat);
        backdrop.addMouseWheelListener(e -> { });

        card = new JPanel(new BorderLayout(0, 16)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.hq(g.create());
                for (int i = 10; i >= 1; i--) {
                    g2.setColor(new Color(0, 0, 0, 7));
                    g2.fill(new RoundRectangle2D.Float(-i + 10, -i + 14, getWidth() + 2 * i - 20, getHeight() + 2 * i - 20, 32, 32));
                }
                RoundRectangle2D r = new RoundRectangle2D.Float(10, 10, getWidth() - 21, getHeight() - 21, 28, 28);
                g2.setColor(Theme.SURFACE);
                g2.fill(r);
                g2.setColor(Theme.BORDER_STRONG);
                g2.draw(r);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(30, 32, 28, 32));

        JPanel head = Ui.transparent(new BorderLayout());
        titleLabel = Ui.label(title, 18, Theme.Weight.BOLD, Theme.TEXT);
        head.add(titleLabel, BorderLayout.CENTER);
        NButton x = NButton.icon(Icons.Name.X, I18n.tr("common.close"), NButton.Variant.GHOST, NButton.Size.SM);
        x.onClick(this::close);
        head.add(x, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        footer = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        card.add(footer, BorderLayout.SOUTH);
        int w = width + 20;
        card.setPreferredSize(null);
        card.putClientProperty("neky.width", w);
        backdrop.add(card);
        backdrop.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { sizeCard(); }
        });
    }

    private void sizeCard() {
        int w = (Integer) card.getClientProperty("neky.width");
        int maxW = Math.max(320, backdrop.getWidth() - 48);
        int cw = Math.min(w, maxW);
        card.setPreferredSize(null);
        card.setSize(cw, 10);
        int ph = card.getPreferredSize().height;
        int maxH = Math.max(240, backdrop.getHeight() - 40);
        card.setPreferredSize(new Dimension(cw, Math.min(ph, maxH)));
        backdrop.revalidate();
    }

    public static Modal open(String title, JComponent body, int width) {
        if (layers == null) throw new IllegalStateException("Modal not initialised");
        Modal m = new Modal(title, body, width);
        m.backdrop.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        layers.add(m.backdrop, JLayeredPane.MODAL_LAYER);
        layers.moveToFront(m.backdrop);
        STACK.push(m);
        m.sizeCard();
        layers.revalidate();
        layers.repaint();
        SwingUtilities.invokeLater(() -> {
            Component f = firstFocusable(body);
            if (f != null) f.requestFocusInWindow();
        });
        return m;
    }

    private static Component firstFocusable(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JTextField t && t.isEditable() && t.isShowing()) return t;
            if (ch instanceof Container cc) {
                Component f = firstFocusable(cc);
                if (f != null) return f;
            }
        }
        return null;
    }

    public Modal footer(JComponent... buttons) {
        footer.removeAll();
        for (JComponent b : buttons) footer.add(b);
        footer.revalidate();
        sizeCard();
        return this;
    }

    /** Re-measures after the body changed size. */
    public void relayout() { sizeCard(); }

    public Modal onClose(Runnable r) {
        onClose = r;
        return this;
    }

    public Modal escClosable(boolean v) {
        escClosable = v;
        return this;
    }

    public void setTitle(String t) { titleLabel.setText(t); }

    public boolean isClosed() { return closed; }

    public void close() {
        if (closed) return;
        closed = true;
        STACK.remove(this);
        layers.remove(backdrop);
        layers.revalidate();
        layers.repaint();
        if (onClose != null) onClose.run();
    }

    // ------------------------------------------------------------------ helpers

    public static void confirm(String title, String message, String confirmText, boolean danger, Runnable onYes) {
        JTextArea text = Ui.paragraph(message, Theme.TEXT_2, 13.5f);
        Modal m = open(title, text, 440);
        NButton cancel = new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST);
        NButton ok = new NButton(confirmText, danger ? NButton.Variant.DANGER : NButton.Variant.PRIMARY);
        cancel.onClick(m::close);
        ok.onClick(() -> {
            m.close();
            onYes.run();
        });
        m.footer(cancel, ok);
    }

    public static void message(String title, String message) {
        JTextArea text = Ui.paragraph(message, Theme.TEXT_2, 13.5f);
        Modal m = open(title, text, 460);
        NButton ok = new NButton(I18n.tr("common.ok"), NButton.Variant.PRIMARY);
        ok.onClick(m::close);
        m.footer(ok);
    }

    public static void error(String title, String message) {
        Stack body = Stack.v(12);
        JPanel row = Ui.transparent(new BorderLayout(12, 0));
        row.add(Ui.icon(Icons.Name.WARNING, 22, Theme.RED), BorderLayout.WEST);
        JTextArea text = Ui.paragraph(message, Theme.TEXT, 13.5f);
        row.add(text, BorderLayout.CENTER);
        body.add(row);
        Modal m = open(title, body, 520);
        NButton copy = new NButton(I18n.tr("common.copy"), Icons.Name.COPY, NButton.Variant.GHOST);
        copy.onClick(() -> OS.copyToClipboard(message));
        NButton ok = new NButton(I18n.tr("common.ok"), NButton.Variant.PRIMARY);
        ok.onClick(m::close);
        m.footer(copy, ok);
    }
}
