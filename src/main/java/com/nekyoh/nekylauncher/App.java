package com.nekyoh.nekylauncher;

import com.nekyoh.nekylauncher.auth.AccountStore;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.core.Settings;
import com.nekyoh.nekylauncher.instance.InstanceManager;
import com.nekyoh.nekylauncher.java.JavaManager;
import com.nekyoh.nekylauncher.launch.LaunchService;

/** Application-wide services. */
public final class App {
    private static App instance;

    public final Dirs dirs;
    public final Settings settings;
    public final AccountStore accounts;
    public final InstanceManager instances;
    public final JavaManager java;
    public final LaunchService launcher;

    private App(Dirs dirs, Settings settings) {
        this.dirs = dirs;
        this.settings = settings;
        this.accounts = new AccountStore(dirs.accountsFile());
        this.instances = new InstanceManager(dirs);
        this.java = new JavaManager(dirs, settings);
        this.launcher = new LaunchService(dirs, settings, accounts, instances, java);
    }

    public static App init(Dirs dirs, Settings settings) {
        instance = new App(dirs, settings);
        return instance;
    }

    public static App get() { return instance; }
}
