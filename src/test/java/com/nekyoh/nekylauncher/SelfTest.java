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
