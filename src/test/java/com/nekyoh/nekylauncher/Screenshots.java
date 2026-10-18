package com.nekyoh.nekylauncher;

import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.dialogs.NewInstanceDialog;
import com.nekyoh.nekylauncher.ui.kit.Images;
import com.nekyoh.nekylauncher.util.FileUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Dev tool: renders every page with demo data to PNGs (run under a display or Xvfb). */
public final class Screenshots {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "screenshots");
        String lang = args.length > 1 ? args[1] : "pt";
        Files.createDirectories(out);
        Path root = Files.createTempDirectory("neky-shots");
        Dirs dirs = new Dirs(root);
        dirs.ensure();
        FileUtil.write(dirs.cache().resolve("version_manifest_v2.json"), """
                {"latest":{"release":"1.21.1","snapshot":"24w45a"},"versions":[
                {"id":"24w45a","type":"snapshot","url":"u","sha1":"s","releaseTime":"2024-11-06T12:00:00+00:00"},
                {"id":"1.21.1","type":"release","url":"u","sha1":"s","releaseTime":"2024-08-08T12:00:00+00:00"},
                {"id":"1.21","type":"release","url":"u","sha1":"s","releaseTime":"2024-06-13T12:00:00+00:00"},
                {"id":"1.20.6","type":"release","url":"u","sha1":"s","releaseTime":"2024-04-29T12:00:00+00:00"},
                {"id":"1.20.4","type":"release","url":"u","sha1":"s","releaseTime":"2023-12-07T12:00:00+00:00"},
                {"id":"1.20.1","type":"release","url":"u","sha1":"s","releaseTime":"2023-06-12T12:00:00+00:00"},
                {"id":"1.19.2","type":"release","url":"u","sha1":"s","releaseTime":"2022-08-05T12:00:00+00:00"},
                {"id":"1.16.5","type":"release","url":"u","sha1":"s","releaseTime":"2021-01-15T12:00:00+00:00"},
                {"id":"1.12.2","type":"release","url":"u","sha1":"s","releaseTime":"2017-09-18T12:00:00+00:00"},
                {"id":"1.8.9","type":"release","url":"u","sha1":"s","releaseTime":"2015-12-09T12:00:00+00:00"},
                {"id":"b1.7.3","type":"old_beta","url":"u","sha1":"s","releaseTime":"2011-07-08T12:00:00+00:00"}]}""");
        Settings s = Settings.load(dirs.settingsFile());
        s.language = lang;
        I18n.set(I18n.Lang.of(lang));
        App app = App.init(dirs, s);
        Images.init(dirs.cache());
        app.accounts.add(SelfTest.account("Nekyoh"));
        app.accounts.add(SelfTest.account("Steve"));
        app.accounts.setActive(app.accounts.all().get(0));
        Instance a = app.instances.create("Survival com amigos", "1.21.1", LoaderType.FABRIC, "0.16.9");
        a.lastPlayed = System.currentTimeMillis() - 3 * 3600_000L;
        a.playTimeSeconds = 26 * 3600 + 1200;
        a.save();
        Instance b = app.instances.create("All the Mods", "1.20.1", LoaderType.FORGE, "47.3.0");
        b.lastPlayed = System.currentTimeMillis() - 2 * 86400_000L;
        b.save();
        app.instances.create("Vanilla 1.21.1", "1.21.1", LoaderType.VANILLA, "");
        app.instances.create("NeoForge Tech", "1.21.1", LoaderType.NEOFORGE, "21.1.77");
        app.instances.create("Quilt Light", "1.20.4", LoaderType.QUILT, "0.26.4");
        SelfTest.writeZip(a.modsDir().resolve("sodium-fabric-0.5.11.jar"), Map.of("fabric.mod.json",
                "{\"id\":\"sodium\",\"version\":\"0.5.11+mc1.21\",\"name\":\"Sodium\",\"description\":\"The fastest and most compatible rendering optimization mod for Minecraft.\",\"authors\":[\"JellySquid\"]}"));
        SelfTest.writeZip(a.modsDir().resolve("iris-1.8.0.jar"), Map.of("fabric.mod.json",
                "{\"id\":\"iris\",\"version\":\"1.8.0\",\"name\":\"Iris\",\"description\":\"A modern shaders mod for Minecraft intended to be compatible with existing OptiFine shader packs.\",\"authors\":[\"coderbot\"]}"));
        SelfTest.writeZip(a.modsDir().resolve("modmenu-11.0.2.jar.disabled"), Map.of("fabric.mod.json",
                "{\"id\":\"modmenu\",\"version\":\"11.0.2\",\"name\":\"Mod Menu\",\"description\":\"Adds a mod menu to view the list of mods you have installed.\"}"));
        SelfTest.writeZip(a.modsDir().resolve("fabric-api-0.102.0.jar"), Map.of("fabric.mod.json",
                "{\"id\":\"fabric-api\",\"version\":\"0.102.0+1.21.1\",\"name\":\"Fabric API\",\"description\":\"Core API module providing key hooks and intercompatibility features.\"}"));
        app.instances.reload();

        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MainWindow w = new MainWindow();
            w.setSize(1280, 800);
            w.setVisible(true);
            frames[0] = w;
        });
        MainWindow w = frames[0];
        Thread.sleep(1500);
        shot(w, out.resolve(lang + "-1-home.png"));
        ui(() -> w.showPage(MainWindow.Page.INSTANCES));
        shot(w, out.resolve(lang + "-2-instances.png"));
        Instance first = app.instances.all().stream().filter(i -> i.name.startsWith("Survival")).findFirst().orElseThrow();
        ui(() -> w.openInstance(first));
        Thread.sleep(800);
        shot(w, out.resolve(lang + "-3-instance-mods.png"));
        ui(() -> clickTab(w, 4));
        Thread.sleep(600);
        shot(w, out.resolve(lang + "-4-instance-settings.png"));
        ui(() -> w.showPage(MainWindow.Page.ACCOUNTS));
        shot(w, out.resolve(lang + "-5-accounts.png"));
        ui(() -> w.showPage(MainWindow.Page.SETTINGS));
        Thread.sleep(800);
        shot(w, out.resolve(lang + "-6-settings.png"));
        ui(() -> {
            JScrollPane sp = find(w.getContentPane(), JScrollPane.class);
            if (sp != null) sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
        });
        Thread.sleep(400);
        shot(w, out.resolve(lang + "-6b-storage.png"));
        ui(() -> com.nekyoh.nekylauncher.ui.dialogs.DataFolderDialog.confirm(w, root.getParent().resolve("D-Jogos").resolve("NekyLauncher")));
        Thread.sleep(500);
        shot(w, out.resolve(lang + "-6c-datadir.png"));
        ui(() -> closeModals(w));
        ui(() -> w.showPage(MainWindow.Page.HOME));
        ui(() -> NewInstanceDialog.open(w));
        Thread.sleep(1500);
        shot(w, out.resolve(lang + "-7-new-instance.png"));
        ui(() -> closeModals(w));
        ui(() -> { App.get().settings.msClientId = "demo"; com.nekyoh.nekylauncher.ui.dialogs.AccountDialogs.microsoft(w); });
        Thread.sleep(800);
        shot(w, out.resolve(lang + "-8b-microsoft.png"));
        ui(() -> closeModals(w));
        ui(() -> com.nekyoh.nekylauncher.ui.dialogs.ExportDialog.open(first));
        Thread.sleep(600);
        shot(w, out.resolve(lang + "-10-export.png"));
        ui(() -> closeModals(w));
        ui(() -> w.openInstance(first));
        ui(() -> clickTab(w, 2));
        Thread.sleep(600);
        shot(w, out.resolve(lang + "-11-shaders.png"));
        ui(() -> com.nekyoh.nekylauncher.ui.dialogs.ModrinthDialog.open(w, first, com.nekyoh.nekylauncher.modrinth.Modrinth.ProjectType.MOD, null));
        Thread.sleep(2500);
        shot(w, out.resolve(lang + "-12-modrinth.png"));
        ui(() -> closeModals(w));
        // console window fed by a short real process
        Process pr = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").start();
        com.nekyoh.nekylauncher.game.GameProcess gp = new com.nekyoh.nekylauncher.game.GameProcess(first, pr,
                java.util.List.of("NekyLauncher 1.0.0 — Survival com amigos (fabric-loader-0.16.9-1.21.1)", "Java 21 @ /usr/bin/java"));
        com.nekyoh.nekylauncher.ui.ConsoleWindow[] cw = new com.nekyoh.nekylauncher.ui.ConsoleWindow[1];
        ui(() -> {
            cw[0] = new com.nekyoh.nekylauncher.ui.ConsoleWindow(first, gp, false);
            cw[0].setVisible(true);
        });
        Thread.sleep(2000);
        shot(cw[0], out.resolve(lang + "-13-console.png"));
        System.exit(0);
    }

    private static final MainWindow[] frames = new MainWindow[1];

    private static void closeModals(MainWindow w) {
        JLayeredPane lp = w.getRootPane().getLayeredPane();
        for (Component c : lp.getComponentsInLayer(JLayeredPane.MODAL_LAYER)) lp.remove(c);
        lp.repaint();
        try {
            java.lang.reflect.Field f = com.nekyoh.nekylauncher.ui.kit.Modal.class.getDeclaredField("STACK");
            f.setAccessible(true);
            ((java.util.Deque<?>) f.get(null)).clear();
        } catch (Exception ignored) { }
    }

    private static void clickTab(MainWindow w, int index) {
        com.nekyoh.nekylauncher.ui.kit.Tabs t = find(w.getContentPane(), com.nekyoh.nekylauncher.ui.kit.Tabs.class);
        if (t != null) t.select(index);
    }

    static <T> T find(Container c, Class<T> type) {
        for (Component ch : c.getComponents()) {
            if (type.isInstance(ch) && ch.isShowing()) return type.cast(ch);
            if (ch instanceof Container cc) {
                T r = find(cc, type);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void ui(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        Thread.sleep(400);
    }

    private static void shot(JFrame f, Path file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            JRootPane rp = f.getRootPane();
            BufferedImage bi = new BufferedImage(rp.getWidth(), rp.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            rp.paint(g);
            g.dispose();
            img[0] = bi;
        });
        ImageIO.write(img[0], "png", file.toFile());
        System.out.println("wrote " + file);
    }
}
