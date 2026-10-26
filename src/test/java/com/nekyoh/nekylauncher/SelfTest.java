package com.nekyoh.nekylauncher;

import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.core.Dirs;
import com.nekyoh.nekylauncher.game.*;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.*;
import com.nekyoh.nekylauncher.loader.LoaderType;
import com.nekyoh.nekylauncher.loader.Loaders;
import com.nekyoh.nekylauncher.util.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free self tests: ./gradlew selfTest */
public final class SelfTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        run("json round trip", SelfTest::json);
        run("rules", SelfTest::rules);
        run("maven paths", SelfTest::maven);
        run("accounts are microsoft only", SelfTest::accounts);
        run("profile merge (fabric over vanilla)", SelfTest::merge);
        run("modern launch command", SelfTest::modernCommand);
        run("legacy launch command", SelfTest::legacyCommand);
        run("arg splitting", SelfTest::splitArgs);
        run("forge token replacement", SelfTest::forgeTokens);
        run("loader version helpers", SelfTest::loaderHelpers);
        run("log4j xml events", SelfTest::log4j);
        run("mods.toml + pack.mcmeta", SelfTest::metadata);
        run("mod jar metadata", SelfTest::modJar);
        run("zip slip protection", SelfTest::zipSlip);
        run("export/import round trip", SelfTest::exportImport);
        run("mrpack import (overrides)", SelfTest::mrpack);
        run("prism import", SelfTest::prism);
        run("translations complete", SelfTest::translations);
        run("legacy natives classifier", SelfTest::natives);
        run("end-to-end launch of a fake game", SelfTest::endToEnd);
        run("data folder change (move + checks)", SelfTest::dataFolder);
        run("microsoft browser login loopback", SelfTest::browserLogin);
        run("loader icon urls", SelfTest::loaderIcons);
        run("skin render (classic, slim, legacy, cape)", SelfTest::skinRender);
        run("skin presets + validation", SelfTest::skinLibrary);
        run("skin api (multipart upload, capes)", SelfTest::skinApi);
        run("curseforge fingerprint (murmur2)", SelfTest::cfFingerprint);
        run("curseforge loader matching", SelfTest::cfLoaders);
        run("curseforge search + install with dependencies", SelfTest::cfInstall);
        run("curseforge modpack import via api", SelfTest::cfModpack);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    interface T { void run() throws Exception; }

    static void run(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object a, Object b) {
        if (!Objects.equals(a, b)) throw new AssertionError("expected <" + b + "> but was <" + a + ">");
    }

    // ------------------------------------------------------------------

    static void json() {
        String src = "{\"a\":1,\"b\":[true,null,2.5,\"x\\u00e7\\n\"],\"c\":{\"d\":-12345678901234}}";
        JObject o = JObject.parse(src);
        eq(o.integer("a", 0), 1);
        eq(o.arr("b").str(3), "xç\n");
        eq(o.obj("c").lng("d", 0), -12345678901234L);
        eq(JObject.parse(o.toJson(true)).toJson(false), o.toJson(false));
        boolean threw = false;
        try { JObject.parse("{\"a\":}"); } catch (Json.JsonException e) { threw = true; }
        check(threw, "invalid json must throw");
    }

    static void rules() {
        JArray winOnly = JArray.parse("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}]");
        Rules.Env win = new Rules.Env("windows", "x86_64", "10.0");
        Rules.Env linux = new Rules.Env("linux", "x86_64", "6.1");
        check(Rules.allowed(winOnly, Map.of(), win), "windows rule on windows");
        check(!Rules.allowed(winOnly, Map.of(), linux), "windows rule on linux");
        JArray notMac = JArray.parse("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]");
        check(Rules.allowed(notMac, Map.of(), linux), "allow-all except osx");
        JArray feature = JArray.parse("[{\"action\":\"allow\",\"features\":{\"has_custom_resolution\":true}}]");
        check(Rules.allowed(feature, Map.of("has_custom_resolution", true), linux), "feature on");
        check(!Rules.allowed(feature, Map.of(), linux), "feature off");
        JArray x86 = JArray.parse("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]");
        check(!Rules.allowed(x86, Map.of(), win), "x86 rule on 64-bit");
    }

    static void maven() {
        eq(Maven.parse("net.fabricmc:fabric-loader:0.15.11").path(), "net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar");
        eq(Maven.parse("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt").path(),
                "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412-mappings.txt");
        eq(Maven.parse("org.lwjgl:lwjgl:3.3.1:natives-windows").key(), "org.lwjgl:lwjgl:natives-windows");
    }

    /** A signed-in Microsoft account with a still-valid token (no network needed). */
    static Account account(String name) {
        Account a = new Account();
        a.id = UUID.randomUUID().toString();
        a.username = name;
        a.uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        a.accessToken = "test-token-" + name;
        a.refreshToken = "refresh";
        a.expiresAt = System.currentTimeMillis() + 3600_000L;
        return a;
    }

    static void accounts() {
        Account a = account("Steve");
        Account back = Account.fromJson(a.toJson());
        eq(back.username, "Steve");
        eq(back.userType(), "msa");
        check(Account.fromJson(JObject.parse("{\"type\":\"OFFLINE\",\"username\":\"x\",\"uuid\":\"00\"}")) == null, "offline entries dropped");
        check(Account.fromJson(JObject.parse("{\"type\":\"ELYBY\",\"username\":\"x\",\"uuid\":\"00\"}")) == null, "ely.by entries dropped");
    }

    static final String VANILLA = """
            {"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main",
             "assetIndex":{"id":"5","sha1":"x","size":1,"totalSize":1,"url":"https://x/5.json"},"assets":"5",
             "javaVersion":{"component":"java-runtime-gamma","majorVersion":17},
             "downloads":{"client":{"sha1":"abc","size":10,"url":"https://x/client.jar"}},
             "logging":{"client":{"argument":"-Dlog4j.configurationFile=${path}","file":{"id":"client-1.12.xml","sha1":"s","size":1,"url":"https://x/c.xml"}}},
             "arguments":{
               "game":["--username","${auth_player_name}","--version","${version_name}","--gameDir","${game_directory}",
                       "--assetsDir","${assets_root}","--assetIndex","${assets_index_name}","--uuid","${auth_uuid}",
                       "--accessToken","${auth_access_token}","--clientId","${clientid}","--xuid","${auth_xuid}",
                       "--userType","${user_type}","--versionType","${version_type}",
                       {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                       {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}","--height","${resolution_height}"]},
                       {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],"value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}],
               "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                      "-Djava.library.path=${natives_directory}","-Dminecraft.launcher.brand=${launcher_name}","-cp","${classpath}"]},
             "libraries":[
               {"name":"org.ow2.asm:asm:9.3","downloads":{"artifact":{"path":"org/ow2/asm/asm/9.3/asm-9.3.jar","sha1":"a","size":1,"url":"https://l/asm.jar"}}},
               {"name":"com.mojang:brigadier:1.1.8","downloads":{"artifact":{"path":"com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar","sha1":"b","size":1,"url":"https://l/b.jar"}}},
               {"name":"org.lwjgl:lwjgl:3.3.1:natives-macos","rules":[{"action":"allow","os":{"name":"osx"}}],
                "downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-macos.jar","sha1":"c","size":1,"url":"https://l/m.jar"}}}
             ]}
            """;

    static final String FABRIC = """
            {"id":"fabric-loader-0.15.11-1.20.1","inheritsFrom":"1.20.1","type":"release",
             "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
             "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
             "libraries":[{"name":"org.ow2.asm:asm:9.6","url":"https://maven.fabricmc.net/"},
                          {"name":"net.fabricmc:fabric-loader:0.15.11","url":"https://maven.fabricmc.net/"}]}
            """;

    static Path tmpDirs() throws Exception {
        Path root = Files.createTempDirectory("neky-test");
        Dirs d = new Dirs(root);
        d.ensure();
        FileUtil.write(d.versionJson("1.20.1"), VANILLA);
        FileUtil.write(d.versionJson("fabric-loader-0.15.11-1.20.1"), FABRIC);
        return root;
    }

    static void merge() throws Exception {
        Dirs d = new Dirs(tmpDirs());
        VersionProfile p = VersionProfile.resolve(d, "fabric-loader-0.15.11-1.20.1");
        eq(p.mainClass, "net.fabricmc.loader.impl.launch.knot.KnotClient");
        eq(p.clientJarId, "1.20.1");
        eq(p.javaMajor, 17);
        eq(p.rootId(), "1.20.1");
        long asm = p.libraries.stream().filter(l -> l.key().equals("org.ow2.asm:asm")).count();
        eq(asm, 1L);
        Library first = p.libraries.get(0);
        eq(first.maven.version(), "9.6");
        eq(first.artifact().url(), "https://maven.fabricmc.net/org/ow2/asm/asm/9.6/asm-9.6.jar");
        check(p.jvmArgs.toJson(false).contains("FabricMcEmu"), "child jvm args appended");
        check(p.gameArgs.size() > 10, "parent game args kept");
    }

    static LaunchBuilder.Options options(VersionProfile p, Path root) {
        LaunchBuilder.Options o = new LaunchBuilder.Options();
        o.profile = p;
        o.account = account("Steve");
        o.javaExe = Path.of("/usr/bin/java");
        o.librariesDir = root.resolve("libraries");
        o.clientJar = root.resolve("versions/x/x.jar");
        o.nativesDir = root.resolve("natives");
        o.gameDir = root.resolve("game dir with spaces");
        o.assetsRoot = root.resolve("assets");
        o.gameAssets = o.assetsRoot;
        o.assetIndexName = "5";
        o.width = 1280;
        o.height = 720;
        return o;
    }

    static void modernCommand() throws Exception {
        Path root = tmpDirs();
        Dirs d = new Dirs(root);
        VersionProfile p = VersionProfile.resolve(d, "fabric-loader-0.15.11-1.20.1");
        LaunchBuilder.Options o = options(p, root);
        o.joinServer = "play.example.net";
        List<String> cmd = LaunchBuilder.build(o);
        String all = String.join("\n", cmd);
        check(!all.contains("${"), "unresolved placeholder in: " + all);
        int main = cmd.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient");
        check(main > 0, "main class present");
        int cp = cmd.indexOf("-cp");
        check(cp > 0 && cp < main, "-cp before main class");
        check(cmd.get(cp + 1).contains("asm-9.6.jar") && !cmd.get(cp + 1).contains("asm-9.3.jar"), "dedupe on classpath");
        check(!cmd.get(cp + 1).contains("natives-macos") || OS.isMac(), "mac natives excluded");
        check(cmd.contains("--width") && cmd.contains("1280"), "custom resolution feature");
        check(!cmd.contains("--demo"), "no demo");
        check(cmd.contains("--quickPlayMultiplayer") && cmd.contains("play.example.net"), "quick play");
        check(cmd.contains(o.gameDir.toString()), "game dir with spaces kept as one arg");
        eq(cmd.get(cmd.indexOf("--username") + 1), "Steve");
        eq(cmd.get(cmd.indexOf("--uuid") + 1), o.account.uuid);
        check(cmd.contains("-XX:+UseG1GC"), "default GC tuning");
        check(cmd.contains("-Dminecraft.launcher.brand=NekyLauncher"), "brand");
    }

    static void legacyCommand() throws Exception {
        String legacy = """
                {"id":"1.7.10","mainClass":"net.minecraft.client.main.Main","type":"release","assets":"1.7.10",
                 "minecraftArguments":"--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${game_assets} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} --userType ${user_type}",
                 "libraries":[]}""";
        String forge = """
                {"id":"1.7.10-Forge10.13.4.1614-1.7.10","inheritsFrom":"1.7.10","mainClass":"net.minecraft.launchwrapper.Launch",
                 "minecraftArguments":"--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} --userType ${user_type} --tweakClass cpw.mods.fml.common.launcher.FMLTweaker",
                 "libraries":[{"name":"net.minecraft:launchwrapper:1.12"},{"name":"lzma:lzma:0.0.1","serverreq":true}]}""";
        Path root = Files.createTempDirectory("neky-legacy");
        Dirs d = new Dirs(root);
        d.ensure();
        FileUtil.write(d.versionJson("1.7.10"), legacy);
        FileUtil.write(d.versionJson("1.7.10-Forge10.13.4.1614-1.7.10"), forge);
        VersionProfile p = VersionProfile.resolve(d, "1.7.10-Forge10.13.4.1614-1.7.10");
        eq(p.requiredJava(), 8);
        eq(p.runtimeComponent(), "jre-legacy");
        List<String> cmd = LaunchBuilder.build(options(p, root));
        String all = String.join(" ", cmd);
        check(!all.contains("${"), "unresolved: " + all);
        check(all.contains("-Djava.library.path="), "legacy natives path");
        check(all.contains("--tweakClass cpw.mods.fml.common.launcher.FMLTweaker"), "forge tweaker");
        check(cmd.contains("--width"), "legacy width appended");
        String cp = cmd.get(cmd.indexOf("-cp") + 1);
        check(cp.contains("launchwrapper-1.12.jar"), "launchwrapper on classpath");
        check(!cp.contains("lzma"), "server-only lib skipped");
    }

    static void splitArgs() {
        eq(LaunchBuilder.splitArgs("-Xss4M  \"-Dfoo=a b\" -Dx='y z'"), List.of("-Xss4M", "-Dfoo=a b", "-Dx=y z"));
        eq(LaunchBuilder.splitArgs("   "), List.of());
    }

    static void forgeTokens() throws Exception {
        Path root = Files.createTempDirectory("neky-forge");
        Dirs d = new Dirs(root);
        Class<?> c = Class.forName("com.nekyoh.nekylauncher.loader.ForgeInstaller");
        Constructor<?> ctor = c.getDeclaredConstructor(Dirs.class, com.nekyoh.nekylauncher.java.JavaInstall.class, int.class, Progress.class, CancelToken.class);
        ctor.setAccessible(true);
        Object fi = ctor.newInstance(d, null, 1, Progress.NONE, new CancelToken());
        Method replace = c.getDeclaredMethod("replace", String.class, Map.class);
        replace.setAccessible(true);
        Map<String, String> data = Map.of("SIDE", "client", "MC_SLIM", "/x/slim.jar");
        eq(replace.invoke(fi, "{SIDE}", data), "client");
        eq(replace.invoke(fi, "--out={MC_SLIM}", data), "--out=/x/slim.jar");
        eq(replace.invoke(fi, "'literal {SIDE}'", data), "literal {SIDE}");
        String art = (String) replace.invoke(fi, "[net.minecraftforge:forge:1.20.1-47.2.0:client]", data);
        check(art.replace('\\', '/').endsWith("libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar"), art);
    }

    static void loaderHelpers() {
        eq(Loaders.neoforgePrefix("1.20.4"), "20.4.");
        eq(Loaders.neoforgePrefix("1.21"), "21.0.");
        eq(Loaders.neoforgePrefix("1.21.1"), "21.1.");
        eq(Loaders.neoforgePrefix("26.1"), "26.1.0.");
        check(Loaders.compareVersions("47.2.10", "47.2.9") > 0, "numeric compare");
        check(Loaders.compareVersions("21.1.0", "21.1.0-beta") > 0, "release beats beta");
        eq(LoaderType.of("neoforge"), LoaderType.NEOFORGE);
    }

    static void log4j() throws Exception {
        Method m = GameProcess.class.getDeclaredMethod("parseEvent", String.class);
        m.setAccessible(true);
        String xml = "<log4j:Event logger=\"net.minecraft\" timestamp=\"1700000000000\" level=\"WARN\" thread=\"Render thread\">\n"
                + "  <log4j:Message><![CDATA[Hello <world> & you]]></log4j:Message>\n"
                + "  <log4j:Throwable><![CDATA[java.lang.RuntimeException: boom\n\tat a.b.C]]></log4j:Throwable>\n"
                + "</log4j:Event>\n";
        @SuppressWarnings("unchecked")
        List<GameProcess.Line> lines = (List<GameProcess.Line>) m.invoke(null, xml);
        check(lines.get(0).text().endsWith("[Render thread/WARN]: Hello <world> & you"), lines.get(0).text());
        eq(lines.get(0).level(), GameProcess.Level.WARN);
        eq(lines.get(1).level(), GameProcess.Level.ERROR);
    }

    static void metadata() throws Exception {
        Method toml = ContentScanner.class.getDeclaredMethod("tomlFirstMod", String.class, Map.class);
        toml.setAccessible(true);
        String t = """
                modLoader="javafml"
                logoFile="logo.png"
                [[mods]] #first
                modId="jei"
                version="${file.jarVersion}"
                displayName="Just Enough Items"
                description='''
                Item list
                and recipes'''
                [[mods]]
                modId="other"
                displayName="Other"
                """;
        Map<String, String> top = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, String> mod = (Map<String, String>) toml.invoke(null, t, top);
        eq(mod.get("displayName"), "Just Enough Items");
        check(mod.get("description").contains("and recipes"), "multiline");
        eq(top.get("logoFile"), "logo.png");
        Method pd = ContentScanner.class.getDeclaredMethod("packDescription", String.class);
        pd.setAccessible(true);
        eq(pd.invoke(null, "{\"pack\":{\"pack_format\":15,\"description\":[{\"text\":\"§6Faithful\"},\" 32x\"]}}"), "Faithful 32x");
    }

    static void modJar() throws Exception {
        Path root = Files.createTempDirectory("neky-mods");
        Instance inst = new Instance(root.resolve("i"));
        inst.name = "t";
        inst.mcVersion = "1.20.1";
        Files.createDirectories(inst.modsDir());
        writeZip(inst.modsDir().resolve("sodium.jar"), Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"0.5.8\",\"name\":\"Sodium\",\"description\":\"Fast\",\"authors\":[\"jellysquid\",{\"name\":\"Other\"}]}"));
        writeZip(inst.modsDir().resolve("old.jar.disabled"), Map.of("mcmod.info", "[{\"modid\":\"x\",\"name\":\"Old Mod\",\"version\":\"1.0\"}]"));
        List<ContentItem> items = ContentScanner.scan(inst, ContentItem.Kind.MOD);
        eq(items.size(), 2);
        ContentItem old = items.get(0), sodium = items.get(1);
        eq(old.title, "Old Mod");
        check(!old.enabled, "disabled detected");
        eq(sodium.title, "Sodium");
        eq(sodium.authors, "jellysquid, Other");
        Path enabled = ContentScanner.setEnabled(old, true);
        eq(enabled.getFileName().toString(), "old.jar");
    }

    static void zipSlip() throws Exception {
        Path root = Files.createTempDirectory("neky-slip");
        boolean threw = false;
        try { FileUtil.safeResolve(root, "../../etc/passwd"); } catch (java.io.IOException e) { threw = true; }
        check(threw, "traversal rejected");
        check(FileUtil.safeResolve(root, "mods/a.jar").startsWith(root), "normal path ok");
    }

    static void exportImport() throws Exception {
        Path root = Files.createTempDirectory("neky-arch");
        Dirs d = new Dirs(root);
        d.ensure();
        InstanceManager im = new InstanceManager(d);
        Instance a = im.create("Meu Pack", "1.20.1", LoaderType.FABRIC, "0.15.11");
        Files.writeString(a.modsDir().resolve("mod.jar"), "jar");
        Files.createDirectories(a.savesDir().resolve("World 1"));
        Files.writeString(a.savesDir().resolve("World 1/level.dat"), "lvl");
        Files.createDirectories(a.gameDir().resolve("logs"));
        Files.writeString(a.gameDir().resolve("logs/latest.log"), "log");
        Path zip = root.resolve("out/export.zip");
        Set<String> include = new LinkedHashSet<>();
        for (InstanceArchive.Entry e : InstanceArchive.exportables(a)) if (!InstanceArchive.DEFAULT_EXCLUDED.contains(e.name())) include.add(e.name());
        InstanceArchive.export(a, zip, include, Progress.NONE);
        InstanceArchive.ImportResult r = InstanceArchive.importArchive(zip, im, Progress.NONE, null);
        Instance b = r.instance();
        check(!b.dir.equals(a.dir), "imported into a new folder");
        eq(b.loader, LoaderType.FABRIC);
        eq(b.loaderVersion, "0.15.11");
        eq(Files.readString(b.modsDir().resolve("mod.jar")), "jar");
        check(Files.exists(b.savesDir().resolve("World 1/level.dat")), "world copied");
        check(!Files.exists(b.gameDir().resolve("logs")), "logs excluded");
    }

    static void mrpack() throws Exception {
        Path root = Files.createTempDirectory("neky-mr");
        Dirs d = new Dirs(root);
        d.ensure();
        Path pack = root.resolve("p.mrpack");
        writeZip(pack, Map.of(
                "modrinth.index.json", "{\"formatVersion\":1,\"game\":\"minecraft\",\"versionId\":\"1\",\"name\":\"Cool Pack\",\"files\":[],"
                        + "\"dependencies\":{\"minecraft\":\"1.20.1\",\"fabric-loader\":\"0.15.11\"}}",
                "overrides/config/a.toml", "x=1",
                "client-overrides/options.txt", "lang:pt_br"));
        Instance i = InstanceArchive.importArchive(pack, new InstanceManager(d), Progress.NONE, null).instance();
        eq(i.name, "Cool Pack");
        eq(i.loader, LoaderType.FABRIC);
        check(Files.exists(i.gameDir().resolve("config/a.toml")), "overrides");
        check(Files.exists(i.gameDir().resolve("options.txt")), "client overrides");
    }

    static void prism() throws Exception {
        Path root = Files.createTempDirectory("neky-prism");
        Dirs d = new Dirs(root);
        d.ensure();
        Path zip = root.resolve("prism.zip");
        writeZip(zip, Map.of(
                "MyInst/instance.cfg", "InstanceType=OneSix\nname=Prism Survival\n",
                "MyInst/mmc-pack.json", "{\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.20.4\"},{\"uid\":\"net.neoforged\",\"version\":\"20.4.237\"}],\"formatVersion\":1}",
                "MyInst/.minecraft/mods/x.jar", "j"));
        Instance i = InstanceArchive.importArchive(zip, new InstanceManager(d), Progress.NONE, null).instance();
        eq(i.name, "Prism Survival");
        eq(i.loader, LoaderType.NEOFORGE);
        eq(i.loaderVersion, "20.4.237");
        check(Files.exists(i.modsDir().resolve("x.jar")), "game files copied");
    }

    static void translations() throws Exception {
        Properties base = load("messages.properties");
        for (String f : new String[]{"messages_pt.properties", "messages_es.properties"}) {
            Properties p = load(f);
            for (String k : base.stringPropertyNames()) check(p.containsKey(k), f + " is missing " + k);
        }
        I18n.set(I18n.Lang.PT);
        eq(I18n.tr("nav.home"), "Início");
        eq(I18n.tr("settings.memoryValue", 512, 4096), "512 MB – 4096 MB");
        I18n.set(I18n.Lang.ES);
        eq(I18n.tr("home.hello", "Steve"), "¡Hola, Steve!");
        I18n.set(I18n.Lang.EN);
        eq(I18n.tr("instance.copyName", "A"), "A (copy)");
    }

    static void natives() {
        Library lib = new Library(JObject.parse("""
                {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209","natives":{"linux":"natives-linux","windows":"natives-windows-${arch}","osx":"natives-osx"},
                 "extract":{"exclude":["META-INF/"]},
                 "downloads":{"classifiers":{"natives-linux":{"path":"l.jar","sha1":"1","size":1,"url":"https://l/l.jar"},
                                             "natives-windows-64":{"path":"w64.jar","sha1":"2","size":1,"url":"https://l/w64.jar"},
                                             "natives-osx":{"path":"o.jar","sha1":"3","size":1,"url":"https://l/o.jar"}}}}"""));
        check(lib.artifact() == null, "natives-only library has no classpath artifact");
        String expected = OS.isWindows() ? "w64.jar" : OS.isMac() ? "o.jar" : "l.jar";
        eq(lib.nativeArtifact().path(), expected);
        eq(lib.extractExcludes(), List.of("META-INF/"));
    }

    /** Full LaunchService pipeline with a tiny fake "Minecraft" compiled on the fly (no network needed). */
    static void endToEnd() throws Exception {
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (jc == null) { System.out.println("       (skipped: no compiler)"); return; }
        Path root = Files.createTempDirectory("neky-e2e");
        Path src = root.resolve("src/FakeGame.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                public class FakeGame {
                    public static void main(String[] a) throws Exception {
                        System.out.println("FAKE " + String.join(" ", a));
                        System.out.println("<log4j:Event logger=\\"x\\" timestamp=\\"1700000000000\\" level=\\"INFO\\" thread=\\"Render thread\\">");
                        System.out.println("  <log4j:Message><![CDATA[Setting user: " + a[1] + "]]></log4j:Message>");
                        System.out.println("</log4j:Event>");
                        System.out.println("libpath=" + System.getProperty("java.library.path"));
                        Class.forName("fakelib.Marker");
                        System.err.println("bye");
                    }
                }""");
        Path lib = root.resolve("src/fakelib/Marker.java");
        Files.createDirectories(lib.getParent());
        Files.writeString(lib, "package fakelib; public class Marker {}");
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        check(jc.run(null, null, null, "-d", classes.toString(), src.toString(), lib.toString()) == 0, "compile fake game");

        Dirs d = new Dirs(root.resolve("data"));
        d.ensure();
        jar(classes, "FakeGame.class", d.versionJar("fake-1.0"));
        jar(classes, "fakelib/Marker.class", d.libraries().resolve("test/fakelib/1.0/fakelib-1.0.jar"));
        FileUtil.write(d.versionJson("fake-1.0"), """
                {"id":"fake-1.0","type":"release","mainClass":"FakeGame",
                 "minecraftArguments":"--username ${auth_player_name} --gameDir ${game_directory} --uuid ${auth_uuid}",
                 "javaVersion":{"component":"java-runtime-gamma","majorVersion":17},
                 "libraries":[{"name":"test:fakelib:1.0"}]}""");
        // the "library" has no download URL on purpose: it is already on disk
        FileUtil.write(d.versionJson("fake-1.0"), FileUtil.read(d.versionJson("fake-1.0")).replace("{\"name\":\"test:fakelib:1.0\"}",
                "{\"name\":\"test:fakelib:1.0\",\"downloads\":{\"artifact\":{\"path\":\"test/fakelib/1.0/fakelib-1.0.jar\",\"url\":\"\"}}}"));

        com.nekyoh.nekylauncher.core.Settings settings = com.nekyoh.nekylauncher.core.Settings.load(d.settingsFile());
        settings.autoDownloadJava = false;
        App app = App.init(d, settings);
        Instance inst = app.instances.create("E2E", "fake-1.0", LoaderType.VANILLA, "");
        Account acc = account("Steve");
        GameProcess gp = app.launcher.launch(inst, acc, Progress.NONE, new CancelToken());
        int[] code = {-99};
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        gp.onExit(c -> { code[0] = c; done.countDown(); });
        check(done.await(60, java.util.concurrent.TimeUnit.SECONDS), "game exited");
        Thread.sleep(300);
        List<String> lines = gp.snapshot().stream().map(GameProcess.Line::text).toList();
        String all = String.join("\n", lines);
        eq(code[0], 0);
        check(all.contains("FAKE --username Steve --gameDir " + inst.gameDir()), "args reached the game:\n" + all);
        check(all.contains("[Render thread/INFO]: Setting user: Steve"), "log4j event parsed:\n" + all);
        check(all.contains("libpath=" + inst.nativesDir()), "natives dir passed");
        check(Files.exists(inst.gameDir()), "game dir created");
        check(inst.lastPlayed > 0, "last played updated");
        check(app.launcher.running(inst) == null, "not running after exit");
    }

    static void dataFolder() throws Exception {
        Path base = Files.createTempDirectory("neky-datadir");
        Path cur = base.resolve("old");
        Dirs d = new Dirs(cur);
        d.ensure();
        Files.writeString(cur.resolve("settings.json"), "{}");
        Files.writeString(cur.resolve(Dirs.POINTER), "/somewhere");
        Files.createDirectories(d.instances().resolve("A/minecraft/saves/W"));
        Files.writeString(d.instances().resolve("A/minecraft/saves/W/level.dat"), "lvl");
        Files.writeString(d.logs().resolve("launcher.log"), "log");
        eq(com.nekyoh.nekylauncher.core.DataFolder.check(cur, cur), com.nekyoh.nekylauncher.core.DataFolder.Problem.SAME);
        eq(com.nekyoh.nekylauncher.core.DataFolder.check(cur, cur.resolve("sub")), com.nekyoh.nekylauncher.core.DataFolder.Problem.INSIDE_CURRENT);
        eq(com.nekyoh.nekylauncher.core.DataFolder.check(cur, base), com.nekyoh.nekylauncher.core.DataFolder.Problem.CONTAINS_CURRENT);
        // a folder with unrelated files gets a NekyLauncher subfolder
        Path docs = base.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("cv.pdf"), "x");
        eq(com.nekyoh.nekylauncher.core.DataFolder.normalizeTarget(docs), docs.resolve("NekyLauncher"));
        eq(com.nekyoh.nekylauncher.core.DataFolder.normalizeTarget(cur), cur);
        Path target = com.nekyoh.nekylauncher.core.DataFolder.normalizeTarget(docs);
        eq(com.nekyoh.nekylauncher.core.DataFolder.check(cur, target), com.nekyoh.nekylauncher.core.DataFolder.Problem.NONE);
        com.nekyoh.nekylauncher.core.DataFolder.move(cur, target, Progress.NONE, null);
        eq(Files.readString(target.resolve("instances/A/minecraft/saves/W/level.dat")), "lvl");
        check(Files.exists(target.resolve("settings.json")), "settings moved");
        check(Files.exists(target.resolve("logs/launcher.log")), "logs copied");
        check(!Files.exists(target.resolve(Dirs.POINTER)), "pointer stays behind");
        check(Files.exists(cur.resolve(Dirs.POINTER)), "pointer kept in old place");
        check(!Files.exists(cur.resolve("instances")), "old instances removed");
        check(com.nekyoh.nekylauncher.core.DataFolder.hasLauncherData(target), "target recognised as launcher data");
    }

    /** Simulates the browser coming back to the launcher's local server (no network needed). */
    static void browserLogin() throws Exception {
        String[] page = {null};
        boolean threw = false;
        try {
            new com.nekyoh.nekylauncher.auth.MicrosoftAuth("test-client").loginWithBrowser(url -> {
                check(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"), url);
                check(url.contains("code_challenge_method=S256"), "PKCE");
                String q = url.substring(url.indexOf('?') + 1);
                java.util.Map<String, String> m = new java.util.HashMap<>();
                for (String part : q.split("&")) {
                    String[] kv = part.split("=", 2);
                    m.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
                String redirect = m.get("redirect_uri");
                check(redirect.startsWith("http://localhost:"), redirect);
                Thread.ofVirtual().start(() -> {
                    try {
                        var client = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
                        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                redirect.replace("localhost", "127.0.0.1") + "/?error=access_denied&state=" + m.get("state"))).build();
                        page[0] = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                    } catch (Exception e) {
                        page[0] = "ERR " + e;
                    }
                });
            }, new CancelToken());
        } catch (java.io.IOException e) {
            threw = true;
            eq(e.getMessage(), I18n.tr("ms.declined"));
        }
        check(threw, "declined sign-in must fail");
        for (int i = 0; i < 50 && page[0] == null; i++) Thread.sleep(50);
        check(page[0] != null && page[0].contains("NekyLauncher"), "browser got the result page: " + page[0]);
    }

    static void loaderIcons() {
        check(LoaderType.VANILLA.iconUrl() == null, "vanilla keeps the block icon");
        eq(LoaderType.NEOFORGE.iconUrl(), "https://github.com/neoforged.png?size=128");
        eq(LoaderType.FORGE.iconUrl(), "https://github.com/MinecraftForge.png?size=128");
    }

    // ------------------------------------------------------------------ skins

    static void fill(java.awt.image.BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) img.setRGB(xx, yy, argb);
    }

    static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, YELLOW = 0xFFFFFF00,
            CYAN = 0xFF00FFFF, MAGENTA = 0xFFFF00FF, WHITE = 0xFFFFFFFF, ORANGE = 0xFFFF8800;

    static void skinRender() {
        var skin = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 8, 8, RED);        // head front
        fill(skin, 20, 20, 8, 12, GREEN);   // body front
        fill(skin, 44, 20, 4, 12, BLUE);    // right arm front
        fill(skin, 36, 52, 4, 12, YELLOW);  // left arm front
        fill(skin, 32, 20, 8, 12, CYAN);    // body back
        fill(skin, 52, 20, 4, 12, MAGENTA); // right arm back (classic)
        var f = com.nekyoh.nekylauncher.ui.Art.renderSkin(skin, false, null, false, 1);
        eq(f.getWidth(), 16);
        eq(f.getHeight(), 32);
        eq(f.getRGB(8, 4), RED);
        eq(f.getRGB(8, 14), GREEN);
        eq(f.getRGB(0, 10), BLUE);
        eq(f.getRGB(15, 10), YELLOW);
        var slim = com.nekyoh.nekylauncher.ui.Art.renderSkin(skin, true, null, false, 1);
        eq(slim.getRGB(0, 10) >>> 24, 0);
        eq(slim.getRGB(1, 10), BLUE);
        eq(slim.getRGB(15, 10) >>> 24, 0);
        var back = com.nekyoh.nekylauncher.ui.Art.renderSkin(skin, false, null, true, 1);
        eq(back.getRGB(8, 14), CYAN);
        eq(back.getRGB(13, 10), MAGENTA);
        var cape = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        fill(cape, 1, 1, 10, 16, ORANGE);
        var caped = com.nekyoh.nekylauncher.ui.Art.renderSkin(skin, false, cape, true, 2);
        eq(caped.getRGB(16, 28), ORANGE);
        // legacy 64x32: the left arm is the right arm mirrored
        var legacy = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        fill(legacy, 44, 20, 4, 12, BLUE);
        fill(legacy, 44, 20, 1, 12, WHITE);
        var lf = com.nekyoh.nekylauncher.ui.Art.renderSkin(legacy, false, null, false, 1);
        eq(lf.getRGB(0, 10), WHITE);
        eq(lf.getRGB(15, 10), WHITE);
        eq(lf.getRGB(12, 10), BLUE);
        check(!com.nekyoh.nekylauncher.skins.SkinLibrary.looksSlim(skin), "classic arm back covers column 54");
        fill(skin, 54, 20, 2, 12, 0);
        check(com.nekyoh.nekylauncher.skins.SkinLibrary.looksSlim(skin), "column 54 empty -> slim guess");
    }

    static byte[] png(int w, int h) throws Exception {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, Math.min(8, w), Math.min(8, h), RED);
        return com.nekyoh.nekylauncher.skins.SkinLibrary.png(img);
    }

    static void skinLibrary() throws Exception {
        Path dir = Files.createTempDirectory("neky-skins").resolve("skins");
        var lib = new com.nekyoh.nekylauncher.skins.SkinLibrary(dir);
        var p = lib.add("  Neko  ", png(64, 64), true, "cape-1");
        lib.add("", png(64, 32), false, null);
        eq(p.name, "Neko");
        var again = new com.nekyoh.nekylauncher.skins.SkinLibrary(dir).all();
        eq(again.size(), 2);
        eq(again.get(1).name, "Neko");
        check(again.get(1).slim, "slim kept");
        eq(again.get(1).capeId, "cape-1");
        eq(again.get(0).capeId, "");
        lib.remove(p);
        eq(new com.nekyoh.nekylauncher.skins.SkinLibrary(dir).all().size(), 1);
        check(!Files.exists(p.file(dir)), "png removed");
        for (byte[] bad : new byte[][]{png(128, 128), png(64, 48), "not a png".getBytes(StandardCharsets.UTF_8)}) {
            boolean threw = false;
            try { lib.add("x", bad, false, ""); } catch (java.io.IOException e) { threw = true; }
            check(threw, "invalid skin rejected");
        }
    }

    record Resp(int status, String type, byte[] body) {
        static Resp json(String s) { return new Resp(200, "application/json", s.getBytes(StandardCharsets.UTF_8)); }
    }

    interface Route { Resp handle(String method, String uri, com.sun.net.httpserver.Headers headers, byte[] body) throws Exception; }

    /** Minimal local HTTP server for API tests. Returns base URL "http://127.0.0.1:port". */
    static com.sun.net.httpserver.HttpServer serve(Route r) throws Exception {
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            try {
                byte[] body = ex.getRequestBody().readAllBytes();
                Resp res = r.handle(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders(), body);
                if (res == null) res = new Resp(404, "text/plain", "nope".getBytes(StandardCharsets.UTF_8));
                ex.getResponseHeaders().add("Content-Type", res.type());
                ex.sendResponseHeaders(res.status(), res.body().length == 0 ? -1 : res.body().length);
                if (res.body().length > 0) ex.getResponseBody().write(res.body());
            } catch (Throwable t) {
                byte[] b = String.valueOf(t).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
            }
        });
        srv.start();
        return srv;
    }

    static String base(com.sun.net.httpserver.HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    static void skinApi() throws Exception {
        String[] state = {"CLASSIC", "c1"};
        byte[] skinPng = png(64, 64);
        var srv = serve((m, uri, h, body) -> {
            if (!"Bearer tok".equals(h.getFirst("Authorization"))) return new Resp(401, "application/json", new byte[0]);
            String profile = "{\"id\":\"abc\",\"name\":\"Neko\",\"skins\":[{\"id\":\"s\",\"state\":\"ACTIVE\",\"url\":\"http://t/skin\",\"variant\":\"" + state[0] + "\"}],"
                    + "\"capes\":[{\"id\":\"c1\",\"state\":\"" + ("c1".equals(state[1]) ? "ACTIVE" : "INACTIVE") + "\",\"url\":\"http://t/c1\",\"alias\":\"Migrator\"},"
                    + "{\"id\":\"c2\",\"state\":\"" + ("c2".equals(state[1]) ? "ACTIVE" : "INACTIVE") + "\",\"url\":\"http://t/c2\",\"alias\":\"Vanilla\"}]}";
            switch (m + " " + uri) {
                case "GET /minecraft/profile" -> { return Resp.json(profile); }
                case "POST /minecraft/profile/skins" -> {
                    String type = h.getFirst("Content-Type");
                    check(type.startsWith("multipart/form-data; boundary="), type);
                    String text = new String(body, StandardCharsets.ISO_8859_1);
                    check(text.contains("name=\"variant\"\r\n\r\nslim\r\n"), "variant part");
                    check(text.contains("name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n"), "file part");
                    check(text.contains(new String(skinPng, StandardCharsets.ISO_8859_1)), "png bytes intact");
                    check(text.endsWith("--\r\n"), "closing boundary");
                    state[0] = "SLIM";
                    return Resp.json(profile.replace("CLASSIC", "SLIM"));
                }
                case "PUT /minecraft/profile/capes/active" -> {
                    state[1] = JObject.parse(new String(body, StandardCharsets.UTF_8)).str("capeId");
                    return new Resp(200, "application/json", new byte[0]); // empty body: client re-fetches
                }
                case "DELETE /minecraft/profile/capes/active" -> {
                    state[1] = "";
                    return new Resp(204, "application/json", new byte[0]);
                }
                default -> { return null; }
            }
        });
        try {
            com.nekyoh.nekylauncher.skins.SkinApi.setProfileUrlForTests(base(srv) + "/minecraft/profile");
            var p = com.nekyoh.nekylauncher.skins.SkinApi.fetch("tok");
            eq(p.name(), "Neko");
            eq(p.capes().size(), 2);
            eq(p.activeCape().alias(), "Migrator");
            check(!p.activeSkin().slim(), "classic");
            p = com.nekyoh.nekylauncher.skins.SkinApi.uploadSkin("tok", skinPng, true);
            check(p.activeSkin().slim(), "now slim");
            p = com.nekyoh.nekylauncher.skins.SkinApi.setCape("tok", "c2");
            eq(p.activeCape().id(), "c2");
            p = com.nekyoh.nekylauncher.skins.SkinApi.setCape("tok", "");
            check(p.activeCape() == null, "cape hidden");
            boolean threw = false;
            try { com.nekyoh.nekylauncher.skins.SkinApi.fetch("old"); } catch (java.io.IOException e) {
                threw = true;
                eq(e.getMessage(), I18n.tr("skins.error.session"));
            }
            check(threw, "401 -> session error");
        } finally {
            com.nekyoh.nekylauncher.skins.SkinApi.setProfileUrlForTests("https://api.minecraftservices.com/minecraft/profile");
            srv.stop(0);
        }
    }

    // ------------------------------------------------------------------ curseforge

    static void cfFingerprint() {
        // reference values from Austin Appleby's MurmurHash2 (C), seed 1, whitespace stripped
        String[] in = {"", "a", "ab", "abc", "abcd", "hello world\n\tNekyLauncher test 12345"};
        long[] want = {1540447798L, 626045324L, 1692487918L, 1621425345L, 3376380438L, 2185227716L};
        for (int i = 0; i < in.length; i++) {
            eq(com.nekyoh.nekylauncher.curseforge.CurseForge.fingerprint(in[i].getBytes(StandardCharsets.UTF_8)), want[i]);
        }
    }

    static com.nekyoh.nekylauncher.curseforge.CurseForge.FileInfo cfFile(List<String> versions) {
        return new com.nekyoh.nekylauncher.curseforge.CurseForge.FileInfo(1, 1, "", "a.jar", "u", null, 0, versions, 1, List.of());
    }

    static void cfLoaders() {
        check(com.nekyoh.nekylauncher.curseforge.CurseForge.matchesLoader(cfFile(List.of("1.20.1", "Fabric")), LoaderType.QUILT, "1.20.1"), "fabric on quilt");
        check(!com.nekyoh.nekylauncher.curseforge.CurseForge.matchesLoader(cfFile(List.of("1.20.1", "Forge")), LoaderType.QUILT, "1.20.1"), "forge not on quilt");
        check(com.nekyoh.nekylauncher.curseforge.CurseForge.matchesLoader(cfFile(List.of("1.20.1", "Forge")), LoaderType.NEOFORGE, "1.20.1"), "forge on neoforge 1.20.1");
        check(!com.nekyoh.nekylauncher.curseforge.CurseForge.matchesLoader(cfFile(List.of("1.21.1", "Forge")), LoaderType.NEOFORGE, "1.21.1"), "forge not on neoforge 1.21");
        check(com.nekyoh.nekylauncher.curseforge.CurseForge.matchesLoader(cfFile(List.of("1.12.2")), LoaderType.FORGE, "1.12.2"), "untagged old file");
        eq(com.nekyoh.nekylauncher.modrinth.Modrinth.loaderFilter(com.nekyoh.nekylauncher.modrinth.Modrinth.ProjectType.MOD, LoaderType.NEOFORGE, "1.20.1"), List.of("neoforge", "forge"));
        eq(com.nekyoh.nekylauncher.modrinth.Modrinth.loaderFilter(com.nekyoh.nekylauncher.modrinth.Modrinth.ProjectType.MOD, LoaderType.NEOFORGE, "1.21.1"), List.of("neoforge"));
    }

    static String cfFileJson(long id, long modId, String name, String url, byte[] data, int release, String deps, String... versions) {
        StringBuilder gv = new StringBuilder();
        for (String v : versions) gv.append(gv.length() == 0 ? "" : ",").append('"').append(v).append('"');
        return "{\"id\":" + id + ",\"modId\":" + modId + ",\"displayName\":\"" + name + "\",\"fileName\":\"" + name + "\",\"releaseType\":" + release
                + ",\"downloadUrl\":" + (url == null ? "null" : "\"" + url + "\"") + ",\"fileLength\":" + data.length
                + ",\"hashes\":[{\"value\":\"" + Hashes.sha1(data) + "\",\"algo\":1},{\"value\":\"x\",\"algo\":2}]"
                + ",\"gameVersions\":[" + gv + "],\"dependencies\":[" + deps + "],\"isAvailable\":true}";
    }

    static void cfInstall() throws Exception {
        Path mods = Files.createTempDirectory("neky-cf").resolve("mods");
        Files.createDirectories(mods);
        byte[] cool = "cool mod jar".getBytes(StandardCharsets.UTF_8), dep = "dependency jar".getBytes(StandardCharsets.UTF_8);
        byte[] already = "already installed library".getBytes(StandardCharsets.UTF_8);
        Files.write(mods.resolve("already.jar"), already);
        long alreadyPrint = com.nekyoh.nekylauncher.curseforge.CurseForge.fingerprint(already);
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        com.sun.net.httpserver.HttpServer[] self = {null};
        var srv = serve((m, uri, h, body) -> {
            calls.add(m + " " + uri);
            String b = base(self[0]);
            if (uri.startsWith("/dl/")) return new Resp(200, "application/java-archive", uri.equals("/dl/cool.jar") ? cool : dep);
            check("k".equals(h.getFirst("x-api-key")), "api key header");
            if (uri.startsWith("/v1/mods/search?")) {
                check(uri.contains("classId=6") && uri.contains("gameVersion=1.20.1") && uri.contains("modLoaderType=4") && uri.contains("searchFilter=cool"), uri);
                return Resp.json("{\"data\":[{\"id\":100,\"name\":\"Cool Mod\",\"slug\":\"cool\",\"summary\":\"Very cool\",\"downloadCount\":5,\"classId\":6,"
                        + "\"authors\":[{\"name\":\"neko\"}],\"logo\":{\"thumbnailUrl\":\"http://x/i.png\"},\"links\":{\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/cool\"},"
                        + "\"categories\":[{\"name\":\"Tech\"}]}],\"pagination\":{\"index\":0,\"pageSize\":20,\"resultCount\":1,\"totalCount\":1}}");
            }
            if (uri.startsWith("/v1/mods/100/files?")) {
                return Resp.json("{\"data\":[" + cfFileJson(10, 100, "cool.jar", b + "/dl/cool.jar", cool, 1,
                        "{\"modId\":200,\"relationType\":3},{\"modId\":300,\"relationType\":3},{\"modId\":500,\"relationType\":3},{\"modId\":400,\"relationType\":2}", "1.20.1", "Fabric")
                        + "," + cfFileJson(11, 100, "cool-beta.jar", b + "/dl/cool.jar", cool, 2, "", "1.20.1", "Fabric") + "]}");
            }
            if (uri.startsWith("/v1/mods/200/files?")) return Resp.json("{\"data\":[" + cfFileJson(20, 200, "dep.jar", b + "/dl/dep.jar", dep, 1, "", "1.20.1", "Fabric") + "]}");
            if (uri.startsWith("/v1/mods/300/files?")) return Resp.json("{\"data\":[" + cfFileJson(30, 300, "blocked.jar", null, dep, 1, "", "1.20.1", "Fabric") + "]}");
            if (uri.equals("/v1/mods/300")) return Resp.json("{\"data\":{\"id\":300,\"name\":\"Blocked Lib\",\"classId\":6,\"links\":{\"websiteUrl\":\"https://cf.test/blocked\"}}}");
            if (uri.startsWith("/v1/mods/600/files?")) return Resp.json("{\"data\":[" + cfFileJson(60, 600, "solo.jar", null, dep, 1, "", "1.20.1", "Fabric") + "]}");
            if (uri.equals("/v1/fingerprints/432")) {
                check(new String(body, StandardCharsets.UTF_8).contains(String.valueOf(alreadyPrint)), "fingerprint sent");
                return Resp.json("{\"data\":{\"exactMatches\":[{\"id\":500,\"file\":{\"modId\":500,\"fileFingerprint\":" + alreadyPrint + "}}]}}");
            }
            return null;
        });
        self[0] = srv;
        try {
            com.nekyoh.nekylauncher.curseforge.CurseForge.setForTests("k", base(srv) + "/v1");
            check(com.nekyoh.nekylauncher.curseforge.CurseForge.available(), "key set");
            var res = com.nekyoh.nekylauncher.curseforge.CurseForge.search("cool", com.nekyoh.nekylauncher.curseforge.CurseForge.ClassType.MOD, "1.20.1", LoaderType.FABRIC, 0, 20);
            eq(res.total(), 1);
            var hit = res.hits().get(0);
            eq(hit.title(), "Cool Mod");
            eq(hit.author(), "neko");
            eq(hit.categories(), List.of("Tech"));
            var files = com.nekyoh.nekylauncher.curseforge.CurseForge.projectFiles(100, com.nekyoh.nekylauncher.curseforge.CurseForge.ClassType.MOD, "1.20.1", LoaderType.FABRIC);
            eq(files.get(0).id(), 11L); // newest first
            var best = com.nekyoh.nekylauncher.curseforge.CurseForge.best(files);
            eq(best.id(), 10L); // release preferred over beta
            eq(best.requiredMods(), List.of(200L, 300L, 500L));
            var r = com.nekyoh.nekylauncher.curseforge.CurseForge.install(hit, best, mods, com.nekyoh.nekylauncher.curseforge.CurseForge.ClassType.MOD,
                    LoaderType.FABRIC, "1.20.1", true, Progress.NONE, null);
            eq(r.written().size(), 2);
            check(Files.exists(mods.resolve("cool.jar")) && Files.exists(mods.resolve("dep.jar")), "mod + dependency downloaded");
            check(Files.exists(mods.resolve("already.jar")), "present dependency untouched");
            eq(r.blocked().size(), 1);
            check(r.blocked().get(0).name().contains("Blocked Lib"), r.blocked().get(0).name());
            eq(r.blocked().get(0).pageUrl(), "https://cf.test/blocked");
            check(calls.stream().noneMatch(c -> c.contains("/mods/500/") || c.contains("/mods/400/")), "skipped present + optional deps: " + calls);
            boolean threw = false;
            try {
                var solo = com.nekyoh.nekylauncher.curseforge.CurseForge.best(com.nekyoh.nekylauncher.curseforge.CurseForge.projectFiles(600,
                        com.nekyoh.nekylauncher.curseforge.CurseForge.ClassType.MOD, "1.20.1", LoaderType.FABRIC));
                com.nekyoh.nekylauncher.curseforge.CurseForge.install(hit, solo, mods, com.nekyoh.nekylauncher.curseforge.CurseForge.ClassType.MOD,
                        LoaderType.FABRIC, "1.20.1", true, Progress.NONE, null);
            } catch (com.nekyoh.nekylauncher.curseforge.CurseForge.DistributionBlockedException e) {
                threw = true;
            }
            check(threw, "third-party download disabled -> not downloaded");
        } finally {
            com.nekyoh.nekylauncher.curseforge.CurseForge.setForTests(null, null);
            srv.stop(0);
        }
    }

    static void cfModpack() throws Exception {
        Path root = Files.createTempDirectory("neky-cfpack");
        Dirs d = new Dirs(root);
        d.ensure();
        byte[] cool = "cool mod jar".getBytes(StandardCharsets.UTF_8), pack = "resource pack zip".getBytes(StandardCharsets.UTF_8);
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        com.sun.net.httpserver.HttpServer[] self = {null};
        var srv = serve((m, uri, h, body) -> {
            calls.add(m + " " + uri + " " + new String(body, StandardCharsets.UTF_8));
            String b = base(self[0]);
            if (uri.equals("/dl/cool.jar")) return new Resp(200, "application/java-archive", cool);
            if (uri.equals("/dl/pack.zip")) return new Resp(200, "application/zip", pack);
            if (uri.equals("/v1/mods/files")) {
                return Resp.json("{\"data\":[" + cfFileJson(10, 100, "cool.jar", b + "/dl/cool.jar", cool, 1, "", "1.20.1", "Fabric") + ","
                        + cfFileJson(70, 700, "pack.zip", b + "/dl/pack.zip", pack, 1, "", "1.20.1") + ","
                        + cfFileJson(30, 300, "blocked.jar", null, cool, 1, "", "1.20.1") + "]}");
            }
            if (uri.equals("/v1/mods")) {
                return Resp.json("{\"data\":[{\"id\":100,\"name\":\"Cool Mod\",\"classId\":6},{\"id\":700,\"name\":\"Pretty Pack\",\"classId\":12},"
                        + "{\"id\":300,\"name\":\"Blocked Lib\",\"classId\":6,\"links\":{\"websiteUrl\":\"https://cf.test/blocked\"}}]}");
            }
            return null;
        });
        self[0] = srv;
        try {
            com.nekyoh.nekylauncher.curseforge.CurseForge.setForTests("k", base(srv) + "/v1");
            Path zip = root.resolve("cf.zip");
            writeZip(zip, Map.of(
                    "manifest.json", "{\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"fabric-0.15.11\",\"primary\":true}]},"
                            + "\"manifestType\":\"minecraftModpack\",\"name\":\"CF Pack\",\"overrides\":\"overrides\",\"files\":["
                            + "{\"projectID\":100,\"fileID\":10,\"required\":true},{\"projectID\":700,\"fileID\":70,\"required\":true},"
                            + "{\"projectID\":300,\"fileID\":30,\"required\":true},{\"projectID\":800,\"fileID\":80,\"required\":false}]}",
                    "overrides/config/a.txt", "x"));
            var res = InstanceArchive.importArchive(zip, new InstanceManager(d), Progress.NONE, null);
            Instance i = res.instance();
            eq(i.name, "CF Pack");
            eq(i.loader, LoaderType.FABRIC);
            eq(i.loaderVersion, "0.15.11");
            check(Files.exists(i.modsDir().resolve("cool.jar")), "mod downloaded");
            check(Files.exists(i.gameDir().resolve("resourcepacks/pack.zip")), "resource pack routed by class");
            check(Files.exists(i.gameDir().resolve("config/a.txt")), "overrides");
            eq(res.blocked().size(), 1);
            eq(res.blocked().get(0).pageUrl(), "https://cf.test/blocked");
            check(calls.stream().noneMatch(c -> c.contains("80")), "optional file skipped: " + calls);
        } finally {
            com.nekyoh.nekylauncher.curseforge.CurseForge.setForTests(null, null);
            srv.stop(0);
        }
    }

    static void jar(Path classes, String entry, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(Files.readAllBytes(classes.resolve(entry)));
            z.closeEntry();
        }
    }

    // ------------------------------------------------------------------

    static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (var in = SelfTest.class.getResourceAsStream("/nekylauncher/i18n/" + name);
             var r = new java.io.InputStreamReader(Objects.requireNonNull(in, name), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    static void writeZip(Path zip, Map<String, String> entries) throws Exception {
        Files.createDirectories(zip.getParent());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (var e : new TreeMap<>(entries).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Files.write(zip, bo.toByteArray());
    }
}
