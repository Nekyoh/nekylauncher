package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.Hashes;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.Log;
import com.nekyoh.nekylauncher.util.Progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Parallel, verified downloads with aggregated progress. */
public final class Downloader {
    private Downloader() {}

    public record Task(String url, Path target, String sha1, long size, boolean verifyHash) {
        public static Task of(String url, Path target, String sha1, long size) { return new Task(url, target, sha1, size, true); }
    }

    /** Filters out files that are already present and valid. */
    public static List<Task> missing(List<Task> tasks) {
        List<Task> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Task t : tasks) {
            if (!seen.add(t.target())) continue;
            boolean ok = t.verifyHash() ? Hashes.matches(t.target(), t.sha1(), t.size()) : sizeOk(t);
            if (!ok) out.add(t);
        }
        return out;
    }

    private static boolean sizeOk(Task t) {
        try {
            return Files.isRegularFile(t.target()) && (t.size() <= 0 || Files.size(t.target()) == t.size());
        } catch (IOException e) {
            return false;
        }
    }

    public static void run(List<Task> tasks, int concurrency, Progress progress, CancelToken cancel) throws IOException {
        List<Task> todo = missing(tasks);
        if (todo.isEmpty()) return;
        long totalBytes = 0;
        for (Task t : todo) totalBytes += Math.max(t.size(), 0);
        final long total = totalBytes;
        AtomicLong done = new AtomicLong();
        AtomicInteger files = new AtomicInteger();
        List<IOException> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(concurrency, todo.size())), r -> {
            Thread th = new Thread(r, "neky-download");
            th.setDaemon(true);
            return th;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Task t : todo) {
                futures.add(pool.submit(() -> {
                    if (cancel != null && cancel.isCancelled()) return;
                    if (errors.size() > 8) return; // fail fast on systemic errors (offline, blocked)
                    try {
                        Http.download(t.url(), t.target(), t.verifyHash() ? t.sha1() : null, n -> {
                            long d = done.addAndGet(n);
                            if (total > 0) progress.fraction(Math.min(1.0, (double) d / total));
                        }, cancel);
                        int f = files.incrementAndGet();
                        if (total <= 0) progress.fraction((double) f / todo.size());
                        progress.detail(f + " / " + todo.size());
                    } catch (IOException e) {
                        if (!(e instanceof CancelToken.CancelledException)) {
                            Log.warn("Download failed: " + t.url() + " -> " + e.getMessage());
                            errors.add(e);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (cancel != null) cancel.check();
        if (!errors.isEmpty()) {
            IOException first = errors.get(0);
            throw new IOException(errors.size() + " download(s) failed. " + first.getMessage(), first);
        }
    }
}
