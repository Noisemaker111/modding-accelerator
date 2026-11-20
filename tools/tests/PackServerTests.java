import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Which jars a pack's server gets, and how each loader in pack-loaders.json starts it: metadata by game version,
 * launch arguments, the probe's game layer.
 * Needs no game and no network (mmt.pack.offline; mod sides are pre-cached).
 */
public final class PackServerTests extends PackCheckFixtures {

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("mmt-pack");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("mmt.pack.offline", "true");
        try {
            serverMods(harness, scratch);
            loaders(harness, scratch);
            fingerprintVerdict(scratch);
            clientLaunch(scratch);
            agentSignals(harness, scratch);
            System.out.println(
                count +
                " pack server checks passed: which jars a server gets (client-side jars a kept mod requires, dependants of client-only ones, a --focus set)," +
                " a library jar's bundled mods, four loader adapters, --fingerprint's verdict (a failed recording fails; a reload change is a note)," +
                " the client launch built from a loader's version file over the game's (one version of a library, this system's rules, the agent built on the launch's ASM)," +
                " the client agent's ready and failed signals whatever the game does to its output, a fatal loading line ends the wait"
            );
        } finally {
            System.setProperty("user.home", home);
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ the client launch
    /**
     * pack-check --side client builds the launch from the loader's version file over the game's, as a launcher does: a
     * library both list is on the class path once, in the loader's version (two ASM builds stop NeoForge's start), a library
     * or argument for another system or a launcher feature is left out, values are filled, and the loader's main class runs.
     */
    static void clientLaunch(Path scratch) throws Exception {
        Path install = scratch.resolve("client-install"), instance = scratch.resolve("client-instance");
        String other = PackClient.OS.equals("windows") ? "osx" : "windows";
        Json.save(install.resolve("versions/1.21.2/1.21.2.json"), Json.map(
            "id", "1.21.2", "mainClass", "net.minecraft.client.main.Main",
            "assetIndex", Json.map("id", "18"),
            "libraries", List.of(
                library("org.ow2.asm:asm:9.3", null),
                library("org.lwjgl:lwjgl:3.3.3", null),
                library("org.lwjgl:lwjgl:3.3.3:natives-" + other, List.of(Json.map("action", "allow", "os", Json.map("name", other)))),
                library("org.lwjgl:lwjgl:3.3.3:natives-" + PackClient.OS, List.of(Json.map("action", "allow", "os", Json.map("name", PackClient.OS))))
            ),
            "arguments", Json.map(
                "jvm", List.of(Json.map("rules", List.of(Json.map("action", "allow", "os", Json.map("name", other))), "value", "-XstartOnFirstThread"),
                    "-Djava.library.path=${natives_directory}", "-cp", "${classpath}"),
                "game", List.of("--gameDir", "${game_directory}", "--assetIndex", "${assets_index_name}",
                    Json.map("rules", List.of(Json.map("action", "allow", "features", Json.map("is_demo_user", true))), "value", "--demo"))
            )
        ));
        Json.save(install.resolve("versions/loader-1/loader-1.json"), Json.map(
            "id", "loader-1", "inheritsFrom", "1.21.2", "mainClass", "loader.Boot",
            // Fabric's launcher profile names a library by its maven coordinates under its repository, with no downloads entry.
            "libraries", List.of(library("org.ow2.asm:asm:9.10.1", null), Json.map("name", "net.fabricmc:fabric-loader:0.16.7", "url", "https://maven.fabricmc.net/")),
            "arguments", Json.map("jvm", List.of("-DlibraryDirectory=${library_directory}"), "game", List.of("--launchTarget", "client"))
        ));
        Files.write(install.resolve("versions/loader-1/loader-1.jar"), new byte[0]);
        List<String> command = PackClient.command(install, "1.21.2", Path.of("java"), instance, List.of("-Xmx1G"));
        String classpath = command.get(command.indexOf("-cp") + 1);
        String[] maven = PackClient.artifact(Json.map("name", "net.fabricmc:fabric-loader:0.16.7", "url", "https://maven.fabricmc.net/"));
        expect(maven[1].equals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.7/fabric-loader-0.16.7.jar")
                && classpath.contains(Path.of("net/fabricmc/fabric-loader/0.16.7/fabric-loader-0.16.7.jar").toString()),
            "a library named only by maven coordinates is downloaded from its repository and on the class path: " + classpath);
        expect(classpath.endsWith(install.resolve("versions/loader-1/loader-1.jar").toString()),
            "the game's jar is on the class path under the name of the version started, as a launcher puts it: " + classpath);
        // The table mmt's agent reads a game in intermediary with: Mojang's names joined to Fabric's through the obfuscated ones.
        String table = PackClient.runtimeTable(
            "net.minecraft.client.Minecraft -> fgo:\n    net.minecraft.client.Options options -> m\n    1:3:void runTick(boolean):10:12 -> f\n"
                + "    net.minecraft.client.Minecraft getInstance() -> Q\nnet.minecraft.client.Options -> fgq:\n",
            "tiny\t2\t0\tofficial\tintermediary\nc\tfgo\tnet/minecraft/class_310\n\tf\tLfgq;\tm\tfield_1690\n\tm\t(Z)V\tf\tmethod_1523\n"
                + "\tm\t()Lfgo;\tQ\tmethod_1551\n\t\tp\t1\t\tflag\nc\tfgq\tnet/minecraft/class_315\n");
        expect(table.contains("c\tnet/minecraft/client/Minecraft\tnet/minecraft/class_310\n") && table.contains("f\tnet/minecraft/client/Minecraft\toptions\tfield_1690\n")
                && table.contains("m\tnet/minecraft/client/Minecraft\trunTick\t(Z)V\tmethod_1523\n")
                && table.contains("m\tnet/minecraft/client/Minecraft\tgetInstance\t()Lnet/minecraft/client/Minecraft;\tmethod_1551\n"),
            "each class, field and method gets its run-time name, members matched by their obfuscated descriptor: " + table);
        expect(classpath.contains("asm-9.10.1.jar") && !classpath.contains("asm-9.3.jar"), "a library both version files list is on the class path once, the loader's: " + classpath);
        // NeoForge 1.21.1's installer leaves its own ASM 9.3 beside the loader's 9.10.1, and "asm-9.3.jar" sorts after
        // "asm-9.10.1.jar": an agent built on 9.3 cannot read Java 21's classes, never hooks a frame, and the check waited 15 minutes.
        for (String version : List.of("9.3", "9.10.1")) {
            Path jar = install.resolve("libraries/org/ow2/asm/asm/" + version + "/asm-" + version + ".jar");
            Files.createDirectories(jar.getParent());
            Files.write(jar, new byte[0]);
        }
        Path asm = PackClient.asm(install, "1.21.2");
        expect(asm.getFileName().toString().equals("asm-9.10.1.jar"), "the agent is built on the ASM the launch runs, not another one on disk: " + asm);
        expect(classpath.contains("natives-" + PackClient.OS) && !classpath.contains("natives-" + other), "a library for another system is left out: " + classpath);
        expect(!command.contains("-XstartOnFirstThread") && !command.contains("--demo"), "an argument for another system or a launcher feature is left out: " + command);
        int main = command.indexOf("loader.Boot");
        expect(main > command.indexOf("-Xmx1G") && main > command.indexOf("-cp") && main < command.indexOf("--launchTarget"), "options, then the loader's main class, then the game's arguments: " + command);
        expect(command.contains(instance.toString()) && command.contains("18") && command.stream().noneMatch(a -> a.contains("${")), "every ${value} is filled: " + command);
    }

    // ------------------------------------------------------------------ the client agent's signals
    static final String GAME = """
        package net.minecraft.client;
        public class Minecraft {
            static final Minecraft INSTANCE = new Minecraft();
            public final Options options = new Options();
            public static Minecraft getInstance() { return INSTANCE; }
            public void runTick(boolean render) { }
        }
        """;
    static final String OPTIONS = "package net.minecraft.client; public class Options { public boolean pauseOnLostFocus = true; }";
    /** A game whose frame method the agent does not know: the agent cannot hook it. */
    static final String UNHOOKABLE = """
        package net.minecraft.client;
        public class Minecraft {
            static final Minecraft INSTANCE = new Minecraft();
            public static Minecraft getInstance() { return INSTANCE; }
            public void tick() { }
        }
        """;
    /**
     * Takes over the game's output the way Crash Assistant does (System.out and System.err replaced, and the streams under
     * the old ones pointed at a sink), then runs frames until the agent has answered in its state folder.
     */
    static final String RUN = """
        import java.io.*;
        import java.lang.reflect.Field;
        import java.nio.file.*;
        public class Run {
            public static void main(String[] a) throws Exception {
                OutputStream sink = OutputStream.nullOutputStream();
                Field out = FilterOutputStream.class.getDeclaredField("out");
                out.setAccessible(true);
                for (PrintStream stream : new PrintStream[] { System.out, System.err }) {
                    OutputStream inner = stream;
                    while (out.get(inner) instanceof FilterOutputStream next) inner = next;
                    out.set(inner, sink);
                }
                System.setOut(new PrintStream(sink));
                System.setErr(new PrintStream(sink));
                Path state = Path.of(a[0]);
                net.minecraft.client.Minecraft game = net.minecraft.client.Minecraft.getInstance();
                for (int frame = 0; frame < 3000 && !Files.exists(state.resolve("agent.json")) && !Files.exists(state.resolve("agent-failed.txt")); frame++) {
                    for (java.lang.reflect.Method tick : game.getClass().getMethods()) if (tick.getName().equals("runTick")) tick.invoke(game, true);
                    Thread.sleep(10);
                }
            }
        }
        """;

    /**
     * pack-check's client waits on mmt's real agent in a game that takes over its output: the agent's agent.json, and its
     * MMT_READY on the process's own standard output, still arrive; a game the agent cannot hook ends the wait at once with
     * the agent's reason; a line where the loader says loading failed ends it too.
     */
    static void agentSignals(Path harness, Path scratch) throws Exception {
        Path dir = scratch.resolve("agent-signals");
        Path agent = Game.agentJar(harness, harness.resolve("tools/perf/lib/asm-9.9.jar"), dir.resolve("agents"), dir, new Mmt.Report(dir, "pack-check"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        Map<String, Map<String, String>> games = Map.of(
            "hookable", Map.of("net/minecraft/client/Minecraft.java", GAME, "net/minecraft/client/Options.java", OPTIONS, "Run.java", RUN),
            "unhookable", Map.of("net/minecraft/client/Minecraft.java", UNHOOKABLE, "Run.java", RUN)
        );
        String vm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Map<String, String> output = new HashMap<>();
        Map<String, Object> outcome = new HashMap<>();
        for (var game : games.entrySet()) {
            Path classes = dir.resolve(game.getKey()), state = Files.createDirectories(dir.resolve(game.getKey() + "-state"));
            List<String> arguments = new ArrayList<>(List.of("-nowarn", "-d", classes.toString()));
            for (var source : game.getValue().entrySet()) {
                Path file = dir.resolve("src-" + game.getKey()).resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                arguments.add(file.toString());
            }
            expect(compiler.run(null, null, null, arguments.toArray(String[]::new)) == 0, "the stand-in game compiles: " + game.getKey());
            String token = UUID.randomUUID().toString();
            try (var wait = new PackClient.AgentWait(state, token)) {
                Process run = new ProcessBuilder(vm, "--add-opens", "java.base/java.io=ALL-UNNAMED", "-javaagent:" + agent,
                    "-Dmmt.token=" + token, "-Dmmt.state=" + state, "-Dmmt.fingerprint=test", "-cp", classes.toString(), "Run", state.toString())
                    .redirectErrorStream(true).start();
                output.put(game.getKey(), new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                run.waitFor();
                try {
                    outcome.put(game.getKey(), wait.ready.get(30, java.util.concurrent.TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException failed) {
                    outcome.put(game.getKey(), failed.getCause());
                } catch (java.util.concurrent.TimeoutException none) {
                    outcome.put(game.getKey(), "no answer");
                }
            }
        }
        expect(outcome.get("hookable") instanceof Map<?, ?> ready && "test".equals(ready.get("fingerprint")),
            "the agent's agent.json ends the wait in a game that took over its output: " + outcome.get("hookable") + "\n" + output.get("hookable"));
        expect(output.get("hookable").contains("MMT_READY "), "MMT_READY reaches the process's standard output past a replaced System.out: " + output.get("hookable"));
        expect(outcome.get("unhookable") instanceof PackClient.AgentFailed failed && failed.getMessage().contains("no render hook"),
            "a game the agent cannot hook ends the wait with the agent's reason: " + outcome.get("unhookable") + "\n" + output.get("unhookable"));
        List<String> failedWords = List.of("Loading errors encountered");
        expect(PackClient.loadingFailure("[11:51:11] [Render thread/FATAL] [ne.ne.fm.ModLoader/CORE]: Error during pre-loading phase: Mod clumps requires minecraft 1.21.4", failedWords) != null
                && PackClient.loadingFailure("[11:51:12] [Render thread/ERROR] [ne.ne.fm.ModLoader/]: Loading errors encountered", failedWords) != null,
            "a line logged at FATAL, or one of the loader's failure words, ends the client's wait");
        expect(PackClient.loadingFailure("[11:51:11] [Render thread/WARN] [minecraft/Pack]: Missing metadata in pack programmer_art", failedWords) == null
                && PackClient.loadingFailure("[main/FATAL] [cpw.mods.modlauncher.RuntimeDistCleaner/DISTXFORM]: Attempted to load class net/minecraft/client/A for invalid dist DEDICATED_SERVER", failedWords) == null,
            "a warning, or Forge's FATAL for a class a mixin only probed for, does not end it");
    }

    static Map<String, Object> library(String name, List<?> rules) {
        String[] p = name.split(":");
        String path = p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
        Map<String, Object> library = Json.map("name", name, "downloads", Json.map("artifact", Json.map("path", path, "url", "https://example.invalid/" + path)));
        if (rules != null) library.put("rules", rules);
        return library;
    }

    // ------------------------------------------------------------------ --fingerprint's verdict
    /** A fingerprint pack-check could not record fails the check; a recorded world that changed on reload is only noted. */
    static void fingerprintVerdict(Path scratch) throws Exception {
        Path clean = scratch.resolve("clean.json"), changed = scratch.resolve("changed.json"), probeError = scratch.resolve("probe-error.json");
        Path reloadError = scratch.resolve("reload-error.json");
        Json.save(clean, Json.map("checkpoints", List.of(), "roundTrip", Json.map("same", true, "differences", 0)));
        Json.save(changed, Json.map("roundTrip", Json.map("same", false, "differences", 15, "changed", Json.map("levels/x/blockEntities/1,2,3", "{}"))));
        Json.save(probeError, Json.map("error", "The world did not advance"));
        Json.save(reloadError, Json.map("roundTrip", Json.map("same", true), "reloadError", "The saved area did not load again in 300 s"));
        Map<String, Map<String, Object>> problems = new LinkedHashMap<>();
        problems.put("a start that wrote no record", Json.map("ok", false, "error", "The fingerprint start wrote no record"));
        problems.put("a record that is missing", Json.map("ok", true, "out", scratch.resolve("absent.json").toString()));
        problems.put("a probe error in the record", Json.map("ok", true, "out", probeError.toString()));
        problems.put("a reload that did not read the world back", Json.map("ok", true, "out", reloadError.toString()));
        problems.put("no answer at all", null);
        for (var entry : problems.entrySet()) {
            List<Object> found = PackCheck.fingerprintFindings(entry.getValue());
            expect(
                found.size() == 1 && "problem".equals(Json.object(found.get(0)).get("severity")) && "fingerprint".equals(Json.object(found.get(0)).get("kind")),
                entry.getKey() + " fails pack-check: " + found
            );
        }
        List<Object> noted = PackCheck.fingerprintFindings(Json.map("ok", true, "out", changed.toString()));
        expect(
            noted.size() == 1 && "note".equals(Json.object(noted.get(0)).get("severity")) && "fingerprint-round-trip".equals(Json.object(noted.get(0)).get("kind")),
            "a recorded world that changed across its reload is a note, not a failure to record: " + noted
        );
        expect(PackCheck.fingerprintFindings(Json.map("ok", true, "out", clean.toString())).isEmpty(), "a clean recording adds nothing: " + clean);
    }

    // ------------------------------------------------------------------ which jars a server gets
    static void serverMods(Path harness, Path scratch) throws Exception {
        // Prominence II (Fabric 1.20.1, from the pack sweep): Chipped needs Athena, Simply Swords needs Simply Tooltips and
        // Forge Config Screens needs Mod Menu, each marked client-side by Modrinth, CurseForge or its own environment. Leaving
        // them out stopped the server ("requires athena, which is missing"); kept, Fabric decides (it keeps an
        // environment-client jar out of the game and treats a dependency on it as met).
        Path fabricMods = Files.createDirectories(scratch.resolve("sides-fabric/mods"));
        List<PackCheck.Mod> fabric = new ArrayList<>();
        for (String[] m : List.of(
            new String[] { "chipped.jar", "unknown", "{\"id\":\"chipped\",\"version\":\"3.0.7\",\"depends\":{\"athena\":\">=3.1.1\",\"minecraft\":\">=1.20.1\"}}" },
            new String[] { "athena.jar", "client", "{\"id\":\"athena\",\"version\":\"3.1.2\"}" },
            new String[] { "simplyswords.jar", "both", "{\"id\":\"simplyswords\",\"version\":\"1.70.2\",\"depends\":{\"simplytooltips\":\"*\"}}" },
            new String[] { "simplytooltips.jar", "client", "{\"id\":\"simplytooltips\",\"version\":\"0.1.5\",\"depends\":{\"fzzy_config\":\"*\"}}" },
            new String[] { "fzzy.jar", "client", "{\"id\":\"fzzy_config\",\"version\":\"0.6.6\"}" },
            new String[] { "forgeconfigscreens.jar", "unknown", "{\"id\":\"forgeconfigscreens\",\"version\":\"8\",\"depends\":{\"modmenu\":\"*\"}}" },
            new String[] { "modmenu.jar", "unknown", "{\"id\":\"modmenu\",\"version\":\"7.2.2\",\"environment\":\"client\"}" },
            new String[] { "sodium.jar", "client", "{\"id\":\"sodium\",\"version\":\"0.5\"}" }
        )) fabric.add(new PackCheck.Mod(m[0], jar(fabricMods.resolve(m[0]), Map.of("fabric.mod.json", m[2])), m[1], "test"));
        PackMods.Scan fabricScan = PackMods.scan(fabric, List.of("fabric.mod.json"));
        fabric = PackCheck.sides(fabric, fabricScan);
        PackCheck.ServerMods kept = PackCheck.serverMods(fabric, fabricScan, Set.of(), List.of());
        PackMods.Scan chippedAlone = PackMods.scan(fabric.subList(0, 1), List.of("fabric.mod.json"));
        expect(
            PackRepairs.dependencyMissing(Map.of("mod", "chipped", "dependency", "athena"), chippedAlone).contains("requires athena >=3.1.1, which no jar") &&
            PackRepairs.dependencyMissing(Map.of("mod", "chipped", "dependency", "athena"), fabricScan) == null,
            "the dependency-missing repair match holds only while no jar provides the dependency"
        );
        expect(
            kept.kept().containsAll(List.of("chipped.jar", "athena.jar", "simplyswords.jar", "simplytooltips.jar", "fzzy.jar", "forgeconfigscreens.jar", "modmenu.jar")) &&
            kept.leftOut().keySet().equals(Set.of("sodium.jar")) &&
            kept.required().get("athena.jar").equals("chipped (chipped.jar) requires athena") &&
            kept.required().get("fzzy.jar").equals("simplytooltips (simplytooltips.jar) requires fzzy_config"),
            "a client-side jar a kept mod requires is kept, through its own dependencies; one nothing requires is left out: " + kept
        );

        // Optimized FPS (Forge 1.16.5, from the pack sweep): Embeddium Extension needs Embeddium, which Modrinth marks
        // client-only. Kept, Embeddium is tried; once a start shows it reaching client-only code, it and every mod that
        // requires it (here, through a second mod) are left out.
        Path forgeMods = Files.createDirectories(scratch.resolve("sides-forge/mods"));
        List<PackCheck.Mod> forge = List.of(
            new PackCheck.Mod("embeddium.jar", jar(forgeMods.resolve("embeddium.jar"), Map.of("META-INF/mods.toml", toml("embeddium", "0.3.18", ""))), "client", "test"),
            new PackCheck.Mod(
                "embeddiumextension.jar",
                jar(forgeMods.resolve("embeddiumextension.jar"), Map.of("META-INF/mods.toml", toml("embeddiumextension", "0.1.1", dependency("embeddium", "true", "[0.1.14,)")))),
                "unknown",
                "test"
            ),
            new PackCheck.Mod(
                "addon.jar",
                jar(forgeMods.resolve("addon.jar"), Map.of("META-INF/mods.toml", toml("addon", "1", dependency("embeddiumextension", "true", "[0,)")))),
                "unknown",
                "test"
            ),
            new PackCheck.Mod("create.jar", jar(forgeMods.resolve("create.jar"), Map.of("META-INF/mods.toml", toml("create", "6", dependency("forge", "true", "[47,)")))), "both", "test")
        );
        PackMods.Scan forgeScan = PackMods.scan(forge, List.of("META-INF/mods.toml"));
        PackCheck.ServerMods tried = PackCheck.serverMods(forge, forgeScan, Set.of(), List.of());
        expect(
            tried.kept().equals(Set.of("embeddium.jar", "embeddiumextension.jar", "addon.jar", "create.jar")) && tried.required().containsKey("embeddium.jar"),
            "Forge: a client-side library a kept mod requires is tried on the server: " + tried
        );
        PackCheck.ServerMods shown = PackCheck.serverMods(forge, forgeScan, Set.of("embeddium.jar"), List.of());
        expect(
            shown.kept().equals(Set.of("create.jar")) &&
            shown.leftOut().get("embeddiumextension.jar").equals("it requires embeddium, and embeddium.jar cannot run on a server") &&
            shown.leftOut().get("addon.jar").startsWith("it requires embeddiumextension") &&
            shown.leftOut().get("embeddium.jar").contains("client-only code"),
            "a mod whose required mod cannot run on a server is left out too, through its dependants: " + shown
        );
        expect(
            PackCheck.serverMods(forge, forgeScan, Set.of(), List.of("embeddiumextension.jar")).kept().equals(Set.of("create.jar", "addon.jar")),
            "a jar a repair removes is not on the server, and nothing is kept for it: " + PackCheck.serverMods(forge, forgeScan, Set.of(), List.of("embeddiumextension.jar"))
        );
        List<String> focused = PackCheck.focus(forge, forgeScan, List.of("addon")).stream().map(PackCheck.Mod::file).toList();
        expect(
            focused.equals(List.of("embeddium.jar", "embeddiumextension.jar", "addon.jar")) &&
            PackCheck.focus(forge, forgeScan, List.of("create")).stream().map(PackCheck.Mod::file).toList().equals(List.of("create.jar")),
            "--focus keeps the named mod and its required mods through theirs, and nothing else: " + focused
        );
        boolean refused = false;
        try {
            PackCheck.focus(forge, forgeScan, List.of("nosuchmod"));
        } catch (IllegalArgumentException e) {
            refused = e.getMessage().contains("nosuchmod");
        }
        expect(refused, "--focus refuses an id no jar of the pack provides");

        // Create+ (Forge 1.19.2, from the pack sweep): Kotlin for Forge 3.12 is a LIBRARY jar whose mod is in its jarjar list.
        Path kffMod = jar(scratch.resolve("kffmod.jar"), Map.of("META-INF/mods.toml", toml("kotlinforforge", "3.12.0", "")));
        Path kff = jar(
            forgeMods.resolve("kotlinforforge-3.12.0-all.jar"),
            Map.of(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nFMLModType: LIBRARY\n",
                "META-INF/jarjar/metadata.json",
                "{\"jars\":[{\"identifier\":{\"group\":\"thedarkcolour\",\"artifact\":\"kffmod\"},\"path\":\"META-INF/jarjar/kffmod-3.12.0.jar\"}]}",
                "META-INF/jarjar/kffmod-3.12.0.jar",
                kffMod
            )
        );
        PackMods.Scan kotlin = PackMods.scan(List.of(new PackCheck.Mod("kotlinforforge-3.12.0-all.jar", kff, "unknown", "test")), List.of("META-INF/mods.toml"));
        expect(
            kotlin.providers.containsKey("kotlinforforge") && kotlin.plugins.contains("kotlinforforge-3.12.0-all.jar"),
            "a loader library's mods in its jarjar list are read: " + kotlin.providers.keySet()
        );
    }

    // ------------------------------------------------------------------ loaders
    static void loaders(Path harness, Path scratch) throws Exception {
        var all = PackLoaders.all(harness);
        expect(all.keySet().equals(new LinkedHashSet<>(List.of("forge", "neoforge", "fabric", "quilt"))), "pack-loaders.json has the four loaders: " + all.keySet());
        PackLoaders.Loader neo = all.get("neoforge");
        expect(
            neo.metadata("1.21.1").equals(List.of("META-INF/neoforge.mods.toml")) && neo.metadata("1.20.4").equals(List.of("META-INF/mods.toml")),
            "NeoForge reads neoforge.mods.toml from 1.20.5 and mods.toml before"
        );
        expect(all.get("quilt").metadata("1.21.1").equals(List.of("quilt.mod.json", "fabric.mod.json")), "Quilt reads its own metadata, then Fabric's");
        // Found by the pack sweep (SodiumPlus, Quilt 1.20.1): QSL modules need quilt_base >=6.1.2+1.20.1 and the pack has
        // 6.1.2+1.20.1. Fabric and Quilt compare SemVer without build metadata, on both sides.
        PackMods.Dependency qsl = new PackMods.Dependency("block_content_registry", "quilt_base", List.of(">=6.1.2+1.20.1"), "quilt.mod.json", "depends");
        // From the pack sweep (Additive, Fabric 1.19.2): the world opened but the probe stopped before placing blocks; the
        // placement check names that as unchecked instead of failing pack-check.
        List<Object> stopped = new ArrayList<>();
        PackChecks.Context placed = new PackChecks.Context(null, new PackMods.Scan(), PackLoaders.all(harness).get("fabric"), stopped);
        placed.start = Map.of("opened", true, "probe", Map.of("error", "java.lang.IllegalStateException: no way to make a player\n\tat x.y", "ok", false));
        PackChecks.placements(placed);
        expect(
            stopped.size() == 1 &&
            "not-checked".equals(Json.object(stopped.get(0)).get("kind")) &&
            "note".equals(Json.object(stopped.get(0)).get("severity")),
            "a probe that stopped before placing is a note that placement went unchecked: " + stopped
        );
        // Optimized FPS (Forge 1.16.5, from the pack sweep): a probe that failed before reading the world wrote only its error.
        List<Object> early = new ArrayList<>();
        PackChecks.Context failed = new PackChecks.Context(null, new PackMods.Scan(), PackLoaders.all(harness).get("forge"), early);
        failed.start = Map.of("opened", true, "probe", Map.of("error", "java.lang.NoSuchMethodError: x", "ok", false));
        PackChecks.dimensions(failed);
        PackChecks.changedBySave(failed);
        PackChecks.placements(failed);
        expect(
            early.size() == 1 && "not-checked".equals(Json.object(early.get(0)).get("kind")),
            "a probe result with only its error is a gap of the check, not a pack-check crash: " + early
        );
        // SodiumPlus (Quilt 1.20.1, the pack sweep): QFAPI accepts Minecraft {"all": [">=1.20-", "<1.20.2-"]}, which 1.20.1 satisfies.
        List<String> allOf = PackMods.quiltRanges(Map.of("all", List.of(">=1.20-", "<1.20.2-")));
        PackMods.Dependency qfapi = new PackMods.Dependency("qfapi", "minecraft", allOf, "quilt.mod.json", "required");
        expect(
            allOf.equals(List.of(">=1.20- <1.20.2-")) &&
            PackMods.accepts(qfapi, "1.20.1") &&
            !PackMods.accepts(qfapi, "1.20.2") &&
            PackMods.quiltRanges(Map.of("any", List.of("1.20", "1.20.1"))).equals(List.of("1.20", "1.20.1")),
            "a Quilt range object: all of its constraints, or any of its alternatives: " + allOf
        );
        // And on Vanilla Perfected 1.0.0+1.21.1 (an independent check): an exact citresewn 1.2.1+1.21, and YACL >=3.5.0+1.21-fabric.
        PackMods.Dependency cit = new PackMods.Dependency("cit-addon", "citresewn", List.of("1.2.1+1.21"), "fabric.mod.json", "depends");
        PackMods.Dependency yacl = new PackMods.Dependency("some-mod", "yet_another_config_lib_v3", List.of(">=3.5.0+1.21-fabric"), "fabric.mod.json", "depends");
        expect(
            PackMods.accepts(qsl, "6.1.2+1.20.1") && PackMods.accepts(qsl, "6.1.3+1.20.1") && !PackMods.accepts(qsl, "6.1.1+1.20.1") &&
            PackMods.accepts(cit, "1.2.1+1.21") && !PackMods.accepts(cit, "1.2.0+1.21") &&
            PackMods.accepts(yacl, "3.5.0+1.21-fabric") && PackMods.accepts(yacl, "3.6.1+1.21.1-fabric") && !PackMods.accepts(yacl, "3.4.4+1.21-fabric"),
            "a Fabric or Quilt range with build metadata accepts the same version with any build metadata"
        );
        Map<String, String> layers = new LinkedHashMap<>();
        for (String v : List.of("1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.20.1", "1.21.1", "1.21.4", "1.21.5", "1.21.10", "1.21.11", "26.1.2", "26.3")) layers.put(
            v,
            PackLoaders.gameLayer(harness, v).getParent().getFileName().toString()
        );
        expect(
            layers.equals(
                Map.ofEntries(
                    Map.entry("1.16.5", "1.16"),
                    Map.entry("1.17.1", "1.16"),
                    Map.entry("1.18.2", "1.18"),
                    Map.entry("1.19.2", "1.18"),
                    Map.entry("1.19.3", "1.19.3"),
                    Map.entry("1.20.1", "1.19.3"),
                    Map.entry("1.21.1", "1.19.3"),
                    Map.entry("1.21.4", "1.21.2"),
                    Map.entry("1.21.5", "1.21.5"),
                    Map.entry("1.21.10", "1.21.5"),
                    Map.entry("1.21.11", "1.21.11"),
                    Map.entry("26.1.2", "26.1"),
                    Map.entry("26.3", "26.1")
                )
            ),
            "the probe compiles with the newest game layer at or below the pack's version: " + layers
        );
        boolean refused = false;
        try {
            PackLoaders.gameLayer(harness, "1.15.2");
        } catch (IOException none) {
            refused = none.getMessage().contains("no game layer");
        }
        expect(refused, "a version older than every game layer is named, not compiled");
        expect(
            PackLoaders.neoforgeLine("1.21.1").equals("21.1") && PackLoaders.neoforgeLine("1.21").equals("21.0") && PackLoaders.neoforgeLine("26.1").equals("26.1"),
            "NeoForge's version line follows the game version"
        );
        var style = (PackLoaders.Style) neo;
        expect(
            style.setting("1.20.1", "installer").contains("net/neoforged/forge/1.20.1-") && style.setting("1.21.1", "installer").contains("net/neoforged/neoforge/"),
            "NeoForge for 1.20.1 comes from its own coordinates, other versions from neoforge's"
        );
        expect(
            PackLoaders.find(harness, "net.fabricmc.fabric-loader").id().equals("fabric") && PackLoaders.find(harness, "net.neoforged").id().equals("neoforge"),
            "loaders are found by the names packs use"
        );
        // The args-file style: the installer's argument file, its library paths made absolute.
        Path install = scratch.resolve("loader");
        Files.createDirectories(install.resolve("libraries/net/neoforged/neoforge/21.1.77"));
        String name = Runtimes.WINDOWS ? "win_args.txt" : "unix_args.txt";
        Files.writeString(install.resolve("libraries/net/neoforged/neoforge/21.1.77/" + name), "-p libraries/a.jar\n-DlibraryDirectory=libraries\n--launchTarget forgeserver\n");
        Path instance = Files.createDirectories(scratch.resolve("loader-instance"));
        List<String> launch = neo.launch(install, instance, "1.21.1", "21.1.77");
        String args = Files.readString(instance.resolve("mmt-args.txt"));
        String libraries = install.resolve("libraries").toString().replace('\\', '/');
        expect(
            launch.equals(List.of("@" + instance.resolve("mmt-args.txt"))) && args.contains("-p " + libraries + "/a.jar") && args.contains("-DlibraryDirectory=" + libraries),
            "the args file's library paths are made absolute: " + args
        );
        // The launch-jar style: the launcher is told where the vanilla server is.
        List<String> fabricLaunch = all.get("fabric").launch(install, instance, "1.21.1", "0.16.7");
        expect(
            fabricLaunch.equals(List.of("-jar", install.resolve("fabric-server-launch.jar").toString())) &&
            Files.readString(instance.resolve("fabric-server-launcher.properties")).equals("serverJar=" + install.resolve("server.jar").toAbsolutePath().toString().replace('\\', '/') + "\n"),
            "Fabric's launcher is pointed at the installed vanilla server"
        );
    }
}
