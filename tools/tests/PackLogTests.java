import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * pack-check reads a failed start: the words it is reported with, client-only mods, the mod a crash names, for Forge
 * and Fabric, a port lost to the other server and a free voice chat port.
 * Needs no game and no network (mmt.pack.offline; mod sides are pre-cached).
 */
public final class PackLogTests extends PackCheckFixtures {

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("mmt-pack");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("mmt.pack.offline", "true");
        try {
            logs(harness, scratch);
            culprits(harness, scratch);
            System.out.println(
                count +
                " pack log checks passed: failed-start words, client-only mods, the mod a crash names (only the failure's own lines; the Java build when the JDK lacks what the loader calls; only the mod reaching a client class)," +
                " client-only code read from server entrypoints, the Java build a loader needs, a port lost to the other server, a free voice chat port"
            );
        } finally {
            System.setProperty("user.home", home);
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ logs
    static void logs(Path harness, Path scratch) throws Exception {
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge"), fabric = PackLoaders.find(harness, "fabric");
        // A failed start is told in the game's own words.
        String log = String.join(
            "\n",
            "[main/INFO] Preparing level \"world\"",
            "[main/ERROR] Failed to load datapacks, can't proceed with server load",
            "java.lang.IllegalStateException: Failed to load datapacks",
            "Caused by: java.lang.IllegalStateException: Overworld settings missing",
            "\tat x.y(Z.java:1)"
        );
        String failure = PackChecks.failure(log, forge.failed());
        expect(
            failure.contains("Overworld settings missing") && failure.contains("Failed to load datapacks") && !failure.contains("at x.y"),
            "a failed start keeps the cause: " + failure
        );
        // Client-only mods stopping a server, in Forge 47's own lines: Blur's cause is nested; a mod failing for another reason is kept.
        PackMods.Scan forgeScan = new PackMods.Scan();
        String clientOnly = String.join(
            "\n",
            "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: blur, class com.tterrag.blur.Blur",
            "java.lang.ExceptionInInitializerError: null",
            "\tat x.y(Z.java:1)",
            "Caused by: java.lang.RuntimeException: Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER",
            "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to register automatic subscribers. ModID: buildpaste, class com.mistrx.buildpaste.BuildPasteMod",
            "java.lang.RuntimeException: Attempted to load class net/minecraft/client/renderer/LevelRenderer for invalid dist DEDICATED_SERVER",
            "[17:06:44] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: citresewn, class shcm.shsupercm.forge.citresewn.CITResewn",
            "java.lang.NoClassDefFoundError: net/minecraft/client/renderer/texture/atlas/SpriteSource",
            "	at java.lang.ClassLoader.defineClass1(Native Method) ~[?:?]",
            "Caused by: java.lang.ClassNotFoundException: net.minecraft.client.renderer.texture.atlas.SpriteSource",
            "[17:06:44] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: brokenmod, class a.B",
            "java.lang.NullPointerException: config was null",
            "[17:06:44] [main/INFO] [STDERR/]: [Rhino] Failed to get declared methods: Attempted to load class net/minecraft/client/gui/GuiGraphics for invalid dist DEDICATED_SERVER",
            "[17:06:56] [main/FATAL] [ne.mi.fm.ModLoader/LOADING]: Failed to complete lifecycle event CONSTRUCT, 3 errors found",
            "java.lang.Exception: Mod Loading has failed"
        );
        expect(
            PackChecks.clientOnly(clientOnly, forge, forgeScan).equals(new LinkedHashSet<>(List.of("blur", "buildpaste", "citresewn"))),
            "client-only mods are read from Forge's failure blocks (a client class reached, or missing from the server jar), and only those: " + PackChecks.clientOnly(clientOnly, forge, forgeScan)
        );
        String started = clientOnly.replace("Mod Loading has failed", "Done").replace("Failed to complete lifecycle event", "Done").replace("java.lang.", "");
        expect(PackChecks.clientOnly(started, forge, forgeScan).isEmpty(), "a server that started has no client-only mods to remove");

        // Fabric names no mod for client code reached on a server: the stack's first frame in a pack jar does.
        PackMods.Scan scan = new PackMods.Scan();
        scan.providers.put("zoomify", new ArrayList<>(List.of(new PackMods.Provider("Zoomify.jar", "2.14", false, "fabric.mod.json"))));
        scan.providers.put("iris", new ArrayList<>(List.of(new PackMods.Provider("iris.jar", "1.7", false, "fabric.mod.json"))));
        scan.packages.put("dev.isxander.zoomify", "Zoomify.jar");
        scan.packages.put("net.irisshaders.iris.mixin", "iris.jar");
        scan.mixins.put("mixins.iris.json", "iris.jar");
        String fabricLog = String.join(
            "\n",
            "[17:42:33] [main/ERROR]: Failed to start Minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'zoomify' at 'dev.isxander.zoomify.Zoomify'!",
            "\tat knot//net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:403)",
            "Caused by: java.lang.RuntimeException: Cannot load class net.minecraft.class_310 in environment type SERVER",
            "\tat knot//net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.loadClass(KnotClassDelegate.java:1)",
            "\tat knot//dev.isxander.zoomify.Zoomify.onInitialize(Zoomify.kt:20)",
            "[17:42:34] [main/INFO]: Stopping"
        );
        expect(PackChecks.clientOnly(fabricLog, fabric, scan).equals(Set.of("zoomify")), "Fabric: a client-only mod is found by its frame: " + PackChecks.clientOnly(fabricLog, fabric, scan));
        // Quilt 1.20.1 (SodiumPlus, from the pack sweep): Lilac reaches Fabric API's client-only classes on a server.
        PackLoaders.Loader quilt = PackLoaders.all(harness).get("quilt");
        scan.providers.put("lilac", new ArrayList<>(List.of(new PackMods.Provider("lilac.jar", "1.0", false, "fabric.mod.json"))));
        String quiltLog = String.join(
            "\n",
            "[20:55:45] [main/ERROR]: Failed to start the minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'lilac'!",
            "\tat org.quiltmc.loader.impl.entrypoint.EntrypointUtils.lambda$invoke0$2(EntrypointUtils.java:66) ~[quilt-loader-0.24.0.jar:?]",
            "Caused by: org.quiltmc.loader.impl.entrypoint.QuiltEntrypointException: Exception while loading entries for entrypoint 'main' provided by 'lilac'",
            "Caused by: java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndWorldTick"
        );
        expect(PackChecks.clientOnly(quiltLog, quilt, scan).equals(Set.of("lilac")), "Quilt: a mod reaching Fabric API's client classes is client-only: " + PackChecks.clientOnly(quiltLog, quilt, scan));
        // Create Astral (Fabric 1.18.2): Drink Beer's main entrypoint builds its item group with FabricItemGroupBuilder, which servers run.
        var classes = new PackLoaders.GameClasses(Set.of(), null);
        expect(
            !classes.clientOnly("net/fabricmc/fabric/api/client/itemgroup/FabricItemGroupBuilder") &&
            classes.clientOnly("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents"),
            "Fabric API's item group builder is common code, its other client packages are not"
        );
        var suspects = PackChecks.suspects(fabricLog, List.of(), scan, fabric);
        expect(
            suspects.size() == 1 && suspects.get(0).get("mod").equals("zoomify") && suspects.get(0).get("jar").equals("Zoomify.jar") && Json.array(suspects.get(0).get("why")).size() == 2,
            "Fabric: the crash names zoomify, by the loader and by its code in the stack: " + suspects
        );
        String mixinLog = String.join(
            "\n",
            "[main/ERROR]: Mixin apply failed mixins.iris.json:MixinLevelRenderer -> net.minecraft.class_761: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException",
            "Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: boom",
            "\tat knot//net.irisshaders.iris.mixin.Foo.bar(Foo.java:1)"
        );
        var mixinSuspects = PackChecks.suspects(mixinLog, List.of(), scan, fabric);
        expect(mixinSuspects.size() == 1 && mixinSuspects.get(0).get("mod").equals("iris"), "a mixin config in the failure names its mod: " + mixinSuspects);
        // Forge's module path names the mod in each frame; a crash report's Suspected Mods are read too.
        PackMods.Scan neo = new PackMods.Scan();
        neo.providers.put("create", new ArrayList<>(List.of(new PackMods.Provider("create.jar", "6", false, "META-INF/neoforge.mods.toml"))));
        neo.providers.put("flywheel", new ArrayList<>(List.of(new PackMods.Provider("create.jar!flywheel.jar", "1", true, "META-INF/neoforge.mods.toml"))));
        String report = "---- Minecraft Crash Report ----\nDescription: Exception in server tick loop\n\njava.lang.NullPointerException\n\tat TRANSFORMER/create@6.0.0/com.simibubi.create.Foo.tick(Foo.java:1)\n\nSuspected Mods:\n\tFlywheel (flywheel), Version: 1\n\n";
        var neoSuspects = PackChecks.suspects("", List.of(report), neo, PackLoaders.find(harness, "neoforge"));
        expect(
            neoSuspects.stream().map(s -> s.get("mod")).toList().equals(List.of("flywheel", "create")) && neoSuspects.get(0).get("jar").equals("create.jar!flywheel.jar"),
            "a crash report's suspected mod and a module frame name mods: " + neoSuspects
        );
        expect(PackChecks.names(neoSuspects).equals("flywheel (create.jar!flywheel.jar), create (create.jar)"), "suspects read as a list of mods and jars");
        // Found by an independent rerun of Optimized FPS (Forge 1.16.5): the probe never found the server, and pack-check blamed
        // ModernFix by quoting a successful start-up log. A server that logged Done blames nobody for what came before it.
        String startedLog = String.join(
            "\n",
            "[main/INFO] [mixin/]: Mixing mixins.iris.json:MixinLevelRenderer",
            "[Server thread/INFO] [minecraft/DedicatedServer]: Done (12.345s)! For help, type \"help\""
        );
        var startedSuspects = PackChecks.suspects(startedLog, List.of(), scan, fabric);
        String startedFailure = PackChecks.failure(startedLog, fabric.failed());
        expect(
            startedSuspects.isEmpty() && startedFailure.contains("finished starting") && startedFailure.contains("not of a mod"),
            "a log with a finished start names no mod, and says the probe never reported: " + startedSuspects + " " + startedFailure
        );
        var crashedAfter = PackChecks.suspects(startedLog + "\n" + mixinLog, List.of(), scan, fabric);
        expect(crashedAfter.size() == 1 && crashedAfter.get(0).get("mod").equals("iris"), "a failure after the start still names its mod: " + crashedAfter);

        // Simple Voice Chat's fixed UDP port, taken by the other lane's server: that start runs again alone.
        String lost = String.join(
            "\n",
            "[16:46:12] [VoiceChatServerThread/ERROR] [voicechat/]: [voicechat] Voice chat server error",
            "java.net.BindException: Address already in use: bind",
            "[16:46:12] [Server thread/INFO] [minecraft/MinecraftServer]: Stopping server"
        );
        expect(PackCheck.lostPort(lost) && !PackCheck.lostPort(log) && !PackCheck.lostPort(clientOnly), "a port lost to another server is told apart from other failed starts");
        Path server = Files.createDirectories(scratch.resolve("server/mods")).getParent();
        Path voice = Files.createDirectories(server.resolve("config/voicechat")).resolve("voicechat-server.properties");
        Files.writeString(voice, "# Simple Voice Chat server config\n# Set this to '-1' to use the same port\nport=24454\nmax_voice_distance=48.0\n");
        PackCheck.ServerLane.voiceChatPort(server);
        expect(Files.readString(voice).contains("port=24454"), "a server without Simple Voice Chat keeps its config: " + Files.readString(voice));
        Files.writeString(server.resolve("mods/voicechat-forge-1.20.1-2.6.22.jar"), "");
        PackCheck.ServerLane.voiceChatPort(server);
        String rewritten = Files.readString(voice);
        expect(
            !rewritten.contains("port=24454") && rewritten.matches("(?s).*\nport=[1-9][0-9]*\n.*") && rewritten.contains("max_voice_distance=48.0"),
            "Simple Voice Chat's fixed port is replaced by a free one, nothing else changed: " + rewritten
        );
        expect(PackCheck.doneSeconds("[Server thread/INFO] Done (41.250s)! For help, type \"help\"") == 41.25, "the start time is read");
        expect(Json.write(Json.map("probeFile", scratch.resolve("probe-place.json"))).contains("probe-place.json"), "a path in a result is written as text");
    }

    /**
     * Found by an independent check of the pack sweep. Create: Above and Beyond (Forge 1.16.5 36.2.20) on Temurin 8u512
     * stopped in ModLauncher with NoSuchMethodError on ManifestEntryVerifier(Manifest), and pack-check blamed antiqueatlas,
     * whose mixin config was named on the log line above. XPlus 2.0 (Fabric 1.17.1) kept mcwifipnp, whose main entrypoint
     * reaches Screen (class_437), and blamed iceberg and worldedit too, for mixin configs in ordinary start-up lines.
     */
    static void culprits(Path harness, Path scratch) throws Exception {
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge"), fabric = PackLoaders.find(harness, "fabric");
        String stderr = "[02:56:35] [main/INFO] [STDERR/]: [java.lang.ThreadGroup:uncaughtException:1052]: ";
        String cab = String.join(
            "\n",
            "[02:56:33] [main/INFO] [mixin/]: SpongePowered MIXIN Subsystem Version=0.8.4 Service=ModLauncher Env=SERVER",
            "[02:56:35] [main/ERROR] [mixin/]: Mixin config antiqueatlas.mixins.json does not specify \"minVersion\" property",
            "Exception in thread \"main\" " + stderr + "java.lang.NoSuchMethodError: sun.security.util.ManifestEntryVerifier.<init>(Ljava/util/jar/Manifest;)V",
            stderr + "\tat cpw.mods.modlauncher.SecureJarHandler.createCodeSource(SecureJarHandler.java:66)",
            stderr + "\tat cpw.mods.modlauncher.TransformingClassLoader$DelegatedClassLoader.findClass(TransformingClassLoader.java:275)",
            stderr + "\tat net.minecraftforge.server.ServerMain.main(ServerMain.java:57)"
        );
        PackMods.Scan cabScan = new PackMods.Scan();
        cabScan.providers.put("antiqueatlas", new ArrayList<>(List.of(new PackMods.Provider("antiqueatlas.jar", "6.0.1", false, "META-INF/mods.toml"))));
        cabScan.mixins.put("antiqueatlas.mixins.json", "antiqueatlas.jar");
        cabScan.packages.put("hunternif.mc.impl.atlas", "antiqueatlas.jar");
        var cabSuspects = PackChecks.suspects(cab, List.of(), cabScan, forge);
        expect(
            cabSuspects.size() == 1 && Boolean.TRUE.equals(cabSuspects.get(0).get("runtime")) && PackChecks.names(cabSuspects).equals("the Java build") &&
            Json.array(cabSuspects.get(0).get("why")).get(0).toString().contains("ManifestEntryVerifier.<init>(Ljava/util/jar/Manifest;)V"),
            "a JDK member missing under the loader's own code blames the Java build, not the mod on the line above: " + cabSuspects
        );
        expect(PackChecks.failure(cab, forge.failed()).contains("NoSuchMethodError: sun.security.util.ManifestEntryVerifier"), "the evidence keeps the uncaught error");
        // The same missing member called from a mod's own code is that mod's.
        String modCalls = cab.replace("cpw.mods.modlauncher.SecureJarHandler.createCodeSource", "hunternif.mc.impl.atlas.Foo.bar");
        var modSuspects = PackChecks.suspects(modCalls, List.of(), cabScan, forge);
        expect(modSuspects.size() == 1 && modSuspects.get(0).get("mod").equals("antiqueatlas"), "a mod calling a JDK member this Java lacks is that mod: " + modSuspects);
        // A mixin config named only in an ordinary line blames nobody; named in the failure, it does.
        String calm = "[main/WARN]: Method overwrite conflict for getLanguage in worldedit.mixins.json:Mixin from mod worldedit. Skipping method.\n" +
            "[main/FATAL]: Failed to start the minecraft server\njava.lang.IllegalStateException: boom\n\tat a.b.C.d(C.java:1)";
        PackMods.Scan xScan = new PackMods.Scan();
        xScan.mixins.put("worldedit.mixins.json", "worldedit.jar");
        xScan.mixins.put("iceberg.mixins.json", "iceberg.jar");
        for (String id : List.of("worldedit", "iceberg", "mcwifipnp")) xScan.providers.put(
            id,
            new ArrayList<>(List.of(new PackMods.Provider(id + ".jar", "1", false, "fabric.mod.json")))
        );
        expect(PackChecks.suspects(calm, List.of(), xScan, fabric).isEmpty(), "a mixin config in a start-up warning is not in the failure");
        String xplus = String.join(
            "\n",
            "[02:55:59] [main/INFO]: Mixing EntityMixin from iceberg.mixins.json into net.minecraft.class_1297",
            "[02:55:59] [main/WARN]: Method overwrite conflict for getLanguage in worldedit.mixins.json:MixinServerPlayerEntity from mod worldedit, previously written by carpet. Skipping method.",
            "[02:56:07] [main/FATAL]: Failed to start the minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'mcwifipnp'!",
            "\tat net.fabricmc.loader.impl.entrypoint.EntrypointUtils.lambda$invoke0$0(EntrypointUtils.java:51) ~[fabric-loader-0.14.21.jar:?]",
            "Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_437",
            "\tat io.github.satxm.mcwifipnp.MCWiFiPnP.onInitialize(MCWiFiPnP.java:30) ~[mcwifipnp.jar:?]",
            "Caused by: java.lang.ClassNotFoundException: net.minecraft.class_437"
        );
        java.util.function.Predicate<String> client = type -> type.equals("net/minecraft/class_437");
        var xSuspects = PackChecks.suspects(xplus, List.of(), xScan, fabric, client);
        expect(
            xSuspects.size() == 1 && xSuspects.get(0).get("mod").equals("mcwifipnp") && Json.array(xSuspects.get(0).get("why")).get(0).toString().contains("class_437"),
            "a client game class missing on the server blames only the mod that reached it: " + xSuspects
        );
        expect(PackChecks.clientOnly(xplus, fabric, xScan, client).equals(Set.of("mcwifipnp")), "a server-side NoClassDefFoundError on a client class is client-only");
        expect(PackChecks.clientOnly(xplus, fabric, xScan).isEmpty(), "a game class the server has, or an unknown one, is not taken for client code");

        // Static: a server entrypoint that reaches a client class as it initialises is client-only; one that only names it elsewhere is not.
        Path src = Files.createDirectories(scratch.resolve("client-code/src")), out = Files.createDirectories(scratch.resolve("client-code/out"));
        Files.createDirectories(src.resolve("net/minecraft"));
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("x"));
        Files.writeString(src.resolve("net/minecraft/class_437.java"), "package net.minecraft; public class class_437 { }");
        Files.writeString(src.resolve("net/minecraft/class_1937.java"), "package net.minecraft; public class class_1937 { }");
        Files.writeString(src.resolve("a/Reaches.java"),
            "package a; public class Reaches { static void after(net.minecraft.class_437 s) { } " +
            "public void onInitialize() { java.util.function.Consumer<net.minecraft.class_437> c = Reaches::after; } }");
        Files.writeString(src.resolve("a/Guarded.java"),
            "package a; public class Guarded { public void onInitialize() { new net.minecraft.class_1937(); } " +
            "public static void client() { new net.minecraft.class_437(); } }");
        Files.writeString(src.resolve("x/V.java"), "package x; public class V { public V(java.util.jar.Manifest m) { } }");
        Files.writeString(src.resolve("x/Caller.java"), "package x; public class Caller { Object make() { return new V(new java.util.jar.Manifest()); } }");
        List<String> javac = new ArrayList<>(List.of("-d", out.toString()));
        try (var walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> javac.add(p.toString()));
        }
        expect(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, javac.toArray(String[]::new)) == 0, "client-code fixtures compile");
        java.util.function.Predicate<String> game = new PackLoaders.GameClasses(
            Set.of("net/minecraft/class_1937"),
            Set.of("net/minecraft/class_1937", "net/minecraft/class_437")
        )::clientOnly;
        Path reaches = jar(scratch.resolve("reaches.jar"), Map.of(
            "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"reaches\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"a.Reaches\"]}}",
            "a/Reaches.class", out.resolve("a/Reaches.class")
        ));
        Path guarded = jar(scratch.resolve("guarded.jar"), Map.of(
            "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"guarded\",\"version\":\"1\",\"entrypoints\":{\"main\":[{\"adapter\":\"default\",\"value\":\"a.Guarded\"}]}}",
            "a/Guarded.class", out.resolve("a/Guarded.class")
        ));
        String why = PackMods.clientCode(reaches, game);
        expect(why != null && why.contains("main entrypoint a.Reaches") && why.contains("net/minecraft/class_437"), "an entrypoint whose onInitialize reaches Screen is client-only: " + why);
        expect(PackMods.clientCode(guarded, game) == null, "a client class named only in a method the server never runs is not client-only");
        List<PackCheck.Mod> sided = PackCheck.clientCode(
            List.of(new PackCheck.Mod("reaches.jar", reaches, "unknown", "folder"), new PackCheck.Mod("guarded.jar", guarded, "unknown", "folder")),
            new PackLoaders.GameClasses(Set.of("net/minecraft/class_1937"), Set.of("net/minecraft/class_1937", "net/minecraft/class_437"))
        );
        expect(
            sided.get(0).side().equals("client") && sided.get(0).source().contains("reaches client-only game classes") && sided.get(1).side().equals("unknown"),
            "a side-unknown jar is client-only by its code: " + sided
        );
        expect(PackLoaders.classMap(writeText(scratch.resolve("i.tiny"), "tiny\t2\t0\tofficial\tintermediary\nc\tdyn\tnet/minecraft/class_437\n\tm\t()V\ta\tmethod_1\n"))
            .equals(Map.of("dyn", "net/minecraft/class_437")), "Tiny v2 classes map official to intermediary names");
        expect(PackLoaders.classMap(writeText(scratch.resolve("j.tsrg"), "a net/minecraft/util/A\n\tb func_1_a\nc/ net/minecraft/\n"))
            .equals(Map.of("a", "net/minecraft/util/A")), "TSRG classes map official to Forge's names");

        // The Java build: the loader's jars call a JDK member; a Java whose class lacks it needs the rule's release.
        String call = "x/V.<init>:(Ljava/util/jar/Manifest;)V";
        Path libraries = Files.createDirectories(scratch.resolve("loader/libraries/cpw/mods/modlauncher/8.0.9"));
        jar(libraries.resolve("modlauncher-8.0.9.jar"), Map.of("x/Caller.class", out.resolve("x/Caller.class")));
        expect(PackLoaders.caller(scratch.resolve("loader/libraries/cpw/mods/modlauncher"), call) != null, "the loader's jars are read for the JDK call");
        expect(PackLoaders.caller(scratch.resolve("loader/libraries/cpw/mods/modlauncher"), "x/V.<init>:(Ljava/util/jar/Manifest;Ljava/lang/String;)V") == null, "only that exact call");
        Path oldJdk = Files.createDirectories(scratch.resolve("jdk-old/jre/lib")), newJdk = Files.createDirectories(scratch.resolve("jdk-new/jre/lib"));
        jar(oldJdk.resolve("rt.jar"), Map.of("x/V.class", out.resolve("x/V.class")));
        Files.writeString(src.resolve("x/V.java"), "package x; public class V { public V(java.util.jar.Manifest m, String name) { } }");
        Path newOut = Files.createDirectories(scratch.resolve("client-code/new"));
        expect(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", newOut.toString(), src.resolve("x/V.java").toString()) == 0, "new V compiles");
        jar(newJdk.resolve("rt.jar"), Map.of("x/V.class", newOut.resolve("x/V.class")));
        expect(PackLoaders.jdkDeclares(scratch.resolve("jdk-old"), call) && !PackLoaders.jdkDeclares(scratch.resolve("jdk-new"), call), "a JDK's rt.jar says whether it has the member");
        var rule = Json.object(Json.array(PackLoaders.data(harness).get("javaBuilds")).get(0));
        expect(
            rule.get("call").equals("sun/security/util/ManifestEntryVerifier.<init>:(Ljava/util/jar/Manifest;)V") && rule.get("release").equals("jdk8u312-b07"),
            "Forge 1.16.5's old ModLauncher runs on Temurin 8u312, the last Java 8 with ManifestEntryVerifier(Manifest)"
        );
        String answer = "{\"release_name\":\"jdk8u312-b07\",\"binaries\":[{\"package\":{\"link\":\"https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u312-b07/a.zip\"," +
            "\"checksum\":\"" + "d".repeat(64) + "\",\"checksum_link\":\"https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u312-b07/a.zip.sha256.txt\"}}]}";
        var asset = JdkDownloads.releaseAsset(answer, "jdk8u312-b07", "windows/x64");
        expect(asset.sha256().equals("d".repeat(64)) && asset.checksumFile().toString().endsWith(".sha256.txt"), "a pinned release comes with the vendor's checksum and checksum file");
        expect(
            throwsMessage(() -> JdkDownloads.releaseAsset(answer.replace("https://github.com", "http://example.com"), "jdk8u312-b07", "windows/x64")).contains("No published"),
            "a release download off Adoptium's or GitHub's https is refused"
        );
    }
}
