package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.game.GameProcess;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.ui.window.NFrame;
import com.nekyoh.nekylauncher.util.OS;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Live game log with level colours, search-free but copyable, kill button. */
public final class ConsoleWindow extends NFrame {
    private static final int MAX_CHARS = 1_500_000;

    private final Instance instance;
    private final JTextPane text = new JTextPane();
    private final StyledDocument doc = text.getStyledDocument();
    private final Style info, warn, error, debug, launcher;
    private final JLabel status = Ui.small("");
    private final NButton killBtn;
    private final Controls.Toggle follow = new Controls.Toggle(true);
    private final List<GameProcess.Line> pending = new ArrayList<>();
    private final Timer flush;
    private GameProcess process;
    private Consumer<GameProcess.Line> sub;

    public ConsoleWindow(Instance instance, GameProcess gp, boolean systemFrame) {
        super(I18n.tr("console.title", instance.name), systemFrame);
        this.instance = instance;
        setSize(980, 600);
        setMinimumSize(new Dimension(620, 360));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        text.setEditable(false);
        text.setBackground(new Color(0x0A0C10));
        text.setBorder(new EmptyBorder(12, 14, 12, 14));
        text.setFont(Theme.mono(12.5f));
        info = style("info", Theme.TEXT);
        warn = style("warn", new Color(0xFBBF24));
        error = style("error", new Color(0xFB7185));
        debug = style("debug", Theme.TEXT_3);
        launcher = style("launcher", Theme.CYAN);

        JScrollPane sp = NScroll.wrap(text, new Color(0x0A0C10));

        NButton copy = new NButton(I18n.tr("common.copy"), Icons.Name.COPY, NButton.Variant.GHOST, NButton.Size.SM);
        copy.onClick(() -> OS.copyToClipboard(text.getText()));
        NButton clear = new NButton(I18n.tr("console.clear"), Icons.Name.TRASH, NButton.Variant.GHOST, NButton.Size.SM);
        clear.onClick(() -> text.setText(""));
        NButton logs = new NButton(I18n.tr("console.logsFolder"), Icons.Name.FOLDER, NButton.Variant.GHOST, NButton.Size.SM);
        logs.onClick(() -> OS.openFolder(instance.gameDir().resolve("logs")));
        killBtn = new NButton(I18n.tr("launch.stop"), Icons.Name.STOP, NButton.Variant.DANGER, NButton.Size.SM);
        killBtn.onClick(() -> {
            if (process != null && process.isAlive()) process.kill();
        });

        Stack bar = Stack.h(8);
        bar.with(status).glue().with(Ui.small(I18n.tr("console.autoscroll")), follow, copy, clear, logs, killBtn);
        bar.pad(10, 16, 10, 12);
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.SURFACE);
        top.add(bar, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        body.add(top, BorderLayout.NORTH);
        body.add(sp, BorderLayout.CENTER);

        flush = new Timer(120, e -> drain());
        flush.start();
        attach(gp);
    }

    private Style style(String name, Color c) {
        Style s = text.addStyle(name, null);
        StyleConstants.setForeground(s, c);
        StyleConstants.setFontFamily(s, Theme.mono(12.5f).getFamily());
        StyleConstants.setFontSize(s, 13);
        return s;
    }

    /** Switches the window to a (new) process run of this instance. */
    public void attach(GameProcess gp) {
        if (process == gp) return;
        if (process != null && sub != null) process.unsubscribe(sub);
        process = gp;
        text.setText("");
        sub = line -> {
            synchronized (pending) { pending.add(line); }
        };
        List<GameProcess.Line> backlog = gp.subscribe(sub);
        synchronized (pending) { pending.addAll(0, backlog); }
        updateStatus();
        gp.onExit(code -> SwingUtilities.invokeLater(this::updateStatus));
    }

    private void updateStatus() {
        if (process == null) return;
        Integer code = process.exitCode();
        if (code == null) {
            status.setText("● " + I18n.tr("console.running") + "  ·  PID " + process.pid());
            status.setForeground(Theme.GREEN);
            killBtn.setEnabled(true);
        } else {
            status.setText(I18n.tr("console.exited", code));
            status.setForeground(code == 0 ? Theme.TEXT_2 : Theme.RED);
            killBtn.setEnabled(false);
        }
    }

    private void drain() {
        List<GameProcess.Line> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        try {
            for (GameProcess.Line l : batch) {
                Style s = switch (l.level()) {
                    case WARN -> warn;
                    case ERROR -> error;
                    case DEBUG -> debug;
                    case LAUNCHER -> launcher;
                    default -> info;
                };
                doc.insertString(doc.getLength(), l.text() + "\n", s);
            }
            if (doc.getLength() > MAX_CHARS) doc.remove(0, doc.getLength() - MAX_CHARS + 200_000);
        } catch (BadLocationException ignored) { }
        if (follow.isOn()) text.setCaretPosition(doc.getLength());
    }

    @Override
    public void dispose() {
        flush.stop();
        if (process != null && sub != null) process.unsubscribe(sub);
        super.dispose();
    }

    public Instance instance() { return instance; }
}
