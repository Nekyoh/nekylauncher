package com.nekyoh.nekylauncher.util;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Background execution helpers (virtual threads) with UI-thread callbacks. */
public final class Tasks {
    private Tasks() {}

    private static final ExecutorService POOL = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("neky-task-", 0).factory());

    public interface Job<T> { T run() throws Exception; }

    public interface VoidJob { void run() throws Exception; }

    /** Runs {@code job} in the background, then delivers the result or error on the Swing thread. */
    public static <T> void async(Job<T> job, Consumer<T> onOk, Consumer<Throwable> onError) {
        POOL.execute(() -> {
            try {
                T v = job.run();
                if (onOk != null) ui(() -> onOk.accept(v));
            } catch (Throwable t) {
                if (!(t instanceof CancelToken.CancelledException)) Log.error("Background task failed", t);
                if (onError != null) ui(() -> onError.accept(t));
            }
        });
    }

    public static void async(VoidJob job, Runnable onOk, Consumer<Throwable> onError) {
        async(() -> { job.run(); return Boolean.TRUE; }, v -> { if (onOk != null) onOk.run(); }, onError);
    }

    public static void background(Runnable r) { POOL.execute(r); }

    public static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /** Human readable root cause message. */
    public static String message(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        if (m == null || m.isBlank()) m = c.getClass().getSimpleName();
        if (c instanceof java.net.ConnectException || c instanceof java.net.UnknownHostException
                || c instanceof java.net.http.HttpConnectTimeoutException) {
            m = com.nekyoh.nekylauncher.i18n.I18n.tr("error.network") + " (" + m + ")";
        }
        return m;
    }
}
