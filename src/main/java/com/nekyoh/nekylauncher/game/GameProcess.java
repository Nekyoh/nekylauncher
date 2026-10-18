package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A running game: streams and parses its output (incl. log4j XML events), notifies on exit. */
public final class GameProcess {
    public enum Level { INFO, WARN, ERROR, DEBUG, LAUNCHER }

    public record Line(String text, Level level) {}

    private static final int MAX_LINES = 8000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    public final Instance instance;
    public final long startedAt = System.currentTimeMillis();
    private final Process process;
    private final Deque<Line> buffer = new ArrayDeque<>();
    private final List<Consumer<Line>> lineListeners = new CopyOnWriteArrayList<>();
    private final List<IntConsumer> exitListeners = new CopyOnWriteArrayList<>();
    private volatile Integer exitCode;

    public GameProcess(Instance instance, Process process, List<String> preamble) {
        this.instance = instance;
        this.process = process;
        for (String s : preamble) push(new Line(s, Level.LAUNCHER));
        pump(process.getInputStream(), false);
        pump(process.getErrorStream(), true);
        Thread.ofPlatform().daemon().name("neky-game-wait").start(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            exitCode = code;
            push(new Line("Process exited with code " + code, code == 0 ? Level.LAUNCHER : Level.ERROR));
            for (IntConsumer c : exitListeners) {
                try { c.accept(code); } catch (Exception e) { Log.error("Exit listener failed", e); }
            }
        });
    }

    public boolean isAlive() { return process.isAlive(); }

    public Integer exitCode() { return exitCode; }

    public long pid() { return process.pid(); }

    private volatile boolean killed;

    public boolean wasKilled() { return killed; }

    public void kill() {
        killed = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public synchronized List<Line> snapshot() { return new ArrayList<>(buffer); }

    /** Adds a listener and returns the lines emitted so far (atomically, so nothing is missed). */
    public synchronized List<Line> subscribe(Consumer<Line> l) {
        lineListeners.add(l);
        return new ArrayList<>(buffer);
    }

    public void unsubscribe(Consumer<Line> l) { lineListeners.remove(l); }

    public void onExit(IntConsumer c) {
        Integer code = exitCode;
        if (code != null) c.accept(code);
        else exitListeners.add(c);
    }

    private synchronized void push(Line line) {
        buffer.addLast(line);
        while (buffer.size() > MAX_LINES) buffer.removeFirst();
        for (Consumer<Line> l : lineListeners) l.accept(line);
    }

    private void pump(InputStream in, boolean stderr) {
        Thread.ofPlatform().daemon().name(stderr ? "neky-game-err" : "neky-game-out").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder event = null;
                String raw;
                while ((raw = r.readLine()) != null) {
                    String t = raw.trim();
                    if (event == null && t.startsWith("<log4j:Event")) {
                        event = new StringBuilder();
                    }
                    if (event != null) {
                        event.append(raw).append('\n');
                        if (t.endsWith("</log4j:Event>")) {
                            for (Line l : parseEvent(event.toString())) push(l);
                            event = null;
                        }
                        continue;
                    }
                    push(new Line(raw, guess(raw, stderr)));
                }
            } catch (IOException ignored) {
                // stream closed when the process ends
            }
        });
    }

    static Level guess(String s, boolean stderr) {
        if (s.contains("/ERROR]") || s.contains("[ERROR]") || s.contains("Exception") || s.startsWith("\tat ")
                || s.contains("FATAL")) return Level.ERROR;
        if (s.contains("/WARN]") || s.contains("[WARN]") || s.contains("WARNING")) return Level.WARN;
        if (s.contains("/DEBUG]")) return Level.DEBUG;
        return stderr ? Level.WARN : Level.INFO;
    }

    /** Converts a log4j XMLLayout event into readable "[time] [thread/LEVEL]: message" lines. */
    static List<Line> parseEvent(String xml) {
        int tagEnd = xml.indexOf('>');
        String head = tagEnd > 0 ? xml.substring(0, tagEnd) : xml;
        String level = "INFO", thread = "main", time = "";
        Matcher m = ATTR.matcher(head);
        while (m.find()) {
            switch (m.group(1)) {
                case "level" -> level = m.group(2);
                case "thread" -> thread = m.group(2);
                case "timestamp" -> {
                    try {
                        time = LocalTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(m.group(2))), ZoneId.systemDefault()).format(TIME);
                    } catch (Exception ignored) { }
                }
                default -> { }
            }
        }
        String msg = cdata(xml, "log4j:Message");
        String thr = cdata(xml, "log4j:Throwable");
        Level lv = switch (level) {
            case "WARN" -> Level.WARN;
            case "ERROR", "FATAL" -> Level.ERROR;
            case "DEBUG", "TRACE" -> Level.DEBUG;
            default -> Level.INFO;
        };
        List<Line> out = new ArrayList<>();
        String prefix = "[" + time + "] [" + thread + "/" + level + "]: ";
        String[] lines = (msg == null ? "" : msg).split("\\R", -1);
        out.add(new Line(prefix + lines[0], lv));
        for (int i = 1; i < lines.length; i++) out.add(new Line(lines[i], lv));
        if (thr != null) for (String l : thr.split("\\R")) if (!l.isBlank()) out.add(new Line(l, Level.ERROR));
        return out;
    }

    private static String cdata(String xml, String tag) {
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) return null;
        String inner = xml.substring(s + tag.length() + 2, e);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int a = inner.indexOf("<![CDATA[", i);
            if (a < 0) { if (sb.isEmpty()) sb.append(unescape(inner.substring(i))); break; }
            int b = inner.indexOf("]]>", a);
            if (b < 0) break;
            sb.append(inner, a + 9, b);
            i = b + 3;
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
