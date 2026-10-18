package com.nekyoh.nekylauncher.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal launcher logger: console + rolling file (launcher.log, previous run kept as launcher.old.log). */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Writer file;

    private Log() {}

    public static synchronized void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path cur = logDir.resolve("launcher.log");
            if (Files.exists(cur)) Files.move(cur, logDir.resolve("launcher.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            file = Files.newBufferedWriter(cur, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not open log file: " + e);
        }
    }

    public static void info(String msg) { write("INFO", msg, null); }

    public static void warn(String msg) { write("WARN", msg, null); }

    public static void error(String msg, Throwable t) { write("ERROR", msg, t); }

    private static synchronized void write(String level, String msg, Throwable t) {
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + Thread.currentThread().getName() + "/" + level + "] " + msg;
        System.out.println(line);
        if (t != null) t.printStackTrace(System.out);
        if (file != null) {
            try {
                file.write(line);
                file.write(System.lineSeparator());
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    file.write(sw.toString());
                }
                file.flush();
            } catch (IOException ignored) { }
        }
    }
}
