package com.nekyoh.nekylauncher.launch;

import com.nekyoh.nekylauncher.AppInfo;
import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.auth.AccountStore;
import com.nekyoh.nekylauncher.auth.MicrosoftAuth;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.game.*;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.instance.InstanceManager;
import com.nekyoh.nekylauncher.java.JavaInstall;
import com.nekyoh.nekylauncher.java.JavaManager;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.loader.Loaders;
import com.nekyoh.nekylauncher.util.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Prepares (downloads/installs) and starts an instance. */
public final class LaunchService {
    private final Dirs dirs;
    private final Settings settings;
    private final AccountStore accounts;
    private final InstanceManager instances;
    private final JavaManager java;
    private final Map<String, GameProcess> running = new ConcurrentHashMap<>();
    private final Set<String> preparing = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public LaunchService(Dirs dirs, Settings settings, AccountStore accounts, InstanceManager instances, JavaManager java) {
        this.dirs = dirs;
        this.settings = settings;
        this.accounts = accounts;
        this.instances = instances;
        this.java = java;
    }

    public void addListener(Runnable r) { listeners.add(r); }

    private void fire() { listeners.forEach(Runnable::run); }

    public GameProcess running(Instance i) { return running.get(i.id()); }

    public boolean isBusy(Instance i) { return running.containsKey(i.id()) || preparing.contains(i.id()); }

    public Collection<GameProcess> allRunning() { return running.values(); }

    /** Brings the account's tokens up to date (no-op for offline). */
    public void refreshAccount(Account acc, Progress p) throws IOException {
        if (!acc.needsRefresh()) return;
        p.stage(I18n.tr("progress.refreshingAccount"));
        new MicrosoftAuth(settings.effectiveMsClientId()).refresh(acc);
        accounts.update(acc);
    }

    public GameProcess launch(Instance inst, Account acc, Progress p, CancelToken cancel) throws IOException {
        if (acc == null) throw new IOException(I18n.tr("error.noAccount"));
        if (!preparing.add(inst.id())) throw new IOException(I18n.tr("error.alreadyRunning"));
        fire();
        try {
            return doLaunch(inst, acc, p, cancel);
        } finally {
            preparing.remove(inst.id());
            fire();
        }
    }

    private GameProcess doLaunch(Instance inst, Account acc, Progress p, CancelToken cancel) throws IOException {
        if (running.containsKey(inst.id())) throw new IOException(I18n.tr("error.alreadyRunning"));
        try {
            refreshAccount(acc, p);
        } catch (IOException e) {
            if (acc.type == Account.Type.MICROSOFT && acc.accessToken != null && !acc.accessToken.isBlank()
                    && System.currentTimeMillis() < acc.expiresAt) {
                Log.warn("Token refresh failed, using current token: " + e.getMessage());
            } else {
                throw e;
            }
        }
        cancel.check();

        p.stage(I18n.tr("progress.fetchingVersions"));
        p.fraction(-1);
        VersionManifest manifest = null;
        try {
            manifest = VersionManifest.get(dirs, false);
        } catch (IOException e) {
            Log.warn("Version manifest unavailable (offline?): " + e.getMessage());
        }
        GameInstaller installer = new GameInstaller(dirs, settings.concurrentDownloads);
        installer.ensureVanillaJson(inst.mcVersion, manifest);
        VersionProfile vanilla = VersionProfile.resolve(dirs, inst.mcVersion);

        JavaInstall jv = java.resolveFor(vanilla, inst.javaPath, p, cancel);
        if (jv.major() > 0 && jv.major() < vanilla.requiredJava()) {
            throw new IOException(I18n.tr("error.javaTooOld", jv.major(), vanilla.requiredJava()));
        }
        cancel.check();

        LaunchBuilder.Options o = new LaunchBuilder.Options();
        o.account = acc;
        o.gameDir = inst.gameDir();
        Files.createDirectories(o.gameDir);
        int w = inst.overrideResolution ? inst.width : settings.gameWidth;
        int h = inst.overrideResolution ? inst.height : settings.gameHeight;
        o.width = Math.max(0, w);
        o.height = Math.max(0, h);
        o.fullscreen = inst.overrideResolution && inst.fullscreen;
        o.joinServer = inst.joinServer == null ? "" : inst.joinServer;
        o.profile = vanilla;
        Map<String, Boolean> features = LaunchBuilder.features(o);

        // vanilla files first: Forge processors need the client jar
        installer.install(vanilla, o.gameDir, features, p, cancel);

        String versionId = inst.expectedVersionId();
        if (inst.loader != LoaderType.VANILLA && (versionId == null || !Files.exists(dirs.versionJson(versionId)))) {
            String lv = inst.loaderVersion;
            if (lv == null || lv.isBlank()) {
                lv = Loaders.pickDefault(Loaders.versions(inst.loader, inst.mcVersion));
                if (lv == null) throw new IOException(I18n.tr("error.noLoaderVersion", inst.loader.display, inst.mcVersion));
                inst.loaderVersion = lv;
            }
            versionId = Loaders.install(inst.loader, inst.mcVersion, lv, dirs, jv, settings.concurrentDownloads, p, cancel);
            inst.versionId = versionId;
            instances.save(inst);
        }
        VersionProfile profile = VersionProfile.resolve(dirs, versionId);
        o.profile = profile;
        features = LaunchBuilder.features(o);
        GameInstaller.Result assets = installer.install(profile, o.gameDir, features, p, cancel);
        cancel.check();

        p.stage(I18n.tr("progress.preparingLaunch"));
        p.fraction(-1);
        // like the official launcher, the launched version gets its own copy of the client jar
        Path clientJar = dirs.versionJar(profile.id);
        Path baseJar = dirs.versionJar(profile.clientJarId);
        if (!clientJar.equals(baseJar)) {
            if (!Files.exists(clientJar) || Files.size(clientJar) != Files.size(baseJar)) {
                Files.createDirectories(clientJar.getParent());
                Files.copy(baseJar, clientJar, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        installer.extractNatives(profile, features, inst.nativesDir());
        writeDefaultOptions(inst);

        o.javaExe = jv.executable();
        o.librariesDir = dirs.libraries();
        o.clientJar = clientJar;
        o.nativesDir = inst.nativesDir();
        o.assetsRoot = assets.assetsRoot();
        o.gameAssets = assets.gameAssets();
        o.assetIndexName = assets.assetIndexName();
        if (profile.loggingClient != null && profile.loggingClient.obj("file") != null) {
            Path cfg = dirs.assets().resolve("log_configs").resolve(profile.loggingClient.obj("file").str("id"));
            if (Files.exists(cfg)) o.loggingConfig = cfg;
        }
        o.minMemoryMb = inst.overrideMemory ? inst.minMemoryMb : settings.minMemoryMb;
        o.maxMemoryMb = inst.overrideMemory ? inst.maxMemoryMb : settings.maxMemoryMb;
        o.extraJvmArgs = inst.jvmArgs != null && !inst.jvmArgs.isBlank() ? inst.jvmArgs : settings.jvmArgs;

        List<String> cmd = LaunchBuilder.build(o);
        List<String> preamble = new ArrayList<>();
        preamble.add(AppInfo.NAME + " " + AppInfo.VERSION + " — " + inst.name + " (" + profile.id + ")");
        preamble.add("Java " + jv.version() + " @ " + jv.executable());
        preamble.add("Account: " + acc.username + " [" + acc.type + "]");
        preamble.add(String.join(" ", LaunchBuilder.redact(cmd, acc.accessToken)));
        Log.info("Launching: " + String.join(" ", LaunchBuilder.redact(cmd, acc.accessToken)));

        cancel.check();
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(o.gameDir.toFile());
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        Process proc = pb.start();
        GameProcess gp = new GameProcess(inst, proc, preamble);
        running.put(inst.id(), gp);
        inst.lastPlayed = System.currentTimeMillis();
        instances.save(inst);
        gp.onExit(code -> {
            running.remove(inst.id());
            inst.playTimeSeconds += Math.max(0, (System.currentTimeMillis() - gp.startedAt) / 1000);
            instances.save(inst);
            fire();
        });
        fire();
        p.stage(I18n.tr("progress.started"));
        p.fraction(1);
        return gp;
    }

    /** On first launch, start the game in the launcher's language. */
    private void writeDefaultOptions(Instance inst) {
        Path options = inst.gameDir().resolve("options.txt");
        if (Files.exists(options)) return;
        String code = switch (I18n.current()) {
            case PT -> "pt_br";
            case ES -> "es_es";
            default -> null;
        };
        if (code == null) return;
        boolean lowercase = true;
        String[] v = inst.mcVersion.split("\\.");
        try {
            if (v.length >= 2 && v[0].equals("1") && Integer.parseInt(v[1]) < 11) lowercase = false;
        } catch (NumberFormatException ignored) { }
        if (!lowercase) code = code.substring(0, 3) + code.substring(3).toUpperCase(Locale.ROOT);
        try {
            Files.writeString(options, "lang:" + code + System.lineSeparator());
        } catch (IOException e) {
            Log.warn("Could not write options.txt: " + e.getMessage());
        }
    }
}
