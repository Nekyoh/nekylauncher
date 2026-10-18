package com.nekyoh.nekylauncher;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.Images;
import com.nekyoh.nekylauncher.util.Log;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;

/** Entry point. */
public final class NekyLauncher {
    private NekyLauncher() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.useSystemProxies", "true");
        System.setProperty("sun.java2d.noddraw", "true");
        if (OS.isLinux()) System.setProperty("jdk.gtk.version", "3");

        Dirs dirs = Dirs.detect();
        dirs.ensure();
        Log.init(dirs.logs());
        Log.info(AppInfo.NAME + " " + AppInfo.VERSION + " on " + System.getProperty("os.name") + " " + OS.ARCH
                + ", Java " + System.getProperty("java.version") + ", data: " + dirs.root);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.error("Uncaught exception in " + t.getName(), e));

        Settings settings = Settings.load(dirs.settingsFile());
        I18n.set(I18n.Lang.of(settings.language));
        App app = App.init(dirs, settings);
        Images.init(dirs.cache());
        app.instances.reload();
        // warm up the Java list in the background so settings open instantly
        Tasks.background(() -> app.java.detect(false));

        SwingUtilities.invokeLater(() -> {
            Theme.install();
            MainWindow w = new MainWindow();
            w.setVisible(true);
        });
    }
}
