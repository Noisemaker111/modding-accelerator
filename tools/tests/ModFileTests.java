import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Resource and metadata checks, and real mods' files the loaders accept.
 * From the stranger playthrough (an outside modder using mmt on Diorama and F3Nope): a message that hid the
 * cause, or a pass that should have failed. Every case runs the real code path on real files (or real tool output)
 * and checks what a person would read.
 */
public final class ModFileTests {

    static int count;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("mmt stranger");
        try {
            modChecks(scratch);
            realModFiles(scratch);
            System.out.println(count + " mod file checks passed: resource and metadata checks, real-mod files the loaders accept");
        } finally {
            for (Path p : walk(scratch)) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** fabric.mod.json errors, a mixin listed but never compiled, a MOD_ID the metadata disagrees with, a missing model and name. */
    static void modChecks(Path scratch) throws Exception {
        Path project = scratch.resolve("mod"), resources = project.resolve("build/resources/main"), classes =
            project.resolve("build/classes/java/main");
        Path sourceResources = project.resolve("src/main/resources"), java = project.resolve("src/main/java");
        write(
            resources.resolve("fabric.mod.json"),
            "{\n  \"schemaVersion\": 1,\n  \"id\": \"compasspin\",\n  \"entrypoints\": {\"main\": [\"dev.pin.PinMod\"]},\n  \"mixins\": [\"pin.mixins.json\"]\n}"
        );
        write(
            resources.resolve("pin.mixins.json"),
            "{\"required\": true, \"package\": \"dev.pin.mixin\", \"client\": [\"TitleMixin\", \"GoneMixin\"]}"
        );
        write(classes.resolve("dev/pin/mixin/TitleMixin.class"), "x");
        write(classes.resolve("dev/pin/Other.class"), "x");
        write(
            resources.resolve("data/compasspin/recipe/pin.json"),
            "{\"type\": \"minecraft:crafting_shapeless\", \"result\": {\"id\": \"compasspin:pin\"}}"
        );
        write(
            resources.resolve("data/compasspin/recipe/broken.json"),
            "{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}"
        );
        write(
            sourceResources.resolve("data/compasspin/recipe/broken.json"),
            "{\n  \"type\": \"minecraft:crafting_shaped\"\n  \"result\": {}\n}"
        );
        write(resources.resolve("assets/compasspin/lang/en_us.json"), "{\"item.compasspin.other\": \"Other\"}");
        // Diorama 1.21.1's gameshim: a NeoForge shim spells the Fabric id "compass-pin-shim" with '_', which NeoForge requires.
        write(
            project.resolve("build/resources/gameshim/fabric.mod.json"),
            "{\"schemaVersion\": 1, \"id\": \"compass-pin-shim\"}"
        );
        write(
            java.resolve("dev/pin/neoforge/Shim.java"),
            "package dev.pin.neoforge;\n@Mod(\"compass_pin_shim\") class Shim {}\n"
        );
        write(
            java.resolve("dev/pin/PinMod.java"),
            "package dev.pin;\n// public static final String MOD_ID = \"old\";\npublic class PinMod {\n    public static final String MOD_ID = \"compass_pin\";\n}\n"
        );
        Map<String, Object> model = Json.map(
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":",
                    "compile",
                    List.of(Json.map("destination", classes.toString())),
                    "sourceSets",
                    List.of(
                        Json.map(
                            "name",
                            "main",
                            "output",
                            List.of(classes.toString(), resources.toString()),
                            "resources",
                            List.of(sourceResources.toString()),
                            "java",
                            List.of(java.toString())
                        ),
                        Json.map(
                            "name",
                            "gameshim",
                            "output",
                            List.of(project.resolve("build/resources/gameshim").toString())
                        ),
                        Json.map("name", "test", "output", List.of(project.resolve("build/resources/test").toString()))
                    )
                )
            )
        );
        Map<String, Object> result = ModChecks.run(model);
        String problems = String.join("\n", ProjectModel.strings(result.get("problems"))), warnings = String.join(
            "\n",
            ProjectModel.strings(result.get("warnings"))
        );
        require(Boolean.FALSE.equals(result.get("ok")), "broken resources fail the check");
        require(
            problems.contains(
                sourceResources.resolve("data/compasspin/recipe/broken.json") + ": invalid JSON, line 3, column 3"
            ),
            "the broken recipe is named by its source file, line and column: " + problems
        );
        require(
            problems.contains(
                "lists mixin \"GoneMixin\" (client), but there is no compiled class dev.pin.mixin.GoneMixin"
            ) &&
            !problems.contains("\"TitleMixin\""),
            "only the uncompiled mixin is a problem: " + problems
        );
        require(
            problems.contains("entrypoint dev.pin.PinMod names no compiled class"),
            "an entrypoint in the mod's own package must be compiled: " + problems
        );
        require(
            warnings.contains("PinMod.java:4: the code says the mod id is \"compass_pin\"") &&
            !warnings.contains("\"old\"") &&
            !warnings.contains("compass_pin_shim") &&
            !problems.contains("the code says"),
            "a MOD_ID that disagrees with fabric.mod.json is a warning (no loader reads it), a commented-out one is not found: " +
            warnings
        );
        require(
            warnings.contains("compasspin:pin is made by") && warnings.contains("has no item model"),
            "a crafted item with no model is a warning: " + warnings
        );
        require(
            warnings.contains("no \"item.compasspin.pin\" entry"),
            "a crafted item with no name is a warning: " + warnings
        );
        require(
            ModChecks.text(result).startsWith(
                ProjectModel.strings(result.get("problems")).size() + " problems the game would hit at start-up:"
            ),
            "the text leads with the problem count"
        );

        write(resources.resolve("fabric.mod.json"), "{\"schemaVersion\": 1, \"id\": \"Compass Pin\"}");
        Files.delete(resources.resolve("data/compasspin/recipe/broken.json"));
        String badId = String.join("\n", ProjectModel.strings(ModChecks.run(model).get("problems")));
        require(badId.contains("mod id \"Compass Pin\" is not valid"), "an invalid Fabric id is a problem: " + badId);

        // F3Nope: Loom's split client set. main's fabric.mod.json names the client set's mixin config and entrypoint; they ship in one jar.
        Path split = scratch.resolve("f3nope"), mainOut = split.resolve("build/resources/main"), clientOut =
            split.resolve("build/resources/client"), clientClasses = split.resolve("build/classes/java/client");
        write(
            mainOut.resolve("fabric.mod.json"),
            "{\"schemaVersion\": 1, \"id\": \"f3nope\", \"entrypoints\": {\"client\": [\"com.f3nope.F3NopeClient\"]}, \"mixins\": [{\"config\": \"f3nope.client.mixins.json\", \"environment\": \"client\"}]}"
        );
        write(
            clientOut.resolve("f3nope.client.mixins.json"),
            "{\"package\": \"com.f3nope.mixin\", \"client\": [\"DebugMixin\"]}"
        );
        write(clientClasses.resolve("com/f3nope/F3NopeClient.class"), "x");
        write(clientClasses.resolve("com/f3nope/mixin/DebugMixin.class"), "x");
        Map<String, Object> splitModel = Json.map(
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":",
                    "sourceSets",
                    List.of(
                        Json.map(
                            "name",
                            "main",
                            "output",
                            List.of(split.resolve("build/classes/java/main").toString(), mainOut.toString())
                        ),
                        Json.map("name", "client", "output", List.of(clientClasses.toString(), clientOut.toString()))
                    )
                )
            )
        );
        Map<String, Object> splitResult = ModChecks.run(splitModel);
        require(
            Boolean.TRUE.equals(splitResult.get("ok")) &&
            String.valueOf(splitResult.get("checked")).contains("1 mixin config(s) with 1 mixin class(es)"),
            "a split client set is checked with main as one jar: " + splitResult
        );

        Path neo = scratch.resolve("neo"), neoResources = neo.resolve("build/resources/main");
        write(
            neoResources.resolve("META-INF/neoforge.mods.toml"),
            "modLoader=\"javafml\"\n[[mods]]\nmodId=\"Bad-Id\"\n[[dependencies.x]]\nmodId=\"minecraft\"\n[[mixins]]\nconfig=\"missing.mixins.json\"\n"
        );
        Map<String, Object> neoModel = Json.map(
            "projects",
            List.of(
                Json.map("sourceSets", List.of(Json.map("name", "main", "output", List.of(neoResources.toString()))))
            )
        );
        String neoProblems = String.join("\n", ProjectModel.strings(ModChecks.run(neoModel).get("problems")));
        require(
            neoProblems.contains("mod id \"Bad-Id\" is not valid") &&
            !neoProblems.contains("\"minecraft\" is not valid") &&
            neoProblems.contains("lists mixin config \"missing.mixins.json\""),
            "neoforge.mods.toml: the [[mods]] id is checked, dependency ids are not, a missing mixin config is a problem: " +
            neoProblems
        );
    }

    /**
     * Files from real mods that PR #28's checks failed although the game loads them, and the loader rules
     * the checks now follow. Each file's text is the real one (path named in the comment); a problem is only
     * what Fabric Loader, FML, Mixin or Minecraft would reject.
     */
    static void realModFiles(Path scratch) throws Exception {
        // Gson's JsonReader (Fabric Loader's copy is strict, Mixin and Minecraft read leniently).
        require(
            ModChecks.Gson.parse("﻿{\"a\": \"tab\there\", \"b\": TRUE}", false) instanceof Map<?, ?> m &&
            Boolean.TRUE.equals(m.get("b")),
            "strict Gson: a byte order mark, a raw tab in a string and TRUE all read"
        );
        require(
            ModChecks.Gson.parse("{\n  // note\n  'a': [1, 2,]; b = x # c\n}", true) instanceof Map<?, ?> m &&
            Arrays.asList(1L, 2L, null).equals(m.get("a")) &&
            "x".equals(m.get("b")),
            "lenient Gson: comments, single quotes, '=' and ';', unquoted values, [1, 2,] is [1, 2, null]"
        );
        for (String strictOnly : List.of("{\"a\": 1 // note\n}", "{\"a\": [1,]}", "{'a': 1}")) try {
            ModChecks.Gson.parse(strictOnly, false);
            throw new AssertionError("strict Gson read " + strictOnly);
        } catch (IllegalArgumentException expected) {
            count++;
        }
        try {
            ModChecks.Gson.parse("{\"a\": 1,}", true);
            throw new AssertionError("a trailing comma in an object parsed leniently");
        } catch (IllegalArgumentException expected) {
            require(
                expected.getMessage().contains("trailing comma in an object"),
                "Gson rejects {\"a\": 1,} even when lenient: " + expected.getMessage()
            );
        }

        Path project = scratch.resolve("real"), out = project.resolve("build/resources/main"), classes =
            project.resolve("build/classes/java/main");
        Path loader = project.resolve("libs/fabric-loader-0.16.14.jar");
        Files.createDirectories(loader.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(loader))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // MineColonies src/main/resources/assets/minecolonies/models/block/crops/corn/stage3.json, as shipped: "bottom" twice.
        write(
            out.resolve("assets/minecolonies/models/block/crops/corn/stage3.json"),
            "{\"parent\": \"minecolonies:block/crops/rice/stage6\",\"textures\": {\"bottom\": \"minecolonies:block/crops/corn/stage3\", \"bottom\": \"minecolonies:block/crops/corn/stage3\"}}"
        );
        // Diorama src/gametest/resources/xaero-icons-pack/.../chicken.json: not JSON even to lenient Gson, but Minecraft never reads it.
        write(
            out.resolve("xaero-icons-pack/assets/xaerominimap/entity/icon/definition/minecraft/chicken.json"),
            "{\"variants\": {\"default\": \"dot\",,,}\n"
        );
        // A models file with a comment: Minecraft reads most resources leniently, some versions strictly.
        write(
            out.resolve("assets/minecolonies/models/item/commented.json"),
            "{\n  // generated\n  \"parent\": \"item/generated\"\n}"
        );
        // A config for another tool in the resources, JSON5-style: nobody in the game reads it.
        write(out.resolve("config/defaults.json"), "{ unquoted: 'yes', }");
        Map<String, Object> plain = model(project, classes, out, List.of(classes, out, loader));
        Map<String, Object> result = ModChecks.run(plain);
        String problems = String.join("\n", ProjectModel.strings(result.get("problems"))), warnings = String.join(
            "\n",
            ProjectModel.strings(result.get("warnings"))
        );
        require(
            Boolean.TRUE.equals(result.get("ok")),
            "MineColonies' duplicate key, a nested pack Minecraft never reads and a commented model fail nothing: " +
            problems
        );
        require(
            warnings.contains("stage3.json: duplicate key \"bottom\"; only the last one is used"),
            "a duplicate key is a warning (Gson keeps the last): " + warnings
        );
        require(
            warnings.contains("chicken.json: invalid JSON") && warnings.contains("never read this file"),
            "invalid JSON nothing in the game reads is a warning: " + warnings
        );
        require(warnings.contains("commented.json: not strict JSON"), "a comment in a model is a warning: " + warnings);
        require(
            warnings.contains("defaults.json: invalid JSON") && !problems.contains("defaults.json"),
            "a JSON5 config nothing in the game reads is only a warning: " + warnings
        );
        write(out.resolve("assets/diorama/models/item/chicken.json"), "{\"variants\": {\"default\": \"dot\",,,}\n");
        require(
            String.join("\n", ProjectModel.strings(ModChecks.run(plain).get("problems"))).contains(
                "chicken.json: invalid JSON, line 1, column 32"
            ),
            "the same text where Minecraft reads it is a problem: lenient Gson rejects it too"
        );
        Files.delete(out.resolve("assets/diorama/models/item/chicken.json"));

        // Architectury 1.21 fabric/build/resources/main/fabric.mod.json: architectury-common.mixins.json ships in :common's
        // dev jar (shadowed into the release jar), which is on :fabric's runtime classpath.
        Path common = project.resolve("common/build/devlibs/architectury-13.0.9999-dev.jar");
        Files.createDirectories(common.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(common))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("architectury-common.mixins.json"));
            zip.write(
                "{\"package\": \"dev.architectury.mixin\", \"required\": true, \"mixins\": [\"MixinX\"]}".getBytes(
                        StandardCharsets.UTF_8
                    )
            );
            zip.closeEntry();
        }
        write(
            out.resolve("fabric.mod.json"),
            "{\n  \"schemaVersion\": 1,\n  \"id\": \"architectury\",\n  \"version\": \"13.0.9999\",\n  \"mixins\": [\n    \"architectury-common.mixins.json\",\n    \"architectury.mixins.json\"\n  ],\n" +
            "  \"entrypoints\": {\n    \"main\": [\n      \"dev.architectury.utils.fabric.GameInstanceImpl::init\"\n    ]\n  }\n}"
        );
        write(
            out.resolve("architectury.mixins.json"),
            "{\"package\": \"dev.architectury.mixin.fabric\", \"required\": true, \"mixins\": [\"MixinFabric\"]}"
        );
        write(classes.resolve("dev/architectury/mixin/fabric/MixinFabric.class"), "x");
        write(classes.resolve("dev/architectury/utils/fabric/GameInstanceImpl.class"), "x");
        Map<String, Object> arch = model(project, classes, out, List.of(classes, out, loader, common));
        Map<String, Object> archResult = ModChecks.run(arch);
        require(
            Boolean.TRUE.equals(archResult.get("ok")) &&
            String.valueOf(archResult.get("checked")).contains("2 mixin config(s)"),
            "a mixin config from another project's jar on the runtime classpath is found, as Mixin finds it: " +
            archResult
        );
        require(
            String.join(
                "\n",
                ProjectModel.strings(
                    ModChecks.run(model(project, classes, out, List.of(classes, out, loader))).get("problems")
                )
            ).contains("lists mixin config \"architectury-common.mixins.json\""),
            "without that jar, and nowhere in the checkout, the missing config is still a problem"
        );

        // Sodium dev fabric/build/resources/main/sodium-frapi.mixins.json: the mixins are in :frapi, whose classes are not
        // compiled at compile time (frapi/build/classes/java/main is on the runtime classpath, sources in frapi/src/main/java).
        Files.delete(out.resolve("fabric.mod.json"));
        write(
            out.resolve("fabric.mod.json"),
            "{\"schemaVersion\": 1, \"id\": \"sodium\", \"version\": \"1\", \"mixins\": [\"sodium-frapi.mixins.json\"]}"
        );
        write(
            out.resolve("sodium-frapi.mixins.json"),
            "{\n  \"package\": \"net.caffeinemc.mods.sodium.mixin.frapi\",\n  \"required\": true,\n  \"compatibilityLevel\": \"JAVA_17\",\n  \"injectors\": {\n    \"defaultRequire\": 1\n  },\n" +
            "  \"client\": [\n    \"FeatureRenderDispatcherMixin\",\n    \"ItemFeatureRendererAccessor\"\n  ]\n}"
        );
        Path frapi = project.resolve("frapi");
        write(
            frapi.resolve("src/main/java/net/caffeinemc/mods/sodium/mixin/frapi/FeatureRenderDispatcherMixin.java"),
            "class FeatureRenderDispatcherMixin {}"
        );
        write(
            frapi.resolve("src/main/java/net/caffeinemc/mods/sodium/mixin/frapi/ItemFeatureRendererAccessor.java"),
            "interface ItemFeatureRendererAccessor {}"
        );
        Map<String, Object> sodium = model(
            project,
            classes,
            out,
            List.of(classes, out, loader, frapi.resolve("build/classes/java/main"))
        );
        Map<String, Object> sodiumResult = ModChecks.run(sodium);
        require(
            Boolean.TRUE.equals(sodiumResult.get("ok")),
            "mixins whose project is on the classpath but not compiled yet fail nothing: " +
            sodiumResult.get("problems")
        );
        write(
            out.resolve("sodium-frapi.mixins.json"),
            "{\"package\": \"net.caffeinemc.mods.sodium.mixin.frapi\", \"required\": true, \"client\": [\"GoneMixin\"]}"
        );
        require(
            String.join("\n", ProjectModel.strings(ModChecks.run(sodium).get("warnings"))).contains("\"GoneMixin\"") &&
            Boolean.TRUE.equals(ModChecks.run(sodium).get("ok")),
            "a missing mixin while part of the classpath is unbuilt is a warning, not certain"
        );
        write(
            out.resolve("sodium-frapi.mixins.json"),
            "{\"package\": \"net.caffeinemc.mods.sodium.mixin.frapi\", \"client\": [\"GoneMixin\"]}"
        );
        Map<String, Object> optional = ModChecks.run(model(project, classes, out, List.of(classes, out, loader)));
        require(
            Boolean.TRUE.equals(optional.get("ok")) &&
            String.join("\n", ProjectModel.strings(optional.get("warnings"))).contains(
                "not \"required\", so Mixin logs a warning"
            ),
            "a missing mixin in a config that is not \"required\" is a warning: Mixin's MixinProcessor only warns: " +
            optional
        );
        Files.delete(out.resolve("sodium-frapi.mixins.json"));

        // In-Game Account Switcher src/main/resources/fabric.mod.json keeps Stonecutter comments; processed output drops them,
        // but a comment that reached the jar is a problem: Fabric Loader's JsonReader is not lenient.
        write(
            out.resolve("fabric.mod.json"),
            "{\n  \"schemaVersion\": 1,\n  \"id\": \"ias\",\n  \"version\": \"9\",\n  \"entrypoints\": {\n    \"client\": [\n      //? if >=1.21.8 {\n      \"ru.vidtu.ias.platform.IFabric\"\n    ]\n  }\n}"
        );
        write(classes.resolve("ru/vidtu/ias/platform/IFabric.class"), "x");
        String fabricComment = String.join("\n", ProjectModel.strings(ModChecks.run(plain).get("problems")));
        require(
            fabricComment.contains("fabric.mod.json: line 7, column 7: a comment") &&
            fabricComment.contains("strict JSON"),
            "a comment in fabric.mod.json is a problem: " + fabricComment
        );
        // On a NeoForge or Forge project (no Fabric Loader on the classpath) nobody reads a spare fabric.mod.json.
        require(
            Boolean.TRUE.equals(
                ModChecks.run(
                    model(project, classes, out, List.of(classes, out, project.resolve("libs/neoforge-21.1.248.jar")))
                ).get("ok")
            ),
            "a fabric.mod.json on a NeoForge build is not held to Fabric's rules"
        );
        // fabric-language-kotlin's adapter reads the value itself (objects, top-level functions): never certain.
        write(
            out.resolve("fabric.mod.json"),
            "{\"schemaVersion\": 1, \"id\": \"kotlinmod\", \"version\": \"1\", \"entrypoints\": {\"main\": [{\"adapter\": \"kotlin\", \"value\": \"dev.kt.ModKt::init\"}]}}"
        );
        write(classes.resolve("dev/kt/Other.class"), "x");
        Map<String, Object> kotlin = ModChecks.run(plain);
        require(
            Boolean.TRUE.equals(kotlin.get("ok")) &&
            String.join("\n", ProjectModel.strings(kotlin.get("warnings"))).contains("(adapter \"kotlin\")"),
            "a Kotlin-adapter entrypoint without its class is a warning: " + kotlin
        );
        Files.delete(out.resolve("fabric.mod.json"));

        // NeoForge FML 5+ (1.21 on) ModInfo.VALID_MODID allows dots; Forge's and FML 4's do not.
        write(out.resolve("META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n[[mods]]\nmodId='my.mod'\n");
        Map<String, Object> neo = model(project, classes, out, List.of(classes, out));
        Map<String, Object> dotted = ModChecks.run(neo);
        require(
            Boolean.TRUE.equals(dotted.get("ok")) &&
            String.join("\n", ProjectModel.strings(dotted.get("warnings"))).contains("mod id \"my.mod\" has a '.'"),
            "a dotted NeoForge id (single-quoted TOML) is a warning: " + dotted
        );
        Files.delete(out.resolve("META-INF/neoforge.mods.toml"));
        write(out.resolve("META-INF/mods.toml"), "modLoader=\"javafml\"\n[[mods]]\nmodId=\"my.mod\"\n");
        require(
            String.join("\n", ProjectModel.strings(ModChecks.run(neo).get("problems"))).contains(
                "mod id \"my.mod\" is not valid. Forge ids"
            ),
            "a dotted Forge id is a problem"
        );
        Files.delete(out.resolve("META-INF/mods.toml"));

        // In-Game Account Switcher :1.21.1-forge: ForgeGradle 7 writes classes and resources into one build/sourceSets/main.
        Path fg = scratch.resolve("fg7"), both = fg.resolve("build/sourceSets/main");
        write(
            both.resolve("META-INF/mods.toml"),
            "modLoader=\"javafml\"\n[[mods]]\nmodId=\"ias\"\n[[mixins]]\nconfig=\"ias.mixins.json\"\n"
        );
        write(
            both.resolve("ias.mixins.json"),
            "{\"package\": \"ru.vidtu.ias.mixins\", \"required\": true, \"client\": [\"TitleScreenMixin\"]}"
        );
        write(both.resolve("ru/vidtu/ias/mixins/TitleScreenMixin.class"), "x");
        Map<String, Object> fgModel = Json.map(
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":1.21.1-forge",
                    "compile",
                    List.of(Json.map("destination", both.toString())),
                    "sourceSets",
                    List.of(Json.map("name", "main", "output", List.of(both.toString())))
                )
            )
        );
        Map<String, Object> fgResult = ModChecks.run(fgModel);
        require(
            Boolean.TRUE.equals(fgResult.get("ok")) &&
            String.valueOf(fgResult.get("checked")).contains(
                "1 JSON file(s), 1 mixin config(s) with 1 mixin class(es)"
            ) &&
            String.valueOf(fgResult.get("checked")).contains("mod id ias"),
            "ForgeGradle 7's shared output folder is read as resources and classes: " + fgResult
        );
    }

    static Map<String, Object> model(Path root, Path classes, Path resources, List<Path> runtime) {
        return Json.map(
            "root",
            root.toString(),
            "projects",
            List.of(
                Json.map(
                    "path",
                    ":",
                    "compile",
                    List.of(Json.map("destination", classes.toString())),
                    "sourceSets",
                    List.of(
                        Json.map(
                            "name",
                            "main",
                            "output",
                            List.of(classes.toString(), resources.toString()),
                            "runtime",
                            runtime.stream().map(Path::toString).toList()
                        )
                    )
                )
            )
        );
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static List<Path> walk(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.sorted(Comparator.reverseOrder()).toList();
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }
}
