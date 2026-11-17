import java.io.*;
import java.nio.file.*;
import java.util.*;

/** What a port changes in a mod: learned rules, accessors, stale mixin targets, and Loom, Parchment, NeoGradle and FML tests following the target. */
public final class PortRepairTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /**
     * Rules learned from Stonecutter switches (bench/stonecutter_rules.py): each one names switches that exist in
     * coverage/stonecutter/switches in two or more mods, its match finds its own compiler error, Port's rewrite
     * turns its test source into the recorded result, and its jump lands on an update a route in that direction crosses.
     */
    static void learnedRuleChecks(Path home) throws Exception {
        int learned = 0;
        Map<String, Map<String, Object>> switches = new HashMap<>();
        for (Object item : Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if (!"stonecutter".equals(rule.get("source"))) continue;
            String id = Json.text(rule, "id", "?");
            Set<String> mods = new HashSet<>();
            for (Object e : Json.array(rule.get("examples"))) {
                var example = Json.object(e);
                String mod = Json.text(example, "mod", "");
                // The packaged jar ships knowledge/ but not the mined switch data; there only the rewrites are checked.
                Path mined = home.resolve("coverage/stonecutter/switches/" + mod.replace("/", "__") + ".json");
                if (!Files.isRegularFile(mined)) {
                    mods.add(mod);
                    continue;
                }
                var data = switches.computeIfAbsent(mod, m -> {
                    try {
                        return Json.read(mined);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
                boolean found = false;
                for (Object r : Json.array(data.get("rows"))) {
                    var row = Json.object(r);
                    if (
                        row.get("file").equals(example.get("file")) &&
                        ((Number) row.get("line")).intValue() == ((Number) example.get("line")).intValue()
                    ) found = true;
                }
                if (!found) throw new AssertionError(
                    id +
                    ": example " +
                    mod +
                    " " +
                    example.get("file") +
                    ":" +
                    example.get("line") +
                    " is not a mined switch"
                );
                mods.add(mod);
            }
            if (mods.size() < 2) throw new AssertionError(
                id + ": a learned rule needs the same change in two or more mods, has " + mods
            );
            var test = Json.object(rule.get("test"));
            if (
                !java.util.regex.Pattern.compile(rule.get("match").toString())
                    .matcher(test.get("error").toString())
                    .find()
            ) throw new AssertionError(id + ": match misses its test error");
            String after = Port.rewrite(rule, test.get("before").toString());
            if (after.equals(test.get("before")) || !after.equals(test.get("after"))) throw new AssertionError(
                id + ": rewrite gave\n" + after + "\nexpected\n" + test.get("after")
            );
            String[] range = Json.text(rule, "jump", "").replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            String names = Json.text(rule, "jump", "").contains("yarn") ? "yarn" : "mojmap";
            Route route = Route.plan(home, range[0], range[1]);
            if (
                route == null || route.stepsCovered(Json.text(rule, "jump", ""), names).isEmpty()
            ) throw new AssertionError(id + ": no route " + Json.text(rule, "jump", "") + " runs it");
            learned++;
        }
        {
            var rule = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))
                .stream()
                .map(Json::object)
                .filter(r -> "glstatemanager-opengl".equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("knowledge/fixes.json has no glstatemanager-opengl rule"));
            String log =
                "HUD.java:3: error: cannot find symbol\nimport com.mojang.blaze3d.platform.GlStateManager;\n  symbol:   class GlStateManager\n  location: package com.mojang.blaze3d.platform\n";
            check(
                java.util.regex.Pattern.compile(rule.get("match").toString()).matcher(log).find() &&
                Port.rewrite(
                    rule,
                    "import com.mojang.blaze3d.platform.GlStateManager;\nimport com.mojang.blaze3d.platform.NativeImage;\n"
                ).equals(
                    "import com.mojang.blaze3d.opengl.GlStateManager;\nimport com.mojang.blaze3d.platform.NativeImage;\n"
                ) &&
                !Route.plan(home, "1.20.1", "1.21.6").stepsCovered(Json.text(rule, "jump", ""), "yarn").isEmpty(),
                "GlStateManager's 1.21.5 package move is rewritten in yarn and mojmap sources, so javac no longer stops at the import"
            );
        }
        check(
            learned >= 30,
            learned +
            " rules learned from Stonecutter switches name real switches in two or more mods, match their error, rewrite their example and run on their update"
        );
        // Every other rule that carries a test is held to the same three facts. Its error is read the way a port reads
        // javac's log, written with Windows line ends, so a match that spans lines holds on every machine.
        int written = 0;
        Path windowsLog = Files.createTempFile("rule-error", ".log");
        String qualifier = Port.logText(Files.writeString(windowsLog,
            "  symbol:   variable Builder\r\n  symbol:   variable EMPTY\r\n  symbol:   variable level\r\n"));
        check(
            qualifier.contains("symbol:   class Builder") && qualifier.contains("variable EMPTY") && qualifier.contains("variable level"),
            "a class javac calls a variable (named as a qualifier) is read as a class; constants and variables stay"
        );
        for (Object item : Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if ("stonecutter".equals(rule.get("source")) || !(rule.get("test") instanceof Map<?, ?>)) continue;
            String id = Json.text(rule, "id", "?");
            var test = Json.object(rule.get("test"));
            // A silent rule has no compiler error to match: it runs whenever the port crosses its update.
            if (
                !rule.containsKey("silent") &&
                !java.util.regex.Pattern.compile(rule.get("match").toString())
                    .matcher(Port.logText(Files.writeString(windowsLog, test.get("error").toString().replace("\n", "\r\n"))))
                    .find()
            ) throw new AssertionError(id + ": match misses its test error");
            String after = "line".equals(rule.get("scope"))
                ? Port.rewriteReported(rule, test.get("before").toString(), test.get("error").toString(), file -> true)
                : Port.rewrite(rule, test.get("before").toString());
            if (after.equals(test.get("before")) || !after.equals(test.get("after"))) throw new AssertionError(
                id + ": rewrite gave\n" + after + "\nexpected\n" + test.get("after")
            );
            String[] range = Json.text(rule, "jump", "").replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            if (
                rule.containsKey("silent") &&
                !(Port.crosses(Json.text(rule, "jump", ""), range[0], range[1]) &&
                    !Port.crosses(Json.text(rule, "jump", ""), range[1], range[1]))
            ) throw new AssertionError(
                id + ": a silent rule must run on the port across its own update and on no other"
            );
            Route route = Route.plan(home, range[0], range[1]);
            if (
                route == null ||
                route
                    .stepsCovered(
                        Json.text(rule, "jump", ""),
                        Json.text(rule, "jump", "").contains("yarn") ? "yarn" : "mojmap"
                    )
                    .isEmpty()
            ) throw new AssertionError(id + ": no route " + Json.text(rule, "jump", "") + " runs it");
            written++;
        }
        check(
            written >= 3,
            written + " written rules with a test match their error, rewrite their example and run on their update"
        );
        {
            var layer = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))
                .stream()
                .map(Json::object)
                .filter(r -> "layered-draw-layer-neoforge".equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("knowledge/fixes.json has no layered-draw-layer-neoforge rule"));
            check(
                Port.loaderFits(layer, Set.of("neoforge")) &&
                !Port.loaderFits(layer, Set.of("fabric")) &&
                Port.loaderFits(Json.map("id", "any"), Set.of("fabric")),
                "a rule for one loader (NeoForge's GuiLayer for the removed LayeredDraw.Layer) runs only on that loader's projects"
            );
        }
    }

    /**
     * A getter the game removed while keeping its field (1.21.2's HungerManager.getExhaustion()) becomes an accessor mixin
     * the port writes into the mod's mixin package, lists in its main mixins config, and deletes again on undo.
     */
    static void accessorChecks(Path home, Path scratch) throws Exception {
        String chain = "x = a.b(c, d(e)).f[0].getExhaustion();";
        check(
            chain.substring(Port.receiverStart(chain, chain.indexOf(".getExhaustion"))).startsWith("a.b(c"),
            "a call's receiver is the whole chain of names, calls and indexes before it"
        );
        String created = "return new Stats(1).getExhaustion();";
        check(
            created
                .substring(Port.receiverStart(created, created.indexOf(".getExhaustion")))
                .startsWith("new Stats(1)"),
            "a receiver made with new keeps its new"
        );
        Path root = scratch.resolve("accessor"), java = root.resolve("src/main/java/mod"), resources = root.resolve(
            "src/main/resources"
        );
        Files.createDirectories(java.resolve("mixin"));
        Files.createDirectories(java.resolve("client"));
        Files.createDirectories(java.resolve("compat"));
        Files.createDirectories(resources);
        Files.writeString(root.resolve("settings.gradle"), "");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n"
        );
        Files.writeString(java.resolve("mixin/HudMixin.java"), "package mod.mixin;\n\nclass HudMixin {}\n");
        Files.writeString(java.resolve("compat/JeiMixin.java"), "package mod.compat;\n\nclass JeiMixin {}\n");
        Path hud = java.resolve("client/HUD.java");
        String original =
            "package mod.client;\n\nclass HUD {\n\tfloat read(PlayerEntity player) {\n\t\treturn player.getHungerManager().getExhaustion();\n\t}\n\tfloat own(Payload payload) { return payload.getExhaustion(); }\n}\n";
        Files.writeString(hud, original);
        Path config = resources.resolve("mod.mixins.json");
        String listed =
            "{\n  \"required\": true,\n  \"package\": \"mod.mixin\",\n  \"client\": [\n    \"HudMixin\"\n  ]\n}\n";
        Files.writeString(config, listed);
        Files.writeString(
            resources.resolve("mod.compat.mixins.json"),
            "{\n  \"required\": false,\n  \"package\": \"mod.compat\",\n  \"mixins\": [\"JeiMixin\"]\n}\n"
        );
        ProjectModel project = new ProjectModel(root);
        Port port = new Port(
            new Build(project, home, "", new Mmt.Report(root, "port")),
            Json.map("projects", List.of())
        );
        port.transaction = project.state().resolve("ports/accessor");
        Files.createDirectories(port.transaction);
        port.saveInProgress();
        port.track(hud);
        var rule = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))
            .stream()
            .map(Json::object)
            .filter(r -> "hunger-exhaustion-accessor".equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("knowledge/fixes.json has no hunger-exhaustion-accessor rule"));
        String log =
            hud +
            ":5: error: cannot find symbol\n\t\treturn player.getHungerManager().getExhaustion();\n\t\t                                ^\n  symbol:   method getExhaustion()\n  location: class HungerManager\n";
        check(
            port.applyAccessor(rule, log),
            "a removed getter named by javac on its owner class is repaired by the accessor rule"
        );
        check(
            Files.readString(hud).split("\n", -1).length == original.split("\n", -1).length &&
            !Files.readString(hud).contains("import mod.mixin"),
            "the import a rule adds waits for the end of the round, so javac's line numbers still hold for the steps after it"
        );
        port.flushImports();
        Path accessor = java.resolve("mixin/HungerManagerAccessor.java");
        String source = Files.readString(accessor);
        check(
            source.contains("@Mixin(HungerManager.class)") &&
            source.contains("@Accessor(\"exhaustion\")") &&
            source.contains("float mmt$getExhaustion();") &&
            !source.contains("mmt$setExhaustion"),
            "the accessor mixin reads the field in the main mixin package, with no setter the mod never used"
        );
        check(
            Files.readString(config).contains(
                "\"package\": \"mod.mixin\",\n  \"mixins\": [\"HungerManagerAccessor\"],\n  \"client\""
            ),
            "the accessor is listed in the required mixins config, not the optional one: " + Files.readString(config)
        );
        String repaired = Files.readString(hud);
        check(
            repaired.contains("return ((HungerManagerAccessor) player.getHungerManager()).mmt$getExhaustion();") &&
            repaired.contains("return payload.getExhaustion();") &&
            repaired.contains("import mod.mixin.HungerManagerAccessor;"),
            "only the call javac reported goes through the accessor; the mod's own getter of the same name is kept"
        );
        Port.finishTransaction(project, port.transaction, port.before);
        Port.undo(project, port.transaction.getFileName().toString());
        check(
            !Files.exists(accessor) &&
            Files.readString(config).equals(listed) &&
            Files.readString(hud).equals(original),
            "undo, given the port's bare id, deletes the accessor it wrote and restores the config and the call"
        );
        check(
            Port.registerMixin("{\"package\": \"a\", \"mixins\": []}", "X").equals(
                "{\"package\": \"a\", \"mixins\": [\"X\"]}"
            ) &&
            Port.registerMixin("{\"package\": \"a\", \"mixins\": [\"Y\"]}", "X").equals(
                "{\"package\": \"a\", \"mixins\": [\"X\", \"Y\"]}"
            ),
            "a mixin goes first in a config's common list, empty or not"
        );
    }

    /**
     * A NeoForge mod with no mixins (Zombie Apocalypse Addon) still gets its accessor: the port adds a mixins config named
     * for the mod id, lists it in neoforge.mods.toml, writes the accessor in <@Mod package>.mixin, wraps the old setter's
     * UUID as the field's EntityReference, and undo takes all of it back out.
     */
    static void accessorWithoutMixinsChecks(Path home, Path scratch) throws Exception {
        Path root = scratch.resolve("accessor-no-mixins"), java = root.resolve("src/main/java/mod"), meta =
            root.resolve("src/main/resources/META-INF");
        Files.createDirectories(java);
        Files.createDirectories(meta);
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("settings.gradle"), "");
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n"
        );
        Files.writeString(root.resolve("gradle.properties"), "mod_id=zombiemod\n");
        Files.writeString(
            java.resolve("ZombieMod.java"),
            "package mod;\n\n@Mod(\"zombiemod\")\npublic class ZombieMod {}\n"
        );
        Path toml = meta.resolve("neoforge.mods.toml");
        String tomlText = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"${mod_id}\"\n";
        Files.writeString(toml, tomlText);
        Path hunt = java.resolve("ZombieHunt.java");
        String original =
            "package mod;\n\nclass ZombieHunt {\n    void f(ZombieHorse zombieHorse, UUID owner) {\n            zombieHorse.setOwnerUUID(owner);\n    }\n}\n";
        Files.writeString(hunt, original);
        ProjectModel project = new ProjectModel(root);
        Port port = new Port(
            new Build(project, home, "", new Mmt.Report(root, "port")),
            Json.map("projects", List.of())
        );
        port.transaction = project.state().resolve("ports/accessor-no-mixins");
        Files.createDirectories(port.transaction);
        port.saveInProgress();
        port.track(hunt);
        var rule = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))
            .stream()
            .map(Json::object)
            .filter(r -> "horse-owner-accessor-official".equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("knowledge/fixes.json has no horse-owner-accessor-official rule"));
        String log =
            hunt +
            ":5: error: cannot find symbol\n            zombieHorse.setOwnerUUID(owner);\n                       ^\n  symbol:   method setOwnerUUID(UUID)\n  location: variable zombieHorse of type ZombieHorse\n";
        check(
            port.applyAccessor(rule, log),
            "an accessor on a subclass's receiver (a ZombieHorse for AbstractHorse's field) runs in a mod with no mixins config"
        );
        port.flushImports();
        Path config = root.resolve("src/main/resources/zombiemod.mmt.mixins.json"), accessor = java.resolve(
            "mixin/AbstractHorseAccessor.java"
        );
        check(
            Files.isRegularFile(config) &&
            Json.text(Json.read(config), "package", "").equals("mod.mixin") &&
            Files.readString(config).contains("\"AbstractHorseAccessor\"") &&
            Files.readString(toml).contains("[[mixins]]\nconfig=\"zombiemod.mmt.mixins.json\""),
            "the port adds a mixins config named for the mod id from its gradle.properties, lists it in neoforge.mods.toml and lists the accessor in it"
        );
        check(
            Files.readString(accessor).contains(
                "void mmt$setOwner(net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.LivingEntity> value);"
            ) &&
            Files.readString(hunt).contains(
                "((AbstractHorseAccessor) zombieHorse).mmt$setOwner(java.util.Optional.ofNullable(owner).map("
            ),
            "the removed setter's UUID is passed as the field's EntityReference through the accessor's setter"
        );
        Port.finishTransaction(project, port.transaction, port.before);
        Port.undo(project, port.transaction.getFileName().toString());
        check(
            !Files.exists(config) &&
            !Files.exists(accessor) &&
            Files.readString(toml).equals(tomlText) &&
            Files.readString(hunt).equals(original),
            "undo removes the mixins config, its mods.toml entry and the accessor it wrote"
        );
    }

    /** Mixin targets that a port leaves stale, repaired from the class map and from Mixin's own words (AppleSkin 1.20.1 -> 1.21.6). */
    static void mixinRepairChecks() {
        Map<String, String> classes = Map.of(
            "net.minecraft.client.item.TooltipData",
            "net.minecraft.item.tooltip.TooltipData",
            "net.minecraft.client.item.TooltipContext$Default",
            "net.minecraft.item.tooltip.TooltipType$Default"
        );
        String strings =
            "method = \"of(Lnet/minecraft/client/item/TooltipData;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;\"\n@Mixin(targets = \"net.minecraft.client.item.TooltipContext$Default\")\nTooltipData data = null; // net/minecraft/client/item/TooltipData\n";
        check(
            Port.classNamesInStrings(strings, classes).equals(
                "method = \"of(Lnet/minecraft/item/tooltip/TooltipData;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;\"\n@Mixin(targets = \"net.minecraft.item.tooltip.TooltipType$Default\")\nTooltipData data = null; // net/minecraft/client/item/TooltipData\n"
            ),
            "a moved game class inside a string gets its full new name, in descriptor and dotted form; code and comments are left to the other renames"
        );
        String stack =
            "\t@Inject(at = @At(\"RETURN\"), method = \"getTooltip(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;\")\n\tprivate void getTooltipFromItem(PlayerEntity player, TooltipType context, CallbackInfoReturnable<List> info)\n\t{\n\t}\n";
        String named = Port.repairMixinSource(
            stack,
            "Critical injection failure: @Inject annotation on getTooltipFromItem could not find any targets matching 'getTooltip(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;' in net/minecraft/item/ItemStack. No refMap loaded."
        );
        check(
            named.contains("method = \"getTooltip\")"),
            "a target pinned to a descriptor the game no longer has is named by its method name alone"
        );
        String handler = Port.repairMixinSource(
            named,
            "Invalid descriptor on appleskin.mixins.json:ItemStackMixin from mod appleskin->@Inject::getTooltipFromItem(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V! Expected (Lnet/minecraft/item/Item$TooltipContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V but found (Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V [INJECT_APPLY]"
        );
        check(
            handler.contains(
                "private void getTooltipFromItem(Item.TooltipContext mmt$tooltipContext0, PlayerEntity player, TooltipType context, CallbackInfoReturnable<List> info)"
            ) &&
            handler.startsWith("import net.minecraft.item.Item;\n"),
            "a handler gets the parameter its target gained, in Mixin's order, and keeps its own names: " +
            handler.lines().filter(l -> l.contains("getTooltipFromItem(")).findFirst().orElse("")
        );
        check(
            Port.repairMixinSource(
                named,
                "Invalid descriptor on x->@Inject::getTooltipFromItem(La;)V! Expected (Lb;)V but found (La;)V"
            ).equals(named),
            "a handler whose own parameter is gone is left for a person, not guessed"
        );
    }

    /** Step 9: Loom follows the target. The new version lands where the build states it, a literal plugin line or the property it reads. */
    static void loomEditChecks(Path home, Path scratch) throws Exception {
        for (boolean property : List.of(false, true)) {
            Path root = scratch.resolve("loom-" + property);
            Files.createDirectories(root);
            Files.writeString(
                root.resolve("build.gradle"),
                "plugins {\n\tid 'fabric-loom' version " +
                (property ? "\"${loom_version}\"" : "'1.8-SNAPSHOT'") +
                "\n}\n"
            );
            Files.writeString(
                root.resolve("gradle.properties"),
                property ? "loom_version=1.8-SNAPSHOT\nother=1\n" : "other=1\n"
            );
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
            );
            ProjectModel project = new ProjectModel(root);
            check(
                project.loomVersion().equals("1.8-SNAPSHOT"),
                "the Loom version is read from " + (property ? "the property the plugin line names" : "the plugin line")
            );
            Build build = new Build(project, home, "", new Mmt.Report(root, "port"));
            Target.Setup setup = new Target.Setup(build, Json.map(), "1.21.6", List.of());
            check(Target.loomEdit(setup, build, "1.8-SNAPSHOT", "1.10.5"), "a stated Loom version can be raised");
            Path changed = root.resolve(property ? "gradle.properties" : "build.gradle");
            check(
                setup
                    .text(changed)
                    .contains(property ? "loom_version=1.10.5\n" : "id 'fabric-loom' version '1.10.5'") &&
                setup.edits().size() == 1,
                "Loom 1.8-SNAPSHOT -> 1.10.5 is written to " + changed.getFileName() + " and nowhere else"
            );
        }
    }

    /** Parchment follows the target: names for another version once compiled the 26.3 game wrong (BundlerInfo "packet == packet"). */
    static void parchmentChecks(Path home, Path scratch) throws Exception {
        String neoGradle =
            "neo_version=21.6.11-beta\nneogradle.subsystems.parchment.minecraftVersion=1.21.5\nneogradle.subsystems.parchment.mappingsVersion=2025.06.15\nother=1\n";
        String modDevProps = "parchment_minecraft_version=1.21.1\nparchment_mappings_version=2024.11.17\nother=1\n";
        String modDevBuild =
            "neoForge {\n    parchment {\n        mappingsVersion = project.parchment_mappings_version\n        minecraftVersion = project.parchment_minecraft_version\n    }\n    runs { }\n}\n";
        String loomBuild =
            "dependencies {\n\tmappings loom.layered {\n\t\tofficialMojangMappings()\n\t\tparchment(\"org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip\")\n\t}\n}\n";
        record Case(String name, String props, String build, String release, String wantProps, String wantBuild) {}
        List<Case> cases = List.of(
            new Case(
                "NeoGradle, none for 26.3",
                neoGradle,
                "plugins { }\n",
                "",
                "neo_version=21.6.11-beta\nother=1\n",
                null
            ),
            new Case(
                "NeoGradle, 1.21.6 published",
                neoGradle,
                "plugins { }\n",
                "2025.06.29",
                "neo_version=21.6.11-beta\nneogradle.subsystems.parchment.minecraftVersion=1.21.6\nneogradle.subsystems.parchment.mappingsVersion=2025.06.29\nother=1\n",
                null
            ),
            new Case(
                "ModDevGradle, none for 26.3",
                modDevProps,
                modDevBuild,
                "",
                "other=1\n",
                "neoForge {\n    runs { }\n}\n"
            ),
            new Case(
                "Loom layered, none for 26.3",
                "other=1\n",
                loomBuild,
                "",
                null,
                "dependencies {\n\tmappings loom.layered {\n\t\tofficialMojangMappings()\n\t}\n}\n"
            ),
            new Case(
                "Loom layered, 1.21.6 published",
                "other=1\n",
                loomBuild,
                "2025.06.29",
                null,
                loomBuild.replace("parchment-1.21.1:2024.11.17", "parchment-1.21.6:2025.06.29")
            )
        );
        for (Case c : cases) {
            Path root = scratch.resolve("parchment-" + c.name().replaceAll("\\W+", "-"));
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
            );
            Files.writeString(root.resolve("gradle.properties"), c.props());
            Files.writeString(root.resolve("build.gradle"), c.build());
            Build build = new Build(new ProjectModel(root), home, "", new Mmt.Report(root, "port"));
            Target.Setup setup = new Target.Setup(
                build,
                Json.map(),
                c.release().isEmpty() ? "26.3" : "1.21.6",
                List.of()
            );
            check(
                Target.parchmentEdit(setup, build, setup.to, c.release()) &&
                (c.wantProps() == null || setup.text(root.resolve("gradle.properties")).equals(c.wantProps())) &&
                (c.wantBuild() == null || setup.text(root.resolve("build.gradle")).equals(c.wantBuild())),
                "Parchment follows the target (" +
                c.name() +
                "): " +
                setup.text(root.resolve("gradle.properties")).replace("\n", "|") +
                " / " +
                setup.text(root.resolve("build.gradle")).replace("\n", "|")
            );
        }
        Path plain = scratch.resolve("parchment-none");
        Files.createDirectories(plain.resolve("gradle/wrapper"));
        Files.writeString(
            plain.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
        );
        Files.writeString(plain.resolve("gradle.properties"), "other=1\n");
        Files.writeString(plain.resolve("build.gradle"), "plugins { }\n");
        Build plainBuild = new Build(new ProjectModel(plain), home, "", new Mmt.Report(plain, "port"));
        Target.Setup untouched = new Target.Setup(plainBuild, Json.map(), "26.3", List.of());
        check(
            !Target.parchmentEdit(untouched, plainBuild, "26.3", null) && untouched.edits().isEmpty(),
            "a build without Parchment is left alone and nothing is looked up"
        );
        neoGradleChecks(home, scratch);
        unitTestsInFmlChecks(home, scratch);
    }

    /** NeoGradle follows a forward port (Notes 1.21 -> 1.21.6 stopped at launch on NeoGradle 7.0.145's ASM 9.5) to one version for every mod, and Gradle follows NeoGradle. */
    static void neoGradleChecks(Path home, Path scratch) throws Exception {
        for (String to : List.of("1.21.6", "1.20.6")) {
            Path root = scratch.resolve("neogradle-" + to);
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n"
            );
            Files.writeString(
                root.resolve("build.gradle"),
                "plugins {\n    id 'net.neoforged.gradle.userdev' version '7.0.145'\n    id 'net.neoforged.gradle.mixin' version '7.0.145'\n}\n\nmixin {\n    config \"${mod_id}.mixins.json\"\n}\n"
            );
            Files.writeString(root.resolve("gradle.properties"), "minecraft_version=1.21\nmod_id=demo\n");
            Files.createDirectories(root.resolve("src/main/resources/META-INF"));
            Files.writeString(
                root.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
                "[[mods]]\nmodId=\"${mod_id}\"\n"
            );
            Build build = new Build(new ProjectModel(root), home, "", new Mmt.Report(root, "port"));
            // The two lookups NeoGradle's planning makes, answered from the metadata cache Target.fetch reads first.
            Map<String, String> answers = Map.of(
                "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml",
                "<metadata><versioning><versions><version>7.0.145</version><version>7.0.192</version><version>7.1.38</version></versions></versioning></metadata>",
                "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/7.1.38/userdev-7.1.38.module",
                "{\"variants\": [{\"attributes\": {\"org.gradle.plugin.api-version\": \"8.14\"}}]}"
            );
            for (var answer : answers.entrySet()) {
                Path cached = build.project
                    .state()
                    .resolve(
                        "metadata/" +
                        Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                        ".json"
                    );
                Files.createDirectories(cached.getParent());
                Files.writeString(cached, answer.getValue());
            }
            Target.Setup setup = new Target.Setup(build, Json.map(), to, List.of());
            setup.pins.put("minecraft_version", "1.21");
            Target.neoGradleFollows(setup, build, to);
            String plugin = setup.text(root.resolve("build.gradle")), wrapper = setup.text(
                root.resolve("gradle/wrapper/gradle-wrapper.properties")
            );
            if (to.equals("1.21.6")) check(
                plugin.contains("userdev' version '7.1.38'") &&
                !plugin.contains("gradle.mixin") &&
                !plugin.contains("mixin {") &&
                setup
                    .text(root.resolve("src/main/resources/META-INF/neoforge.mods.toml"))
                    .contains("[[mixins]]\nconfig = \"${mod_id}.mixins.json\"") &&
                wrapper.contains("gradle-8.14-bin.zip"),
                "a forward port moves NeoGradle to the newest 7.x, the same for every mod so they share one game setup (7.0.145 -> 7.1.38), drops the mixin plugin 7.1 no longer has (Jade's failed to apply) with its config declared in the mods.toml instead, and moves Gradle to what it requires (8.9 -> 8.14): " +
                plugin.strip() +
                " / " +
                wrapper.strip()
            );
            else check(setup.edits().isEmpty(), "a backport keeps its NeoGradle and Gradle");
        }
    }

    /**
     * A NeoGradle mod's JUnit tests move onto junit-fml on a port into 26.x (its Commands class needs the game
     * bootstrapped, which only FancyModLoader can do there), with FML's version read from NeoForge's published pom.
     */
    static void unitTestsInFmlChecks(Path home, Path scratch) throws Exception {
        for (String to : List.of("26.3", "1.21.6")) {
            Path root = scratch.resolve("junit-fml-" + to);
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip\n"
            );
            Files.writeString(root.resolve("build.gradle"), "plugins {\n    id 'net.neoforged.gradle.userdev' version '7.1.39'\n}\n");
            Files.writeString(root.resolve("gradle.properties"), "minecraft_version=1.21.1\nmod_id=demo\n");
            Files.createDirectories(root.resolve("src/main/resources/META-INF"));
            Files.writeString(root.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "[[mods]]\nmodId=\"${mod_id}\"\n");
            Files.createDirectories(root.resolve("src/test/java/demo"));
            Files.writeString(
                root.resolve("src/test/java/demo/CommandsTest.java"),
                "package demo;\n\nimport org.junit.jupiter.api.Test;\n\nclass CommandsTest {\n    @Test\n    void tree() {}\n}\n"
            );
            Build build = new Build(new ProjectModel(root), home, "", new Mmt.Report(root, "port"));
            String pom = "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.103/neoforge-26.3.0.103.pom";
            Path cached = build.project
                .state()
                .resolve("metadata/" + Fingerprints.bytes(pom.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
            Files.createDirectories(cached.getParent());
            Files.writeString(
                cached,
                "<project><dependencies><dependency><groupId>net.neoforged.fancymodloader</groupId><artifactId>loader</artifactId><version>12.0.12</version></dependency></dependencies></project>"
            );
            Target.Setup setup = new Target.Setup(build, Json.map(), to, List.of());
            setup.pins.put("minecraft_version", "1.21.1");
            Target.unitTestsInFml(setup, build, to, "26.3.0.103");
            String script = setup.text(root.resolve("build.gradle"));
            if (to.equals("26.3")) check(
                script.contains("testRuntimeOnly 'net.neoforged.fancymodloader:junit-fml:12.0.12'") &&
                script.contains("unitTestSource sourceSets.test") &&
                script.contains("demo%%${it}") &&
                script.contains("java.base/java.lang.invoke=ALL-UNNAMED"),
                "a port into 26.x runs a NeoGradle mod's JUnit tests on junit-fml (FML 12.0.12 from NeoForge's pom), the mod and its tests in FML's class loader: " +
                script
            );
            else check(setup.edits().isEmpty(), "a port that stays before 26.1 leaves the tests where they run");
        }
    }

    static void carryChecks() {
        // A removed game class is carried from the old game's source: a static nested one whole, braces matched; an inner
        // (non-static) one needs its outer instance, so it is never carried.
        String oldGame = "public class BlockEntityType<T> {\n    private final Set<Block> validBlocks;\n"
            + "    public static final class Builder<T extends BlockEntity> {\n        final Set<Block> validBlocks;\n"
            + "        public BlockEntityType<T> build(Type<?> t) { if (t == null) { return null; } return new BlockEntityType<>(); }\n    }\n"
            + "    class Inner { }\n}\n";
        String carried = Port.nestedDeclaration(oldGame, "Builder");
        if (carried == null || !carried.startsWith("    public static final class Builder") || !carried.endsWith("    }") || carried.contains("Inner")) throw new IllegalStateException("static nested class carried whole: " + carried);
        if (Port.nestedDeclaration(oldGame, "Inner") != null) throw new IllegalStateException("an inner class is not carried");
        // An access transformer follows the map: a renamed method keeps its access under the new name and descriptor, a
        // field under its new name, a moved class in a descriptor follows; an entry the map does not know stays as written.
        List<String> at = new ArrayList<>(List.of(
            "public net.minecraft.client.gui.GuiGraphics blitSprite(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIIIIIII)V # Jade",
            "public net.minecraft.client.renderer.LevelRenderer rainSoundTime",
            "protected net.minecraft.world.entity.animal.Sheep getOffspringColor(Lnet/minecraft/world/entity/animal/Animal;Lold/Thing;)Lnet/minecraft/world/item/DyeColor;",
            "public net.minecraft.world.level.biome.Biome TEMPERATURE_NOISE"
        ));
        Map<String, String[]> members = new HashMap<>();
        members.put("net.minecraft.client.gui.GuiGraphics blitSprite(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIIIIIII)V", new String[] { "net.minecraft.client.gui.GuiGraphics", "blitSprite", "(Ljava/util/function/Function;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIIIIIIII)V" });
        members.put("net.minecraft.client.renderer.LevelRenderer rainSoundTime", new String[] { "net.minecraft.client.renderer.WeatherEffectRenderer", "rainSoundTime", "" });
        int followed = Port.followMap(at, Map.of("old.Thing", "fresh.Thing"), members);
        if (followed != 3
            || !at.get(0).equals("public net.minecraft.client.gui.GuiGraphics blitSprite(Ljava/util/function/Function;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIIIIIIII)V # Jade")
            || !at.get(1).equals("public net.minecraft.client.renderer.WeatherEffectRenderer rainSoundTime")
            || !at.get(2).contains("(Lnet/minecraft/world/entity/animal/Animal;Lfresh/Thing;)")
            || !at.get(3).equals("public net.minecraft.world.level.biome.Biome TEMPERATURE_NOISE")) throw new IllegalStateException("access transformer follows the map: " + at);
        String top = Port.topLevel("package a;\n\nimport b.C;\n\n@Deprecated\npublic final class Gone {\n    int x;\n}\n", "Gone");
        if (top == null || !top.startsWith("@Deprecated\npublic final class Gone") || !top.endsWith("}")) throw new IllegalStateException("a top-level class is carried from its annotations on: " + top);
        count++;
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        learnedRuleChecks(home);
        loomEditChecks(home, scratch);
        accessorChecks(home, scratch);
        accessorWithoutMixinsChecks(home, scratch);
        mixinRepairChecks();
        parchmentChecks(home, scratch);
        carryChecks();
        System.out.println(count + " port repair checks passed");
    }
}
