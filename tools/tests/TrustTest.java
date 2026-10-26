import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Verdicts that used to come from modaccel's own guess. Each case is one the old code got wrong:
 * a game check passed with the mod refused, a frame change counted as the mod's behaviour, a
 * Maven range was read as text, an Architectury NeoForge project was called Fabric, pre-releases
 * sorted after their release, the own compile looked like Gradle's, and a newer-Loom dependency
 * was skipped on a guess.
 */
public final class TrustTest {
    static int count;
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("modaccel-trust");
        try {
            modsLoaded(scratch);
            behaviour(scratch);
            mavenRanges(scratch);
            architecturyLoader();
            preReleases();
            ownCompile();
            newerLoom();
            System.out.println(count + " trust checks passed: loader mod list, behaviour from the mod only, Maven ranges, loader from the loader artifact, pre-release order, own compile labelled, newer-Loom warning");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    /** ZombieApocalypseAddon's 1.21.4 client listed "0 mods" and ui-check passed. Now the loader's own list decides. */
    static void modsLoaded(Path scratch) throws Exception {
        Path root = scratch.resolve("zombie"), resources = root.resolve("build/resources/main"), classes = root.resolve("build/classes/java/main"), test = root.resolve("build/resources/test");
        Files.createDirectories(resources.resolve("META-INF")); Files.createDirectories(classes); Files.createDirectories(test);
        Files.writeString(resources.resolve("META-INF/neoforge.mods.toml"), """
                modLoader="javafml" #mandatory
                loaderVersion="[1,)"
                [[mods]] #mandatory
                modId="zombieapocalypseaddon"
                version="2.8.0"
                displayName="Zombie Apocalypse Addon"
                [[dependencies.zombieapocalypseaddon]]
                modId="minecraft"
                versionRange="[1.21.1]"
                """, StandardCharsets.UTF_8);
        Files.writeString(test.resolve("fabric.mod.json"), "{\"id\":\"testonly\",\"version\":\"1\"}");
        Map<String, Object> model = Json.map("root", root.toString(), "projects", List.of(Json.map("path", ":", "sourceSets", List.of(
                Json.map("name", "main", "runtime", List.of(classes.toString(), resources.toString(), scratch.resolve("neoforge-21.4.161.jar").toString())),
                Json.map("name", "test", "runtime", List.of(test.toString()))))));
        List<Path> outputs = LoadedMods.outputs(model, LoadedMods.owner(":runClient"));
        require(outputs.equals(List.of(classes, resources)), "client outputs are the main source set's folders inside the build root, not jars or the test set: " + outputs);
        List<LoadedMods.Declared> declared = LoadedMods.declared(outputs);
        require(declared.size() == 1 && declared.get(0).id().equals("zombieapocalypseaddon") && declared.get(0).version().equals("2.8.0"), "the built neoforge.mods.toml declares the mod id and version");
        require(LoadedMods.owner(":fabric:runClient").equals(":fabric"), "a subproject's client task belongs to that subproject");

        // The real case: NeoForge refused the mod and listed only itself and the game.
        Map<String, Object> refused = LoadedMods.verdict(declared, outputs, Json.map("loader", "neoforge", "all", Json.map("minecraft", "1.21.4", "neoforge", "21.4.161")));
        require(Boolean.FALSE.equals(refused.get("ok")) && String.valueOf(refused.get("details")).contains("did not load zombieapocalypseaddon")
                && String.valueOf(refused.get("details")).contains("2 mods: minecraft 1.21.4, neoforge 21.4.161"), "a refused mod fails by name, with the loader's own list: " + refused.get("details"));
        Map<String, Object> loaded = LoadedMods.verdict(declared, outputs, Json.map("loader", "neoforge", "all", Json.map("minecraft", "1.21.4", "neoforge", "21.4.161", "zombieapocalypseaddon", "2.8.0")));
        require(Boolean.TRUE.equals(loaded.get("ok")) && String.valueOf(loaded.get("details")).contains("zombieapocalypseaddon 2.8.0"), "a loaded mod passes with its version: " + loaded.get("details"));
        Map<String, Object> other = LoadedMods.verdict(declared, outputs, Json.map("loader", "neoforge", "all", Json.map("zombieapocalypseaddon", "2.7.0")));
        require(Boolean.FALSE.equals(other.get("ok")) && String.valueOf(other.get("details")).contains("declared 2.8.0, loaded 2.7.0"), "another version of the mod is not this build");
        require(Boolean.FALSE.equals(LoadedMods.verdict(declared, outputs, Json.map("loader", "unknown", "all", Json.map())).get("ok")), "a game whose loader can't be asked fails, never passes");
        require(Boolean.FALSE.equals(LoadedMods.verdict(declared, outputs, Json.map("loader", "fabric", "all", Json.map("minecraft", "1.21.4"))).get("ok")), "no metadata for the running loader fails: which mods to expect is unknown");
        require(Boolean.FALSE.equals(LoadedMods.verdict(List.of(), List.of(), Json.map("loader", "neoforge", "all", Json.map("minecraft", "1.21.4"))).get("ok")), "a build output with no metadata fails");
        List<LoadedMods.Declared> template = List.of(new LoadedMods.Declared("fabric", "${mod_id}", "1", resources.resolve("fabric.mod.json")));
        require(Boolean.FALSE.equals(LoadedMods.verdict(template, outputs, Json.map("loader", "fabric", "all", Json.map("${mod_id}", "1"))).get("ok")), "an unexpanded id never matches");
        List<LoadedMods.Declared> jarVersion = List.of(new LoadedMods.Declared("forge", "m", "${file.jarVersion}", resources.resolve("META-INF/mods.toml")));
        Map<String, Object> idOnly = LoadedMods.verdict(jarVersion, outputs, Json.map("loader", "forge", "all", Json.map("m", "0.0NONE")));
        require(Boolean.TRUE.equals(idOnly.get("ok")) && String.valueOf(idOnly.get("details")).contains("only its id was matched"), "a version filled in from the jar at runtime is said to be unchecked");
        List<Map<String, String>> two = LoadedMods.tomlMods("[[mods]]\nmodId = 'a'\nversion=\"1\"\n[[dependencies.a]]\nmodId=\"minecraft\"\n[[mods]]\nmodId=\"b\"\n");
        require(two.size() == 2 && two.get(0).get("modId").equals("a") && two.get(1).get("modId").equals("b") && !two.get(1).containsKey("version"), "every [[mods]] table is read and dependency tables are not mods");
        require(LoadedMods.forLoader(List.of(new LoadedMods.Declared("forge", "old", "1", resources)), "neoforge").size() == 1, "NeoForge 1.20.x still reads mods.toml");
    }

    /** AppleSkin 1.21.5 -> 1.21.6 was "behaviour checked" because opening the inventory changed 95.6% of the frame. */
    static void behaviour(Path scratch) throws Exception {
        Path classes = scratch.resolve("own/build/classes/java/main");
        Files.createDirectories(classes.resolve("com/example")); Files.write(classes.resolve("com/example/Checks.class"), new byte[] {1});
        Files.write(classes.resolve("com/example/HungerScreen.class"), new byte[] {1});
        List<Path> own = List.of(classes);
        Map<String, Object> frame = Game.behaviour(own, null, null, null, true, true);
        require(Boolean.FALSE.equals(frame.get("behaviorVerified")) && String.valueOf(frame.get("behaviorBasis")).contains("frame change"), "a frame change and a save round-trip are not the mod's behaviour");
        require(Boolean.TRUE.equals(Game.behaviour(own, "com.example.Checks#saturationShown", null, null, false, false).get("behaviorVerified")), "a passed assertion in the mod's own class counts");
        require(Boolean.FALSE.equals(Game.behaviour(own, "net.minecraft.client.Minecraft#isRunning", null, null, true, false).get("behaviorVerified")), "an assertion on a game class is not the mod's behaviour");
        require(Boolean.TRUE.equals(Game.behaviour(own, null, "com.example.HungerScreen", "saturation=6", false, false).get("behaviorVerified")), "a value read from the mod's own screen counts");
        require(Boolean.FALSE.equals(Game.behaviour(own, null, "net.minecraft.client.gui.screens.inventory.InventoryScreen", "title=Crafting", false, false).get("behaviorVerified")), "a value read from a vanilla screen is not the mod's behaviour");
    }

    /** The NeoForge range was matched as text: [1.21,) refused 1.21.4 and [1.20.1,1.21.4) accepted it. */
    static void mavenRanges(Path scratch) throws Exception {
        String[][] cases = {
                {"[1.21,)", "1.21.4", "true"}, {"[1.21.1,1.22)", "1.21.4", "true"}, {"[1.20.1,1.21.4)", "1.21.4", "false"}, {"[1.21.10,)", "1.21.1", "false"},
                {"[1.21.1]", "1.21.4", "false"}, {"[1.21.4,1.21.4]", "1.21.4", "true"}, {"1.21.1", "1.21.4", "true"}, {"(,1.21.4]", "1.21.4", "true"}, {"(,1.21.4)", "1.21.4", "false"},
                {"[1.20,1.20.6],[1.21,1.21.4]", "1.21.2", "true"}, {"[1.20,1.20.6],[1.21,1.21.4]", "1.20.8", "false"}, {"[1.21.6,)", "1.21.6-pre1", "true"}, {"[26.1,)", "26.3", "true"}};
        for (String[] c : cases) require(MavenRange.own(c[0], c[1]) == Boolean.parseBoolean(c[2]), "Maven range " + c[0] + " contains " + c[1] + " is " + c[2]);
        try { MavenRange.own("[1.21", "1.21"); throw new AssertionError("an unbounded range was accepted"); } catch (IllegalArgumentException expected) { count++; }
        // The same answers from maven-artifact itself, where a Gradle cache has it (every NeoForge build fetches it).
        Path real = mavenArtifact();
        if (real != null) {
            List<Path> classpath = List.of(real);
            for (String[] c : cases) {
                MavenRange.Answer answer = MavenRange.contains(c[0], c[1], classpath);
                require(answer.decidedBy().contains("maven-artifact") && answer.contains() == Boolean.parseBoolean(c[2]), "maven-artifact agrees: " + c[0] + " contains " + c[1] + " is " + c[2] + " (" + answer.decidedBy() + ")");
            }
            String[] versions = {"1.21", "1.21.0", "1.21.4", "1.21.10", "1.21.6-pre1", "1.21.6-rc1", "1.21.6", "26.1-snapshot-1", "26.1", "1.0-alpha-1", "1.0-SNAPSHOT", "1.0-sp1", "1.0.final", "1-a1", "1.0-beta2"};
            for (String a : versions) for (String b : versions) {
                String spec = "[" + a + "]";
                require(MavenRange.contains(spec, b, classpath).contains() == MavenRange.own(spec, b), "ComparableVersion order agrees with maven-artifact for " + a + " and " + b);
            }
        } else System.out.println("note: no maven-artifact jar in this machine's Gradle cache; checked modaccel's copy of the rules only");
        require(MavenRange.contains("[1.21,)", "1.21.4", List.of()).decidedBy().contains("copy"), "without the library, the answer says who decided");

        // The port's packaged-metadata check reads the range the same way.
        Path project = scratch.resolve("architectury"), libs = project.resolve("build/libs"); Files.createDirectories(libs);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(libs.resolve("architectury-13.0.0.jar")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write("[[mods]]\nmodId=\"architectury\"\n[[dependencies.architectury]]\nmodId=\"minecraft\"\nversionRange=\"[1.21,)\"\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Map<String, Object> neo = Json.map("projects", List.of(Json.map("plugins", List.of("net.neoforged.moddev.NeoForgeModDevPlugin"))));
        Map<String, Object> accepted = Port.verifyModMetadata(project, neo, "1.21.4");
        require(Boolean.TRUE.equals(accepted.get("ok")) && Boolean.TRUE.equals(accepted.get("checked")), "Architectury's [1.21,) accepts 1.21.4: " + accepted.get("details"));
    }
    static Path mavenArtifact() throws Exception {
        Path cache = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.apache.maven/maven-artifact");
        if (!Files.isDirectory(cache)) return null;
        try (var walk = Files.walk(cache)) { return walk.filter(p -> p.getFileName().toString().matches("maven-artifact-3\\.[0-9.]+\\.jar")).findFirst().orElse(null); }
    }

    /** Architectury Loom builds NeoForge projects with net.fabricmc.loom.* plugins; they were classed as Fabric. */
    static void architecturyLoader() {
        List<String> plugins = List.of("net.fabricmc.loom.LoomRepositoryPlugin", "net.fabricmc.loom.bootstrap.LoomGradlePluginBootstrap", "dev.architectury.plugin.ArchitecturyPlugin");
        Map<String, Object> neoforge = Json.map("plugins", plugins, "dependencies", List.of(
                Json.map("configuration", "architecturyTransformerClasspath", "group", "net.fabricmc", "name", "fabric-loader"),
                Json.map("configuration", "neoForge", "group", "net.neoforged", "name", "neoforge")));
        require(Support.loaders(neoforge).equals(Set.of("neoforge")), "Architectury's :neoforge is NeoForge: " + Support.loaders(neoforge));
        Map<String, Object> fabric = Json.map("plugins", plugins, "dependencies", List.of(
                Json.map("configuration", "architecturyTransformerClasspath", "group", "net.fabricmc", "name", "fabric-loader"),
                Json.map("configuration", "modImplementation", "group", "net.fabricmc", "name", "fabric-loader")));
        require(Support.loaders(fabric).equals(Set.of("fabric")), "Architectury's :fabric is Fabric");
        require(Support.loaders(Json.map("plugins", List.of("net.minecraftforge.gradle.userdev.UserDevPlugin"), "dependencies", List.of(Json.map("configuration", "minecraft", "group", "net.minecraftforge", "name", "forge")))).equals(Set.of("forge")), "ForgeGradle is Forge");
        require(Support.loaders(Json.map("plugins", List.of("net.neoforged.moddev.NeoForgeModDevPlugin"))).equals(Set.of("neoforge")), "with no loader artifact the plugin still decides");
    }

    /** 1.21.6-pre1 sorted after 1.21.6, and 26.1-snapshot-1 after 26.1. */
    static void preReleases() {
        require(Versions.compare("1.21.6-pre1", "1.21.6") < 0 && Versions.compare("1.21.6-rc1", "1.21.6") < 0, "pre-releases and release candidates come before the release");
        require(Versions.compare("26.1-snapshot-1", "26.1") < 0 && Versions.compare("26.1-snapshot-1", "26.1-pre-1") < 0 && Versions.compare("1.21.6-pre2", "1.21.6-rc1") < 0, "snapshot, pre, rc order");
        require(Versions.compare("1.21.6-pre2", "1.21.6-pre10") < 0 && Versions.compare("1.21.6", "1.21.5") > 0 && Versions.compare("1.21", "1.21.0") == 0, "numbers still compare as numbers");
        require(Versions.compare("1.21.6-pre1", "1.21.5") > 0, "a pre-release is still after the previous release");
        require(!Versions.matches(">=1.21.6", "1.21.6-pre1") && Versions.matches(">=1.21.6-", "1.21.6-pre1") && Versions.matches(">=1.21.6-", "1.21.6"), "a trailing dash admits pre-releases, as in Fabric");
    }

    /** #22's own javac compile reported tier "compile", the same as Gradle's. */
    static void ownCompile() {
        Map<String, Object> result = OwnCompile.result(List.of(":classes"), "21.1.248", "game.jar", "universal.jar", List.of(), List.of(), Path.of("out"));
        require("own-compile".equals(result.get("tier")) && Boolean.FALSE.equals(result.get("gradleConfirmed")) && String.valueOf(result.get("details")).startsWith("Own compile"), "the own compile says it is modaccel's, not Gradle's");
    }

    /** #17 skipped a dependency release built with a newer Loom, guessing it unreadable. */
    static void newerLoom() {
        Map<String, Object> release = Json.map("version_number", "3.6.2+1.21.4-fabric");
        String warning = Dependencies.loomWarning(release, "1.11.4", "1.10-SNAPSHOT");
        require(warning != null && warning.contains("kept") && warning.contains("1.11.4") && warning.contains("1.10-SNAPSHOT"), "a newer-Loom release is kept with a warning, not skipped");
        require(Dependencies.loomWarning(release, "1.10.1", "1.10-SNAPSHOT") == null && Dependencies.loomWarning(release, "", "1.10") == null, "no warning when the Loom is not newer or unknown");
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); count++; }
}
