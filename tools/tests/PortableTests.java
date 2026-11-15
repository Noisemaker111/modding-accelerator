import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import javax.imageio.ImageIO;

/** Outcome checks: stale bytes, empty test reports, rollback conflicts, and unrelated checkouts. */
public final class PortableTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** Hub routing from the change index, rule ranges, the pre-1.14.4 refusal and errors named by update. */
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
        Map<String, Object> refused = Route.blocked(home, "1.12.2", "1.20.1");
        check(
            refused != null &&
            "blocked".equals(refused.get("tier")) &&
            String.valueOf(refused.get("details")).contains("1.14.4") &&
            Route.blocked(home, "1.20.1", "1.21.6") == null,
            "releases before 1.14.4 are refused with the reason; later ones are not: " +
            (refused == null ? null : refused.get("details"))
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
        // Every other rule that carries a test is held to the same three facts.
        int written = 0;
        for (Object item : Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if ("stonecutter".equals(rule.get("source")) || !(rule.get("test") instanceof Map<?, ?>)) continue;
            String id = Json.text(rule, "id", "?");
            var test = Json.object(rule.get("test"));
            // A silent rule has no compiler error to match: it runs whenever the port crosses its update.
            if (
                !rule.containsKey("silent") &&
                !java.util.regex.Pattern.compile(rule.get("match").toString())
                    .matcher(test.get("error").toString())
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
            new Build(project, home, "", new Modaccel.Report(root, "port")),
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
            source.contains("float modaccel$getExhaustion();") &&
            !source.contains("modaccel$setExhaustion"),
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
            repaired.contains("return ((HungerManagerAccessor) player.getHungerManager()).modaccel$getExhaustion();") &&
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
            new Build(project, home, "", new Modaccel.Report(root, "port")),
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
        Path config = root.resolve("src/main/resources/zombiemod.modaccel.mixins.json"), accessor = java.resolve(
            "mixin/AbstractHorseAccessor.java"
        );
        check(
            Files.isRegularFile(config) &&
            Json.text(Json.read(config), "package", "").equals("mod.mixin") &&
            Files.readString(config).contains("\"AbstractHorseAccessor\"") &&
            Files.readString(toml).contains("[[mixins]]\nconfig=\"zombiemod.modaccel.mixins.json\""),
            "the port adds a mixins config named for the mod id from its gradle.properties, lists it in neoforge.mods.toml and lists the accessor in it"
        );
        check(
            Files.readString(accessor).contains(
                "void modaccel$setOwner(net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.LivingEntity> value);"
            ) &&
            Files.readString(hunt).contains(
                "((AbstractHorseAccessor) zombieHorse).modaccel$setOwner(java.util.Optional.ofNullable(owner).map("
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
                "private void getTooltipFromItem(Item.TooltipContext modaccel$tooltipContext0, PlayerEntity player, TooltipType context, CallbackInfoReturnable<List> info)"
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
            Build build = new Build(project, home, "", new Modaccel.Report(root, "port"));
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
            Build build = new Build(new ProjectModel(root), home, "", new Modaccel.Report(root, "port"));
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
        Build plainBuild = new Build(new ProjectModel(plain), home, "", new Modaccel.Report(plain, "port"));
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
            Build build = new Build(new ProjectModel(root), home, "", new Modaccel.Report(root, "port"));
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
            Build build = new Build(new ProjectModel(root), home, "", new Modaccel.Report(root, "port"));
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

    static Map<String, Object> testModel(String... runtime) {
        return Json.map(
            "projects",
            List.of(
                Json.map(
                    "sourceSets",
                    List.of(
                        Json.map("name", "main", "runtime", List.of()),
                        Json.map("name", "test", "runtime", List.of(runtime), "output", List.of())
                    )
                )
            )
        );
    }

    /** Which tests fast selects (empty-run wording is covered by StrangerTest.noTests and FastLaneIsolationTest.plain). */
    static void fastLaneChecks() throws Exception {
        String junit =
            "/home/u/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api/5.13.4/x/junit-jupiter-api-5.13.4.jar";
        check(
            FastLane.gameFree(
                testModel(
                    junit,
                    "/home/u/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.214-stable/x/paper-api-26.3.build.214-stable.jar"
                )
            ),
            "a Paper plugin's test classpath (paper-api, JUnit) is game-free, so fast runs every test"
        );
        check(FastLane.gameFree(testModel(junit)), "a plain Java test classpath is game-free");
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "C:\\Users\\u\\.gradle\\caches\\fabric-loom\\minecraftMaven\\net\\minecraft\\minecraft-merged-1.21.6\\minecraft-merged.jar",
                    "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\net.fabricmc\\fabric-loader\\0.16.14\\x\\fabric-loader-0.16.14.jar"
                )
            ),
            "a Fabric test classpath has the game, so fast keeps running only tests tagged pure"
        );
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "/p/build/neoForge/neoFormJoined/1.21.1/artifacts/neoforge-21.1.77.jar",
                    "/home/u/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.77/x/neoforge-21.1.77-universal.jar"
                )
            ),
            "a NeoForge test classpath has the game, so fast keeps running only tests tagged pure"
        );
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "/home/u/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0.jar"
                )
            ),
            "a Forge test classpath has the game"
        );
        check(!FastLane.gameFree(Json.map("projects", List.of())), "no test source set is never treated as game-free");
    }

    /** plugin-check picks MockBukkit by the plugin's own Paper API line, and refuses a line it has no MockBukkit for. */
    static void pluginCheckChecks(Path home) throws Exception {
        Map<String, Object> paper = Json.map(
            "projects",
            List.of(
                Json.map(
                    "compile",
                    List.of(
                        Json.map(
                            "classpath",
                            List.of(
                                "/home/u/.gradle/caches/modules-2/files-2.1/net.kyori/adventure-api/4.24.0/x/adventure-api-4.24.0.jar",
                                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\io.papermc.paper\\paper-api\\26.3.build.214-stable\\x\\paper-api-26.3.build.214-stable.jar"
                            )
                        )
                    )
                )
            )
        );
        var api = PluginCheck.api(paper);
        check(
            api != null && api.kind().equals("paper-api") && api.version().equals("26.3.build.214-stable"),
            "plugin-check reads the Paper API version from the compile class path: " + api
        );
        var spigot = PluginCheck.api(
            Json.map(
                "projects",
                List.of(
                    Json.map(
                        "compile",
                        List.of(
                            Json.map(
                                "classpath",
                                List.of(
                                    "/m2/org/spigotmc/spigot-api/1.21.4-R0.1-SNAPSHOT/spigot-api-1.21.4-R0.1-SNAPSHOT.jar"
                                )
                            )
                        )
                    )
                )
            )
        );
        check(
            spigot != null && spigot.kind().equals("spigot-api") && PluginCheck.line(spigot.version()).equals("1.21"),
            "a Spigot plugin is checked on the Paper line of its version: " + spigot
        );
        check(
            PluginCheck.api(testModel("/m2/net/fabricmc/fabric-loader-0.16.14.jar")) == null,
            "a Fabric mod has no server API, so plugin-check says it is not a plugin"
        );
        var r263 = PluginCheck.release(home, "26.3.build.214-stable");
        var r121 = PluginCheck.release(home, "1.21.10-R0.1-SNAPSHOT");
        check(
            r263 != null && r263.artifact().endsWith("mockbukkit-v26.3") && r263.java() == 25,
            "Paper 26.3 is checked on MockBukkit for 26.3: " + r263
        );
        check(
            r121 != null && r121.artifact().endsWith("mockbukkit-v1.21") && r121.java() == 21,
            "Paper 1.21.10 is checked on MockBukkit for 1.21: " + r121
        );
        check(
            PluginCheck.release(home, "1.20.6-R0.1-SNAPSHOT") == null,
            "a Paper line with no MockBukkit release in the table is not guessed (it is reported unchecked)"
        );
        String text = PluginCheck.text(
            Json.map(
                "failed",
                true,
                "plugin",
                Json.map("name", "X", "version", "1"),
                "server",
                Json.map("paperApi", "26.3"),
                "checks",
                List.of(
                    Json.map(
                        "name",
                        "commands",
                        "status",
                        "fail",
                        "details",
                        "/x has no handler.",
                        "file",
                        Path.of("/p/src/main/resources/plugin.yml").toAbsolutePath().toString(),
                        "line",
                        9
                    )
                ),
                "unchecked",
                List.of("uses NMS"),
                "warnings",
                List.of()
            ),
            Path.of("/p").toAbsolutePath()
        );
        check(
            text.startsWith("plugin-check FAILED for X 1") &&
            text.contains("FAIL commands: /x has no handler. (src/main/resources/plugin.yml:9)") &&
            text.contains("UNCHECKED uses NMS"),
            "a plugin-check failure names the check, the file relative to the project and the line: " + text
        );
    }

    /**
     * convert: every rule in knowledge/loaders.json against its own before/after test, both ways; then a small Fabric
     * mod moved to NeoForge and back with --no-compile (the release lookups come from a seeded cache, so no network),
     * and both conversions undone byte for byte.
     */
    static void convertChecks(Path home, Path scratch) throws Exception {
        Map<String, Object> rules = Json.read(home.resolve("knowledge/loaders.json"));
        int tested = 0;
        for (String kind : List.of("types", "expressions", "statements", "events")) for (Object item : Json.array(
            rules.get(kind)
        )) {
            var rule = Json.object(item);
            var test = Json.object(rule.get("test"));
            String fabric = Json.text(test, "fabric", ""), neo = Json.text(test, "neoforge", ""), back = Json.text(
                test,
                "back",
                fabric
            );
            // A rule written for a Minecraft range is tested at the version its test names (the newest release otherwise).
            String game = Json.text(test, "minecraft", "26.3");
            String forward = new LoaderRules(rules, "fabric", "neoforge", "demo", game).apply(fabric).text;
            String reverse = new LoaderRules(rules, "neoforge", "fabric", "demo", game).apply(neo).text;
            if (!forward.equals(neo)) throw new AssertionError(
                "rule " + rule.get("id") + " to NeoForge gave:\n" + forward
            );
            if (!reverse.equals(back)) throw new AssertionError(
                "rule " + rule.get("id") + " back to Fabric gave:\n" + reverse
            );
            tested++;
        }
        check(
            tested >= 19,
            "every loader rule turns its Fabric test into its NeoForge test and back (" + tested + " rules)"
        );
        check(
            LoaderRules.find(
                "String s = \"FabricLoader.getInstance().getGameDir()\"; // FabricLoader.getInstance().getGameDir()",
                "FabricLoader.getInstance().getGameDir()"
            ).isEmpty(),
            "a rule never matches inside a string or a comment"
        );
        check(
            Convert.mavenRange("~26.3").equals("[26.3,26.4)") &&
            Convert.mavenRange(">=1.2 <2").equals("[1.2,2)") &&
            Convert.mavenRange("*").equals("[0,)") &&
            Convert.mavenRange("26.1.2").equals("[26.1.2]") &&
            Convert.fabricPredicate("[26.3,26.4)").equals("~26.3") &&
            Convert.fabricPredicate("[1.2,)").equals(">=1.2"),
            "Fabric version predicates and Maven ranges convert both ways"
        );
        check(
            Convert.neoforgeModId("pointless-piglins").equals("pointless_piglins") && Convert.neoforgeModId("demo").equals("demo") &&
            Convert.neoforgeModId("2fast").equals("mod_2fast"),
            "a Fabric mod id with a hyphen becomes one NeoForge accepts"
        );
        // Found by an independent check: modaccel package copied whatever desktop app build was in app/target/release.
        Path app = scratch.resolve("stale-app/app");
        Files.createDirectories(app.resolve("src"));
        Files.createDirectories(app.resolve("target/release"));
        Path exe = app.resolve("target/release/" + Installation.DESKTOP_WINDOWS), source = app.resolve("src/main.rs");
        Files.writeString(exe, "MZ");
        Files.writeString(source, "fn main() {}\n");
        Files.writeString(app.resolve("target/release/build.log"), "later output\n");
        Files.setLastModifiedTime(exe, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(1_000_000_500_000L));
        Path stale = Installation.newerSource(app, exe);
        Files.setLastModifiedTime(exe, java.nio.file.attribute.FileTime.fromMillis(1_000_001_000_000L));
        check(
            source.equals(stale) && Installation.newerSource(app, exe) == null,
            "a desktop app build older than the app's sources is found stale, and its own build output does not count: " + stale
        );
        // Found by an independent check on Krypton: NeoForge 26.1+ logs an ERROR for every @OnlyIn class (OnlyInWarningsHandler).
        String sided = "import net.fabricmc.api.EnvType;\nimport net.fabricmc.api.Environment;\n\n@Environment(EnvType.CLIENT)\nclass A {\n\t// @Environment(EnvType.SERVER)\n\t@Environment(EnvType.CLIENT) void draw() {}\n}\n";
        String unsided = Convert.withoutSideOnly(sided);
        check(
            unsided.equals("import net.fabricmc.api.EnvType;\n\nclass A {\n\t// @Environment(EnvType.SERVER)\n\tvoid draw() {}\n}\n") &&
            Convert.ONLY_IN_ERROR.equals("26.1"),
            "converted to NeoForge 26.1 or later, Fabric's @Environment marks go instead of becoming @OnlyIn:\n" + unsided
        );
        // Found by the convert sweep on MoreRed (26.1.2): its gradle.properties names only neo_version.
        check(
            Convert.minecraftOfNeoForge("26.1.2.48-beta").equals("26.1.2") &&
            Convert.minecraftOfNeoForge("26.1.0.3").equals("26.1") &&
            Convert.minecraftOfNeoForge("21.1.250").equals("1.21.1") &&
            Convert.minecraftOfNeoForge("21.0.167").equals("1.21") &&
            Convert.minecraftOfNeoForge("20.4.232").equals("1.20.4") &&
            Convert.minecraftOfNeoForge("${neo}").isEmpty(),
            "the Minecraft version is read from a NeoForge release number, before and after 26.1"
        );
        // Found by the convert sweep on Client Commands and SeedMapper: Loom configured in two loom blocks and in
        // top-level loom. statements; all of it goes with the plugin.
        String loomTwice = "loom {\n\taccessWidenerPath = file('a')\n}\n\nloom.runs.configureEach {\n\tvmArg '-Dx'\n}\ndelete loom.runs.client.runDirectory\n\nloom {\n\tmixin { useLegacyMixinAp = false }\n}\n";
        int[] firstLoom = Convert.block(loomTwice, "loom");
        int[] secondLoom = Convert.nextBlock(loomTwice, "loom", firstLoom[1]);
        List<String> loomDropped = new ArrayList<>();
        String loomLeft = Convert.dropStatements(loomTwice, Convert::loomStatement, loomDropped);
        check(
            secondLoom != null && loomTwice.substring(secondLoom[0]).startsWith("loom {\n\tmixin") &&
            loomDropped.size() == 2 && !loomLeft.contains("loom.") && loomLeft.contains("mixin {"),
            "every loom block is found, and top-level loom. statements are dropped: " + loomDropped
        );
        // Found by the convert sweep on Client Commands: a configuration of its own, includedLibrary, became jarJardLibrary.
        check(
            Convert.loomConfigurationsToNeoForge("\tincludedLibrary \"a:b:1\"\n").equals("\tincludedLibrary \"a:b:1\"\n") &&
            Convert.loomConfigurationsToNeoForge("\tinclude implementation(\"a:b:1\")\n").equals("\tjarJar implementation(\"a:b:1\")\n") &&
            Convert.loomConfigurationsToNeoForge("\tmodImplementation include(\"a:b:1\")\n").equals("\timplementation jarJar(\"a:b:1\")\n"),
            "Loom's configurations become ModDevGradle's, and only whole configuration names"
        );
        List<String> unconverted = new ArrayList<>();
        List<String> transformers = Convert.widenerToTransformer(
            "classTweaker v1 official\n# comment\naccessible class net/minecraft/A$B\nextendable class net/minecraft/C\n" +
            "accessible method net/minecraft/D run (I)V\nextendable method net/minecraft/D run (I)V\n" +
            "accessible field net/minecraft/E size I\nmutable field net/minecraft/E size I\n" +
            "transitive-accessible method net/minecraft/F <init> ()V\ninject-interface net/minecraft/G demo/Extra\n",
            true,
            unconverted
        );
        check(
            transformers.equals(
                List.of(
                    "public net.minecraft.A$B",
                    "public-f net.minecraft.C",
                    "public-f net.minecraft.D run(I)V",
                    "public-f net.minecraft.E size",
                    "public net.minecraft.F <init>()V"
                )
            ) &&
            unconverted.equals(List.of("inject-interface net/minecraft/G demo/Extra")),
            "a Fabric access widener becomes NeoForge access transformer lines, entries for one target merged: " +
            transformers +
            " " +
            unconverted
        );
        List<String> yarnLeft = new ArrayList<>();
        check(
            Convert.widenerToTransformer("accessWidener v2 named\naccessible class net/minecraft/a\n", false, yarnLeft).isEmpty() &&
            yarnLeft.size() == 1,
            "an access widener in yarn's names is named, not converted"
        );
        List<String> widenerLeft = new ArrayList<>();
        String widener = Convert.transformerToWidener(
            "# opens\npublic net.minecraft.A$B\npublic-f net.minecraft.C\nprotected-f net.minecraft.D run(I)V\npublic net.minecraft.E size\npublic net.minecraft.H *()\n",
            true,
            widenerLeft
        );
        check(
            widener.equals(
                "accessWidener v2 official\naccessible class net/minecraft/A$B\nextendable class net/minecraft/C\n" +
                "accessible method net/minecraft/D run (I)V\nextendable method net/minecraft/D run (I)V\n"
            ) &&
            widenerLeft.size() == 2 &&
            widenerLeft.get(0).startsWith("public net.minecraft.E size") &&
            widenerLeft.get(1).equals("public net.minecraft.H *()") &&
            Convert.transformerToWidener("public net.minecraft.A\n", false, new ArrayList<>()).startsWith("accessWidener v2 named\n"),
            "NeoForge access transformer lines become a Fabric access widener; a field (no type) and a wildcard are named:\n" +
            widener +
            widenerLeft
        );
        Map<String, String> fieldTypes = Convert.mappingFieldTypes(
            "# {\"fileName\":\"client.txt\"}\nnet.minecraft.client.renderer.block.ModelBlockRenderer -> fxx:\n" +
            "    net.minecraft.client.color.block.BlockColors blockColors -> b\n    int[][] grid -> c\n" +
            "    15:20:void <init>(net.minecraft.client.color.block.BlockColors) -> <init>\n"
        );
        List<String> typedLeft = new ArrayList<>();
        String typed = Convert.transformerToWidener(
            "public-f net.minecraft.client.renderer.block.ModelBlockRenderer blockColors\npublic net.minecraft.client.renderer.block.ModelBlockRenderer missing\n",
            false,
            typedLeft,
            (owner, field) -> fieldTypes.get(owner + " " + field)
        );
        check(
            fieldTypes.get("net/minecraft/client/renderer/block/ModelBlockRenderer grid").equals("[[I") &&
            fieldTypes.size() == 2 &&
            typed.equals(
                "accessWidener v2 named\naccessible field net/minecraft/client/renderer/block/ModelBlockRenderer blockColors Lnet/minecraft/client/color/block/BlockColors;\n" +
                "mutable field net/minecraft/client/renderer/block/ModelBlockRenderer blockColors Lnet/minecraft/client/color/block/BlockColors;\n"
            ) &&
            typedLeft.size() == 1,
            "an access transformer field line becomes an access widener field with its type from Mojang's mappings:\n" + typed + typedLeft
        );
        String cleaned = Convert.dropUnusedImports(
            "import net.fabricmc.loader.api.FabricLoader;\nimport net.fabricmc.api.EnvType;\nimport java.util.List;\n\n// FabricLoader is only named here\nclass A {\n\tEnvType side;\n\tString s = \"FabricLoader\";\n}\n",
            List.of("net.fabricmc.")
        );
        check(
            cleaned.equals("import net.fabricmc.api.EnvType;\nimport java.util.List;\n\n// FabricLoader is only named here\nclass A {\n\tEnvType side;\n\tString s = \"FabricLoader\";\n}\n"),
            "an import of the old loader that no code uses is dropped, not reported:\n" + cleaned
        );
        check(
            "jei-26.1.2-fabric".equals(Convert.swapLoaderWord("jei-26.1.2-neoforge", "neoforge", "fabric")) &&
            "Ponder-Fabric-26.1".equals(Convert.swapLoaderWord("Ponder-NeoForge-26.1", "neoforge", "fabric")) &&
            "curios-neoforge".equals(Convert.swapLoaderWord("curios-fabric", "fabric", "neoforge")) &&
            Convert.swapLoaderWord("fabricator", "fabric", "neoforge") == null &&
            Convert.loaderNeutral("jei-26.1.2-common-api") &&
            !Convert.loaderNeutral("betterconfig-fabric") &&
            Convert.remapConfiguration("\tcompileOnly \"a:b-fabric:1\"").equals("\tmodCompileOnly \"a:b-fabric:1\"") &&
            Convert.repositories("repositories {\n\tmaven { url = 'https://maven.blamejared.com/' }\n\tmaven { url \"https://maven.example.org/releases\" }\n}\n").equals(
                List.of("https://maven.blamejared.com", "https://maven.example.org/releases", "https://repo.maven.apache.org/maven2")
            ),
            "a dependency's loader word changes only as a whole part of its artifact name; loader-neutral artifacts are told apart"
        );
        check(
            Convert.dropCalls(
                "CreativeModeTab.builder()\n\t.title(t)\n\t.withTabsBefore(CreativeModeTabs.COMBAT)\n\t.icon(() -> f(\".withTabsAfter(\"))\n\t.build()",
                List.of("withTabsBefore", "withTabsAfter")
            ).equals("CreativeModeTab.builder()\n\t.title(t)\n\t.icon(() -> f(\".withTabsAfter(\"))\n\t.build()"),
            "NeoForge's tab order calls leave a builder chain whole, and a string that names one is kept"
        );
        List<String> removed = new ArrayList<>();
        String script = Convert.dropLoomTypes(
            "import net.fabricmc.loom.task.prod.ClientProductionRunTask\nimport java.util.List\n\nplugins {\n\tid 'java'\n}\n\n" +
            "// a ClientProductionRunTask comment stays\ntasks.register('prod', ClientProductionRunTask) {\n\tjvmArgs.add('-Xmx2G')\n}\n\nversion = '1'\n",
            removed
        );
        check(
            script.equals(
                "import java.util.List\n\nplugins {\n\tid 'java'\n}\n\n// a ClientProductionRunTask comment stays\n\nversion = '1'\n"
            ) &&
            removed.size() == 2,
            "a build script's Loom imports and the statements that use their types are removed:\n" + script
        );

        LoaderRules.Provider models = LoaderRules.datagenProvider(
            "package d;\n\nimport net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;\nimport net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;\n\npublic class Models extends FabricModelProvider {\n\tpublic Models(FabricPackOutput output) {\n\t\tsuper(output);\n\t}\n\n\t@Override\n\tpublic void generateBlockStateModels(BlockModelGenerators blocks) {\n\t}\n\n\t@Override\n\tpublic void generateItemModels(ItemModelGenerators items) {\n\t}\n}\n",
            "demo"
        );
        check(
            models.kind().equals("model") &&
            models.text().contains("public class Models extends ModelProvider {") &&
            models.text().contains("super(output, \"demo\");") &&
            models
                .text()
                .contains(
                    "protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {\n\t\tgenerateBlockStateModels(blockModels);"
                ) &&
            !models.text().contains("fabricmc") &&
            models.text().contains("\tpublic void generateItemModels(ItemModelGenerators items) {"),
            "a Fabric model provider becomes NeoForge's ModelProvider for the mod, one registerModels calling both Fabric methods:\n" +
            models.text()
        );
        LoaderRules.Provider loot = LoaderRules.datagenProvider(
            "package d;\n\nimport java.util.concurrent.CompletableFuture;\nimport net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;\nimport net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;\n\npublic class Loot extends FabricBlockLootSubProvider {\n\tpublic Loot(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {\n\t\tsuper(output, lookup);\n\t}\n\n\t@Override\n\tpublic void generate() {\n\t\tdropSelf(GEM_BLOCK);\n\t}\n}\n",
            "demo"
        );
        String entry = LoaderRules.datagenEntry(
            "package d;\n\nimport net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;\nimport net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;\n\npublic class Gen implements DataGeneratorEntrypoint {\n\t@Override\n\tpublic void onInitializeDataGenerator(FabricDataGenerator generator) {\n\t\tFabricDataGenerator.Pack pack = generator.createPack();\n\t\tpack.addProvider(Models::new);\n\t\tpack.addProvider(Loot::new);\n\t}\n}\n",
            Map.of("Models", "model", "Loot", "blockLoot"),
            new ArrayList<>()
        );
        check(
            loot.kind().equals("blockLoot") &&
            loot
                .text()
                .contains(
                    "public Loot(HolderLookup.Provider lookup) {\n\t\tsuper(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);"
                ) &&
            loot.text().contains("getNamespace().equals(\"demo\")") &&
            !loot.text().contains("CompletableFuture") &&
            entry.contains(
                "public void onInitializeDataGenerator(GatherDataEvent generator) {\n\t\tgenerator.createProvider(Models::new);\n\t\tgenerator.createProvider((output, lookup) -> new LootTableProvider(output, Set.of(), List.of(new LootTableProvider.SubProviderEntry(Loot::new, LootContextParamSets.BLOCK)), lookup));"
            ) &&
            !entry.contains("fabricmc") &&
            !entry.contains("@Override"),
            "a block loot provider covers the mod's own blocks, and the datagen entrypoint registers each provider with GatherDataEvent:\n" +
            loot.text() +
            "\n" +
            entry
        );

        Path root = scratch.resolve("convert-mod");
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
        );
        files.put(
            "settings.gradle",
            "pluginManagement {\n\trepositories {\n\t\tmaven {\n\t\t\tname = 'Fabric'\n\t\t\turl = 'https://maven.fabricmc.net/'\n\t\t}\n\t\tgradlePluginPortal()\n\t}\n}\n"
        );
        files.put(
            "gradle.properties",
            "minecraft_version=26.3\nloader_version=0.19.5\nloom_version=1.18.2\nmod_version=1.0.0\nfabric_api_version=0.161.0+26.3\nmodmenu_version=21.0.0\n"
        );
        files.put(
            "build.gradle",
            "plugins {\n\tid 'net.fabricmc.fabric-loom' version \"${loom_version}\"\n}\n\nversion = project.mod_version\n\nloom {\n\truns {\n\t\tselftest {\n\t\t\tclient()\n\t\t\tname = \"Self Test\"\n\t\t\tvmArg \"-Ddemo.selftest=true\"\n\t\t\trunDir = \"run\"\n\t\t}\n\t}\n}\n\ndependencies {\n\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n\n\tcompileOnly \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n}\n\nprocessResources {\n\tfilesMatching(\"fabric.mod.json\") {\n\t\texpand \"version\": project.version\n\t}\n}\n\ntasks.withType(JavaCompile).configureEach {\n\tit.options.release = 21\n}\n"
        );
        files.put(
            "src/main/resources/fabric.mod.json",
            "{\n\t\"schemaVersion\": 1,\n\t\"id\": \"demo\",\n\t\"version\": \"${version}\",\n\t\"name\": \"Demo\",\n\t\"description\": \"A demo.\",\n\t\"authors\": [\"Someone\"],\n\t\"license\": \"MIT\",\n\t\"icon\": \"assets/demo/icon.png\",\n\t\"environment\": \"*\",\n\t\"entrypoints\": {\n\t\t\"main\": [\"demo.Demo\"],\n\t\t\"client\": [\"demo.DemoClient\"],\n\t\t\"modmenu\": [\"demo.Menu\"]\n\t},\n\t\"mixins\": [\"demo.mixins.json\"],\n\t\"depends\": {\n\t\t\"fabricloader\": \">=0.19.5\",\n\t\t\"minecraft\": \"~26.3\",\n\t\t\"java\": \">=21\",\n\t\t\"fabric-api\": \"*\"\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/Demo.java",
            "package demo;\n\nimport net.fabricmc.api.ModInitializer;\nimport net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;\nimport net.fabricmc.fabric.api.registry.FlammableBlockRegistry;\nimport net.minecraft.world.level.block.Blocks;\n\npublic class Demo implements ModInitializer {\n\tpublic static int ticks;\n\n\t@Override\n\tpublic void onInitialize() {\n\t\tServerTickEvents.END_SERVER_TICK.register(server -> ticks++);\n\t\tFlammableBlockRegistry.getDefaultInstance().add(Blocks.WHITE_WOOL, 30, 60);\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/DemoClient.java",
            "package demo;\n\nimport net.fabricmc.api.ClientModInitializer;\nimport net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\nimport net.minecraft.client.KeyMapping;\n\npublic class DemoClient implements ClientModInitializer {\n\tstatic KeyMapping key;\n\n\t@Override\n\tpublic void onInitializeClient() {\n\t\tkey = KeyMappingHelper.registerKeyMapping(new KeyMapping(\"key.demo\", 0, KeyMapping.Category.MISC));\n\t\tClientTickEvents.END_CLIENT_TICK.register(client -> {\n\t\t\twhile (key.consumeClick()) Config.toggle();\n\t\t});\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/Config.java",
            "package demo;\n\nimport java.nio.file.Path;\nimport net.fabricmc.loader.api.FabricLoader;\n\nclass Config {\n\tstatic Path path() {\n\t\treturn FabricLoader.getInstance().getConfigDir().resolve(\"demo.json\");\n\t}\n\n\tstatic void toggle() {}\n}\n"
        );
        files.put(
            "src/main/java/demo/Menu.java",
            "package demo;\n\nimport com.terraformersmc.modmenu.api.ConfigScreenFactory;\nimport com.terraformersmc.modmenu.api.ModMenuApi;\n\npublic class Menu implements ModMenuApi {\n\t@Override\n\tpublic ConfigScreenFactory<?> getModConfigScreenFactory() {\n\t\treturn Settings::new;\n\t}\n}\n"
        );
        for (var file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel project = new ProjectModel(root);
        // The release lookups convert makes, answered from Target.fetch's cache as the loaders' own Maven repositories would.
        Map<String, String> answers = Map.of(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
            "<metadata><versioning><versions><version>26.3.0.90</version><version>26.3.0.103</version><version>26.1.2.126</version><version>26.3.0.104-beta</version></versions></versioning></metadata>",
            "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml",
            "<metadata><versioning><versions><version>2.0.140</version><version>2.0.148</version><version>2.1.0-beta.3</version></versions></versioning></metadata>",
            "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/2.0.148/moddev-gradle-2.0.148.module",
            "{\"variants\": [{\"attributes\": {}}]}",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml",
            "<metadata><versioning><versions><version>1.18.2</version><version>1.18.4</version><version>1.20.0-alpha.31</version></versions></versioning></metadata>",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.18.4/fabric-loom-1.18.4.module",
            "{\"variants\": [{\"attributes\": {\"org.gradle.plugin.api-version\": \"9.7.0\"}}]}",
            "https://meta.fabricmc.net/v2/versions/loader",
            "[{\"version\": \"0.20.2-beta\", \"stable\": false}, {\"version\": \"0.20.1\", \"stable\": true}]",
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml",
            "<metadata><versioning><versions><version>0.161.0+26.3</version><version>0.166.2+26.3</version><version>0.150.0+26.1.2</version></versions></versioning></metadata>",
            "https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.3%22%5D&loaders=%5B%22fabric%22%5D",
            "[{\"version_number\": \"21.0.1\"}, {\"version_number\": \"21.0.0\"}, {\"version_number\": \"21.1.0-beta.1\"}]"
        );
        for (var answer : answers.entrySet()) {
            Path cache = project
                .state()
                .resolve(
                    "metadata/" +
                    Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                    ".json"
                );
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        Map<String, byte[]> original = new LinkedHashMap<>();
        for (String name : files.keySet()) original.put(name, Files.readAllBytes(root.resolve(name)));

        // Found by an independent check: --dry-run wrote the conversion, its state and a receipt into the mod folder, and
        // exited 1 when items were left. It converts a copy elsewhere and changes nothing in the project.
        Map<String, String> beforePreview = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) beforePreview.put(root.relativize(p).toString(), Fingerprints.bytes(Files.readAllBytes(p)));
        }
        Map<String, Object> preview = new Convert(new Build(project, home, "", new Modaccel.Report(root, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--dry-run" })
        );
        Map<String, String> afterPreview = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) afterPreview.put(root.relativize(p).toString(), Fingerprints.bytes(Files.readAllBytes(p)));
        }
        Path previewCopy = Path.of(String.valueOf(preview.get("preview")));
        check(
            afterPreview.equals(beforePreview) &&
            Boolean.TRUE.equals(preview.get("ok")) &&
            Boolean.TRUE.equals(preview.get("dryRun")) &&
            !previewCopy.startsWith(root) &&
            Files.isRegularFile(previewCopy.resolve("src/main/resources/META-INF/neoforge.mods.toml")),
            "convert --dry-run converts a copy outside the project and leaves every file in it as it was: " + preview.get("details")
        );

        Build build = new Build(project, home, "", new Modaccel.Report(root, "convert"));
        Map<String, Object> toNeo = new Convert(build).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--no-compile" })
        );
        String demo = Files.readString(root.resolve("src/main/java/demo/Demo.java")), client = Files.readString(
            root.resolve("src/main/java/demo/DemoClient.java")
        );
        String gradle = Files.readString(root.resolve("build.gradle")), toml = Files.readString(
            root.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        );
        check(
            Boolean.TRUE.equals(toNeo.get("ok")) &&
            Json.array(toNeo.get("left")).isEmpty() &&
            !Files.exists(root.resolve("src/main/resources/fabric.mod.json")),
            "a Fabric mod whose every loader call has a rule converts with nothing left: " + toNeo.get("details")
        );
        check(
            demo.contains("@Mod(\"demo\")\npublic class Demo {") &&
            demo.contains("if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) onInitialize();") &&
            demo.contains("NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {") &&
            demo.contains("((FireBlock) Blocks.FIRE).setFlammable(Blocks.WHITE_WOOL, 30, 60);") &&
            !demo.contains("@Override") &&
            !demo.contains("fabricmc"),
            "the main entrypoint becomes a @Mod class that runs it in the first registry event, with its events and flammability converted:\n" +
            demo
        );
        check(
            client.contains("@Mod(value = \"demo\", dist = Dist.CLIENT)") &&
            client.contains(
                "modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> onInitializeClient());"
            ) &&
            client.contains(
                "container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new demo.Menu().getModConfigScreenFactory().apply(parent));"
            ) &&
            client.contains("event.register(key)"),
            "the client entrypoint runs at NeoForge's first client event, registers its key mapping and Mod Menu's config screen:\n" +
            client
        );
        check(
            gradle.contains("id 'net.neoforged.moddev' version '2.0.148'") &&
            gradle.contains("version = project.neo_version") &&
            gradle.contains("systemProperty 'demo.selftest', 'true'") &&
            gradle.contains("ideName = \"Self Test\"") &&
            !gradle.contains("fabric") &&
            !gradle.contains("modmenu") &&
            Files.readString(root.resolve("gradle.properties")).contains("neo_version=26.3.0.103") &&
            Files.readString(root.resolve("src/main/resources/META-INF/accesstransformer.cfg")).contains(
                "public net.minecraft.world.level.block.FireBlock setFlammable("
            ),
            "the build moves to ModDevGradle (newest stable) and NeoForge (newest release for 26.3), keeping the custom run:\n" +
            gradle
        );
        check(
            toml.contains("modId = \"demo\"") &&
            toml.contains("iconFile = \"assets/demo/icon.png\"") &&
            toml.contains("versionRange = \"[26.3,26.4)\"") &&
            toml.contains("[[mixins]]\nconfig = \"demo.mixins.json\"") &&
            toml.contains("[[accessTransformers]]"),
            "fabric.mod.json becomes neoforge.mods.toml:\n" + toml
        );
        Map<String, Object> back = new Convert(new Build(project, home, "", new Modaccel.Report(root, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "fabric", "--no-compile" })
        );
        boolean javaSame = true;
        for (String name : files.keySet()) if (
            name.endsWith(".java") && !Arrays.equals(original.get(name), Files.readAllBytes(root.resolve(name)))
        ) javaSame = false;
        String fabricGradle = Files.readString(root.resolve("build.gradle"));
        check(
            Boolean.TRUE.equals(back.get("ok")) &&
            javaSame &&
            !Files.exists(root.resolve("src/main/resources/META-INF")) &&
            fabricGradle.contains("id 'net.fabricmc.fabric-loom' version '1.18.4'") &&
            fabricGradle.contains("vmArg \"-Ddemo.selftest=true\"") &&
            fabricGradle.contains("localRuntime \"com.terraformersmc:modmenu:${project.modmenu_version}\"") &&
            Files.readString(root.resolve("gradle.properties")).contains("modmenu_version=21.0.1") &&
            Files.readString(root.resolve("src/main/resources/fabric.mod.json")).contains(
                "\"modmenu\": [\n\t\t\t\"demo.Menu\"\n\t\t]"
            ),
            "converted back to Fabric, every Java file is byte for byte the original (Loom: newest stable on this Gradle): " +
            back.get("details")
        );
        Port.undo(project, String.valueOf(back.get("undoConvert")), Convert.FOLDER);
        Port.undo(project, String.valueOf(toNeo.get("undoConvert")), Convert.FOLDER);
        boolean restored = true;
        for (var file : original.entrySet()) if (
            !Arrays.equals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())))
        ) restored = false;
        check(
            restored && !Files.exists(root.resolve("src/main/resources/META-INF")),
            "undoing both conversions restores every file byte for byte and removes the files they made, and the folders those emptied"
        );

        // Found by an independent check on FastQuit (contariaa/FastQuit 8efed17): dependencies with an exclude block are whole
        // statements. Mod Menu goes with its block, and Cloth Config moves to its NeoForge build, which its repository has.
        Path quit = scratch.resolve("convert-exclude-mod");
        Map<String, String> quitFiles = new LinkedHashMap<>();
        quitFiles.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        quitFiles.put("settings.gradle", "rootProject.name = 'quit'\n");
        quitFiles.put("gradle.properties", "minecraft_version=26.3\nloader_version=0.19.5\nfabric_api_version=0.161.0+26.3\nmodmenu_version=21.0.0\ncloth_config_version=26.3.1\n");
        quitFiles.put(
            "build.gradle",
            "plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.18.2'\n}\n\nrepositories {\n\tmaven { url \"https://maven.shedaniel.me/\" }\n}\n\ndependencies {\n" +
            "\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n" +
            "\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n\n\t// ModMenu\n" +
            "\timplementation(\"com.terraformersmc:modmenu:${project.modmenu_version}\"){\n\t\texclude(group: \"eu.pb4\")\n\t}\n\n\t// Cloth Config\n" +
            "\timplementation(\"me.shedaniel.cloth:cloth-config-fabric:${project.cloth_config_version}\") {\n\t\texclude(group: \"net.fabricmc.fabric-api\")\n\t}\n}\n"
        );
        quitFiles.put(
            "src/main/resources/fabric.mod.json",
            "{\n\t\"schemaVersion\": 1,\n\t\"id\": \"quit\",\n\t\"version\": \"1.0.0\",\n\t\"entrypoints\": {\n\t\t\"main\": [\"quit.Quit\"]\n\t},\n\t\"depends\": {\n\t\t\"minecraft\": \"~26.3\"\n\t}\n}\n"
        );
        quitFiles.put(
            "src/main/java/quit/Quit.java",
            "package quit;\n\nimport net.fabricmc.api.ModInitializer;\n\npublic class Quit implements ModInitializer {\n\t@Override\n\tpublic void onInitialize() {\n\t}\n}\n"
        );
        for (var file : quitFiles.entrySet()) {
            Path path = quit.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel quitProject = new ProjectModel(quit);
        Map<String, String> quitAnswers = new LinkedHashMap<>(answers);
        quitAnswers.put(
            "https://maven.shedaniel.me/me/shedaniel/cloth/cloth-config-neoforge/maven-metadata.xml",
            "<metadata><versioning><versions><version>26.3.1</version></versions></versioning></metadata>"
        );
        for (var answer : quitAnswers.entrySet()) {
            Path cache = quitProject.state().resolve("metadata/" + Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        new Convert(new Build(quitProject, home, "", new Modaccel.Report(quit, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--no-compile" })
        );
        String quitGradle = Files.readString(quit.resolve("build.gradle"));
        check(
            quitGradle.chars().filter(c -> c == '{').count() == quitGradle.chars().filter(c -> c == '}').count() &&
            !quitGradle.contains("modmenu") &&
            !quitGradle.contains("eu.pb4") &&
            quitGradle.contains("\timplementation(\"me.shedaniel.cloth:cloth-config-neoforge:${project.cloth_config_version}\") {\n\t\texclude(group: \"net.fabricmc.fabric-api\")\n\t}\n") &&
            !quitGradle.contains("cloth-config-fabric"),
            "a dependency with an exclude block is removed or moved whole; Cloth Config moves to its NeoForge build:\n" + quitGradle
        );

        // A mod written for NeoForge first goes to Fabric through the NeoForge bridge (knowledge/loaders.json "bridges").
        Map<String, Object> bridgeRule = null;
        for (Object b : LoaderRules.list(rules.get("bridges"))) if ("neoforge-on-fabric".equals(Json.object(b).get("id"))) bridgeRule = Json.object(b);
        check(bridgeRule != null, "knowledge/loaders.json has the NeoForge bridge");
        Map<String, Object> bridgeTest = Json.object(bridgeRule.get("test"));
        Path neo = scratch.resolve("convert-neoforge-mod");
        Map<String, String> neoFiles = new LinkedHashMap<>();
        neoFiles.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        neoFiles.put("settings.gradle", "rootProject.name = 'demo'\n");
        neoFiles.put("gradle.properties", "minecraft_version=26.3\nneo_version=26.3.0.103\nmod_id=demo\n");
        neoFiles.put(
            "build.gradle",
            "plugins {\n\tid 'net.neoforged.moddev' version '2.0.148'\n}\n\nneoForge {\n\tversion = project.neo_version\n\truns {\n\t\tclient {\n\t\t\tclient()\n\t\t}\n\t}\n\tmods {\n\t\t\"demo\" {\n\t\t\tsourceSet(sourceSets.main)\n\t\t}\n\t}\n}\n\ndependencies {\n}\n"
        );
        neoFiles.put(
            "src/main/resources/META-INF/neoforge.mods.toml",
            "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"${mod_id}\"\nversion=\"1.0.0\"\ndisplayName=\"Demo\"\n"
        );
        neoFiles.put("src/main/java/demo/Demo.java", Json.text(bridgeTest, "neoforge", ""));
        for (var file : neoFiles.entrySet()) {
            Path path = neo.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel neoProject = new ProjectModel(neo);
        for (var answer : answers.entrySet()) {
            Path cache = neoProject.state().resolve("metadata/" + Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        Map<String, Object> bridged = new Convert(new Build(neoProject, home, "", new Modaccel.Report(neo, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "fabric", "--no-compile" })
        );
        String bridgedEntry = Files.readString(neo.resolve("src/main/java/demo/Demo.java"));
        String fabricJson = Files.readString(neo.resolve("src/main/resources/fabric.mod.json"));
        check(
            bridgedEntry.equals(Json.text(bridgeTest, "fabric", "")) &&
            Files.readString(neo.resolve("src/main/java/demo/loaderbridge/DeferredRegister.java")).startsWith("package demo.loaderbridge;") &&
            Files.isRegularFile(neo.resolve("src/main/java/demo/loaderbridge/ClientEntrypoint.java")) &&
            fabricJson.contains("\"demo.Demo\"") &&
            fabricJson.contains("\"demo.loaderbridge.ClientEntrypoint\"") &&
            Json.array(bridged.get("left")).isEmpty(),
            "a NeoForge @Mod class becomes a Fabric entrypoint through the NeoForge bridge, its listeners typed and its imports on the bridge:\n" +
            bridgedEntry +
            bridged.get("details")
        );
        // Found by an independent check on EndNetwork: the bridge, which both sides use, went into a client package.
        check(
            "net.x.core.loaderbridge".equals(Convert.bridgePackage(List.of(
                "package net.x.client;\n@Mod(value = X.ID, dist = Dist.CLIENT)\nclass XClient {}\n",
                "package net.x.core;\n@Mod(X.ID)\nclass X {}\n"
            ))) &&
            "net.x.loaderbridge".equals(Convert.bridgePackage(List.of("package net.x.client;\n@Mod(value = X.ID, dist = Dist.CLIENT)\nclass XClient {}\n"))) &&
            Convert.bridgePackage(List.of("package net.x;\nclass X {}\n")) == null,
            "the NeoForge bridge goes beside the common @Mod class, never in a client package"
        );
        // Found by the convert sweep: the NeoForge MDK converted to Fabric did not convert back. The way back takes the bridge out.
        Map<String, Object> unbridged = new Convert(new Build(neoProject, home, "", new Modaccel.Report(neo, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--no-compile" })
        );
        String backEntry = Files.readString(neo.resolve("src/main/java/demo/Demo.java"));
        String neoOriginal = Json.text(bridgeTest, "neoforge", "");
        check(
            backEntry.contains("@Mod(Demo.MODID)\npublic class Demo {") &&
            backEntry.contains("\tpublic Demo(IEventBus modEventBus, ModContainer modContainer) {\n\t\tmodEventBus.addListener(this::commonSetup);\n\t\tBLOCKS.register(modEventBus);\n\t\tNeoForge.EVENT_BUS.register(this);\n\t\tmodContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);\n\t}\n") &&
            !backEntry.contains("loaderbridge") &&
            !backEntry.contains("fabricmc") &&
            neoOriginal.lines().filter(l -> l.startsWith("import ")).allMatch(backEntry::contains) &&
            !Files.exists(neo.resolve("src/main/java/demo/loaderbridge")) &&
            Files.isRegularFile(neo.resolve("src/main/resources/META-INF/neoforge.mods.toml")),
            "a mod that went to Fabric through the NeoForge bridge comes back as a NeoForge @Mod class, without the bridge:\n" +
            backEntry +
            unbridged.get("details")
        );
        // Found by an independent check on EndNetwork: undo left the bridge's emptied folder behind.
        Port.undo(neoProject, String.valueOf(unbridged.get("undoConvert")), Convert.FOLDER);
        Port.undo(neoProject, String.valueOf(bridged.get("undoConvert")), Convert.FOLDER);
        check(
            !Files.exists(neo.resolve("src/main/java/demo/loaderbridge")) &&
            !Files.exists(neo.resolve("src/main/resources/fabric.mod.json")) &&
            Files.readString(neo.resolve("src/main/java/demo/Demo.java")).equals(Json.text(bridgeTest, "neoforge", "")),
            "undoing both conversions restores the NeoForge mod and leaves no folder the bridge emptied"
        );
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        String restartTimes = ServerCheck.text(
            Json.map(
                "ok",
                true,
                "server",
                Json.map("minecraft", "26.3", "build", "1"),
                "bot",
                Json.map("version", "x"),
                "steps",
                List.of(
                    Json.map(
                        "line",
                        1,
                        "step",
                        "start-server",
                        "status",
                        "done",
                        "seconds",
                        10.0,
                        "serverStart",
                        Json.map("seconds", 9.0)
                    ),
                    Json.map(
                        "line",
                        2,
                        "step",
                        "restart-server",
                        "status",
                        "done",
                        "seconds",
                        6.0,
                        "serverRestarts",
                        List.of(Json.map("seconds", 5.0))
                    )
                ),
                "serverStopSeconds",
                1.0,
                "scenario",
                "fixture"
            ),
            scratch
        );
        check(
            restartTimes.contains("server start 14.0s, scenario 2.0s, stop 1.0s"),
            "server timings include every start and restart: " + restartTimes
        );
        Path pluginFolder = scratch.resolve("plugins, fixtures");
        Files.createDirectories(pluginFolder);
        Path pluginA = pluginFolder.resolve("a.jar"), pluginB = pluginFolder.resolve("b.jar");
        Files.writeString(pluginA, "fixture");
        Files.writeString(pluginB, "fixture");
        check(
            ServerCheck.pluginPaths(scratch, pluginFolder.toString()).size() == 2,
            "plugin folder names may contain commas"
        );
        check(
            ServerCheck.pluginPaths(scratch, pluginA + File.pathSeparator + pluginB).size() == 2,
            "individual absolute plugin jars, including commas in their paths, can be listed together"
        );
        Path existingArgs = scratch.resolve("existing loom args.txt");
        Files.writeString(existingArgs, "-Dmodaccel.launchProbe=existing-file\n");
        String classpath =
            System.getProperty("java.class.path") +
            (File.pathSeparator + scratch.resolve("nonexistent # ' path")).repeat(1100);
        List<String> launch = List.of(
            Path.of(System.getProperty("java.home"), "bin", Runtimes.WINDOWS ? "java.exe" : "java").toString(),
            "@" + existingArgs.toAbsolutePath(),
            "-cp",
            classpath,
            "PortableTests$LaunchProbe",
            "@literal",
            "spaces and slash\\"
        );
        List<String> saved = Game.savedCommand(launch, scratch.resolve("saved classpath.args"));
        Process child = new ProcessBuilder(saved).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(
            child.waitFor() == 0 && output.contains("LAUNCH_OK"),
            "saved launches handle classpaths beyond Windows limits, existing @files and literal program arguments: " +
            output.strip()
        );
        check(
            saved.stream().mapToInt(String::length).sum() < 8000 && saved.contains(launch.get(1)),
            "saved launches keep existing argument files at the top level"
        );
        Object json = Json.parse(
            Json.write(
                Json.map("unicode", "mod 🧱", "path", "C:\\mods with spaces\\test", "rows", List.of(1, true, "\n\""))
            )
        );
        check(
            Json.object(json).get("unicode").equals("mod 🧱"),
            "structured results preserve Unicode and quoted paths"
        );
        Path quietDir = scratch.resolve("quiet game");
        Files.createDirectories(quietDir);
        Files.writeString(
            quietDir.resolve("options.txt"),
            "version:5023\r\nnarrator:2\r\nguiScale:2\r\nsoundCategory_master:1.0\r\nonboardAccessibility:true\r\nkey_key.jump:key.keyboard.space\r\n"
        );
        QuietOptions.seedArgs(
            new String[] { "--username", "Dev", "--gameDir", quietDir.toAbsolutePath().toString(), "--assetsDir", "x" }
        );
        Path quietFile = quietDir.resolve("options.txt");
        List<String> quiet = Files.readAllLines(quietFile);
        check(
            quiet.containsAll(
                List.of(
                    "narrator:0",
                    "onboardAccessibility:false",
                    "soundCategory_master:0.0",
                    "tutorialStep:none",
                    "skipMultiplayerWarning:true",
                    "joinedFirstServer:true"
                )
            ),
            "seeded options.txt has no narrator, no sound, no tutorial or first-server prompts: " + quiet
        );
        check(
            quiet.containsAll(List.of("version:5023", "guiScale:2", "key_key.jump:key.keyboard.space")) &&
            quiet.indexOf("version:5023") == 0 &&
            quiet.size() == 9,
            "seeding options.txt keeps every other key and its order"
        );
        Path freshQuiet = QuietOptions.seed(scratch.resolve("fresh quiet"));
        check(
            Files.readAllLines(freshQuiet).equals(
                List.of(
                    "narrator:0",
                    "onboardAccessibility:false",
                    "soundCategory_master:0.0",
                    "tutorialStep:none",
                    "skipMultiplayerWarning:true",
                    "joinedFirstServer:true"
                )
            ),
            "a first launch gets a quiet options.txt"
        );
        Path blockedQuiet = scratch.resolve("not a directory");
        Files.writeString(blockedQuiet, "file");
        try {
            QuietOptions.seedArgs(new String[] { "--gameDir", blockedQuiet.toAbsolutePath().toString() });
            throw new AssertionError("Minecraft must not start when its quiet options cannot be written");
        } catch (IllegalStateException expected) {
            check(
                expected.getMessage().contains("Cannot start Minecraft quietly"),
                "quiet setup reports why the game cannot start"
            );
        }
        Path prefs = scratch.resolve("user/preferences.json");
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")) &&
            !Files.exists(prefs),
            "a new user gets a normal window and a muted game without creating a preference file"
        );
        Preferences.change(
            prefs,
            new Modaccel.Options(new String[] { "preferences", "--background", "on", "--quiet", "on" })
        );
        check(
            Boolean.TRUE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
            "personal background and quiet choices survive a reload"
        );
        Preferences.change(prefs, new Modaccel.Options(new String[] { "preferences", "--background", "off" }));
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
            "each personal choice can change without changing the other"
        );
        try {
            Preferences.change(prefs, new Modaccel.Options(new String[] { "preferences", "--quiet", "maybe" }));
            throw new AssertionError("invalid preference accepted");
        } catch (IllegalArgumentException expected) {
            check(
                Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
                "an invalid choice leaves the saved preference intact"
            );
        }
        Preferences.change(prefs, new Modaccel.Options(new String[] { "preferences", "--quiet", "off" }));
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("quiet")),
            "a user can opt out of the muted game and the choice is kept"
        );
        Path dark = scratch.resolve("frame dark.png"), half = scratch.resolve("frame half.png");
        BufferedImage left = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), right = new BufferedImage(
            10,
            10,
            BufferedImage.TYPE_INT_RGB
        );
        for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) right.setRGB(x, y, 0xFFFFFF);
        ImageIO.write(left, "png", dark.toFile());
        ImageIO.write(right, "png", half.toFile());
        check(
            Boolean.TRUE.equals(Game.compare(dark, half, 40).get("passed")),
            "visual gate accepts a real frame change (50% vs 40% required)"
        );
        try {
            Game.compare(dark, half, 60);
            throw new AssertionError("small change accepted");
        } catch (IOException expected) {
            check(
                expected.getMessage().contains("no visible result"),
                "visual gate rejects an invisible result (50% vs 60% required)"
            );
        }
        try {
            Game.compare(dark, dark, 1);
            throw new AssertionError("identical frames accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("only 0.000%"), "identical frames are reported as no visible change");
        }
        Map<String, Object> positionA = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionB = Json.map(
            "x",
            1.5,
            "y",
            64.0,
            "z",
            -3.5
        ), positionC = Json.map("x", 1.56, "y", 64.0, "z", -3.5);
        check(Game.samePosition(positionA, positionB, 0.05), "save round-trip accepts an unchanged position");
        check(
            !Game.samePosition(positionA, positionC, 0.05),
            "save round-trip rejects a moved position beyond tolerance"
        );
        Map<String, Object> hidden = Json.map("background", true, "log", "game.log"), shown = Json.map(
            "background",
            false,
            "log",
            "game.log"
        );
        check(
            Game.hiddenWindowFailure(
                hidden,
                Json.map("backgroundApplied", "org/lwjgl/sdl/SDLVideo,org/lwjgl/sdl/SDLMouse", "backgroundError", "")
            ) ==
            null,
            "a hidden window that was rewritten lets the check run"
        );
        check(
            String.valueOf(
                Game.hiddenWindowFailure(
                    hidden,
                    Json.map(
                        "backgroundApplied",
                        "org/lwjgl/sdl/SDLVideo",
                        "backgroundError",
                        "Unsupported org/lwjgl/sdl/SDLMouse window controls"
                    )
                )
            ).contains("SDLMouse window controls"),
            "a failed rewrite stops the check and names the library"
        );
        check(
            String.valueOf(
                Game.hiddenWindowFailure(hidden, Json.map("backgroundApplied", "", "backgroundError", ""))
            ).contains("no window library was rewritten"),
            "nothing rewritten stops the check"
        );
        check(
            Game.hiddenWindowFailure(shown, Json.map("backgroundApplied", "", "backgroundError", "")) == null,
            "a visible window by choice is not a failure"
        );
        List<Map<String, Object>> loaded = new ArrayList<>(
            List.of(
                Json.map("event", "start"),
                Json.map("event", "mods", "ours", List.of("demo")),
                Json.map("event", "registered", "mod", "demo", "counts", Json.map("item", 1, "loot_table", 1)),
                Json.map(
                    "event",
                    "item",
                    "id",
                    "demo:gem",
                    "name",
                    "Gem",
                    "maxStack",
                    16,
                    "maxDamage",
                    0,
                    "rarity",
                    "common"
                ),
                Json.map("event", "done", "cpu", 17.0)
            )
        );
        var dataOk = DataCheck.verdict(loaded, 0, "log");
        check(
            Boolean.TRUE.equals(dataOk.get("ok")) &&
            String.valueOf(dataOk.get("details")).contains("demo:gem \"Gem\": stacks to 16"),
            "data-check passes a clean load and shows each item: " + dataOk.get("details")
        );
        loaded.add(
            3,
            Json.map("event", "log", "level", "ERROR", "logger", "Minecraft", "message", "Couldn't parse demo:broken")
        );
        check(
            !Boolean.TRUE.equals(DataCheck.verdict(loaded, 0, "log").get("ok")),
            "an error the game logged fails data-check"
        );
        var stopped = DataCheck.verdict(
            List.of(
                Json.map("event", "start"),
                Json.map(
                    "event",
                    "log",
                    "level",
                    "ERROR",
                    "logger",
                    "Minecraft",
                    "message",
                    "Errors in element demo:broken"
                ),
                Json.map("event", "error", "phase", "probe", "message", "Registry Loading")
            ),
            4,
            "log"
        );
        check(
            !Boolean.TRUE.equals(stopped.get("ok")) &&
            String.valueOf(stopped.get("details")).contains("Errors in element demo:broken"),
            "a load that stops shows what the game said first"
        );
        check(
            !Boolean.TRUE.equals(
                DataCheck.verdict(
                    List.of(
                        Json.map("event", "start"),
                        Json.map("event", "mods", "ours", List.of()),
                        Json.map("event", "done")
                    ),
                    0,
                    "log"
                ).get("ok")
            ),
            "nothing of the project loaded is not a pass"
        );
        var again = DataCheck.verdict(
            List.of(
                Json.map("event", "start", "cpu", 40.0, "first", false),
                Json.map("event", "mods", "ours", List.of("demo")),
                Json.map("event", "done", "cpu", 42.2)
            ),
            0,
            "log"
        );
        check(
            Double.valueOf(2.2).equals(again.get("cpuSeconds")) && Boolean.FALSE.equals(again.get("firstInJvm")),
            "a check in the loaded game counts only its own CPU: " + again.get("cpuSeconds")
        );
        // data-check's spare: it may take changed code only when nothing it has already read changed.
        Path out = scratch.resolve("spare-out");
        String initClass = out.resolve("demo/Demo.class").toString(), items = out
            .resolve("demo/Items.class")
            .toString(), mixin = out.resolve("demo/mixin/M.class").toString(), newMixin = out
            .resolve("demo/mixin/N.class")
            .toString(), mod = out.resolve("fabric.mod.json").toString();
        Map<String, Object> spare = Json.map(
            "files",
            Json.map(initClass, "a", items, "b", mixin, "c", mod, "d"),
            "early",
            List.of(mod),
            "mixinPackages",
            List.of(out.resolve("demo/mixin").toString())
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "A", items, "B", mixin, "c", mod, "d"),
                List.of()
            ).isEmpty(),
            "a spare takes changed classes it has not read yet"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "A", items, "b", mixin, "c", mod, "d"),
                List.of(items)
            ).isEmpty(),
            "a spare takes changed code when the classes it read are unchanged"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "a", items, "B", mixin, "c", mod, "d"),
                List.of(items)
            ).equals(List.of("Items.class")),
            "a spare refuses code when a class it has defined changed"
        );
        check(
            DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "C", mod, "d"), List.of()).equals(
                List.of("M.class")
            ),
            "a spare refuses code when a mixin class changed"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "a", items, "b", mixin, "c", newMixin, "e", mod, "d"),
                List.of()
            ).equals(List.of("N.class")),
            "a spare refuses code when a mixin class appeared"
        );
        check(
            DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "c", mod, "D"), List.of()).equals(
                List.of("fabric.mod.json")
            ),
            "a spare refuses code when loader metadata changed"
        );
        // A changed resource file Gradle copies as it is: copied without Gradle; anything else waits for Gradle.
        Path res = scratch.resolve("rc/src/main/resources"), built = scratch.resolve("rc/build/resources/main"), code =
            scratch.resolve("rc/src/main/java/A.java");
        Files.createDirectories(res.resolve("data"));
        Files.createDirectories(built.resolve("data"));
        Files.createDirectories(code.getParent());
        Files.writeString(res.resolve("data/r.json"), "{\"v\":1}");
        Files.writeString(built.resolve("data/r.json"), "{\"v\":1}");
        Files.writeString(res.resolve("data/gone.json"), "{}");
        Files.writeString(built.resolve("data/gone.json"), "{}");
        Files.writeString(res.resolve("fabric.mod.json"), "{\"version\":\"${version}\"}");
        Files.writeString(built.resolve("fabric.mod.json"), "{\"version\":\"1.0\"}");
        Map<String, Object> rcModel = Json.map(
            "projects",
            List.of(
                Json.map(
                    "sourceSets",
                    List.of(
                        Json.map(
                            "resources",
                            List.of(res.toString()),
                            "output",
                            List.of(scratch.resolve("rc/build/classes/java/main").toString(), built.toString())
                        )
                    )
                )
            )
        );
        List<List<String>> rcRoots = ResourceCopy.roots(rcModel);
        String r = res.resolve("data/r.json").toString(), gone = res.resolve("data/gone.json").toString(), meta = res
            .resolve("fabric.mod.json")
            .toString(), a = code.toString();
        Map<String, Object> rcRecord = Json.map(
            "inputs",
            Json.map(r, "1", gone, "2", meta, "3", a, "4"),
            "resourceRoots",
            rcRoots,
            "filtered",
            ResourceCopy.filtered(rcRoots)
        );
        check(
            rcRoots.equals(List.of(List.of(res.toString(), built.toString()))) &&
            ProjectModel.strings(rcRecord.get("filtered")).equals(List.of(meta)),
            "resource folders map to their output and filtered files are known: " +
            rcRoots +
            " " +
            rcRecord.get("filtered")
        );
        check(
            ResourceCopy.copy(Json.map("task", ":runClient"), Map.of(r, "9")) == -1,
            "a launch recorded without file hashes runs Gradle"
        );
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "3", a, "5")) == -1,
            "a changed class source runs Gradle"
        );
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "6", a, "4")) == -1,
            "a resource Gradle filtered runs Gradle"
        );
        Files.writeString(res.resolve("data/r.json"), "{\"v\":\"${v}\"}");
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "7", gone, "2", meta, "3", a, "4")) == -1,
            "a placeholder runs Gradle"
        );
        Files.writeString(res.resolve("data/r.json"), "{\"v\":2}");
        Files.delete(res.resolve("data/gone.json"));
        int copiedFiles = ResourceCopy.copy(rcRecord, Map.of(r, "8", gone, "missing", meta, "3", a, "4"));
        check(
            copiedFiles == 2 &&
            Files.readString(built.resolve("data/r.json")).equals("{\"v\":2}") &&
            !Files.exists(built.resolve("data/gone.json")),
            "changed and deleted data files are copied and removed without Gradle: " + copiedFiles
        );
        try {
            Json.parse("{\"ok\":true,\"ok\":false}");
            throw new AssertionError("duplicate accepted");
        } catch (IllegalArgumentException expected) {
            count++;
        }
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
        Path stale = root.resolve("build/test-results/modaccelPure/TEST-old.xml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "<testsuite tests=\"8\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        check(
            ((Number) TestResults.read(model, "modaccelPure", "fresh-run").get("executed")).intValue() == 0,
            "old successful XML cannot make an empty run pass"
        );
        Build build = new Build(project, home, "", new Modaccel.Report(root, "test"));
        Port port = new Port(build, model);
        Build portBuild = new Build(project, home, "", new Modaccel.Report(root, "port"));
        routeChecks(home, new Port(portBuild, model), scratch);
        learnedRuleChecks(home);
        loomEditChecks(home, scratch);
        accessorChecks(home, scratch);
        accessorWithoutMixinsChecks(home, scratch);
        mixinRepairChecks();
        parchmentChecks(home, scratch);
        convertChecks(home, scratch);
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
            "modaccel's Gradle runs keep the build's configure-on-demand"
        );
        Path missed = scratch.resolve("missed.log");
        Files.writeString(
            missed,
            "* What went wrong:\nCannot locate tasks that match ':modaccelMetadata' as task 'modaccelMetadata' not found in root project 'diorama'.\n"
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
        fastLaneChecks();
        pluginCheckChecks(home);
        System.out.println(count + " portable checks passed");
    }

    public static final class LaunchProbe {

        public static void main(String[] args) {
            if (
                !"existing-file".equals(System.getProperty("modaccel.launchProbe")) ||
                !Arrays.equals(args, new String[] { "@literal", "spaces and slash\\" })
            ) throw new AssertionError(
                "launch arguments changed: " + System.getProperty("modaccel.launchProbe") + " " + Arrays.toString(args)
            );
            System.out.println("LAUNCH_OK");
        }
    }
}
