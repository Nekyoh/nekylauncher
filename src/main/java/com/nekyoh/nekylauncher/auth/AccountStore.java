package com.nekyoh.nekylauncher.auth;

import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** accounts.json; tokens are stored owner-readable only on POSIX systems. */
public final class AccountStore {
    private final Path file;
    private final List<Account> accounts = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String activeId = "";

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    public void addListener(Runnable r) { listeners.add(r); }

    private void fire() { listeners.forEach(Runnable::run); }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JObject o = JObject.parse(FileUtil.read(file));
            for (JObject a : o.arrOrEmpty("accounts").objects()) {
                Account acc = Account.fromJson(a);
                if (acc != null) accounts.add(acc); // non-Microsoft entries from older builds are dropped
            }
            activeId = o.str("active", "");
        } catch (Exception e) {
            Log.error("Could not read accounts", e);
        }
    }

    public synchronized void save() {
        JArray arr = new JArray();
        for (Account a : accounts) arr.add(a.toJson());
        try {
            FileUtil.write(file, new JObject().put("active", activeId).put("accounts", arr).toJson(true));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
        } catch (Exception e) {
            Log.error("Could not save accounts", e);
        }
        fire();
    }

    public List<Account> all() { return new ArrayList<>(accounts); }

    public Account active() {
        for (Account a : accounts) if (a.id.equals(activeId)) return a;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public void setActive(Account a) {
        activeId = a.id;
        save();
    }

    /** Adds or replaces (same type + uuid) and makes it active. */
    public void add(Account a) {
        accounts.removeIf(x -> x.type == a.type && x.uuid.equalsIgnoreCase(a.uuid));
        accounts.add(a);
        activeId = a.id;
        save();
    }

    public void remove(Account a) {
        accounts.removeIf(x -> x.id.equals(a.id));
        if (a.id.equals(activeId)) activeId = accounts.isEmpty() ? "" : accounts.get(0).id;
        save();
    }

    public void update(Account a) { save(); }
}
