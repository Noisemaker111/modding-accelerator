import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Outcome checks: stale bytes, empty test reports, rollback conflicts, and unrelated checkouts. */
public final class PortableTests {
    static int count;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); count++; System.out.println("PASS " + message); }
    /** Hub routing from the change index, rule ranges, the pre-1.14.4 refusal and errors named by update. */
    static void routeChecks(Path home, Port port, Path scratch) throws Exception {
        Route forward = Route.plan(home, "1.20.1", "1.21.6");
        check(forward != null && forward.waypoints.equals(List.of("1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6"))
                && forward.steps().size() == 12 && forward.hops.size() == 8, "1.20.1 -> 1.21.6 routes through every family hub between, one hop each: " + (forward == null ? null : forward.waypoints));
        Route back = Route.plan(home, "1.21.6", "1.21.1");
        check(back.waypoints.equals(List.of("1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1")) && back.steps().get(0).equals("1.21.6 -> 1.21.5"), "a backport walks the same hubs in reverse");
        Route late = Route.plan(home, "1.21.6", "26.3");
        check(late.mappedChain().get(late.mappedChain().size() - 1).equals("1.21.11") && late.linkOf("1.21.11 -> 26.1").equals("by-name"),
                "a route into 26.x joins ids only up to 1.21.11 and marks the rest by-name");
        Route zombie = Route.plan(home, "1.21.1", "1.21.6");
        check(zombie.stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "mojmap").equals(List.of("1.21.1 -> 1.21.2", "1.21.2 -> 1.21.3", "1.21.3 -> 1.21.4")),
                "a rule runs on the updates its jump covers");
        check(zombie.stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "yarn").isEmpty() && back.stepsCovered("1.21.1 -> 1.21.4 (mojmap)", "mojmap").isEmpty()
                && forward.stepsCovered("1.21.6 -> 26.3 (official names)", "mojmap").isEmpty(), "a rule for other names, the other direction or updates not crossed does not run");
        Map<String, Object> refused = Route.blocked(home, "1.12.2", "1.20.1");
        check(refused != null && "blocked".equals(refused.get("tier")) && String.valueOf(refused.get("details")).contains("1.14.4") && Route.blocked(home, "1.20.1", "1.21.6") == null,
                "releases before 1.14.4 are refused with the reason; later ones are not: " + (refused == null ? null : refused.get("details")));
        check(Route.plan(home, "1.21.1-rc1", "1.21.6") == null, "a release outside the index falls back to the one-jump map");
        port.route = zombie;
        Path log = scratch.resolve("route errors.log");
        Files.writeString(log, "C:\\mod\\src\\A.java:10: error: cannot find symbol\n        int y = level.getMinBuildHeight();\n                     ^\n"
                + "  symbol:   method getMinBuildHeight()\n  location: variable level of type Level\n"
                + "C:\\mod\\src\\B.java:5: error: incompatible types: int cannot be converted to String\n");
        Map<String, Object> history = Json.map("history", List.of(Json.map("kind", "method", "owner", "net.minecraft.world.level.Level", "name", "getMinBuildHeight", "desc", "()I",
                "what", "removed", "was", "net.minecraft.world.level.Level.getMinBuildHeight", "to", null, "step", "1.21.1 -> 1.21.2")));
        List<Object> named = port.namedErrors(log, history);
        var first = Json.object(named.get(0)); var placed = Json.object(Json.array(first.get("changedIn")).get(0));
        check(named.size() == 2 && "1.21.1 -> 1.21.2".equals(placed.get("step")) && "1.21.1 -> 1.21.3".equals(placed.get("hop")) && String.valueOf(placed.get("change")).contains("removed"),
                "a remaining error names the update and hop that removed its symbol");
        var second = Json.object(named.get(1));
        check(Json.array(second.get("changedIn")).isEmpty() && second.get("why") != null, "an error the history cannot place is still listed, with the reason");
        // Gradle's failure summary repeats the errors and glues javac's last note onto one (AppleSkin 1.20.1 -> 1.21.6, 2026-11-07).
        Path glued = scratch.resolve("glued errors.log");
        Files.writeString(glued, "C:\\mod\\src\\A.java:10: error: cannot find symbol\n  symbol:   method getMinBuildHeight()\n"
                + "Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full output\n1 error\n\n* What went wrong:\n"
                + "  \t        ^Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full outputC:\\mod\\src\\A.java:10: error: cannot find symbol\n"
                + "  \t        ^C:\\mod\\src\\A.java:10: error: cannot find symbol\n"
                + "    (actual and formal argument lists differ in length)C:\\mod\\src\\A.java:10: error: cannot find symbol\n"
                + "    ^/home/me/mod/src/A.java:10: error: cannot find symbol\n");
        check(port.namedErrors(glued, null).size() == 1, "a javac note Gradle glued onto a repeated error is not read as a file path");
        String exhaustion = "C:\\mod\\src\\H.java:67: error: cannot find symbol\n\t\tfloat e = player.getHungerManager().getExhaustion();\n\t\t                                   ^\n  symbol:   method getExhaustion()\n  location: class HungerManager\n";
        var offered = Port.repairGuides(home, "1.20.1", "1.21.6", exhaustion).stream().map(guide -> guide.get("id")).toList();
        check(offered.equals(List.of("hunger-exhaustion-accessor"))
                && Port.repairGuides(home, "1.20.1", "1.21.1", exhaustion).isEmpty()
                && Port.repairGuides(home, "1.20.1", "1.21.6", "> Task :compileJava\nsymbol:   method getExhaustion()\n").isEmpty(),
                "a stopped port offers the repair guide for its error only when it crosses the release that changed it");
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
                var example = Json.object(e); String mod = Json.text(example, "mod", "");
                // The packaged jar ships knowledge/ but not the mined switch data; there only the rewrites are checked.
                Path mined = home.resolve("coverage/stonecutter/switches/" + mod.replace("/", "__") + ".json");
                if (!Files.isRegularFile(mined)) { mods.add(mod); continue; }
                var data = switches.computeIfAbsent(mod, m -> {
                    try { return Json.read(mined); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
                boolean found = false;
                for (Object r : Json.array(data.get("rows"))) {
                    var row = Json.object(r);
                    if (row.get("file").equals(example.get("file")) && ((Number) row.get("line")).intValue() == ((Number) example.get("line")).intValue()) found = true;
                }
                if (!found) throw new AssertionError(id + ": example " + mod + " " + example.get("file") + ":" + example.get("line") + " is not a mined switch");
                mods.add(mod);
            }
            if (mods.size() < 2) throw new AssertionError(id + ": a learned rule needs the same change in two or more mods, has " + mods);
            var test = Json.object(rule.get("test"));
            if (!java.util.regex.Pattern.compile(rule.get("match").toString()).matcher(test.get("error").toString()).find()) throw new AssertionError(id + ": match misses its test error");
            String after = Port.rewrite(rule, test.get("before").toString());
            if (after.equals(test.get("before")) || !after.equals(test.get("after"))) throw new AssertionError(id + ": rewrite gave\n" + after + "\nexpected\n" + test.get("after"));
            String[] range = Json.text(rule, "jump", "").replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            String names = Json.text(rule, "jump", "").contains("yarn") ? "yarn" : "mojmap";
            Route route = Route.plan(home, range[0], range[1]);
            if (route == null || route.stepsCovered(Json.text(rule, "jump", ""), names).isEmpty()) throw new AssertionError(id + ": no route " + Json.text(rule, "jump", "") + " runs it");
            learned++;
        }
        {
            var rule = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules")).stream().map(Json::object)
                    .filter(r -> "glstatemanager-opengl".equals(r.get("id"))).findFirst().orElseThrow(() -> new AssertionError("knowledge/fixes.json has no glstatemanager-opengl rule"));
            String log = "HUD.java:3: error: cannot find symbol\nimport com.mojang.blaze3d.platform.GlStateManager;\n  symbol:   class GlStateManager\n  location: package com.mojang.blaze3d.platform\n";
            check(java.util.regex.Pattern.compile(rule.get("match").toString()).matcher(log).find()
                    && Port.rewrite(rule, "import com.mojang.blaze3d.platform.GlStateManager;\nimport com.mojang.blaze3d.platform.NativeImage;\n")
                        .equals("import com.mojang.blaze3d.opengl.GlStateManager;\nimport com.mojang.blaze3d.platform.NativeImage;\n")
                    && !Route.plan(home, "1.20.1", "1.21.6").stepsCovered(Json.text(rule, "jump", ""), "yarn").isEmpty(),
                    "GlStateManager's 1.21.5 package move is rewritten in yarn and mojmap sources, so javac no longer stops at the import");
        }
        check(learned >= 30, learned +" rules learned from Stonecutter switches name real switches in two or more mods, match their error, rewrite their example and run on their update");
        // Every other rule that carries a test is held to the same three facts.
        int written = 0;
        for (Object item : Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            if ("stonecutter".equals(rule.get("source")) || !(rule.get("test") instanceof Map<?, ?>)) continue;
            String id = Json.text(rule, "id", "?");
            var test = Json.object(rule.get("test"));
            // A silent rule has no compiler error to match: it runs whenever the port crosses its update.
            if (!rule.containsKey("silent") && !java.util.regex.Pattern.compile(rule.get("match").toString()).matcher(test.get("error").toString()).find()) throw new AssertionError(id + ": match misses its test error");
            String after = "line".equals(rule.get("scope")) ? Port.rewriteReported(rule, test.get("before").toString(), test.get("error").toString(), file -> true)
                    : Port.rewrite(rule, test.get("before").toString());
            if (after.equals(test.get("before")) || !after.equals(test.get("after"))) throw new AssertionError(id + ": rewrite gave\n" + after + "\nexpected\n" + test.get("after"));
            String[] range = Json.text(rule, "jump", "").replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            if (rule.containsKey("silent") && !(Port.crosses(Json.text(rule, "jump", ""), range[0], range[1]) && !Port.crosses(Json.text(rule, "jump", ""), range[1], range[1])))
                throw new AssertionError(id + ": a silent rule must run on the port across its own update and on no other");
            Route route = Route.plan(home, range[0], range[1]);
            if (route == null || route.stepsCovered(Json.text(rule, "jump", ""), Json.text(rule, "jump", "").contains("yarn") ? "yarn" : "mojmap").isEmpty()) throw new AssertionError(id + ": no route " + Json.text(rule, "jump", "") + " runs it");
            written++;
        }
        check(written >= 3, written + " written rules with a test match their error, rewrite their example and run on their update");
    }
    /**
     * A getter the game removed while keeping its field (1.21.2's HungerManager.getExhaustion()) becomes an accessor mixin
     * the port writes into the mod's mixin package, lists in its main mixins config, and deletes again on undo.
     */
    static void accessorChecks(Path home, Path scratch) throws Exception {
        String chain = "x = a.b(c, d(e)).f[0].getExhaustion();";
        check(chain.substring(Port.receiverStart(chain, chain.indexOf(".getExhaustion"))).startsWith("a.b(c"), "a call's receiver is the whole chain of names, calls and indexes before it");
        String created = "return new Stats(1).getExhaustion();";
        check(created.substring(Port.receiverStart(created, created.indexOf(".getExhaustion"))).startsWith("new Stats(1)"), "a receiver made with new keeps its new");
        Path root = scratch.resolve("accessor"), java = root.resolve("src/main/java/mod"), resources = root.resolve("src/main/resources");
        Files.createDirectories(java.resolve("mixin")); Files.createDirectories(java.resolve("client")); Files.createDirectories(java.resolve("compat")); Files.createDirectories(resources);
        Files.writeString(root.resolve("settings.gradle"), "");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n");
        Files.writeString(java.resolve("mixin/HudMixin.java"), "package mod.mixin;\n\nclass HudMixin {}\n");
        Files.writeString(java.resolve("compat/JeiMixin.java"), "package mod.compat;\n\nclass JeiMixin {}\n");
        Path hud = java.resolve("client/HUD.java");
        String original = "package mod.client;\n\nclass HUD {\n\tfloat read(PlayerEntity player) {\n\t\treturn player.getHungerManager().getExhaustion();\n\t}\n\tfloat own(Payload payload) { return payload.getExhaustion(); }\n}\n";
        Files.writeString(hud, original);
        Path config = resources.resolve("mod.mixins.json");
        String listed = "{\n  \"required\": true,\n  \"package\": \"mod.mixin\",\n  \"client\": [\n    \"HudMixin\"\n  ]\n}\n";
        Files.writeString(config, listed);
        Files.writeString(resources.resolve("mod.compat.mixins.json"), "{\n  \"required\": false,\n  \"package\": \"mod.compat\",\n  \"mixins\": [\"JeiMixin\"]\n}\n");
        ProjectModel project = new ProjectModel(root);
        Port port = new Port(new Build(project, home, "", new Modaccel.Report(root, "port")), Json.map("projects", List.of()));
        port.transaction = project.state().resolve("ports/accessor"); Files.createDirectories(port.transaction); port.saveInProgress();
        port.track(hud);
        var rule = Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules")).stream().map(Json::object)
                .filter(r -> "hunger-exhaustion-accessor".equals(r.get("id"))).findFirst().orElseThrow(() -> new AssertionError("knowledge/fixes.json has no hunger-exhaustion-accessor rule"));
        String log = hud + ":5: error: cannot find symbol\n\t\treturn player.getHungerManager().getExhaustion();\n\t\t                                ^\n  symbol:   method getExhaustion()\n  location: class HungerManager\n";
        check(port.applyAccessor(rule, log), "a removed getter named by javac on its owner class is repaired by the accessor rule");
        check(Files.readString(hud).split("\n", -1).length == original.split("\n", -1).length && !Files.readString(hud).contains("import mod.mixin"),
                "the import a rule adds waits for the end of the round, so javac's line numbers still hold for the steps after it");
        port.flushImports();
        Path accessor = java.resolve("mixin/HungerManagerAccessor.java");
        String source = Files.readString(accessor);
        check(source.contains("@Mixin(HungerManager.class)") && source.contains("@Accessor(\"exhaustion\")") && source.contains("float modaccel$getExhaustion();") && !source.contains("modaccel$setExhaustion"),
                "the accessor mixin reads the field in the main mixin package, with no setter the mod never used");
        check(Files.readString(config).contains("\"package\": \"mod.mixin\",\n  \"mixins\": [\"HungerManagerAccessor\"],\n  \"client\""), "the accessor is listed in the required mixins config, not the optional one: " + Files.readString(config));
        String repaired = Files.readString(hud);
        check(repaired.contains("return ((HungerManagerAccessor) player.getHungerManager()).modaccel$getExhaustion();") && repaired.contains("return payload.getExhaustion();")
                && repaired.contains("import mod.mixin.HungerManagerAccessor;"), "only the call javac reported goes through the accessor; the mod's own getter of the same name is kept");
        Port.finishTransaction(project, port.transaction, port.before);
        Port.undo(project, port.transaction.toString());
        check(!Files.exists(accessor) && Files.readString(config).equals(listed) && Files.readString(hud).equals(original), "undo deletes the accessor it wrote and restores the config and the call");
        check(Port.registerMixin("{\"package\": \"a\", \"mixins\": []}", "X").equals("{\"package\": \"a\", \"mixins\": [\"X\"]}")
                && Port.registerMixin("{\"package\": \"a\", \"mixins\": [\"Y\"]}", "X").equals("{\"package\": \"a\", \"mixins\": [\"X\", \"Y\"]}"), "a mixin goes first in a config's common list, empty or not");
    }
    /** Mixin targets that a port leaves stale, repaired from the class map and from Mixin's own words (AppleSkin 1.20.1 -> 1.21.6). */
    static void mixinRepairChecks() {
        Map<String, String> classes = Map.of("net.minecraft.client.item.TooltipData", "net.minecraft.item.tooltip.TooltipData", "net.minecraft.client.item.TooltipContext$Default", "net.minecraft.item.tooltip.TooltipType$Default");
        String strings = "method = \"of(Lnet/minecraft/client/item/TooltipData;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;\"\n@Mixin(targets = \"net.minecraft.client.item.TooltipContext$Default\")\nTooltipData data = null; // net/minecraft/client/item/TooltipData\n";
        check(Port.classNamesInStrings(strings, classes).equals("method = \"of(Lnet/minecraft/item/tooltip/TooltipData;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;\"\n@Mixin(targets = \"net.minecraft.item.tooltip.TooltipType$Default\")\nTooltipData data = null; // net/minecraft/client/item/TooltipData\n"),
                "a moved game class inside a string gets its full new name, in descriptor and dotted form; code and comments are left to the other renames");
        String stack = "\t@Inject(at = @At(\"RETURN\"), method = \"getTooltip(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;\")\n\tprivate void getTooltipFromItem(PlayerEntity player, TooltipType context, CallbackInfoReturnable<List> info)\n\t{\n\t}\n";
        String named = Port.repairMixinSource(stack, "Critical injection failure: @Inject annotation on getTooltipFromItem could not find any targets matching 'getTooltip(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;' in net/minecraft/item/ItemStack. No refMap loaded.");
        check(named.contains("method = \"getTooltip\")"), "a target pinned to a descriptor the game no longer has is named by its method name alone");
        String handler = Port.repairMixinSource(named, "Invalid descriptor on appleskin.mixins.json:ItemStackMixin from mod appleskin->@Inject::getTooltipFromItem(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V! Expected (Lnet/minecraft/item/Item$TooltipContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V but found (Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V [INJECT_APPLY]");
        check(handler.contains("private void getTooltipFromItem(Item.TooltipContext modaccel$tooltipContext0, PlayerEntity player, TooltipType context, CallbackInfoReturnable<List> info)") && handler.startsWith("import net.minecraft.item.Item;\n"),
                "a handler gets the parameter its target gained, in Mixin's order, and keeps its own names: " + handler.lines().filter(l -> l.contains("getTooltipFromItem(")).findFirst().orElse(""));
        check(Port.repairMixinSource(named, "Invalid descriptor on x->@Inject::getTooltipFromItem(La;)V! Expected (Lb;)V but found (La;)V").equals(named), "a handler whose own parameter is gone is left for a person, not guessed");
    }
    /** Step 9: Loom follows the target. The new version lands where the build states it, a literal plugin line or the property it reads. */
    static void loomEditChecks(Path home, Path scratch) throws Exception {
        for (boolean property : List.of(false, true)) {
            Path root = scratch.resolve("loom-" + property); Files.createDirectories(root);
            Files.writeString(root.resolve("build.gradle"), "plugins {\n\tid 'fabric-loom' version " + (property ? "\"${loom_version}\"" : "'1.8-SNAPSHOT'") + "\n}\n");
            Files.writeString(root.resolve("gradle.properties"), property ? "loom_version=1.8-SNAPSHOT\nother=1\n" : "other=1\n");
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
            ProjectModel project = new ProjectModel(root);
            check(project.loomVersion().equals("1.8-SNAPSHOT"), "the Loom version is read from " + (property ? "the property the plugin line names" : "the plugin line"));
            Build build = new Build(project, home, "", new Modaccel.Report(root, "port"));
            Target.Setup setup = new Target.Setup(build, Json.map(), "1.21.6", List.of());
            check(Target.loomEdit(setup, build, "1.8-SNAPSHOT", "1.10.5"), "a stated Loom version can be raised");
            Path changed = root.resolve(property ? "gradle.properties" : "build.gradle");
            check(setup.text(changed).contains(property ? "loom_version=1.10.5\n" : "id 'fabric-loom' version '1.10.5'") && setup.edits().size() == 1,
                    "Loom 1.8-SNAPSHOT -> 1.10.5 is written to " + changed.getFileName() + " and nowhere else");
        }
    }
    /** Parchment follows the target: names for another version once compiled the 26.3 game wrong (BundlerInfo "packet == packet"). */
    static void parchmentChecks(Path home, Path scratch) throws Exception {
        String neoGradle = "neo_version=21.6.11-beta\nneogradle.subsystems.parchment.minecraftVersion=1.21.5\nneogradle.subsystems.parchment.mappingsVersion=2025.06.15\nother=1\n";
        String modDevProps = "parchment_minecraft_version=1.21.1\nparchment_mappings_version=2024.11.17\nother=1\n";
        String modDevBuild = "neoForge {\n    parchment {\n        mappingsVersion = project.parchment_mappings_version\n        minecraftVersion = project.parchment_minecraft_version\n    }\n    runs { }\n}\n";
        String loomBuild = "dependencies {\n\tmappings loom.layered {\n\t\tofficialMojangMappings()\n\t\tparchment(\"org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip\")\n\t}\n}\n";
        record Case(String name, String props, String build, String release, String wantProps, String wantBuild) {}
        List<Case> cases = List.of(
                new Case("NeoGradle, none for 26.3", neoGradle, "plugins { }\n", "", "neo_version=21.6.11-beta\nother=1\n", null),
                new Case("NeoGradle, 1.21.6 published", neoGradle, "plugins { }\n", "2025.06.29",
                        "neo_version=21.6.11-beta\nneogradle.subsystems.parchment.minecraftVersion=1.21.6\nneogradle.subsystems.parchment.mappingsVersion=2025.06.29\nother=1\n", null),
                new Case("ModDevGradle, none for 26.3", modDevProps, modDevBuild, "", "other=1\n", "neoForge {\n    runs { }\n}\n"),
                new Case("Loom layered, none for 26.3", "other=1\n", loomBuild, "", null, "dependencies {\n\tmappings loom.layered {\n\t\tofficialMojangMappings()\n\t}\n}\n"),
                new Case("Loom layered, 1.21.6 published", "other=1\n", loomBuild, "2025.06.29", null, loomBuild.replace("parchment-1.21.1:2024.11.17", "parchment-1.21.6:2025.06.29")));
        for (Case c : cases) {
            Path root = scratch.resolve("parchment-" + c.name().replaceAll("\\W+", "-")); Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
            Files.writeString(root.resolve("gradle.properties"), c.props()); Files.writeString(root.resolve("build.gradle"), c.build());
            Build build = new Build(new ProjectModel(root), home, "", new Modaccel.Report(root, "port"));
            Target.Setup setup = new Target.Setup(build, Json.map(), c.release().isEmpty() ? "26.3" : "1.21.6", List.of());
            check(Target.parchmentEdit(setup, build, setup.to, c.release())
                    && (c.wantProps() == null || setup.text(root.resolve("gradle.properties")).equals(c.wantProps()))
                    && (c.wantBuild() == null || setup.text(root.resolve("build.gradle")).equals(c.wantBuild())),
                    "Parchment follows the target (" + c.name() + "): " + setup.text(root.resolve("gradle.properties")).replace("\n", "|") + " / " + setup.text(root.resolve("build.gradle")).replace("\n", "|"));
        }
        Path plain = scratch.resolve("parchment-none"); Files.createDirectories(plain.resolve("gradle/wrapper"));
        Files.writeString(plain.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        Files.writeString(plain.resolve("gradle.properties"), "other=1\n"); Files.writeString(plain.resolve("build.gradle"), "plugins { }\n");
        Build plainBuild = new Build(new ProjectModel(plain), home, "", new Modaccel.Report(plain, "port"));
        Target.Setup untouched = new Target.Setup(plainBuild, Json.map(), "26.3", List.of());
        check(!Target.parchmentEdit(untouched, plainBuild, "26.3", null) && untouched.edits().isEmpty(), "a build without Parchment is left alone and nothing is looked up");
    }
    static Map<String, Object> testModel(String... runtime) {
        return Json.map("projects", List.of(Json.map("sourceSets", List.of(Json.map("name", "main", "runtime", List.of()),
                Json.map("name", "test", "runtime", List.of(runtime), "output", List.of())))));
    }
    /** Which tests fast selects (empty-run wording is covered by StrangerTest.noTests and FastLaneIsolationTest.plain). */
    static void fastLaneChecks() throws Exception {
        String junit = "/home/u/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api/5.13.4/x/junit-jupiter-api-5.13.4.jar";
        check(FastLane.gameFree(testModel(junit, "/home/u/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.214-stable/x/paper-api-26.3.build.214-stable.jar")),
                "a Paper plugin's test classpath (paper-api, JUnit) is game-free, so fast runs every test");
        check(FastLane.gameFree(testModel(junit)), "a plain Java test classpath is game-free");
        check(!FastLane.gameFree(testModel(junit, "C:\\Users\\u\\.gradle\\caches\\fabric-loom\\minecraftMaven\\net\\minecraft\\minecraft-merged-1.21.6\\minecraft-merged.jar",
                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\net.fabricmc\\fabric-loader\\0.16.14\\x\\fabric-loader-0.16.14.jar")),
                "a Fabric test classpath has the game, so fast keeps running only tests tagged pure");
        check(!FastLane.gameFree(testModel(junit, "/p/build/neoForge/neoFormJoined/1.21.1/artifacts/neoforge-21.1.77.jar",
                "/home/u/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.77/x/neoforge-21.1.77-universal.jar")),
                "a NeoForge test classpath has the game, so fast keeps running only tests tagged pure");
        check(!FastLane.gameFree(testModel(junit, "/home/u/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0.jar")),
                "a Forge test classpath has the game");
        check(!FastLane.gameFree(Json.map("projects", List.of())), "no test source set is never treated as game-free");
    }
    /** plugin-check picks MockBukkit by the plugin's own Paper API line, and refuses a line it has no MockBukkit for. */
    static void pluginCheckChecks(Path home) throws Exception {
        Map<String, Object> paper = Json.map("projects", List.of(Json.map("compile", List.of(Json.map("classpath", List.of(
                "/home/u/.gradle/caches/modules-2/files-2.1/net.kyori/adventure-api/4.24.0/x/adventure-api-4.24.0.jar",
                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\io.papermc.paper\\paper-api\\26.3.build.214-stable\\x\\paper-api-26.3.build.214-stable.jar"))))));
        var api = PluginCheck.api(paper);
        check(api != null && api.kind().equals("paper-api") && api.version().equals("26.3.build.214-stable"), "plugin-check reads the Paper API version from the compile class path: " + api);
        var spigot = PluginCheck.api(Json.map("projects", List.of(Json.map("compile", List.of(Json.map("classpath", List.of("/m2/org/spigotmc/spigot-api/1.21.4-R0.1-SNAPSHOT/spigot-api-1.21.4-R0.1-SNAPSHOT.jar")))))));
        check(spigot != null && spigot.kind().equals("spigot-api") && PluginCheck.line(spigot.version()).equals("1.21"), "a Spigot plugin is checked on the Paper line of its version: " + spigot);
        check(PluginCheck.api(testModel("/m2/net/fabricmc/fabric-loader-0.16.14.jar")) == null, "a Fabric mod has no server API, so plugin-check says it is not a plugin");
        var r263 = PluginCheck.release(home, "26.3.build.214-stable");
        var r121 = PluginCheck.release(home, "1.21.10-R0.1-SNAPSHOT");
        check(r263 != null && r263.artifact().endsWith("mockbukkit-v26.3") && r263.java() == 25, "Paper 26.3 is checked on MockBukkit for 26.3: " + r263);
        check(r121 != null && r121.artifact().endsWith("mockbukkit-v1.21") && r121.java() == 21, "Paper 1.21.10 is checked on MockBukkit for 1.21: " + r121);
        check(PluginCheck.release(home, "1.20.6-R0.1-SNAPSHOT") == null, "a Paper line with no MockBukkit release in the table is not guessed (it is reported unchecked)");
        String text = PluginCheck.text(Json.map("failed", true, "plugin", Json.map("name", "X", "version", "1"), "server", Json.map("paperApi", "26.3"),
                "checks", List.of(Json.map("name", "commands", "status", "fail", "details", "/x has no handler.", "file", Path.of("/p/src/main/resources/plugin.yml").toAbsolutePath().toString(), "line", 9)),
                "unchecked", List.of("uses NMS"), "warnings", List.of()), Path.of("/p").toAbsolutePath());
        check(text.startsWith("plugin-check FAILED for X 1") && text.contains("FAIL commands: /x has no handler. (src/main/resources/plugin.yml:9)") && text.contains("UNCHECKED uses NMS"),
                "a plugin-check failure names the check, the file relative to the project and the line: " + text);
    }
    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(Files.createDirectories(Path.of(args[1])), "run-");
        String restartTimes = ServerCheck.text(Json.map("ok", true, "server", Json.map("minecraft", "26.3", "build", "1"), "bot", Json.map("version", "x"),
                "steps", List.of(Json.map("line", 1, "step", "start-server", "status", "done", "seconds", 10.0, "serverStart", Json.map("seconds", 9.0)),
                        Json.map("line", 2, "step", "restart-server", "status", "done", "seconds", 6.0, "serverRestarts", List.of(Json.map("seconds", 5.0)))),
                "serverStopSeconds", 1.0, "scenario", "fixture"), scratch);
        check(restartTimes.contains("server start 14.0s, scenario 2.0s, stop 1.0s"), "server timings include every start and restart: " + restartTimes);
        Path pluginFolder = scratch.resolve("plugins, fixtures"); Files.createDirectories(pluginFolder);
        Path pluginA = pluginFolder.resolve("a.jar"), pluginB = pluginFolder.resolve("b.jar");
        Files.writeString(pluginA, "fixture"); Files.writeString(pluginB, "fixture");
        check(ServerCheck.pluginPaths(scratch, pluginFolder.toString()).size() == 2, "plugin folder names may contain commas");
        check(ServerCheck.pluginPaths(scratch, pluginA + File.pathSeparator + pluginB).size() == 2, "individual absolute plugin jars, including commas in their paths, can be listed together");
        Path existingArgs = scratch.resolve("existing loom args.txt");
        Files.writeString(existingArgs, "-Dmodaccel.launchProbe=existing-file\n");
        String classpath = System.getProperty("java.class.path") + (File.pathSeparator + scratch.resolve("nonexistent # ' path")).repeat(1100);
        List<String> launch = List.of(Path.of(System.getProperty("java.home"), "bin", Runtimes.WINDOWS ? "java.exe" : "java").toString(),
                "@" + existingArgs.toAbsolutePath(), "-cp", classpath, "PortableTests$LaunchProbe", "@literal", "spaces and slash\\");
        List<String> saved = Game.savedCommand(launch, scratch.resolve("saved classpath.args"));
        Process child = new ProcessBuilder(saved).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(child.waitFor() == 0 && output.contains("LAUNCH_OK"), "saved launches handle classpaths beyond Windows limits, existing @files and literal program arguments: " + output.strip());
        check(saved.stream().mapToInt(String::length).sum() < 8000 && saved.contains(launch.get(1)), "saved launches keep existing argument files at the top level");
        Object json = Json.parse(Json.write(Json.map("unicode", "mod 🧱", "path", "C:\\mods with spaces\\test", "rows", List.of(1, true, "\n\""))));
        check(Json.object(json).get("unicode").equals("mod 🧱"), "structured results preserve Unicode and quoted paths");
        Path quietDir = scratch.resolve("quiet game"); Files.createDirectories(quietDir);
        Files.writeString(quietDir.resolve("options.txt"), "version:5023\r\nnarrator:2\r\nguiScale:2\r\nsoundCategory_master:1.0\r\nonboardAccessibility:true\r\nkey_key.jump:key.keyboard.space\r\n");
        QuietOptions.seedArgs(new String[]{"--username", "Dev", "--gameDir", quietDir.toAbsolutePath().toString(), "--assetsDir", "x"});
        Path quietFile = quietDir.resolve("options.txt");
        List<String> quiet = Files.readAllLines(quietFile);
        check(quiet.containsAll(List.of("narrator:0", "onboardAccessibility:false", "soundCategory_master:0.0", "tutorialStep:none", "skipMultiplayerWarning:true", "joinedFirstServer:true")),
                "seeded options.txt has no narrator, no sound, no tutorial or first-server prompts: " + quiet);
        check(quiet.containsAll(List.of("version:5023", "guiScale:2", "key_key.jump:key.keyboard.space")) && quiet.indexOf("version:5023") == 0 && quiet.size() == 9,
                "seeding options.txt keeps every other key and its order");
        Path freshQuiet = QuietOptions.seed(scratch.resolve("fresh quiet"));
        check(Files.readAllLines(freshQuiet).equals(List.of("narrator:0", "onboardAccessibility:false", "soundCategory_master:0.0", "tutorialStep:none", "skipMultiplayerWarning:true", "joinedFirstServer:true")),
                "a first launch gets a quiet options.txt");
        Path blockedQuiet = scratch.resolve("not a directory"); Files.writeString(blockedQuiet, "file");
        try {
            QuietOptions.seedArgs(new String[]{"--gameDir", blockedQuiet.toAbsolutePath().toString()});
            throw new AssertionError("Minecraft must not start when its quiet options cannot be written");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Cannot start Minecraft quietly"), "quiet setup reports why the game cannot start");
        }
        Path prefs = scratch.resolve("user/preferences.json");
        check(Boolean.FALSE.equals(Preferences.read(prefs).get("background")) && Boolean.FALSE.equals(Preferences.read(prefs).get("quiet")) && !Files.exists(prefs),
                "a new user keeps normal window and sound settings without creating a preference file");
        Preferences.change(prefs, new Modaccel.Options(new String[]{"preferences", "--background", "on", "--quiet", "on"}));
        check(Boolean.TRUE.equals(Preferences.read(prefs).get("background")) && Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
                "personal background and quiet choices survive a reload");
        Preferences.change(prefs, new Modaccel.Options(new String[]{"preferences", "--background", "off"}));
        check(Boolean.FALSE.equals(Preferences.read(prefs).get("background")) && Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
                "each personal choice can change without changing the other");
        try { Preferences.change(prefs, new Modaccel.Options(new String[]{"preferences", "--quiet", "maybe"})); throw new AssertionError("invalid preference accepted"); }
        catch (IllegalArgumentException expected) { check(Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")), "an invalid choice leaves the saved preference intact"); }
        Path dark = scratch.resolve("frame dark.png"), half = scratch.resolve("frame half.png");
        BufferedImage left = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), right = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) right.setRGB(x, y, 0xFFFFFF);
        ImageIO.write(left, "png", dark.toFile()); ImageIO.write(right, "png", half.toFile());
        check(Boolean.TRUE.equals(Game.compare(dark, half, 40).get("passed")), "visual gate accepts a real frame change (50% vs 40% required)");
        try { Game.compare(dark, half, 60); throw new AssertionError("small change accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("no visible result"), "visual gate rejects an invisible result (50% vs 60% required)"); }
        try { Game.compare(dark, dark, 1); throw new AssertionError("identical frames accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("only 0.000%"), "identical frames are reported as no visible change"); }
        Map<String, Object> positionA = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionB = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionC = Json.map("x", 1.56, "y", 64.0, "z", -3.5);
        check(Game.samePosition(positionA, positionB, 0.05), "save round-trip accepts an unchanged position");
        check(!Game.samePosition(positionA, positionC, 0.05), "save round-trip rejects a moved position beyond tolerance");
        Map<String, Object> hidden = Json.map("background", true, "log", "game.log"), shown = Json.map("background", false, "log", "game.log");
        check(Game.hiddenWindowFailure(hidden, Json.map("backgroundApplied", "org/lwjgl/sdl/SDLVideo,org/lwjgl/sdl/SDLMouse", "backgroundError", "")) == null, "a hidden window that was rewritten lets the check run");
        check(String.valueOf(Game.hiddenWindowFailure(hidden, Json.map("backgroundApplied", "org/lwjgl/sdl/SDLVideo", "backgroundError", "Unsupported org/lwjgl/sdl/SDLMouse window controls"))).contains("SDLMouse window controls"), "a failed rewrite stops the check and names the library");
        check(String.valueOf(Game.hiddenWindowFailure(hidden, Json.map("backgroundApplied", "", "backgroundError", ""))).contains("no window library was rewritten"), "nothing rewritten stops the check");
        check(Game.hiddenWindowFailure(shown, Json.map("backgroundApplied", "", "backgroundError", "")) == null, "a visible window by choice is not a failure");
        List<Map<String, Object>> loaded = new ArrayList<>(List.of(Json.map("event", "start"), Json.map("event", "mods", "ours", List.of("demo")),
                Json.map("event", "registered", "mod", "demo", "counts", Json.map("item", 1, "loot_table", 1)),
                Json.map("event", "item", "id", "demo:gem", "name", "Gem", "maxStack", 16, "maxDamage", 0, "rarity", "common"), Json.map("event", "done", "cpu", 17.0)));
        var dataOk = DataCheck.verdict(loaded, 0, "log");
        check(Boolean.TRUE.equals(dataOk.get("ok")) && String.valueOf(dataOk.get("details")).contains("demo:gem \"Gem\": stacks to 16"), "data-check passes a clean load and shows each item: " + dataOk.get("details"));
        loaded.add(3, Json.map("event", "log", "level", "ERROR", "logger", "Minecraft", "message", "Couldn't parse demo:broken"));
        check(!Boolean.TRUE.equals(DataCheck.verdict(loaded, 0, "log").get("ok")), "an error the game logged fails data-check");
        var stopped = DataCheck.verdict(List.of(Json.map("event", "start"), Json.map("event", "log", "level", "ERROR", "logger", "Minecraft", "message", "Errors in element demo:broken"),
                Json.map("event", "error", "phase", "probe", "message", "Registry Loading")), 4, "log");
        check(!Boolean.TRUE.equals(stopped.get("ok")) && String.valueOf(stopped.get("details")).contains("Errors in element demo:broken"), "a load that stops shows what the game said first");
        check(!Boolean.TRUE.equals(DataCheck.verdict(List.of(Json.map("event", "start"), Json.map("event", "mods", "ours", List.of()), Json.map("event", "done")), 0, "log").get("ok")), "nothing of the project loaded is not a pass");
        var again = DataCheck.verdict(List.of(Json.map("event", "start", "cpu", 40.0, "first", false), Json.map("event", "mods", "ours", List.of("demo")), Json.map("event", "done", "cpu", 42.2)), 0, "log");
        check(Double.valueOf(2.2).equals(again.get("cpuSeconds")) && Boolean.FALSE.equals(again.get("firstInJvm")), "a check in the loaded game counts only its own CPU: " + again.get("cpuSeconds"));
        // data-check's spare: it may take changed code only when nothing it has already read changed.
        Path out = scratch.resolve("spare-out");
        String initClass = out.resolve("demo/Demo.class").toString(), items = out.resolve("demo/Items.class").toString(), mixin = out.resolve("demo/mixin/M.class").toString(),
                newMixin = out.resolve("demo/mixin/N.class").toString(), mod = out.resolve("fabric.mod.json").toString();
        Map<String, Object> spare = Json.map("files", Json.map(initClass, "a", items, "b", mixin, "c", mod, "d"), "early", List.of(mod), "mixinPackages", List.of(out.resolve("demo/mixin").toString()));
        check(DataCheck.changedReads(spare, Map.of(initClass, "A", items, "B", mixin, "c", mod, "d"), List.of()).isEmpty(), "a spare takes changed classes it has not read yet");
        check(DataCheck.changedReads(spare, Map.of(initClass, "A", items, "b", mixin, "c", mod, "d"), List.of(items)).isEmpty(), "a spare takes changed code when the classes it read are unchanged");
        check(DataCheck.changedReads(spare, Map.of(initClass, "a", items, "B", mixin, "c", mod, "d"), List.of(items)).equals(List.of("Items.class")), "a spare refuses code when a class it has defined changed");
        check(DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "C", mod, "d"), List.of()).equals(List.of("M.class")), "a spare refuses code when a mixin class changed");
        check(DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "c", newMixin, "e", mod, "d"), List.of()).equals(List.of("N.class")), "a spare refuses code when a mixin class appeared");
        check(DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "c", mod, "D"), List.of()).equals(List.of("fabric.mod.json")), "a spare refuses code when loader metadata changed");
        // A changed resource file Gradle copies as it is: copied without Gradle; anything else waits for Gradle.
        Path res = scratch.resolve("rc/src/main/resources"), built = scratch.resolve("rc/build/resources/main"), code = scratch.resolve("rc/src/main/java/A.java");
        Files.createDirectories(res.resolve("data")); Files.createDirectories(built.resolve("data")); Files.createDirectories(code.getParent());
        Files.writeString(res.resolve("data/r.json"), "{\"v\":1}"); Files.writeString(built.resolve("data/r.json"), "{\"v\":1}");
        Files.writeString(res.resolve("data/gone.json"), "{}"); Files.writeString(built.resolve("data/gone.json"), "{}");
        Files.writeString(res.resolve("fabric.mod.json"), "{\"version\":\"${version}\"}"); Files.writeString(built.resolve("fabric.mod.json"), "{\"version\":\"1.0\"}");
        Map<String, Object> rcModel = Json.map("projects", List.of(Json.map("sourceSets", List.of(Json.map("resources", List.of(res.toString()), "output", List.of(scratch.resolve("rc/build/classes/java/main").toString(), built.toString()))))));
        List<List<String>> rcRoots = ResourceCopy.roots(rcModel);
        String r = res.resolve("data/r.json").toString(), gone = res.resolve("data/gone.json").toString(), meta = res.resolve("fabric.mod.json").toString(), a = code.toString();
        Map<String, Object> rcRecord = Json.map("inputs", Json.map(r, "1", gone, "2", meta, "3", a, "4"), "resourceRoots", rcRoots, "filtered", ResourceCopy.filtered(rcRoots));
        check(rcRoots.equals(List.of(List.of(res.toString(), built.toString()))) && ProjectModel.strings(rcRecord.get("filtered")).equals(List.of(meta)), "resource folders map to their output and filtered files are known: " + rcRoots + " " + rcRecord.get("filtered"));
        check(ResourceCopy.copy(Json.map("task", ":runClient"), Map.of(r, "9")) == -1, "a launch recorded without file hashes runs Gradle");
        check(ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "3", a, "5")) == -1, "a changed class source runs Gradle");
        check(ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "6", a, "4")) == -1, "a resource Gradle filtered runs Gradle");
        Files.writeString(res.resolve("data/r.json"), "{\"v\":\"${v}\"}");
        check(ResourceCopy.copy(rcRecord, Map.of(r, "7", gone, "2", meta, "3", a, "4")) == -1, "a placeholder runs Gradle");
        Files.writeString(res.resolve("data/r.json"), "{\"v\":2}"); Files.delete(res.resolve("data/gone.json"));
        int copiedFiles = ResourceCopy.copy(rcRecord, Map.of(r, "8", gone, "missing", meta, "3", a, "4"));
        check(copiedFiles == 2 && Files.readString(built.resolve("data/r.json")).equals("{\"v\":2}") && !Files.exists(built.resolve("data/gone.json")), "changed and deleted data files are copied and removed without Gradle: " + copiedFiles);
        try { Json.parse("{\"ok\":true,\"ok\":false}"); throw new AssertionError("duplicate accepted"); } catch (IllegalArgumentException expected) { count++; }
        Path root = scratch.resolve("mod with spaces"); Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[0]);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Path source = root.resolve("src/custom/Food.java"); Files.createDirectories(source.getParent()); Files.writeString(source, "class Food { int n = 1; }\r\n");
        ProjectModel project = new ProjectModel(root); Path init = home.resolve("tools/cli/gradle.init.gradle");
        String key = project.configurationHash(init); FileTime time = Files.getLastModifiedTime(root.resolve("build.gradle.kts"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java } //changed\n"); Files.setLastModifiedTime(root.resolve("build.gradle.kts"), time);
        check(!key.equals(project.configurationHash(init)), "build configuration changes invalidate metadata even with preserved timestamps");
        Path nested = root.resolve("unrelated/.git"); Files.createDirectories(nested); Files.writeString(nested.getParent().resolve("build.gradle"), "secret build");
        check(project.inputs().stream().noneMatch(p -> p.startsWith(nested.getParent())), "nested git checkouts are excluded from project discovery");
        Map<String, Object> model = Json.map("projects", List.of(Json.map("directory", root.toString(), "buildFile", root.resolve("build.gradle.kts").toString(),
                "sourceSets", List.of(Json.map("name", "test", "sources", List.of(source.getParent().toString()), "java", List.of(source.getParent().toString()), "output", List.of())), "compile", List.of())));
        String sourceHash = Fingerprints.build(model, project, init); FileTime modified = Files.getLastModifiedTime(source);
        Files.writeString(source, "class Food { int n = 2; }\r\n"); Files.setLastModifiedTime(source, modified);
        check(!sourceHash.equals(Fingerprints.build(model, project, init)), "source content invalidates warm builds even when timestamps and length match");
        Path layered = root.resolve("custom-client/Layered.java"); Files.createDirectories(layered.getParent()); Files.writeString(layered, "class Layered {}\n");
        String layeredHash = Fingerprints.build(model, project, init); Files.writeString(layered, "class Layered { int n; }\n");
        check(!layeredHash.equals(Fingerprints.build(model, project, init)), "custom source layouts cannot bypass the build identity");
        // A jar Gradle makes inside the project (NeoGradle's ng_dummy_ng repository) is rewritten by the first launch: its
        // bytes must not count, or a never-launched copy's first check is refused. A jar the author keeps in libs/ must count.
        Path made = root.resolve(".gradle/repositories/ng_dummy_ng/neoforge.jar"), kept = root.resolve("libs/helper.jar");
        Files.createDirectories(made.getParent()); Files.createDirectories(kept.getParent()); Files.writeString(made, "a"); Files.writeString(kept, "a");
        Map<String, Object> jars = Json.map("projects", List.of(Json.map("directory", root.toString(), "buildFile", root.resolve("build.gradle.kts").toString(), "sourceSets", List.of(),
                "compile", List.of(Json.map("sources", List.of(), "classpath", List.of(made.toString(), kept.toString()))))));
        String jarHash = Fingerprints.build(jars, project, init); Files.writeString(made, "b");
        check(jarHash.equals(Fingerprints.build(jars, project, init)), "a classpath jar Gradle rewrites inside the project's .gradle folder does not make the project look changed");
        Files.writeString(kept, "b");
        check(!jarHash.equals(Fingerprints.build(jars, project, init)), "a changed jar in the project's libs folder does make it look changed");
        check(Fingerprints.changed(Map.of("x", "1"), Map.of("x", "2")).equals(List.of("x (changed)")), "a refused check names the file that changed");
        Path stale = root.resolve("build/test-results/modaccelPure/TEST-old.xml"); Files.createDirectories(stale.getParent()); Files.writeString(stale, "<testsuite tests=\"8\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        check(((Number)TestResults.read(model, "modaccelPure", "fresh-run").get("executed")).intValue() == 0, "old successful XML cannot make an empty run pass");
        Build build = new Build(project, home, "", new Modaccel.Report(root, "test")); Port port = new Port(build, model);
        Build portBuild = new Build(project, home, "", new Modaccel.Report(root, "port"));
        routeChecks(home, new Port(portBuild, model), scratch);
        learnedRuleChecks(home);
        loomEditChecks(home, scratch);
        accessorChecks(home, scratch);
        mixinRepairChecks();
        parchmentChecks(home, scratch);
        for (var groups : List.of(List.of("net.minecraftforge"), List.of("net.fabricmc", "net.neoforged"), List.<String>of())) {
            var dependencies = groups.stream().map(group -> Json.map("group", group)).toList();
            try {
                Target.plan(portBuild, Json.map("projects", List.of(Json.map("dependencies", dependencies))), "1.21.6");
                throw new AssertionError("unsupported loader reached automatic target setup: " + groups);
            } catch (IOException expected) {
                check(expected.getMessage().contains("Fabric or NeoForge") && expected.getMessage().contains("target dependencies"),
                        "unsupported loader gives manual setup guidance before network or edits: " + groups);
            }
        }
        check(portBuild.command(List.of("help")).contains("-Dfabric.loom.ci=true"), "port Gradle skips dependency source remapping");
        check(!portBuild.command(List.of("help")).contains("--no-configure-on-demand"), "modaccel's Gradle runs keep the build's configure-on-demand");
        Path missed = scratch.resolve("missed.log");
        Files.writeString(missed, "* What went wrong:\nCannot locate tasks that match ':modaccelMetadata' as task 'modaccelMetadata' not found in root project 'diorama'.\n");
        check(Build.selectionFailed(missed), "a task-selection failure is recognized for the configure-on-demand retry");
        Files.writeString(missed, "* What went wrong:\nExecution failed for task ':compileJava'.\n");
        check(!Build.selectionFailed(missed), "a compile failure does not trigger the configure-on-demand retry");
        check(build.command(List.of("help")).contains("-Dfabric.loom.ci=true"), "verification Gradle also skips dependency source remapping");
        Path ngGame = scratch.resolve("ng/.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.248/neoforge-21.1.248.jar");
        Map<String, Object> ngModel = Json.map("projects", List.of(Json.map("path", ":", "plugins", List.of("net.neoforged.gradle.userdev.UserDevPlugin"),
                "compile", List.of(Json.map("task", ":compileJava", "classpath", List.of(ngGame.toString()))))));
        check("21.1.248".equals(OwnCompile.neoforgeVersion(ngModel)), "a NeoGradle build waiting on NeoForm's game jar gets the installer-made jar");
        Files.createDirectories(ngGame.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(ngGame))) { zip.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/client/Minecraft.class")); zip.closeEntry(); }
        check(OwnCompile.neoforgeVersion(ngModel) == null, "once NeoForm has built the game jar, the Gradle compile is used");
        check(OwnCompile.neoforgeVersion(Json.map("projects", List.of(Json.map("path", ":", "compile", List.of(Json.map("task", ":compileJava", "classpath", List.of(scratch.resolve("minecraft-merged.jar").toString()))))))) == null,
                "a Fabric build keeps the Gradle compile");
        Path transaction = project.state().resolve("ports/test-undo"), backup = transaction.resolve("before/src/custom/Food.java"); Files.createDirectories(backup.getParent());
        byte[] original = "class Food { int n = 1; }\r\n// original 🧱".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(backup, original); Map<String, Object> before = Json.map("src/custom/Food.java", Fingerprints.bytes(original));
        port.finishTransaction(transaction, before);
        String repaired = Files.readString(source); Files.writeString(source, repaired + "// later user edit\n");
        try { port.undo(transaction.toString()); throw new AssertionError("overwrote later edit"); } catch (IOException expected) { check(Files.readString(source).contains("later user edit"), "undo refuses and preserves edits made after a port"); }
        Files.writeString(source, repaired); port.undo(transaction.toString());
        check(Arrays.equals(original, Files.readAllBytes(source)), "undo restores exact original bytes, Unicode, CRLF and missing final newline");
        check(!Files.readString(backup).contains("later user edit"), "repair backups remain independent of working files");
        check(Target.replace("# MC\r\nminecraft_version = 1.21.5\r\nother=kept\r\n", "minecraft_version", "1.21.6")
                .equals("# MC\r\nminecraft_version = 1.21.6\r\nother=kept\r\n"), "target setup preserves property spacing, comments and line endings");
        try { Target.replace("minecraft_version=1\nminecraft_version=2\n", "minecraft_version", "3"); throw new AssertionError("ambiguous pin accepted"); }
        catch (IOException expected) { check(true, "target setup refuses duplicate property owners"); }
        Path pin = root.resolve("gradle.properties"); Files.writeString(pin, "minecraft_version=1\n");
        var edit = new Target.Edit(pin, Files.readString(pin), "minecraft_version=2\n"); Files.writeString(pin, "minecraft_version=3\n");
        try { Target.apply(List.of(edit)); throw new AssertionError("overwrote concurrent config edit"); }
        catch (IOException expected) { check(Files.readString(pin).contains("=3"), "target setup preserves configuration edited after resolution"); }
        String groovy = "// keep\r\nmodCompileOnly 'example:mod:1.0'\r\n";
        check(Dependencies.literal(groovy, "example:mod", "1.0", "2.0").equals(groovy.replace(":1.0'", ":2.0'")), "dependency migration preserves Groovy quotes, comments and CRLF");
        String kotlin = "modImplementation(\"example:mod:1.0\")\n";
        check(Dependencies.literal(kotlin, "example:mod", "1.0", "2.0").equals(kotlin.replace(":1.0\"", ":2.0\"")), "dependency migration preserves Kotlin syntax");
        try { Dependencies.literal(groovy + groovy, "example:mod", "1.0", "2.0"); throw new AssertionError("ambiguous dependency accepted"); }
        catch (IOException expected) { check(true, "dependency migration refuses ambiguous literal owners"); }
        Path crash = scratch.resolve("startup.log"); Files.writeString(crash, "Caused by: third.party.MixinFailure: broken hook\n" + "Gradle footer\n".repeat(40));
        check(Run.failure(crash).contains("third.party.MixinFailure"), "startup errors retain the actual cause above the Gradle footer");
        Path metadataRoot = scratch.resolve("metadata-mod"); Path libs = metadataRoot.resolve("build/libs"); Files.createDirectories(libs);
        String toml = "[modLoader=\"javafml\"]\n[[mods]]\nmodId=\"m\"\n[[dependencies.m]]\nmodId=\"neoforge\"\nversionRange=\"[21.4,)\"\n[[dependencies.m]]\nmodId=\"minecraft\"\nversionRange=\"%s\"\n";
        Map<String, Object> neoModel = Json.map("projects", List.of(Json.map("plugins", List.of("net.neoforged.moddev.NeoForgeModDevPlugin"))));
        Path packaged = libs.resolve("m-1.0.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.1,1.21.1]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Map<String, Object> staleMetadata = Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4");
        check(Boolean.FALSE.equals(staleMetadata.get("ok")) && staleMetadata.get("details").toString().contains("0 mods"),
                "a packaged mod that still declares the old Minecraft range is refused instead of looking like a passing port");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.4,1.21.4]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        check(Boolean.TRUE.equals(Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4").get("ok")),
                "packaged metadata that accepts the target version passes the loader check");
        fastLaneChecks();
        pluginCheckChecks(home);
        System.out.println(count + " portable checks passed");
    }
    public static final class LaunchProbe {
        public static void main(String[] args) {
            if (!"existing-file".equals(System.getProperty("modaccel.launchProbe")) || !Arrays.equals(args, new String[] {"@literal", "spaces and slash\\"}))
                throw new AssertionError("launch arguments changed: " + System.getProperty("modaccel.launchProbe") + " " + Arrays.toString(args));
            System.out.println("LAUNCH_OK");
        }
    }
}
