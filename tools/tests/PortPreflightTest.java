import java.nio.file.*;
import java.util.*;

/** Source-only gates for the reviewed, mapping-backed precompile recipes. */
public final class PortPreflightTest {
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("mmt-preflight-");
        Path map = scratch.resolve("reverse.json");
        Files.writeString(map, """
            { "from": "1.21.6", "to": "1.21.5", "names": "yarn",
              "signatures": [
                { "owner": "net.minecraft.client.gui.DrawContext", "from": "drawGuiTexture", "to": "drawGuiTexture", "newDesc": "(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIII)V" },
                { "owner": "net.minecraft.client.gui.DrawContext", "from": "drawTexture", "to": "drawTexture", "newDesc": "(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIII)V" },
                { "owner": "net.minecraft.client.gui.DrawContext", "from": "getMatrices", "to": "getMatrices", "newDesc": "()Lnet/minecraft/client/util/math/MatrixStack;" }
              ] }
            """);
        VersionMap.Known reverse = VersionMap.Known.load(map);
        String good = """
            import net.minecraft.client.gui.DrawContext;
            import net.minecraft.client.gl.RenderPipelines;
            import org.joml.Matrix3x2fStack;
            class Demo {
              void draw(DrawContext context) {
                Matrix3x2fStack stack;
                stack = context.getMatrices();
                stack.pushMatrix();
                stack.translate(1, 2);
                stack.scale(0.5f, 0.5f);
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, texture, 1, 2, 3, 4);
                stack.popMatrix();
              }
            }
            """;
        String changed = VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), good);
        require(changed.contains("RenderLayer::getGuiTextured"), "pipeline backport");
        require(changed.contains("net.minecraft.client.util.math.MatrixStack stack"), "local matrix type");
        require(changed.contains("stack.push()") && changed.contains("stack.translate(1, 2, 0)") && changed.contains("stack.scale(0.5f, 0.5f, 1)"), "local matrix operations");
        require(VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), changed).equals(changed), "idempotent backport");
        String lookalike = good.replace("import net.minecraft.client.gui.DrawContext;", "import demo.DrawContext;");
        require(VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), lookalike).equals(lookalike), "unrelated DrawContext import");
        String localContext = good.replace("class Demo {", "class Demo {\n  static class DrawContext {}");
        require(VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), localContext).equals(localContext), "local DrawContext shadows import");
        String localPipeline = good.replace("class Demo {", "class Demo {\n  static class RenderPipelines { static Object GUI_TEXTURED; }");
        String localPipelineResult = VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), localPipeline);
        require(localPipelineResult.contains("drawGuiTexture(RenderPipelines.GUI_TEXTURED"), "local RenderPipelines shadows import");
        String escape = good.replace("stack.popMatrix();", "escape(stack);\n    stack.popMatrix();");
        String escaped = VersionMap.ApiFix.preflight(reverse, scratch.resolve("Demo.java"), escape);
        require(escaped.contains("Matrix3x2fStack stack") && escaped.contains("pushMatrix()"), "escaping matrix remains untouched");
        VersionMap.Known noTarget = new VersionMap.Known();
        noTarget.names = "yarn"; noTarget.toVersion = "1.21.5";
        require(VersionMap.ApiFix.preflight(noTarget, scratch.resolve("Demo.java"), good).equals(good), "absent target signatures");

        Path forwardMap = scratch.resolve("forward.json");
        Files.writeString(forwardMap, """
            { "from": "1.21.6", "to": "1.21.9", "names": "yarn",
              "signatures": [
                { "owner": "net.minecraft.client.util.InputUtil", "from": "isKeyPressed", "to": "isKeyPressed", "newDesc": "(Lnet/minecraft/client/util/Window;I)Z" }
              ] }
            """);
        VersionMap.Known forward = VersionMap.Known.load(forwardMap);
        String key = """
            import net.minecraft.client.MinecraftClient;
            import net.minecraft.client.util.InputUtil;
            class Keys {
              boolean test() {
                long handle = MinecraftClient.getInstance().getWindow().getHandle();
                return InputUtil.isKeyPressed(handle, 65);
              }
            }
            """;
        String keyed = VersionMap.ApiFix.preflight(forward, scratch.resolve("Keys.java"), key);
        require(keyed.contains("InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), 65)"), "native window source: " + keyed);
        String unknown = key.replace("MinecraftClient.getInstance().getWindow().getHandle()", "unknownHandle()");
        require(VersionMap.ApiFix.preflight(forward, scratch.resolve("Keys.java"), unknown).equals(unknown), "unknown handle provenance");
        require(VersionMap.ApiFix.preflight(forward, scratch.resolve("Keys.java"), key.replace("import net.minecraft.client.util.InputUtil;", "import demo.InputUtil;")).equals(key.replace("import net.minecraft.client.util.InputUtil;", "import demo.InputUtil;")), "unrelated InputUtil import");
        String shadowedInput = key.replace("class Keys {", "class Keys {\n  Object InputUtil;");
        require(VersionMap.ApiFix.preflight(forward, scratch.resolve("Keys.java"), shadowedInput).equals(shadowedInput), "InputUtil field shadows import");
        VersionMap.Known noWindow = new VersionMap.Known(); noWindow.names = "yarn"; noWindow.toVersion = "1.21.9";
        require(VersionMap.ApiFix.preflight(noWindow, scratch.resolve("Keys.java"), key).equals(key), "absent Window signature");

        Path colorMap = scratch.resolve("color.json");
        Files.writeString(colorMap, """
            { "from": "1.21.5", "to": "1.21.6", "names": "yarn",
              "signatures": [
                { "owner": "net.minecraft.client.gui.DrawContext", "from": "drawTextWithShadow", "desc": "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I", "to": "drawTextWithShadow", "newDesc": "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V" },
                { "owner": "net.minecraft.client.gui.DrawContext", "from": "drawText", "desc": "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I", "to": "drawText", "newDesc": "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V" }
              ] }
            """);
        VersionMap.Known color = VersionMap.Known.load(colorMap);
        String rgb = """
            import net.minecraft.client.gui.DrawContext;
            class ColorDemo {
              void draw(DrawContext context, Config config, int color) {
                context.drawTextWithShadow(renderer, text, 1, 2, config.textColor);
                context.drawText(renderer, text, 1, 2, color, false);
                context.drawTextWithShadow(renderer, text, 1, 2, 0xFFFFFF);
                context.drawTextWithShadow(renderer, text, 1, 2, 0x80FFFFFF);
                context.drawTextWithShadow(renderer, text, 1, 2, nextColor());
              }
            }
            """;
        String colored = VersionMap.ApiFix.preflight(color, scratch.resolve("ColorDemo.java"), rgb);
        require(colored.contains("(config.textColor & 0xFC000000) == 0 ? (config.textColor | 0xFF000000) : config.textColor"), "stable field preserves old alpha fallback");
        require(colored.contains("(color & 0xFC000000) == 0 ? (color | 0xFF000000) : color"), "stable identifier preserves old alpha fallback");
        require(colored.contains("0xFFFFFFFF") && colored.contains("0x80FFFFFF"), "RGB literal folded, explicit ARGB preserved");
        require(colored.contains("nextColor())"), "effectful color expression refused");
        require(VersionMap.ApiFix.preflight(color, scratch.resolve("ColorDemo.java"), colored).equals(colored), "text color preflight idempotent");
        require(VersionMap.ApiFix.preflight(color, scratch.resolve("ColorDemo.java"), rgb.replace("import net.minecraft.client.gui.DrawContext;", "import demo.DrawContext;")).equals(rgb.replace("import net.minecraft.client.gui.DrawContext;", "import demo.DrawContext;")), "text color wrong declaring type refused");
        require(VersionMap.ApiFix.preflight(color, scratch.resolve("ColorDemo.java"), rgb.replace("class ColorDemo {", "class ColorDemo { static class DrawContext {}"))
                .equals(rgb.replace("class ColorDemo {", "class ColorDemo { static class DrawContext {}")), "shadowed DrawContext refused");
        VersionMap.Known missingColorMap = new VersionMap.Known(); missingColorMap.names = "yarn"; missingColorMap.fromVersion = "1.21.5"; missingColorMap.toVersion = "1.21.6";
        require(VersionMap.ApiFix.preflight(missingColorMap, scratch.resolve("ColorDemo.java"), rgb).equals(rgb), "missing text API evidence refused");
        VersionMap.Known wrongHop = VersionMap.Known.load(colorMap); wrongHop.fromVersion = "1.21.4";
        require(VersionMap.ApiFix.preflight(wrongHop, scratch.resolve("ColorDemo.java"), rgb).equals(rgb), "unknown version hop refused");

        // An @Override that stopped overriding: the game renamed render differently per owner, so the class's own
        // supertypes pick the new name; without them nothing is guessed.
        Path repo = Files.createDirectories(scratch.resolve("overrides"));
        Path overrideMap = scratch.resolve("override.json");
        Files.writeString(overrideMap, """
            { "from": "26.1.2", "to": "26.2", "names": "mojmap",
              "methods": [
                { "owner": "net.minecraft.client.gui.screens.Screen", "from": "render", "desc": "()V", "to": "extractRenderState" },
                { "owner": "net.minecraft.client.gui.components.Panel", "from": "render", "desc": "()V", "to": "draw" }
              ]
            }
            """);
        String screen = "import net.minecraft.client.gui.screens.Screen;\nclass MyScreen extends Screen {\n  @Override\n  public void render() {}\n}\n";
        String panel = "import net.minecraft.client.gui.components.Panel;\nclass MyPanel extends ModPanel {\n  @Override\n  public void render() {}\n}\n";
        Files.writeString(repo.resolve("MyScreen.java"), screen);
        Files.writeString(repo.resolve("MyPanel.java"), panel);
        Path overrideLog = scratch.resolve("override.log");
        Files.writeString(overrideLog, repo.resolve("MyScreen.java") + ":4: error: method does not override or implement a method from a supertype\n  public void render() {}\n  ^\n"
            + repo.resolve("MyPanel.java") + ":4: error: method does not override or implement a method from a supertype\n  public void render() {}\n  ^\n");
        VersionMap.Fix.run(overrideMap, overrideLog, repo, false, null);
        require(Files.readString(repo.resolve("MyScreen.java")).equals(screen), "no supertypes: no guess between two renames");
        VersionMap.SUPERS.put("MyScreen", Set.of("Screen", "AbstractContainerEventHandler"));
        VersionMap.SUPERS.put("MyPanel", Set.of("ModPanel", "Panel"));
        VersionMap.Fix.run(overrideMap, overrideLog, repo, false, null);
        require(Files.readString(repo.resolve("MyScreen.java")).contains("void extractRenderState()"), "Screen override renamed as Screen's");
        require(Files.readString(repo.resolve("MyPanel.java")).contains("void draw()"), "override through the mod's own parent renamed as Panel's");
        VersionMap.SUPERS.clear();

        // An on-demand import of a package the game split: each moved class the file names gets its own import, a renamed
        // one is renamed in the body, the wildcard goes only when javac says the package is gone, and a class of the
        // mod's own package with the same name is never shadowed.
        Path split = Files.createDirectories(scratch.resolve("split"));
        Path splitMap = scratch.resolve("split.json");
        Files.writeString(splitMap, """
            { "from": "1.21.1", "to": "26.2", "names": "mojmap",
              "classes": [
                { "from": "net.minecraft.advancements.critereon.InventoryChangeTrigger", "to": "net.minecraft.advancements.triggers.InventoryChangeTrigger" },
                { "from": "net.minecraft.advancements.critereon.MinMaxBounds", "to": "net.minecraft.advancements.predicates.MinMaxBounds" },
                { "from": "net.minecraft.advancements.critereon.KilledByCrossbowTrigger", "to": "net.minecraft.advancements.triggers.KilledByArrowTrigger" },
                { "from": "net.minecraft.advancements.critereon.ItemPredicate", "to": "net.minecraft.advancements.predicates.ItemPredicate" },
                { "from": "net.minecraft.advancements.critereon.EntityPredicate", "to": "net.minecraft.advancements.predicates.EntityPredicate" }
              ]
            }
            """);
        String gen = "package mod;\nimport net.minecraft.advancements.critereon.*;\nimport java.util.List;\n\nclass Gen {\n  Object a = InventoryChangeTrigger.TriggerInstance.hasItems();\n  Object b = KilledByCrossbowTrigger.TriggerInstance.crossbowKilled();\n  Object c = MinMaxBounds.Ints.ANY;\n  Object d = EntityPredicate.ANY;\n}\n";
        Files.writeString(split.resolve("Gen.java"), gen);
        Files.writeString(split.resolve("EntityPredicate.java"), "package mod;\nclass EntityPredicate { static Object ANY; }\n");
        Path splitLog = scratch.resolve("split.log");
        Files.writeString(splitLog, split.resolve("Gen.java") + ":6: error: cannot find symbol\n  Object a = InventoryChangeTrigger.TriggerInstance.hasItems();\n             ^\n");
        VersionMap.Fix.run(splitMap, splitLog, split, false, null);
        String kept = Files.readString(split.resolve("Gen.java"));
        require(kept.contains("import net.minecraft.advancements.critereon.*;"), "wildcard kept while the package still exists");
        require(kept.contains("import net.minecraft.advancements.triggers.InventoryChangeTrigger;") && kept.contains("import net.minecraft.advancements.predicates.MinMaxBounds;"), "each moved class used gets its import");
        require(kept.contains("import net.minecraft.advancements.triggers.KilledByArrowTrigger;") && kept.contains("KilledByArrowTrigger.TriggerInstance.crossbowKilled()"), "renamed class imported and renamed in the body");
        require(!kept.contains("ItemPredicate;"), "an unused moved class is not imported");
        require(!kept.contains("predicates.EntityPredicate"), "the mod's own EntityPredicate is not shadowed");
        Files.writeString(split.resolve("Gen.java"), gen);
        Files.writeString(splitLog, split.resolve("Gen.java") + ":2: error: package net.minecraft.advancements.critereon does not exist\nimport net.minecraft.advancements.critereon.*;\n^\n");
        VersionMap.Fix.run(splitMap, splitLog, split, false, null);
        String gone = Files.readString(split.resolve("Gen.java"));
        require(!gone.contains("critereon") && gone.contains("import net.minecraft.advancements.triggers.InventoryChangeTrigger;"), "wildcard of a gone package replaced");
        require(gone.contains("import java.util.List;"), "other imports untouched");

        // A nested class written through its outer class, renamed in place by the game: renamed where javac reports it,
        // and only when javac looked it up in that outer class.
        Path nested = Files.createDirectories(scratch.resolve("nested"));
        Path nestedMap = scratch.resolve("nested.json");
        Files.writeString(nestedMap, """
            { "from": "1.21.1", "to": "26.3", "names": "mojmap",
              "classes": [
                { "from": "net.neoforged.neoforge.capabilities.Capabilities$ItemHandler", "to": "net.neoforged.neoforge.capabilities.Capabilities$Item" }
              ]
            }
            """);
        String caps = "import net.neoforged.neoforge.capabilities.Capabilities;\nclass Caps {\n  Object a = Capabilities.ItemHandler.BLOCK;\n  Object b = Other.ItemHandler.BLOCK;\n}\n";
        Files.writeString(nested.resolve("Caps.java"), caps);
        Path nestedLog = scratch.resolve("nested.log");
        Files.writeString(nestedLog, nested.resolve("Caps.java") + ":3: error: cannot find symbol\n  Object a = Capabilities.ItemHandler.BLOCK;\n                         ^\n  symbol:   variable ItemHandler\n  location: class Capabilities\n"
            + nested.resolve("Caps.java") + ":4: error: cannot find symbol\n  Object b = Other.ItemHandler.BLOCK;\n                  ^\n  symbol:   variable ItemHandler\n  location: class Other\n");
        VersionMap.Fix.run(nestedMap, nestedLog, nested, false, null);
        String renamedCaps = Files.readString(nested.resolve("Caps.java"));
        require(renamedCaps.contains("Capabilities.Item.BLOCK"), "nested class renamed through its outer class: " + renamedCaps);
        require(renamedCaps.contains("Other.ItemHandler.BLOCK"), "another outer class's ItemHandler untouched");

        // A method that only lost parameters: the arguments for them go (even over several lines) when the kept types
        // match one way only; (int,int) -> (int) could drop either, so it is left alone.
        Path drops = Files.createDirectories(scratch.resolve("drops"));
        Path dropMap = scratch.resolve("drops.json");
        Files.writeString(dropMap, """
            { "from": "26.1.2", "to": "26.2", "names": "mojmap",
              "signatures": [
                { "owner": "net.minecraft.client.renderer.LevelRenderer", "from": "setSectionDirty", "desc": "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V", "to": "markDirty", "newDesc": "(Lnet/minecraft/core/BlockPos;I)V" },
                { "owner": "com.mojang.blaze3d.platform.Window", "from": "resize", "desc": "(II)V", "to": "resize", "newDesc": "(I)V" }
              ]
            }
            """);
        String calls = "class Calls {\n  void f() {\n    renderer.setSectionDirty(\n      pos,\n      level.getBlockState(pos),\n      3);\n    window.resize(w, h);\n  }\n}\n";
        Files.writeString(drops.resolve("Calls.java"), calls);
        Path dropLog = scratch.resolve("drops.log");
        Files.writeString(dropLog, drops.resolve("Calls.java") + ":3: error: method setSectionDirty in class LevelRenderer cannot be applied to given types;\n    renderer.setSectionDirty(\n            ^\n"
            + drops.resolve("Calls.java") + ":7: error: method resize in class Window cannot be applied to given types;\n    window.resize(w, h);\n          ^\n");
        VersionMap.Fix.run(dropMap, dropLog, drops, false, null);
        String dropped = Files.readString(drops.resolve("Calls.java"));
        require(dropped.contains("renderer.markDirty(pos, 3);"), "dropped parameter's argument removed and the call renamed: " + dropped);
        require(dropped.contains("window.resize(w, h);"), "an ambiguous drop is left alone");
        // A caret column past the end of the line as it is now, and a line the file no longer has, never stop the repair
        // (Farmer's Delight 1.21.1 -> 1.21.6 crashed the whole port on it).
        Files.writeString(drops.resolve("Calls.java"), calls);
        Files.writeString(dropLog, drops.resolve("Calls.java") + ":7: error: method resize in class Window cannot be applied to given types;\n    window.resize(w, h);\n" + " ".repeat(120) + "^\n"
            + drops.resolve("Calls.java") + ":3: error: method setSectionDirty in class LevelRenderer cannot be applied to given types;\n    renderer.setSectionDirty(\n" + " ".repeat(200) + "^\n"
            + drops.resolve("Calls.java") + ":400: error: method setSectionDirty in class LevelRenderer cannot be applied to given types;\n    renderer.setSectionDirty(\n            ^\n");
        VersionMap.Fix.run(dropMap, dropLog, drops, false, null);
        require(Files.readString(drops.resolve("Calls.java")).contains("renderer.markDirty(pos, 3);"), "a caret past the line's end still finds the call");

        System.out.println("PASS: mapping-backed preflight and guarded lookalikes");
    }
    private static void require(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
