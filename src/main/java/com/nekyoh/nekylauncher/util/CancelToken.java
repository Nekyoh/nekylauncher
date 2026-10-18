package com.nekyoh.nekylauncher.util;

import java.io.IOException;

/** Cooperative cancellation flag shared between UI and background work. */
public final class CancelToken {
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    public void check() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    public static final class CancelledException extends IOException {
        public CancelledException() { super("Cancelled"); }
    }
}
