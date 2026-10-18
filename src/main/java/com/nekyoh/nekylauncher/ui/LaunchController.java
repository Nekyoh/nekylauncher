package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.game.GameProcess;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.ui.kit.Modal;
import com.nekyoh.nekylauncher.ui.kit.Toast;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** UI glue for launching: per-instance progress state, after-launch window behaviour, console windows. */
public final class LaunchController {
    public static final class State implements Progress {
        public volatile String stage = "";
        public volatile String detail = "";
        public volatile double fraction = -1;
        public final CancelToken cancel = new CancelToken();
        private final LaunchController owner;
        private volatile boolean pending;

        State(LaunchController owner) { this.owner = owner; }

        @Override public void stage(String text) { stage = text; detail = ""; push(); }

        @Override public void fraction(double value) { fraction = value; push(); }

        @Override public void detail(String text) { detail = text; push(); }

        private void push() {
            if (pending) return;
            pending = true;
            SwingUtilities.invokeLater(() -> {
                pending = false;
                owner.fire();
            });
        }
    }

    private final MainWindow window;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, ConsoleWindow> consoles = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public LaunchController(MainWindow window) {
        this.window = window;
        App.get().launcher.addListener(() -> Tasks.ui(this::fire));
    }

    public void addListener(Runnable r) { listeners.add(r); }

    void fire() { listeners.forEach(Runnable::run); }

    public State state(Instance i) { return states.get(i.id()); }

    public boolean isRunning(Instance i) { return App.get().launcher.running(i) != null; }

    public boolean isPreparing(Instance i) { return states.containsKey(i.id()); }

    public void cancel(Instance i) {
        State s = states.get(i.id());
        if (s != null) s.cancel.cancel();
    }

    public void stop(Instance i) {
        GameProcess gp = App.get().launcher.running(i);
        if (gp == null) return;
        Modal.confirm(I18n.tr("launch.stopTitle"), I18n.tr("launch.stopMessage", i.name), I18n.tr("launch.stop"), true, gp::kill);
    }

    public void play(Instance inst) {
        App app = App.get();
        if (isPreparing(inst)) return;
        if (isRunning(inst)) {
            openConsole(inst);
            Toast.show(I18n.tr("launch.alreadyRunning", inst.name), Toast.Kind.INFO);
            return;
        }
        Account acc = app.accounts.active();
        if (acc == null) {
            window.showPage(MainWindow.Page.ACCOUNTS);
            Toast.show(I18n.tr("launch.needAccount"), Toast.Kind.WARNING);
            return;
        }
        State st = new State(this);
        st.stage = I18n.tr("progress.starting");
        states.put(inst.id(), st);
        app.settings.lastInstance = inst.id();
        app.settings.save();
        fire();
        Tasks.async(() -> app.launcher.launch(inst, acc, st, st.cancel), gp -> {
            states.remove(inst.id());
            fire();
            Toast.show(I18n.tr("launch.started", inst.name), Toast.Kind.SUCCESS, I18n.tr("launch.console"), () -> openConsole(inst));
            if (app.settings.showConsoleOnLaunch) openConsole(inst);
            applyAfterLaunch(app.settings.afterLaunch);
            gp.onExit(code -> Tasks.ui(() -> onExit(inst, gp, code)));
        }, err -> {
            states.remove(inst.id());
            fire();
            if (err instanceof CancelToken.CancelledException) {
                Toast.show(I18n.tr("launch.cancelled"), Toast.Kind.INFO);
            } else {
                Modal.error(I18n.tr("launch.failedTitle", inst.name), Tasks.message(err));
            }
        });
    }

    private void applyAfterLaunch(Settings.AfterLaunch mode) {
        switch (mode) {
            case MINIMIZE -> window.setExtendedState(window.getExtendedState() | Frame.ICONIFIED);
            case HIDE -> window.setVisible(false);
            default -> { }
        }
    }

    private void onExit(Instance inst, GameProcess gp, int code) {
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        if ((window.getExtendedState() & Frame.ICONIFIED) != 0 && App.get().settings.afterLaunch == Settings.AfterLaunch.HIDE) {
            window.setExtendedState(window.getExtendedState() & ~Frame.ICONIFIED);
        }
        fire();
        // a stop requested by the user is not a crash
        boolean crashed = code != 0 && !gp.wasKilled();
        if (crashed) {
            if (App.get().settings.afterLaunch == Settings.AfterLaunch.HIDE || App.get().settings.afterLaunch == Settings.AfterLaunch.MINIMIZE) {
                window.setExtendedState(window.getExtendedState() & ~Frame.ICONIFIED);
                window.toFront();
            }
            Toast.show(I18n.tr("launch.crashed", inst.name, code), Toast.Kind.ERROR, I18n.tr("launch.viewLog"), () -> openConsole(inst));
            openConsole(inst);
        }
    }

    public void openConsole(Instance inst) {
        GameProcess gp = App.get().launcher.running(inst);
        ConsoleWindow existing = consoles.get(inst.id());
        if (existing != null && existing.isDisplayable()) {
            if (gp != null) existing.attach(gp);
            existing.setVisible(true);
            existing.toFront();
            return;
        }
        if (gp == null) {
            Toast.show(I18n.tr("launch.noConsole"), Toast.Kind.INFO);
            return;
        }
        ConsoleWindow w = new ConsoleWindow(inst, gp, App.get().settings.systemWindowFrame);
        consoles.put(inst.id(), w);
        w.setLocationRelativeTo(window);
        w.setVisible(true);
    }
}
