package com.nekyoh.nekylauncher.loader;

import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.java.JavaInstall;
import com.nekyoh.nekylauncher.util.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lists and installs mod loaders (Fabric, Quilt, Forge, NeoForge). */
public final class Loaders {
    private Loaders() {}

    public record LoaderVersion(String id, boolean stable, boolean recommended) {
        @Override
        public String toString() { return id; }
    }

    static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    static final String QUILT_META = "https://meta.quiltmc.org/v3";
    static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    static final String NEOFORGE_LEGACY_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/forge/";

    private static final Map<String, List<String>> XML_CACHE = new ConcurrentHashMap<>();

    /** Versions of {@code type} available for Minecraft {@code mc}, newest first. */
    public static List<LoaderVersion> versions(LoaderType type, String mc) throws IOException {
        return switch (type) {
            case VANILLA -> List.of();
            case FABRIC -> fabricLike(FABRIC_META + "/versions/loader/" + Http.enc(mc), true);
            case QUILT -> fabricLike(QUILT_META + "/versions/loader/" + Http.enc(mc), false);
            case FORGE -> forge(mc);
            case NEOFORGE -> neoforge(mc);
        };
    }

    private static List<LoaderVersion> fabricLike(String url, boolean hasStableFlag) throws IOException {
        JArray arr;
        try {
            arr = Http.getJsonArray(url);
        } catch (Http.HttpException e) {
            if (e.status == 400 || e.status == 404) return List.of();
            throw e;
        }
        List<LoaderVersion> out = new ArrayList<>();
        boolean recommendedSet = false;
        for (JObject o : arr.objects()) {
            JObject loader = o.objOrEmpty("loader");
            String v = loader.str("version");
            if (v == null) continue;
            boolean stable = hasStableFlag ? loader.bool("stable", true) : !(v.contains("beta") || v.contains("pre") || v.contains("rc"));
            boolean rec = stable && !recommendedSet;
            if (rec) recommendedSet = true;
            out.add(new LoaderVersion(v, stable, rec));
        }
        return out;
    }

    private static List<String> mavenVersions(String metadataUrl) throws IOException {
        List<String> cached = XML_CACHE.get(metadataUrl);
        if (cached != null) return cached;
        byte[] xml = Http.getString(metadataUrl).getBytes(StandardCharsets.UTF_8);
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList nodes = d.getElementsByTagName("version");
            List<String> list = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) list.add(nodes.item(i).getTextContent().trim());
            XML_CACHE.put(metadataUrl, list);
            return list;
        } catch (Exception e) {
            throw new IOException("Invalid maven metadata from " + metadataUrl, e);
        }
    }

    private static List<LoaderVersion> forge(String mc) throws IOException {
        List<String> all = mavenVersions(FORGE_MAVEN + "maven-metadata.xml");
        String recommended = null, latest = null;
        try {
            JObject promos = Http.getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json").objOrEmpty("promos");
            recommended = promos.str(mc + "-recommended");
            latest = promos.str(mc + "-latest");
        } catch (IOException e) {
            Log.warn("Forge promotions unavailable: " + e.getMessage());
        }
        List<LoaderVersion> out = new ArrayList<>();
        String prefix = mc + "-";
        for (String v : all) {
            if (!v.startsWith(prefix)) continue;
            String loader = v.substring(prefix.length());
            boolean rec = loader.equals(recommended) || (recommended == null && loader.equals(latest));
            out.add(new LoaderVersion(loader, true, rec));
        }
        sortDesc(out);
        return out;
    }

    private static List<LoaderVersion> neoforge(String mc) throws IOException {
        List<LoaderVersion> out = new ArrayList<>();
        if (mc.equals("1.20.1")) {
            for (String v : mavenVersions(NEOFORGE_LEGACY_MAVEN + "maven-metadata.xml")) {
                if (v.startsWith("1.20.1-")) out.add(new LoaderVersion(v.substring(7), true, false));
            }
        } else {
            String prefix = neoforgePrefix(mc);
            if (prefix == null) return out;
            for (String v : mavenVersions(NEOFORGE_MAVEN + "maven-metadata.xml")) {
                if (v.startsWith(prefix)) out.add(new LoaderVersion(v, !v.contains("beta") && !v.contains("alpha"), false));
            }
        }
        sortDesc(out);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).stable()) {
                LoaderVersion lv = out.get(i);
                out.set(i, new LoaderVersion(lv.id(), true, true));
                break;
            }
        }
        return out;
    }

    /**
     * NeoForge numbering: MC 1.X.Y -> "X.Y.", MC 1.X -> "X.0."; year-based MC A.B[.C] -> "A.B.C." (C defaults to 0).
     */
    public static String neoforgePrefix(String mc) {
        String[] p = mc.split("\\.");
        try {
            if (p.length >= 2 && p[0].equals("1")) {
                return Integer.parseInt(p[1]) + "." + (p.length > 2 ? Integer.parseInt(p[2]) : 0) + ".";
            }
            if (p.length >= 2 && Integer.parseInt(p[0]) >= 25) {
                return Integer.parseInt(p[0]) + "." + Integer.parseInt(p[1]) + "." + (p.length > 2 ? Integer.parseInt(p[2]) : 0) + ".";
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    static void sortDesc(List<LoaderVersion> list) {
        list.sort((a, b) -> compareVersions(b.id(), a.id()));
    }

    /** Numeric-aware version comparison ("47.2.10" > "47.2.9"). */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String s = i < x.length ? x[i] : "", t = i < y.length ? y[i] : "";
            boolean ns = s.matches("\\d+"), nt = t.matches("\\d+");
            // "1.0" vs "1.0.1" -> shorter is older; "1.0" vs "1.0-beta" -> qualifier is older
            if (s.isEmpty()) return nt ? -1 : 1;
            if (t.isEmpty()) return ns ? 1 : -1;
            int c;
            if (ns && nt) c = Long.compare(Long.parseLong(s), Long.parseLong(t));
            else if (ns != nt) c = ns ? 1 : -1; // numbers beat qualifiers ("1.0" > "1.0-beta")
            else c = s.compareTo(t);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Installs the loader profile and returns the version id to launch. */
    public static String install(LoaderType type, String mc, String loaderVersion, Dirs dirs, JavaInstall java,
                                 int concurrency, Progress progress, CancelToken cancel) throws IOException {
        progress.stage(I18n.tr("progress.installingLoader", type.display, loaderVersion));
        progress.fraction(-1);
        return switch (type) {
            case VANILLA -> mc;
            case FABRIC -> installProfile(dirs, FABRIC_META + "/versions/loader/" + Http.enc(mc) + "/" + Http.enc(loaderVersion) + "/profile/json");
            case QUILT -> installProfile(dirs, QUILT_META + "/versions/loader/" + Http.enc(mc) + "/" + Http.enc(loaderVersion) + "/profile/json");
            case FORGE, NEOFORGE -> new ForgeInstaller(dirs, java, concurrency, progress, cancel)
                    .install(installerUrl(type, mc, loaderVersion), mc);
        };
    }

    static String installerUrl(LoaderType type, String mc, String loaderVersion) {
        if (type == LoaderType.FORGE) {
            String full = mc + "-" + loaderVersion;
            return FORGE_MAVEN + full + "/forge-" + full + "-installer.jar";
        }
        if (mc.equals("1.20.1")) {
            String full = "1.20.1-" + loaderVersion;
            return NEOFORGE_LEGACY_MAVEN + full + "/forge-" + full + "-installer.jar";
        }
        return NEOFORGE_MAVEN + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
    }

    private static String installProfile(Dirs dirs, String url) throws IOException {
        JObject profile = Http.getJson(url);
        String id = profile.str("id");
        if (id == null) throw new IOException("Loader profile without id from " + url);
        FileUtil.write(dirs.versionJson(id), profile.toJson(true));
        return id;
    }

    /** Recommended (or newest stable) loader version, or null. */
    public static String pickDefault(List<LoaderVersion> list) {
        for (LoaderVersion v : list) if (v.recommended()) return v.id();
        for (LoaderVersion v : list) if (v.stable()) return v.id();
        return list.isEmpty() ? null : list.get(0).id();
    }

    public static List<LoaderVersion> safe(List<LoaderVersion> l) { return l == null ? Collections.emptyList() : l; }
}
