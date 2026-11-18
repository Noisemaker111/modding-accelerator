import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** A mod project under port: build identity, the Gradle it runs, routes between releases, target setup, undo and packaged metadata. */
public final class PortTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** Hub routing from the change index, rule ranges, the legacy SRG route across the seam both ways, and errors named by update. */
    static void routeChecks(Path home, Port port, Path scratch) throws Exception {
        Route forward = Route.plan(home, "1.20.1", "1.21.6");
        check(
            forward != null &&
            forward.waypoints.equals(
                List.of("1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6")
            ) &&
            forward.steps().size() == 12 &&
            forward.hops.size() == 8,
            "1.20.1 -> 1.21.6 routes through every family hub between, one hop each: " +
            (forward == null ? null : forward.waypoints)
        );
        Route back = Route.plan(home, "1.21.6", "1.21.1");
        check(
            back.waypoints.equals(List.of("1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1")) &&
            back.steps().get(0).equals("1.21.6 -> 1.21.5"),
            "a backport walks the same hubs in reverse"
        );
        Route late = Route.plan(home, "1.21.6", "26.3");
        check(
            late.mappedChain().get(late.mappedChain().size() - 1).equals("1.21.11") &&
            late.linkOf("1.21.11 -> 26.1").equals("by-name"),
            "a route into 26.x joins ids only up to 1.21.11 and marks the rest by-name"
        );
        Route zombie = Route.plan(home, "1.21.1", "1.21.6");
        check(
            zombie
                .stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "mojmap")
                .equals(List.of("1.21.1 -> 1.21.2", "1.21.2 -> 1.21.3", "1.21.3 -> 1.21.4")),
            "a rule runs on the updates its jump covers"
        );
        check(
            zombie.stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "yarn").isEmpty() &&
            back.stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "mojmap").isEmpty() &&
            forward.stepsCovered("1.21.6 -> 26.3 (official names)", "mojmap").isEmpty(),
            "a rule for other names, the other direction or updates not crossed does not run"
        );
        Map<String, Object> refuse = Route.blocked(home, "1.9", "1.12.2");
        check(
            Route.blocked(home, "1.12.2", "1.20.1") == null &&
            Route.blocked(home, "1.20.1", "1.12.2") == null &&
            Route.blocked(home, "1.20.1", "1.21.6") == null &&
            refuse != null &&
            "blocked".equals(refuse.get("tier")),
            "a port crossing 1.14.4 is portable either way through the bridge; only releases no indexed step touches stay blocked: " +
            (refuse == null ? null : refuse.get("details"))
        );
        Route seam = Route.plan(home, "1.12.2", "1.20.1");
        check(
            seam != null &&
            seam.crossesSeam() &&
            seam.seamChain().get(0).equals("1.12.2") &&
            seam.seamChain().get(seam.seamChain().size() - 1).equals("1.20.1") &&
            seam.steps().contains(Route.BRIDGE_STEP) &&
            Route.BRIDGE_STEP.equals(Route.FIRST_MAPPED + " -> " + Route.FIRST_MAPPED) &&
            seam.srgChain().get(seam.srgChain().size() - 1).equals("1.14.4") &&
            seam.mappedChain().get(0).equals("1.14.4"),
            "a seam route joins the SRG run, the bridge pseudo-step and the intermediary run: " +
            (seam == null ? null : seam.steps())
        );
        Route seamBack = Route.plan(home, "1.20.1", "1.12.2");
        check(
            seamBack != null &&
            seamBack.crossesSeam() &&
            seamBack.seamChain().get(0).equals("1.20.1") &&
            seamBack.seamChain().get(seamBack.seamChain().size() - 1).equals("1.12.2") &&
            seamBack.steps().contains(Route.BRIDGE_STEP) &&
            seamBack.srgChain().get(0).equals("1.14.4") &&
            seamBack.srgChain().get(seamBack.srgChain().size() - 1).equals("1.12.2"),
            "a backport walks the same chain in reverse, bridge step included: " +
            (seamBack == null ? null : seamBack.steps())
        );
        check(
            seam.stepsCovered("1.12.2 -> 1.20.1 (mcp)", "mcp").contains(Route.BRIDGE_STEP) &&
            seam.stepsCovered("1.12.2 -> 1.20.1 (mcp)", "mojmap").isEmpty(),
            "a rule spanning the seam covers the bridge step on an MCP source, nothing on a Mojang one"
        );
        Route hubs = Route.plan(home, "1.7.10", "1.14.4");
        check(
            hubs != null &&
            hubs.waypoints.equals(List.of("1.7.10", "1.8.9", "1.10.2", "1.12.2", "1.14.4")) &&
            hubs.srgChain().equals(hubs.versions) &&
            hubs.mappedChain().equals(List.of("1.14.4")) &&
            hubs.linkOf("1.12.2 -> 1.14.4").equals("srg") &&
            Route.blocked(home, "1.7.10", "1.12.2") == null &&
            Route.blocked(home, "1.12.2", "1.14.4") == null &&
            Route.blocked(home, "1.14.4", "1.12.2") == null &&
            Route.blocked(home, "1.9", "1.12.2") != null,
            "the legacy hubs route hub to hub on the SRG chain to 1.14.4; a non-hub legacy release stays blocked: " +
            (hubs == null ? null : hubs.waypoints)
        );
        check(
            hubs.stepsCovered("1.12.2 -> 1.14.4 (mojmap)", "mcp").isEmpty() &&
            hubs.stepsCovered("1.12.2 -> 1.14.4 (mcp)", "mcp").equals(List.of("1.12.2 -> 1.14.4")) &&
            hubs.stepsCovered("1.12.2 -> 1.14.4 (mcp)", "mojmap").isEmpty() &&
            hubs.stepsCovered("1.12.2 -> 1.14.4 (mcp)", "mcp:stable_39").equals(List.of("1.12.2 -> 1.14.4")),
            "Mojang-named rules never run on an MCP source; MCP rules run only there, pinned channel included"
        );
        check(
            Route.plan(home, "1.21.1-rc1", "1.21.6") == null,
            "a release outside the index falls back to the one-jump map"
        );
        port.route = zombie;
        Path log = scratch.resolve("route errors.log");
        Files.writeString(
            log,
            "C:\\mod\\src\\A.java:10: error: cannot find symbol\n        int y = level.getMinBuildHeight();\n                     ^\n" +
            "  symbol:   method getMinBuildHeight()\n  location: variable level of type Level\n" +
            "C:\\mod\\src\\B.java:5: error: incompatible types: int cannot be converted to String\n"
        );
        Map<String, Object> history = Json.map(
            "history",
            List.of(
                Json.map(
                    "kind",
                    "method",
                    "owner",
                    "net.minecraft.world.level.Level",
                    "name",
                    "getMinBuildHeight",
                    "desc",
                    "()I",
                    "what",
                    "removed",
                    "was",
                    "net.minecraft.world.level.Level.getMinBuildHeight",
                    "to",
                    null,
                    "step",
                    "1.21.1 -> 1.21.2"
                )
            )
        );
        List<Object> named = port.namedErrors(log, history);
        var first = Json.object(named.get(0));
        var placed = Json.object(Json.array(first.get("changedIn")).get(0));
        check(
            named.size() == 2 &&
            "1.21.1 -> 1.21.2".equals(placed.get("step")) &&
            "1.21.1 -> 1.21.3".equals(placed.get("hop")) &&
            String.valueOf(placed.get("change")).contains("removed"),
            "a remaining error names the update and hop that removed its symbol"
        );
        var second = Json.object(named.get(1));
        check(
            Json.array(second.get("changedIn")).isEmpty() && second.get("why") != null,
            "an error the history cannot place is still listed, with the reason"
        );
        // Gradle's failure summary repeats the errors and glues javac's last note onto one (AppleSkin 1.20.1 -> 1.21.6, 2026-11-07).
        Path glued = scratch.resolve("glued errors.log");
        Files.writeString(
            glued,
            "C:\\mod\\src\\A.java:10: error: cannot find symbol\n  symbol:   method getMinBuildHeight()\n" +
            "Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full output\n1 error\n\n* What went wrong:\n" +
            "  \t        ^Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full outputC:\\mod\\src\\A.java:10: error: cannot find symbol\n" +
            "  \t        ^C:\\mod\\src\\A.java:10: error: cannot find symbol\n" +
            "    (actual and formal argument lists differ in length)C:\\mod\\src\\A.java:10: error: cannot find symbol\n" +
            "    ^/home/me/mod/src/A.java:10: error: cannot find symbol\n"
        );
        check(
            port.namedErrors(glued, null).size() == 1,
            "a javac note Gradle glued onto a repeated error is not read as a file path"
        );
        String exhaustion =
            "C:\\mod\\src\\H.java:67: error: cannot find symbol\n\t\tfloat e = player.getHungerManager().getExhaustion();\n\t\t                                   ^\n  symbol:   method getExhaustion()\n  location: class HungerManager\n";
        var offered = Port.repairGuides(home, "1.20.1", "1.21.6", exhaustion)
            .stream()
            .map(guide -> guide.get("id"))
            .toList();
        check(
            offered.equals(List.of("hunger-exhaustion-accessor")) &&
            Port.repairGuides(home, "1.20.1", "1.21.1", exhaustion).isEmpty() &&
            Port.repairGuides(
                home,
                "1.20.1",
                "1.21.6",
                "> Task :compileJava\nsymbol:   method getExhaustion()\n"
            ).isEmpty(),
            "a stopped port offers the repair guide for its error only when it crosses the release that changed it"
        );
    }

    /** A build that pins its game as mcVersion reads as that Minecraft version, like the longer pin names. */
    static void versionsChecks() {
        Map<String, Object> mcVersion = Json.map(
            "projects",
            List.of(Json.map("pins", Json.map("mcVersion", "1.21.1"), "dependencies", List.of()))
        );
        check(
            Port.versions(mcVersion).equals(Set.of("1.21.1")),
            "a build that pins its game as mcVersion reads as that Minecraft version"
        );
        Map<String, Object> standard = Json.map(
            "projects",
            List.of(Json.map("pins", Json.map("minecraft_version", "1.21.1"), "dependencies", List.of()))
        );
        check(
            Port.versions(standard).equals(Set.of("1.21.1")),
            "a build that pins its game as minecraft_version still reads as that version"
        );
    }

    /** A dry run whose target planning fails previews the source rewrite; a real run still throws. */
    static void planningDegradeChecks(Path home, Path scratch) throws Exception {
        Path root = scratch.resolve("unplannable");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
        );
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[0]);
        ProjectModel project = new ProjectModel(root);
        Build build = new Build(project, home, "", new Mmt.Report(root, "degrade"));
        Map<String, Object> unpinned = Json.map(
            "projects",
            List.of(Json.map("path", ":", "plugins", List.of("test.loomshim"), "pins", Json.map(), "dependencies", List.of()))
        );
        Target.Setup degraded = Port.planTargets(
            build, unpinned, "1.7.10", List.of(), Set.of("1.21.1"), new Mmt.Options(new String[] {"--dry-run"})
        );
        check(
            degraded == null,
            "a dry run whose target planning fails continues with no build changes"
        );
        check(
            build.report.stages.stream().anyMatch(s -> "target-dependencies-skipped".equals(Json.object(s).get("name"))),
            "a dry run whose target planning fails records why in its stages"
        );
        boolean thrown = false;
        try {
            Port.planTargets(build, unpinned, "1.7.10", List.of(), Set.of("1.21.1"), new Mmt.Options(new String[] {}));
        } catch (IOException expected) {
            thrown = expected.getMessage().contains("pinned inside build logic");
        }
        check(thrown, "a real run whose target planning fails still throws instead of previewing");
    }

    /** Mixin triage sorts any mod's mixins against the pinned legacy Mixin, no game jar needed. */
    static void triageChecks() {
        String plain = """
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Shadow;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @Mixin(net.minecraft.world.entity.LivingEntity.class)
            public class LivingEntityMixin {
              @Shadow int health;
              @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
              void onHurt() {}
            }
            """;
        MixinTriage.Verdict portable = MixinTriage.triage("LivingEntityMixin", plain);
        check(
            portable.tier() == MixinTriage.Tier.PORTABLE &&
            portable.targets().equals(List.of("net.minecraft.world.entity.LivingEntity")),
            "a plain inject+shadow mixin is portable and names its target: " + portable
        );
        String modern = plain.replace("@Inject(method", "@ModifyExpressionValue(method");
        MixinTriage.Verdict manual = MixinTriage.triage("ModernMixin", modern);
        check(
            manual.tier() == MixinTriage.Tier.MANUAL &&
            manual.reasons().stream().anyMatch(reason -> reason.contains("ModifyExpressionValue")),
            "a MixinExtras-era injector is manual with the injector named: " + manual
        );
        String custom = plain.replace("public class LivingEntityMixin {", "public class LivingEntityMixin extends InjectionPoint {");
        MixinTriage.Verdict adapt = MixinTriage.triage("CustomMixin", custom);
        check(
            adapt.tier() == MixinTriage.Tier.ADAPT,
            "a custom extension point is adapt, not portable: " + adapt
        );
        String config = """
            { "package": "example.mixin", "minVersion": "0.15.2", "compatibilityLevel": "JAVA_21", "mixins": [] }
            """;
        MixinTriage.ConfigVerdict rewritten = MixinTriage.triageConfig(config);
        check(
            rewritten.tier() == MixinTriage.Tier.ADAPT &&
            rewritten.rewrites().stream().anyMatch(rewrite -> rewrite.contains("0.7")) &&
            rewritten.rewrites().stream().anyMatch(rewrite -> rewrite.contains("JAVA_8")),
            "a modern config is adapt with both pins rewritten: " + rewritten
        );
        MixinTriage.ConfigVerdict clean = MixinTriage.triageConfig("{ \"package\": \"example.mixin\", \"mixins\": [] }");
        check(
            clean.tier() == MixinTriage.Tier.PORTABLE,
            "a config with no version pins is portable: " + clean
        );
    }

    /** Converted subscribers leave their mixin config: entries die, and the file when nothing remains. */
    static void mixinConfigChecks() {
        String config = "{\n\t\"required\": true,\n\t\"package\": \"de.smallinger.cinematiczoom.mixin\",\n\t\"client\": [\n\t\t\"GameRendererMixin\",\n\t\t\"MouseMixin\",\n\t\t\"InGameHudMixin\"\n\t]\n}";
        String stripped = Port.stripConvertedMixins(
            config,
            "de.smallinger.cinematiczoom.mixin",
            List.of("GameRendererMixin", "MouseMixin", "InGameHudMixin")
        );
        check(
            !Port.mixinsRemain(stripped) && !stripped.contains("Mixin"),
            "converting every listed mixin empties its config: " + stripped.replace('\n', ' ')
        );
        String partial = Port.stripConvertedMixins(config, "de.smallinger.cinematiczoom.mixin", List.of("MouseMixin"));
        check(
            Port.mixinsRemain(partial) &&
            partial.contains("\"GameRendererMixin\"") &&
            partial.contains("\"InGameHudMixin\"") &&
            !partial.contains("MouseMixin") &&
            !partial.contains(",]") &&
            !partial.contains(",\n]"),
            "a partial conversion keeps the remaining mixins in valid JSON"
        );
        check(
            Port.stripConvertedMixins(config, "other.mixin", List.of("GameRendererMixin")).equals(config),
            "a config for another package passes through untouched"
        );
        String single = "{ \"package\": \"example.mixin\", \"client\": [\"OnlyMixin\"] }";
        String emptied = Port.stripConvertedMixins(single, "example.mixin", List.of("OnlyMixin"));
        check(
            !Port.mixinsRemain(emptied) && emptied.contains("\"client\": []"),
            "a single-entry inline array empties cleanly: " + emptied
        );
    }

    /**
     * A 1.7.10 target names its mod through mcmod.info translated from the modern metadata; newer
     * targets keep their TOML and get none, and the game check reads the built mcmod.info back.
     */
    static void legacyMetadataChecks(Path scratch) throws IOException {
        Path root = scratch.resolve("legacy-meta");
        Path templates = root.resolve("src/main/templates/META-INF");
        Files.createDirectories(templates);
        Files.writeString(
            templates.resolve("neoforge.mods.toml"),
            "license=\"MIT\"\n[[mods]]\nmodId=\"${mod_id}\"\nversion=\"${mod_version}\"\ndisplayName=\"${mod_name}\"\n"
                + "description='''${mod_description}'''\nauthors=\"${mod_authors}\"\n[[mixins]]\nconfig=\"other.mixins.json\"\n"
        );
        Files.writeString(
            root.resolve("gradle.properties"),
            "mod_id=cinematiczoom\nmod_version=1.0.0\nmod_name=CinematicZoom\n"
                + "mod_description=Smooth zoom.\nmod_authors=Mel1x (Ported by smallinger)\n"
        );
        Map<String, Object> info = Port.legacyModInfo(root, "1.7.10");
        check(
            info != null &&
            info.get("modid").equals("cinematiczoom") &&
            info.get("version").equals("1.0.0") &&
            info.get("name").equals("CinematicZoom") &&
            info.get("mcversion").equals("1.7.10") &&
            info.get("authorList").equals(List.of("Mel1x (Ported by smallinger)")) &&
            info.values().stream().noneMatch(v -> v.toString().contains("${")),
            "the template becomes a resolved mcmod.info entry: " + info
        );
        check(
            Port.legacyModInfo(root, "1.21.1") == null,
            "a modern target gets no mcmod.info: its TOML stays the metadata"
        );
        Path fabricRoot = scratch.resolve("legacy-fabric");
        Files.createDirectories(fabricRoot.resolve("src/main/resources"));
        Files.writeString(
            fabricRoot.resolve("src/main/resources/fabric.mod.json"),
            "{\"id\":\"zoom\",\"version\":\"2.0\",\"name\":\"Zoom\",\"authors\":[\"One\",\"Two\"],\"contact\":{\"homepage\":\"https://example.invalid\"}}"
        );
        Map<String, Object> fabricInfo = Port.legacyModInfo(fabricRoot, "1.7.10");
        check(
            fabricInfo != null &&
            fabricInfo.get("modid").equals("zoom") &&
            fabricInfo.get("authorList").equals(List.of("One", "Two")) &&
            fabricInfo.get("url").equals("https://example.invalid"),
            "fabric metadata translates too: " + fabricInfo
        );
        Path out = scratch.resolve("legacy-built");
        Files.createDirectories(out);
        Files.writeString(out.resolve("mcmod.info"), Json.write(List.of(info)));
        List<LoadedMods.Declared> declared = LoadedMods.declared(List.of(out));
        check(
            declared.size() == 1 &&
            declared.get(0).kind().equals("forge") &&
            declared.get(0).id().equals("cinematiczoom") &&
            declared.get(0).version().equals("1.0.0"),
            "the game check reads the built mcmod.info: " + declared
        );
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        Path root = scratch.resolve("mod with spaces");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
        );
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[0]);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Path source = root.resolve("src/custom/Food.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Food { int n = 1; }\r\n");
        ProjectModel project = new ProjectModel(root);
        Path init = home.resolve("tools/cli/gradle.init.gradle");
        String key = project.configurationHash(init);
        FileTime time = Files.getLastModifiedTime(root.resolve("build.gradle.kts"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java } //changed\n");
        Files.setLastModifiedTime(root.resolve("build.gradle.kts"), time);
        check(
            !key.equals(project.configurationHash(init)),
            "build configuration changes invalidate metadata even with preserved timestamps"
        );
        Path nested = root.resolve("unrelated/.git");
        Files.createDirectories(nested);
        Files.writeString(nested.getParent().resolve("build.gradle"), "secret build");
        check(
            project.inputs().stream().noneMatch(p -> p.startsWith(nested.getParent())),
            "nested git checkouts are excluded from project discovery"
        );
        Map<String, Object> model = Json.map(
            "projects",
            List.of(
                Json.map(
                    "directory",
                    root.toString(),
                    "buildFile",
                    root.resolve("build.gradle.kts").toString(),
                    "sourceSets",
                    List.of(
                        Json.map(
                            "name",
                            "test",
                            "sources",
                            List.of(source.getParent().toString()),
                            "java",
                            List.of(source.getParent().toString()),
                            "output",
                            List.of()
                        )
                    ),
                    "compile",
                    List.of()
                )
            )
        );
        String sourceHash = Fingerprints.build(model, project, init);
        FileTime modified = Files.getLastModifiedTime(source);
        Files.writeString(source, "class Food { int n = 2; }\r\n");
        Files.setLastModifiedTime(source, modified);
        check(
            !sourceHash.equals(Fingerprints.build(model, project, init)),
            "source content invalidates warm builds even when timestamps and length match"
        );
        Path layered = root.resolve("custom-client/Layered.java");
        Files.createDirectories(layered.getParent());
        Files.writeString(layered, "class Layered {}\n");
        String layeredHash = Fingerprints.build(model, project, init);
        Files.writeString(layered, "class Layered { int n; }\n");
        check(
            !layeredHash.equals(Fingerprints.build(model, project, init)),
            "custom source layouts cannot bypass the build identity"
        );
        // A jar Gradle makes inside the project (NeoGradle's ng_dummy_ng repository) is rewritten by the first launch: its
        // bytes must not count, or a never-launched copy's first check is refused. A jar the author keeps in libs/ must count.
        Path made = root.resolve(".gradle/repositories/ng_dummy_ng/neoforge.jar"), kept = root.resolve(
            "libs/helper.jar"
        );
        Files.createDirectories(made.getParent());
        Files.createDirectories(kept.getParent());
        Files.writeString(made, "a");
        Files.writeString(kept, "a");
        Map<String, Object> jars = Json.map(
            "projects",
            List.of(
                Json.map(
                    "directory",
                    root.toString(),
                    "buildFile",
                    root.resolve("build.gradle.kts").toString(),
                    "sourceSets",
                    List.of(),
                    "compile",
                    List.of(Json.map("sources", List.of(), "classpath", List.of(made.toString(), kept.toString())))
                )
            )
        );
        String jarHash = Fingerprints.build(jars, project, init);
        Files.writeString(made, "b");
        check(
            jarHash.equals(Fingerprints.build(jars, project, init)),
            "a classpath jar Gradle rewrites inside the project's .gradle folder does not make the project look changed"
        );
        Files.writeString(kept, "b");
        check(
            !jarHash.equals(Fingerprints.build(jars, project, init)),
            "a changed jar in the project's libs folder does make it look changed"
        );
        check(
            Fingerprints.changed(Map.of("x", "1"), Map.of("x", "2")).equals(List.of("x (changed)")),
            "a refused check names the file that changed"
        );
        Path stale = root.resolve("build/test-results/mmtPure/TEST-old.xml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "<testsuite tests=\"8\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        check(
            ((Number) TestResults.read(model, "mmtPure", "fresh-run").get("executed")).intValue() == 0,
            "old successful XML cannot make an empty run pass"
        );
        Build build = new Build(project, home, "", new Mmt.Report(root, "test"));
        Port port = new Port(build, model);
        Build portBuild = new Build(project, home, "", new Mmt.Report(root, "port"));
        routeChecks(home, new Port(portBuild, model), scratch);
        versionsChecks();
        planningDegradeChecks(home, scratch);
        triageChecks();
        mixinConfigChecks();
        legacyMetadataChecks(scratch);
        for (var groups : List.of(
            List.of("net.minecraftforge"),
            List.of("net.fabricmc", "net.neoforged"),
            List.<String>of()
        )) {
            var dependencies = groups.stream().map(group -> Json.map("group", group)).toList();
            try {
                Target.plan(portBuild, Json.map("projects", List.of(Json.map("dependencies", dependencies))), "1.21.6");
                throw new AssertionError("unsupported loader reached automatic target setup: " + groups);
            } catch (IOException expected) {
                check(
                    expected.getMessage().contains("Fabric or NeoForge") &&
                    expected.getMessage().contains("target dependencies"),
                    "unsupported loader gives manual setup guidance before network or edits: " + groups
                );
            }
        }
        check(
            portBuild.command(List.of("help")).contains("-Dfabric.loom.ci=true"),
            "port Gradle skips dependency source remapping"
        );
        check(
            !portBuild.command(List.of("help")).contains("--no-configure-on-demand"),
            "mmt's Gradle runs keep the build's configure-on-demand"
        );
        Path missed = scratch.resolve("missed.log");
        Files.writeString(
            missed,
            "* What went wrong:\nCannot locate tasks that match ':mmtMetadata' as task 'mmtMetadata' not found in root project 'diorama'.\n"
        );
        check(
            Build.selectionFailed(missed),
            "a task-selection failure is recognized for the configure-on-demand retry"
        );
        Files.writeString(missed, "* What went wrong:\nExecution failed for task ':compileJava'.\n");
        check(!Build.selectionFailed(missed), "a compile failure does not trigger the configure-on-demand retry");
        check(
            build.command(List.of("help")).contains("-Dfabric.loom.ci=true"),
            "verification Gradle also skips dependency source remapping"
        );
        Path ngGame = scratch.resolve(
            "ng/.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.248/neoforge-21.1.248.jar"
        );
        Map<String, Object> ngModel = Json.map(
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":",
                    "plugins",
                    List.of("net.neoforged.gradle.userdev.UserDevPlugin"),
                    "compile",
                    List.of(Json.map("task", ":compileJava", "classpath", List.of(ngGame.toString())))
                )
            )
        );
        check(
            "21.1.248".equals(OwnCompile.neoforgeVersion(ngModel)),
            "a NeoGradle build waiting on NeoForm's game jar gets the installer-made jar"
        );
        Files.createDirectories(ngGame.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(ngGame))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/client/Minecraft.class"));
            zip.closeEntry();
        }
        check(
            OwnCompile.neoforgeVersion(ngModel) == null,
            "once NeoForm has built the game jar, the Gradle compile is used"
        );
        check(
            OwnCompile.neoforgeVersion(
                Json.map(
                    "projects",
                    List.of(
                        Json.map(
                            "path",
                            ":",
                            "compile",
                            List.of(
                                Json.map(
                                    "task",
                                    ":compileJava",
                                    "classpath",
                                    List.of(scratch.resolve("minecraft-merged.jar").toString())
                                )
                            )
                        )
                    )
                )
            ) ==
            null,
            "a Fabric build keeps the Gradle compile"
        );
        Path transaction = project.state().resolve("ports/test-undo"), backup = transaction.resolve(
            "before/src/custom/Food.java"
        );
        Files.createDirectories(backup.getParent());
        byte[] original =
            "class Food { int n = 1; }\r\n// original 🧱".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(backup, original);
        Map<String, Object> before = Json.map("src/custom/Food.java", Fingerprints.bytes(original));
        port.finishTransaction(transaction, before);
        String repaired = Files.readString(source);
        Files.writeString(source, repaired + "// later user edit\n");
        try {
            port.undo(transaction.toString());
            throw new AssertionError("overwrote later edit");
        } catch (IOException expected) {
            check(
                Files.readString(source).contains("later user edit"),
                "undo refuses and preserves edits made after a port"
            );
        }
        Files.writeString(source, repaired);
        port.undo(transaction.toString());
        check(
            Arrays.equals(original, Files.readAllBytes(source)),
            "undo restores exact original bytes, Unicode, CRLF and missing final newline"
        );
        check(
            !Files.readString(backup).contains("later user edit"),
            "repair backups remain independent of working files"
        );
        check(
            Target.replace(
                "# MC\r\nminecraft_version = 1.21.5\r\nother=kept\r\n",
                "minecraft_version",
                "1.21.6"
            ).equals("# MC\r\nminecraft_version = 1.21.6\r\nother=kept\r\n"),
            "target setup preserves property spacing, comments and line endings"
        );
        try {
            Target.replace("minecraft_version=1\nminecraft_version=2\n", "minecraft_version", "3");
            throw new AssertionError("ambiguous pin accepted");
        } catch (IOException expected) {
            check(true, "target setup refuses duplicate property owners");
        }
        Path pin = root.resolve("gradle.properties");
        Files.writeString(pin, "minecraft_version=1\n");
        var edit = new Target.Edit(pin, Files.readString(pin), "minecraft_version=2\n");
        Files.writeString(pin, "minecraft_version=3\n");
        try {
            Target.apply(List.of(edit));
            throw new AssertionError("overwrote concurrent config edit");
        } catch (IOException expected) {
            check(Files.readString(pin).contains("=3"), "target setup preserves configuration edited after resolution");
        }
        String groovy = "// keep\r\nmodCompileOnly 'example:mod:1.0'\r\n";
        check(
            Dependencies.literal(groovy, "example:mod", "1.0", "2.0").equals(groovy.replace(":1.0'", ":2.0'")),
            "dependency migration preserves Groovy quotes, comments and CRLF"
        );
        String kotlin = "modImplementation(\"example:mod:1.0\")\n";
        check(
            Dependencies.literal(kotlin, "example:mod", "1.0", "2.0").equals(kotlin.replace(":1.0\"", ":2.0\"")),
            "dependency migration preserves Kotlin syntax"
        );
        try {
            Dependencies.literal(groovy + groovy, "example:mod", "1.0", "2.0");
            throw new AssertionError("ambiguous dependency accepted");
        } catch (IOException expected) {
            check(true, "dependency migration refuses ambiguous literal owners");
        }
        Path crash = scratch.resolve("startup.log");
        Files.writeString(crash, "Caused by: third.party.MixinFailure: broken hook\n" + "Gradle footer\n".repeat(40));
        check(
            Run.failure(crash).contains("third.party.MixinFailure"),
            "startup errors retain the actual cause above the Gradle footer"
        );
        Path metadataRoot = scratch.resolve("metadata-mod");
        Path libs = metadataRoot.resolve("build/libs");
        Files.createDirectories(libs);
        String toml =
            "[modLoader=\"javafml\"]\n[[mods]]\nmodId=\"m\"\n[[dependencies.m]]\nmodId=\"neoforge\"\nversionRange=\"[21.4,)\"\n[[dependencies.m]]\nmodId=\"minecraft\"\nversionRange=\"%s\"\n";
        Map<String, Object> neoModel = Json.map(
            "projects",
            List.of(Json.map("plugins", List.of("net.neoforged.moddev.NeoForgeModDevPlugin")))
        );
        Path packaged = libs.resolve("m-1.0.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.1,1.21.1]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Map<String, Object> staleMetadata = Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4");
        check(
            Boolean.FALSE.equals(staleMetadata.get("ok")) && staleMetadata.get("details").toString().contains("0 mods"),
            "a packaged mod that still declares the old Minecraft range is refused instead of looking like a passing port"
        );
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.4,1.21.4]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        check(
            Boolean.TRUE.equals(Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4").get("ok")),
            "packaged metadata that accepts the target version passes the loader check"
        );
        System.out.println(count + " port checks passed");
    }
}
