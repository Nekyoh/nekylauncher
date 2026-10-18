package com.nekyoh.nekylauncher.instance;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public final class InstanceManager {
    private final Dirs dirs;
    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public InstanceManager(Dirs dirs) { this.dirs = dirs; }

    public void addListener(Runnable r) { listeners.add(r); }

    public void fireChanged() { listeners.forEach(Runnable::run); }

    public synchronized void reload() {
        List<Instance> list = new ArrayList<>();
        try (Stream<Path> s = Files.list(dirs.instances())) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                if (!Files.exists(d.resolve(Instance.FILE))) continue;
                try {
                    list.add(Instance.load(d));
                } catch (Exception e) {
                    Log.error("Skipping broken instance " + d, e);
                }
            }
        } catch (IOException e) {
            Log.error("Could not list instances", e);
        }
        instances.clear();
        instances.addAll(list);
        fireChanged();
    }

    /** Most recently played first, then newest. */
    public List<Instance> all() {
        List<Instance> l = new ArrayList<>(instances);
        l.sort(Comparator.comparingLong((Instance i) -> Math.max(i.lastPlayed, i.created)).reversed());
        return l;
    }

    public Optional<Instance> byId(String id) {
        return instances.stream().filter(i -> i.id().equals(id)).findFirst();
    }

    public Path newInstanceDir(String name) {
        return FileUtil.uniquePath(dirs.instances(), FileUtil.safeName(name));
    }

    public synchronized Instance create(String name, String mcVersion, LoaderType loader, String loaderVersion) throws IOException {
        Path dir = newInstanceDir(name);
        Files.createDirectories(dir);
        Instance i = new Instance(dir);
        i.name = name.isBlank() ? mcVersion : name.trim();
        i.mcVersion = mcVersion;
        i.loader = loader;
        i.loaderVersion = loaderVersion == null ? "" : loaderVersion;
        i.versionId = loader == LoaderType.VANILLA ? mcVersion : "";
        for (Path p : new Path[]{i.modsDir(), i.resourcePacksDir(), i.shaderPacksDir(), i.savesDir()}) {
            if (loader != LoaderType.VANILLA || p != i.modsDir()) Files.createDirectories(p);
        }
        i.save();
        instances.add(i);
        fireChanged();
        return i;
    }

    /** Registers an instance whose folder was populated externally (import). */
    public synchronized void register(Instance i) throws IOException {
        i.save();
        instances.removeIf(x -> x.dir.equals(i.dir));
        instances.add(i);
        fireChanged();
    }

    public synchronized void delete(Instance i) throws IOException {
        FileUtil.deleteRecursive(i.dir);
        instances.remove(i);
        fireChanged();
    }

    public synchronized Instance duplicate(Instance src, String newName) throws IOException {
        Path dir = newInstanceDir(newName);
        FileUtil.copyRecursive(src.dir, dir);
        Instance copy = Instance.load(dir);
        copy.name = newName;
        copy.created = System.currentTimeMillis();
        copy.lastPlayed = 0;
        copy.playTimeSeconds = 0;
        copy.save();
        instances.add(copy);
        fireChanged();
        return copy;
    }

    public void save(Instance i) {
        try {
            i.save();
        } catch (IOException e) {
            Log.error("Could not save instance " + i.id(), e);
        }
        fireChanged();
    }
}
