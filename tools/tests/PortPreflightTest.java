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

        lintChecks(scratch);

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

        // Calls the game rewrote itself (GameDiff's call-site rewrites): instance -> instance on another owner, static ->
        // static on another class (imported), instance -> static with the receiver dropped or passed in, and a rewrite
        // whose new parameter no argument can fill left alone.
        Path rw = Files.createDirectories(scratch.resolve("rewrites"));
        Path rwMap = scratch.resolve("rewrites.json");
        Files.writeString(rwMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "rewrites": [
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.core.Registry", "from": "getHolderOrThrow", "desc": "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;", "static": false, "toOwner": "net.minecraft.core.HolderGetter", "to": "getOrThrow", "newDesc": "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;", "newStatic": false, "votes": 22, "of": 22 },
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.client.model.HumanoidModel", "from": "rotlerpRad", "desc": "(FFF)F", "static": false, "toOwner": "net.minecraft.util.Mth", "to": "rotLerpRad", "newDesc": "(FFF)F", "newStatic": true, "votes": 11, "of": 11 },
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.server.MinecraftServer", "from": "getProfiler", "desc": "()Lnet/minecraft/util/profiling/ProfilerFiller;", "static": false, "toOwner": "net.minecraft.util.profiling.Profiler", "to": "get", "newDesc": "()Lnet/minecraft/util/profiling/ProfilerFiller;", "newStatic": true, "votes": 3, "of": 3 },
                { "step": "1.20.1 -> 1.20.2", "owner": "net.minecraft.network.FriendlyByteBuf", "from": "getVarIntSize", "desc": "(I)I", "static": true, "toOwner": "net.minecraft.network.VarInt", "to": "getByteSize", "newDesc": "(I)I", "newStatic": true, "votes": 4, "of": 4 },
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.client.gui.GuiGraphics", "from": "blitSprite", "desc": "(Lnet/minecraft/resources/ResourceLocation;IIII)V", "static": false, "toOwner": "net.minecraft.client.gui.GuiGraphics", "to": "blitSprite", "newDesc": "(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;IIII)V", "newStatic": false, "votes": 98, "of": 101 }
              ]
            }
            """);
        String uses = "package mod;\nimport net.minecraft.core.Registry;\n\nclass Uses {\n  void f() {\n    var h = BuiltInRegistries.BLOCK.getHolderOrThrow(key);\n    float r = this.model.rotlerpRad(a, b, c);\n    server.getProfiler().push(\"x\");\n    int n = FriendlyByteBuf.getVarIntSize(size(list.get(0)));\n    graphics.blitSprite(SPRITE, x, y, 9, 9);\n  }\n}\n";
        Files.writeString(rw.resolve("Uses.java"), uses);
        Path rwLog = scratch.resolve("rewrites.log");
        String usesFile = rw.resolve("Uses.java").toString();
        Files.writeString(rwLog,
            usesFile + ":6: error: cannot find symbol\n    var h = BuiltInRegistries.BLOCK.getHolderOrThrow(key);\n                                   ^\n  symbol:   method getHolderOrThrow(ResourceKey<Block>)\n  location: variable BLOCK of type Registry<Block>\n"
            + usesFile + ":7: error: cannot find symbol\n    float r = this.model.rotlerpRad(a, b, c);\n                        ^\n  symbol:   method rotlerpRad(float,float,float)\n  location: variable model of type HumanoidModel\n"
            + usesFile + ":8: error: cannot find symbol\n    server.getProfiler().push(\"x\");\n          ^\n  symbol:   method getProfiler()\n  location: variable server of type MinecraftServer\n"
            + usesFile + ":9: error: cannot find symbol\n    int n = FriendlyByteBuf.getVarIntSize(size(list.get(0)));\n                           ^\n  symbol:   method getVarIntSize(int)\n  location: class FriendlyByteBuf\n"
            + usesFile + ":10: error: cannot find symbol\n    graphics.blitSprite(SPRITE, x, y, 9, 9);\n            ^\n  symbol:   method blitSprite(ResourceLocation,int,int,int,int)\n  location: variable graphics of type GuiGraphics\n");
        VersionMap.Fix.run(rwMap, rwLog, rw, false, null);
        String rewritten = Files.readString(rw.resolve("Uses.java"));
        require(rewritten.contains("BuiltInRegistries.BLOCK.getOrThrow(key)"), "instance rewrite keeps the receiver: " + rewritten);
        require(rewritten.contains("float r = Mth.rotLerpRad(a, b, c);") && rewritten.contains("import net.minecraft.util.Mth;"), "instance -> static helper, imported");
        require(rewritten.contains("Profiler.get().push(\"x\");") && rewritten.contains("import net.minecraft.util.profiling.Profiler;"), "a receiver the game no longer needs is dropped");
        require(rewritten.contains("int n = VarInt.getByteSize(size(list.get(0)));"), "static -> static on another class, nested call arguments kept");
        require(rewritten.contains("graphics.blitSprite(SPRITE, x, y, 9, 9);"), "a new parameter nothing fills is left to the compiler");
        // A link from the game's map carries no static-ness: a call on the old class's name is a static call, any other an
        // instance call (a yarn route's PacketByteBuf.getVarIntLength -> VarInts.getSizeInBytes).
        Path ys = Files.createDirectories(scratch.resolve("unknown-static"));
        Path ysMap = scratch.resolve("unknown-static.json");
        Files.writeString(ysMap, """
            { "from": "1.20.1", "to": "1.20.2", "names": "yarn",
              "rewrites": [
                { "owner": "net.minecraft.network.PacketByteBuf", "from": "getVarIntLength", "desc": "(I)I", "toOwner": "net.minecraft.network.encoding.VarInts", "to": "getSizeInBytes", "newDesc": "(I)I" },
                { "owner": "net.minecraft.server.world.ServerChunkLoadingManager", "from": "isWithinDistance", "desc": "(IIIII)Z", "toOwner": "net.minecraft.server.world.ServerChunkLoadingManager", "to": "isWithinViewDistance", "newDesc": "(IIIII)Z" }
              ]
            }
            """);
        String palette = "class Palette {\n  int size() {\n    return PacketByteBuf.getVarIntLength(this.size) + n;\n  }\n  boolean near() {\n    return this.manager.isWithinDistance(a, b, c, d, e);\n  }\n}\n";
        Files.writeString(ys.resolve("Palette.java"), palette);
        Path ysLog = scratch.resolve("unknown-static.log");
        Files.writeString(ysLog, ys.resolve("Palette.java") + ":3: error: cannot find symbol\n    return PacketByteBuf.getVarIntLength(this.size) + n;\n                        ^\n  symbol:   method getVarIntLength(int)\n  location: class PacketByteBuf\n"
            + ys.resolve("Palette.java") + ":6: error: cannot find symbol\n    return this.manager.isWithinDistance(a, b, c, d, e);\n                       ^\n  symbol:   method isWithinDistance(int,int,int,int,int)\n  location: variable manager of type ServerChunkLoadingManager\n");
        VersionMap.Fix.run(ysMap, ysLog, ys, false, null);
        String paletted = Files.readString(ys.resolve("Palette.java"));
        require(paletted.contains("return VarInts.getSizeInBytes(this.size) + n;") && paletted.contains("import net.minecraft.network.encoding.VarInts;"), "a call on the class name is static: " + paletted);
        require(paletted.contains("this.manager.isWithinViewDistance(a, b, c, d, e)"), "a call on a value stays an instance call");

        // Arguments javac says the only candidate no longer takes go, for a constructor too, without a map: trailing ones, or
        // ones matched one way only; an inner choice between equal types is left alone.
        Path jr = Files.createDirectories(scratch.resolve("javac-required"));
        String targets = "class Targets {\n  void f() {\n    var t = new TextureTarget(w, h,\n        true, Minecraft.ON_OSX);\n    var u = new Other(\"a\", 1, 2, \"b\");\n    tip(this.getDescriptionId(stack));\n    tip(this.getDescriptionId(stack.copy()));\n  }\n}\n";
        Files.writeString(jr.resolve("Targets.java"), targets);
        Path jrLog = scratch.resolve("javac-required.log");
        Files.writeString(jrLog, jr.resolve("Targets.java") + ":3: error: constructor TextureTarget in class TextureTarget cannot be applied to given types;\n    var t = new TextureTarget(w, h,\n            ^\n  required: int,int,boolean\n  found:    int,int,boolean,boolean\n  reason: actual and formal argument lists differ in length\n"
            + jr.resolve("Targets.java") + ":5: error: constructor Other in class Other cannot be applied to given types;\n    var u = new Other(\"a\", 1, 2, \"b\");\n            ^\n  required: String,int,String\n  found:    String,int,int,String\n  reason: actual and formal argument lists differ in length\n"
            + jr.resolve("Targets.java") + ":6: error: method getDescriptionId in class Item cannot be applied to given types;\n    tip(this.getDescriptionId(stack));\n            ^\n  required: no arguments\n  found:    ItemStack\n  reason: actual and formal argument lists differ in length\n"
            + jr.resolve("Targets.java") + ":7: error: method getDescriptionId in class Item cannot be applied to given types;\n    tip(this.getDescriptionId(stack.copy()));\n            ^\n  required: no arguments\n  found:    ItemStack\n  reason: actual and formal argument lists differ in length\n");
        VersionMap.Fix.run(map, jrLog, jr, false, null);
        String targeted = Files.readString(jr.resolve("Targets.java"));
        require(targeted.contains("var t = new TextureTarget(w, h,\n        true);\n"), "the argument javac no longer takes goes, its lines kept: " + targeted);
        require(targeted.contains("tip(this.getDescriptionId());"), "a method that takes nothing now loses a plain argument");
        require(targeted.contains("tip(this.getDescriptionId(stack.copy()));"), "a computed argument is not thrown away");

        // A registry the game's builders now ask for is passed where every candidate javac names takes it, in a static
        // helper with nothing else to give; a data-driven registry is the world's, and is left alone.
        Path hg = Files.createDirectories(scratch.resolve("holder-getter"));
        Files.writeString(hg.resolve("Crafting.java"), "class Crafting {\n  static void register(RecipeOutput output) {\n    ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS,\n        Items.LEAD).pattern(\"x\");\n    ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, Items.SCAFFOLDING, 6);\n    Enchants.of(RecipeCategory.TOOLS);\n  }\n}\n");
        Path hgLog = scratch.resolve("holder-getter.log");
        String crafting = hg.resolve("Crafting.java").toString();
        Files.writeString(hgLog, crafting + ":3: error: no suitable method found for shaped(RecipeCategory,Item)\n    ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS,\n                       ^\n"
            + "    method ShapedRecipeBuilder.shaped(HolderGetter<Item>,RecipeCategory,ItemLike) is not applicable\n      (actual and formal argument lists differ in length)\n"
            + "    method ShapedRecipeBuilder.shaped(HolderGetter<Item>,RecipeCategory,ItemLike,int) is not applicable\n      (actual and formal argument lists differ in length)\n"
            + crafting + ":5: error: no suitable method found for shaped(RecipeCategory,Item,int)\n    ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, Items.SCAFFOLDING, 6);\n                       ^\n"
            + "    method ShapedRecipeBuilder.shaped(HolderGetter<Item>,RecipeCategory,ItemLike) is not applicable\n      (argument mismatch; RecipeCategory cannot be converted to HolderGetter<Item>)\n"
            + crafting + ":6: error: method of in class Enchants cannot be applied to given types;\n    Enchants.of(RecipeCategory.TOOLS);\n            ^\n  required: HolderGetter<Enchantment>,RecipeCategory\n  found:    RecipeCategory\n  reason: actual and formal argument lists differ in length\n");
        VersionMap.Fix.run(map, hgLog, hg, false, null);
        String crafted = Files.readString(hg.resolve("Crafting.java"));
        require(crafted.contains("shaped(net.minecraft.core.registries.BuiltInRegistries.ITEM, RecipeCategory.TOOLS,\n        Items.LEAD)"), "the item registry is passed, the call's lines kept: " + crafted);
        require(crafted.contains("shaped(net.minecraft.core.registries.BuiltInRegistries.ITEM, RecipeCategory.DECORATIONS, Items.SCAFFOLDING, 6)"), "and where javac names only candidates of the same length");
        require(crafted.contains("    Enchants.of(RecipeCategory.TOOLS);"), "a data-driven registry is not guessed");

        // A value whose type the game changed is converted where javac's caret is: an initializer, an argument, a call's
        // argument found by its place when javac names candidates, and a getter put before a block; an unknown type stays.
        Path cv = Files.createDirectories(scratch.resolve("convert"));
        String[] lines = {
            "class Ids {",
            "  void f(ResourceKey<Recipe<?>> id) {",
            "    ResourceLocation recipeId = id;",
            "    manager.byKey(entry.getKey()).ifPresent(r -> {});",
            "    save(output, ResourceLocation.fromNamespaceAndPath(a, b).withPrefix(\"cooking/\"));",
            "    BlockPredicate.Builder.block().of(ModBlocks.CROP.get()).build();",
            "    Widget w = id;",
            "    builder.build(id.location().toString());",
            "  }",
            "}",
        };
        Files.writeString(cv.resolve("Ids.java"), String.join("\n", lines) + "\n");
        String ids = cv.resolve("Ids.java").toString();
        java.util.function.BiFunction<Integer, String, String> at = (line, token) -> ids + ":" + line + ": error: ";
        java.util.function.BiFunction<Integer, String, String> caret = (line, token) -> "\n" + lines[line - 1] + "\n" + " ".repeat(lines[line - 1].indexOf(token)) + "^\n";
        Path cvLog = scratch.resolve("convert.log");
        Files.writeString(cvLog,
            at.apply(3, "") + "incompatible types: ResourceKey<Recipe<?>> cannot be converted to ResourceLocation" + caret.apply(3, "id;")
            + at.apply(4, "") + "incompatible types: ResourceLocation cannot be converted to ResourceKey<Recipe<?>>" + caret.apply(4, "())")
            + at.apply(5, "") + "no suitable method found for save(RecipeOutput,ResourceLocation)" + caret.apply(5, "save")
            + "    method RecipeBuilder.save(RecipeOutput,String) is not applicable\n      (argument mismatch; ResourceLocation cannot be converted to String)\n"
            + "    method CookingPotRecipeBuilder.save(RecipeOutput,ResourceKey) is not applicable\n      (argument mismatch; ResourceLocation cannot be converted to ResourceKey)\n"
            + at.apply(6, "") + "incompatible types: CropBlock cannot be converted to HolderGetter<Block>" + caret.apply(6, "()).")
            + at.apply(7, "") + "incompatible types: ResourceKey<Recipe<?>> cannot be converted to Widget" + caret.apply(7, "id;")
            + at.apply(8, "") + "incompatible types: String cannot be converted to ResourceKey<EntityType<?>>" + caret.apply(8, "id.location().toString"));
        VersionMap.Fix.run(map, cvLog, cv, false, null);
        String converted = Files.readString(cv.resolve("Ids.java"));
        require(converted.contains("ResourceLocation recipeId = id.location();"), "a key gives its location: " + converted);
        require(converted.contains("byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, entry.getKey()))"), "an id becomes its recipe key");
        require(converted.contains("save(output, net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, ResourceLocation.fromNamespaceAndPath(a, b).withPrefix(\"cooking/\")));"), "the argument of the one candidate that takes a key");
        require(converted.contains(".of(net.minecraft.core.registries.BuiltInRegistries.BLOCK, ModBlocks.CROP.get())"), "the block registry goes before the block");
        require(converted.contains("    Widget w = id;"), "a type with no known conversion is left alone");
        require(converted.contains("builder.build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id.location()));"), "an id written as text becomes the key of its location: " + converted);
        require(targeted.contains("var u = new Other(\"a\", 1, 2, \"b\");"), "which of two equal arguments goes is not guessed");

        // A member the game replaced with a chain on the same receiver: a field read becomes the chain, a call keeps its
        // arguments on the last step, an assigned field is left for the compiler to name.
        Path ch = Files.createDirectories(scratch.resolve("chains"));
        Path chMap = scratch.resolve("chains.json");
        Files.writeString(chMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "chains": [
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.client.player.Input", "newOwner": "net.minecraft.client.player.ClientInput", "from": "up", "desc": "", "field": true, "steps": "f:keyPresses,m:forward", "votes": 2, "of": 3 },
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.core.Registry", "newOwner": "net.minecraft.core.Registry", "from": "getOrCreateTag", "desc": "(Lnet/minecraft/tags/TagKey;)Lnet/minecraft/core/HolderSet$Named;", "field": false, "steps": "m:getOrThrow", "votes": 2, "of": 2 }
              ]
            }
            """);
        String[] keys = {
            "class Keys {",
            "  void f(ClientInput input, Registry<Item> items) {",
            "    if (input.up && go) run();",
            "    input.up = true;",
            "    var tag = items.getOrCreateTag(ModTags.FOOD);",
            "  }",
            "}",
        };
        Files.writeString(ch.resolve("Keys.java"), String.join("\n", keys) + "\n");
        String keysFile = ch.resolve("Keys.java").toString();
        java.util.function.BiFunction<Integer, String, String> keyErr = (line, token) -> keysFile + ":" + line + ": error: cannot find symbol\n"
            + keys[line - 1] + "\n" + " ".repeat(keys[line - 1].indexOf(token)) + "^\n";
        Files.writeString(scratch.resolve("chains.log"),
            keyErr.apply(3, ".up") + "  symbol:   variable up\n  location: variable input of type ClientInput\n"
            + keyErr.apply(4, ".up") + "  symbol:   variable up\n  location: variable input of type ClientInput\n"
            + keyErr.apply(5, ".getOrCreateTag") + "  symbol:   method getOrCreateTag(TagKey<Item>)\n  location: variable items of type Registry<Item>\n");
        VersionMap.Fix.run(chMap, scratch.resolve("chains.log"), ch, false, null);
        String chained = Files.readString(ch.resolve("Keys.java"));
        require(chained.contains("if (input.keyPresses.forward() && go) run();"), "a field read becomes the game's chain: " + chained);
        require(chained.contains("    input.up = true;"), "an assigned field is not turned into a call");
        require(chained.contains("items.getOrThrow(ModTags.FOOD)"), "a call keeps its arguments on the last step");

        // An argument the game added and always passes the same value for: a call one argument short gets that value at
        // the game's place; a call javac did not name stays as written.
        Path ad = Files.createDirectories(scratch.resolve("arg-defaults"));
        Path adMap = scratch.resolve("arg-defaults.json");
        Files.writeString(adMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "argDefaults": [
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.client.gui.GuiGraphics", "from": "blitSprite", "desc": "(Lnet/minecraft/resources/ResourceLocation;IIII)V", "toOwner": "net.minecraft.client.gui.GuiGraphics", "to": "blitSprite", "newDesc": "(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;IIII)V", "index": 0, "value": "net.minecraft.client.renderer.RenderType::guiTextured", "votes": 61, "of": 62 }
              ]
            }
            """);
        String[] gui = {
            "class Hud {",
            "  void draw(GuiGraphics graphics) {",
            "    graphics.blitSprite(SPRITE, x, y, 16, 16);",
            "    graphics.blitSprite(OTHER, x, y, 8, 8);",
            "    graphics.blitSprite(SHEET, 64, 64, 0, 0, x, y, 16, 16);",
            "  }",
            "}",
        };
        Files.writeString(ad.resolve("Hud.java"), String.join("\n", gui) + "\n");
        Files.writeString(scratch.resolve("arg-defaults.log"), ad.resolve("Hud.java") + ":3: error: method blitSprite in class GuiGraphics cannot be applied to given types;\n"
            + gui[2] + "\n            ^\n  required: Function<ResourceLocation,RenderType>,ResourceLocation,int,int,int,int\n  found:    ResourceLocation,int,int,int,int\n  reason: actual and formal argument lists differ in length\n"
            + ad.resolve("Hud.java") + ":5: error: no suitable method found for blitSprite(ResourceLocation,int,int,int,int,int,int,int,int)\n" + gui[4] + "\n            ^\n"
            + "    method GuiGraphics.blitSprite(Function<ResourceLocation,RenderType>,ResourceLocation,int,int,int,int) is not applicable\n      (actual and formal argument lists differ in length)\n"
            + "    method GuiGraphics.blitSprite(Function<ResourceLocation,RenderType>,ResourceLocation,int,int,int,int,int,int,int,int) is not applicable\n      (actual and formal argument lists differ in length)\n");
        VersionMap.Fix.run(adMap, scratch.resolve("arg-defaults.log"), ad, false, null);
        String hud = Files.readString(ad.resolve("Hud.java"));
        require(hud.contains("graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, SPRITE, x, y, 16, 16);"), "the game's own value goes in first: " + hud);
        require(hud.contains("graphics.blitSprite(OTHER, x, y, 8, 8);"), "a call javac did not name is left alone");
        require(hud.contains("graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, SHEET, 64, 64, 0, 0, x, y, 16, 16);"), "an overload the game never calls gains the value every called one did: " + hud);

        // Trailing arguments the game added that the enclosing method has (1.20.2: renderBackground(context) inside
        // render(context, mouseX, mouseY, delta)): they are passed in the method's order; where the types do not line up
        // exactly, nothing is guessed.
        Path ep = Files.createDirectories(scratch.resolve("enclosing"));
        Files.writeString(scratch.resolve("enclosing.json"), "{ \"from\": \"1.20.1\", \"to\": \"1.20.2\", \"names\": \"yarn\" }\n");
        String[] configLines = {
            "class ConfigScreen extends Screen {",
            "  public void render(DrawContext context, int mouseX, int mouseY, float delta) {",
            "    renderBackground(context);",
            "  }",
            "  public void tick(DrawContext context, int ticks) {",
            "    renderBackground(context);",
            "  }",
            "}",
        };
        Files.writeString(ep.resolve("ConfigScreen.java"), String.join("\n", configLines) + "\n");
        String needs = "\n    ^\n  required: DrawContext,int,int,float\n  found:    DrawContext\n  reason: actual and formal argument lists differ in length\n";
        Files.writeString(scratch.resolve("enclosing.log"),
            ep.resolve("ConfigScreen.java") + ":3: error: method renderBackground in class Screen cannot be applied to given types;\n" + configLines[2] + needs
            + ep.resolve("ConfigScreen.java") + ":6: error: method renderBackground in class Screen cannot be applied to given types;\n" + configLines[5] + needs);
        VersionMap.Fix.run(scratch.resolve("enclosing.json"), scratch.resolve("enclosing.log"), ep, false, null);
        String config = Files.readString(ep.resolve("ConfigScreen.java"));
        require(config.contains("    renderBackground(context, mouseX, mouseY, delta);\n  }\n  public void tick"), "the enclosing method's parameters are passed: " + config);
        require(config.contains("int ticks) {\n    renderBackground(context);"), "types that do not line up are left: " + config);

        // A method moved to a class one holder keeps beside the old one (26.2: LevelRenderer.blockChanged -> LevelExtractor,
        // Minecraft.levelRenderer and Minecraft.levelExtractor): the call goes through the other field; another call stays.
        Path sb = Files.createDirectories(scratch.resolve("siblings"));
        Path sbMap = scratch.resolve("siblings.json");
        Files.writeString(sbMap, """
            { "from": "26.1.2", "to": "26.2", "names": "mojmap",
              "siblings": [
                { "step": "26.1.2 -> 26.2", "owner": "net.minecraft.client.renderer.LevelRenderer", "from": "blockChanged", "desc": "(I)V", "holder": "net.minecraft.client.Minecraft", "fromField": "levelRenderer", "toField": "levelExtractor", "newOwner": "net.minecraft.client.renderer.extract.LevelExtractor", "newDesc": "(I)V" }
              ]
            }
            """);
        String[] sync = {
            "class Sync {",
            "  void f() {",
            "    Minecraft.getInstance().levelRenderer.blockChanged(null, pos, null, null, 0);",
            "    Minecraft.getInstance().levelRenderer.needsUpdate();",
            "  }",
            "}",
        };
        Files.writeString(sb.resolve("Sync.java"), String.join("\n", sync) + "\n");
        Files.writeString(scratch.resolve("siblings.log"), sb.resolve("Sync.java") + ":3: error: cannot find symbol\n" + sync[2]
            + "\n                                               ^\n  symbol:   method blockChanged(<null>,BlockPos,<null>,<null>,int)\n  location: variable levelRenderer of type LevelRenderer\n");
        VersionMap.Fix.run(sbMap, scratch.resolve("siblings.log"), sb, false, null);
        String synced = Files.readString(sb.resolve("Sync.java"));
        require(synced.contains("Minecraft.getInstance().levelExtractor.blockChanged(null, pos, null, null, 0);"), "the call goes through the holder's other field: " + synced);
        require(synced.contains("Minecraft.getInstance().levelRenderer.needsUpdate();"), "a call javac did not name stays");

        // A nested class the game moved out of its outer class (1.21.2: ArmorItem.Type -> equipment.ArmorType): every use
        // written through the outer class becomes the new class, imported; the outer class itself stays.
        Path mo = Files.createDirectories(scratch.resolve("moved-out"));
        Path moMap = scratch.resolve("moved-out.json");
        Files.writeString(moMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "classes": [
                { "from": "net.minecraft.world.item.ArmorItem$Type", "to": "net.minecraft.world.item.equipment.ArmorType" }
              ]
            }
            """);
        String[] armor = {
            "package mod;",
            "import net.minecraft.world.item.ArmorItem;",
            "class Boots extends ArmorItem {",
            "  Boots(ArmorItem.Type type) { super(type); }",
            "  static final java.util.Map<ArmorItem.Type, Integer> M = new java.util.EnumMap<>(ArmorItem . Type.class);",
            "}",
        };
        Files.writeString(mo.resolve("Boots.java"), String.join("\n", armor) + "\n");
        Files.writeString(scratch.resolve("moved-out.log"), mo.resolve("Boots.java") + ":4: error: cannot find symbol\n" + armor[3] + "\n                ^\n  symbol:   class Type\n  location: class ArmorItem\n");
        VersionMap.Fix.run(moMap, scratch.resolve("moved-out.log"), mo, false, null);
        String boots = Files.readString(mo.resolve("Boots.java"));
        require(boots.contains("Boots(ArmorType type)") && boots.contains("Map<ArmorType, Integer> M = new java.util.EnumMap<>(ArmorType.class)"), "every Outer.Nested becomes the moved class: " + boots);
        require(boots.contains("import net.minecraft.world.item.equipment.ArmorType;") && boots.contains("extends ArmorItem"), "the moved class is imported and the outer class stays: " + boots);

        // A name the game reused (1.21.2: Registry.get(id) -> getValue, getHolder(id) -> get): the old call still compiles
        // and javac objects where its result goes, finding the Optional the new get returns. That call takes the old
        // method's new name; a line javac did not name, and a get with another argument count, stay.
        Path ru = Files.createDirectories(scratch.resolve("reused"));
        Path ruMap = scratch.resolve("reused.json");
        Files.writeString(ruMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "methods": [
                { "owner": "net.minecraft.core.Registry", "from": "get", "desc": "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;", "to": "getValue", "newDesc": "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;" },
                { "owner": "net.minecraft.core.Registry", "from": "getHolder", "desc": "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", "to": "get", "newDesc": "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;" }
              ]
            }
            """);
        String[] cache = {
            "class Cache {",
            "  static Supplier<Item> of(Registry<Item> registry, ResourceLocation id) { return cache(() -> registry.get(id)); }",
            "  static Optional<Reference<Item>> holder(Registry<Item> registry, ResourceLocation id) { return registry.get(id); }",
            "}",
        };
        Files.writeString(ru.resolve("Cache.java"), String.join("\n", cache) + "\n");
        Files.writeString(scratch.resolve("reused.log"), ru.resolve("Cache.java") + ":2: error: incompatible types: inference variable T#1 has incompatible bounds\n"
            + cache[1] + "\n                                                                        ^\n    equality constraints: Item\n    lower bounds: Optional<Reference<Item>>\n");
        VersionMap.Fix.run(ruMap, scratch.resolve("reused.log"), ru, false, null);
        String reused = Files.readString(ru.resolve("Cache.java"));
        require(reused.contains("cache(() -> registry.getValue(id))"), "the reused name's old call takes its new name: " + reused);
        require(reused.contains("{ return registry.get(id); }"), "a call javac did not name keeps the reused name");

        // A method the game renamed, implemented without @Override: javac only says the class misses the new name, and the
        // one declaration the map renames to it on that supertype takes it; a same-name method with other arity stays.
        Path ab = Files.createDirectories(scratch.resolve("abstract"));
        Path abMap = scratch.resolve("abstract.json");
        Files.writeString(abMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "methods": [
                { "owner": "net.minecraft.world.level.LevelHeightAccessor", "from": "getMinBuildHeight", "desc": "()I", "to": "getMinY", "newDesc": "()I" }
              ]
            }
            """);
        Files.writeString(ab.resolve("Heights.java"), "public class Heights implements LevelHeightAccessor {\n  public int getHeight() { return 1; }\n\n  public int getMinBuildHeight() {\n    return 0;\n  }\n\n  int getMinBuildHeight(int y) { return y; }\n}\n");
        String heights = ab.resolve("Heights.java").toString();
        Files.writeString(scratch.resolve("abstract.log"), heights + ":1: error: Heights is not abstract and does not override abstract method getMinY() in LevelHeightAccessor\n"
            + "public class Heights implements LevelHeightAccessor {\n       ^\n");
        VersionMap.Fix.run(abMap, scratch.resolve("abstract.log"), ab, false, null);
        String heighted = Files.readString(ab.resolve("Heights.java"));
        require(heighted.contains("  public int getMinY() {"), "the renamed method takes the name the supertype asks for: " + heighted);
        require(heighted.contains("int getMinBuildHeight(int y)"), "a same-name method with other parameters keeps its name");

        // A call the game deleted: a no-argument void call that is a whole statement goes; one with arguments, or used
        // inside an expression, stays for the compiler to name.
        Path del = Files.createDirectories(scratch.resolve("deleted"));
        Path delMap = scratch.resolve("deleted.json");
        Files.writeString(delMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "rewrites": [
                { "step": "1.21.1 -> 1.21.2", "owner": "com.mojang.blaze3d.systems.RenderSystem", "from": "applyModelViewMatrix", "desc": "()V", "static": true, "toOwner": "", "to": "", "newDesc": "", "newStatic": false, "votes": 8, "of": 10 },
                { "step": "1.21.1 -> 1.21.2", "owner": "net.minecraft.data.worldgen.BiomeDefaultFeatures", "from": "addDefaultSeagrass", "desc": "(Lnet/minecraft/world/level/biome/BiomeGenerationSettings$Builder;)V", "static": true, "toOwner": "", "to": "", "newDesc": "", "newStatic": false, "votes": 3, "of": 3 }
              ]
            }
            """);
        String render = "class Render {\n  void f() {\n    RenderSystem.applyModelViewMatrix();\n    BiomeDefaultFeatures.addDefaultSeagrass(builder);\n    run(() -> RenderSystem.applyModelViewMatrix());\n  }\n}\n";
        Files.writeString(del.resolve("Render.java"), render);
        Path delLog = scratch.resolve("deleted.log");
        String renderFile = del.resolve("Render.java").toString();
        Files.writeString(delLog,
            renderFile + ":3: error: cannot find symbol\n    RenderSystem.applyModelViewMatrix();\n                ^\n  symbol:   method applyModelViewMatrix()\n  location: class RenderSystem\n"
            + renderFile + ":4: error: cannot find symbol\n    BiomeDefaultFeatures.addDefaultSeagrass(builder);\n                        ^\n  symbol:   method addDefaultSeagrass(Builder)\n  location: class BiomeDefaultFeatures\n"
            + renderFile + ":5: error: cannot find symbol\n    run(() -> RenderSystem.applyModelViewMatrix());\n                          ^\n  symbol:   method applyModelViewMatrix()\n  location: class RenderSystem\n");
        VersionMap.Fix.run(delMap, delLog, del, false, null);
        String rendered = Files.readString(del.resolve("Render.java"));
        require(rendered.contains("  void f() {\n    \n    BiomeDefaultFeatures.addDefaultSeagrass(builder);"), "the deleted no-argument call goes, line kept: " + rendered);
        require(rendered.contains("run(() -> RenderSystem.applyModelViewMatrix());"), "a deleted call inside an expression is left alone");

        // One overload renamed while another keeps the name: the overload with the call's argument types gives the name.
        // A nested class written through the mod's own subclass is renamed as its declaring class's.
        Path ov = Files.createDirectories(scratch.resolve("overloads"));
        Path ovMap = scratch.resolve("overloads.json");
        Files.writeString(ovMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "classes": [
                { "from": "net.minecraft.world.level.chunk.ChunkAccess$TicksToSave", "to": "net.minecraft.world.level.chunk.ChunkAccess$PackedTicks" }
              ],
              "methods": [
                { "owner": "net.minecraft.core.Direction", "from": "getNearest", "desc": "(DDD)Lnet/minecraft/core/Direction;", "to": "getApproximateNearest", "newDesc": "(DDD)Lnet/minecraft/core/Direction;" },
                { "owner": "net.minecraft.core.Direction", "from": "getNearest", "desc": "(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/core/Direction;", "to": "getApproximateNearest", "newDesc": "(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/core/Direction;" }
              ]
            }
            """);
        String sound = "class Sound {\n  void f() {\n    var a = Direction.getNearest(x, y, z);\n    var b = Direction.getNearest(1, 2, 3);\n    ClonedLevelChunk.TicksToSave t = null;\n  }\n}\n";
        Files.writeString(ov.resolve("Sound.java"), sound);
        Path ovLog = scratch.resolve("overloads.log");
        Files.writeString(ovLog, ov.resolve("Sound.java") + ":3: error: no suitable method found for getNearest(double,double,double)\n    var a = Direction.getNearest(x, y, z);\n                     ^\n"
            + ov.resolve("Sound.java") + ":5: error: cannot find symbol\n    ClonedLevelChunk.TicksToSave t = null;\n                    ^\n  symbol:   class TicksToSave\n  location: class ClonedLevelChunk\n");
        VersionMap.SUPERS.put("ClonedLevelChunk", Set.of("ChunkAccess", "BlockGetter"));
        VersionMap.Fix.run(ovMap, ovLog, ov, false, null);
        VersionMap.SUPERS.clear();
        String sounded = Files.readString(ov.resolve("Sound.java"));
        require(sounded.contains("Direction.getApproximateNearest(x, y, z)") && sounded.contains("Direction.getNearest(1, 2, 3)"), "the renamed overload, by its argument types: " + sounded);
        require(sounded.contains("ClonedLevelChunk.PackedTicks t"), "a nested class through the mod's subclass: " + sounded);

        // An @Override whose method gained a parameter or narrowed one: the declaration and its super call follow; one whose
        // return type changed is left to the compiler.
        Path sig = Files.createDirectories(scratch.resolve("override-signatures"));
        Path sigMap = scratch.resolve("override-signatures.json");
        Files.writeString(sigMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "signatures": [
                { "owner": "net.minecraft.world.entity.Mob", "from": "doHurtTarget", "desc": "(Lnet/minecraft/world/entity/Entity;)Z", "to": "doHurtTarget", "newDesc": "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z" },
                { "owner": "net.minecraft.world.level.block.Block", "from": "wasExploded", "desc": "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;)V", "to": "wasExploded", "newDesc": "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;)V" },
                { "owner": "net.minecraft.world.entity.Entity", "from": "hurt", "desc": "(Lnet/minecraft/world/damagesource/DamageSource;F)Z", "to": "hurt", "newDesc": "(Lnet/minecraft/world/damagesource/DamageSource;F)V" },
                { "owner": "net.minecraft.world.level.chunk.ChunkAccess", "from": "getTicksForSerialization", "desc": "()Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;", "to": "getTicksForSerialization", "newDesc": "(J)Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;" }
              ],
              "classes": [
                { "from": "net.minecraft.world.level.chunk.ChunkAccess$TicksToSave", "to": "net.minecraft.world.level.chunk.ChunkAccess$PackedTicks" }
              ]
            }
            """);
        Files.writeString(sig.resolve("Chunk.java"), "package mod;\n\nclass Chunk extends ChunkAccess {\n  @Override\n  public PackedTicks getTicksForSerialization() {\n    return null;\n  }\n}\n");
        String ghost = "package mod;\n\nclass Ghost extends Monster {\n  @Override\n  public boolean doHurtTarget(Entity target) {\n    return super.doHurtTarget(target) && target.isAlive();\n  }\n"
            + "  @Override\n  public boolean hurt(DamageSource source, float amount) {\n    return false;\n  }\n}\n";
        String stone = "package mod;\n\nclass Stone extends Block {\n  @Override\n  public void wasExploded(Level level, BlockPos pos, Explosion explosion) {\n    super.wasExploded(level, pos, explosion);\n  }\n}\n";
        Files.writeString(sig.resolve("Ghost.java"), ghost);
        Files.writeString(sig.resolve("Stone.java"), stone);
        Path sigLog = scratch.resolve("override-signatures.log");
        String err = ": error: method does not override or implement a method from a supertype\n  @Override\n  ^\n";
        Files.writeString(sigLog, sig.resolve("Ghost.java") + ":4" + err + sig.resolve("Ghost.java") + ":8" + err + sig.resolve("Stone.java") + ":4" + err + sig.resolve("Chunk.java") + ":4" + err);
        VersionMap.SUPERS.put("Ghost", Set.of("Monster", "PathfinderMob", "Mob", "LivingEntity", "Entity"));
        VersionMap.SUPERS.put("Stone", Set.of("Block", "BlockBehaviour"));
        VersionMap.SUPERS.put("Chunk", Set.of("ChunkAccess"));
        VersionMap.Fix.run(sigMap, sigLog, sig, false, null);
        VersionMap.SUPERS.clear();
        String ghosted = Files.readString(sig.resolve("Ghost.java")), stoned = Files.readString(sig.resolve("Stone.java"));
        require(ghosted.contains("public boolean doHurtTarget(ServerLevel serverLevel, Entity target) {") && ghosted.contains("super.doHurtTarget(serverLevel, target)"), "an added parameter, passed on by super: " + ghosted);
        require(ghosted.contains("import net.minecraft.server.level.ServerLevel;"), "the new parameter's type imported");
        require(ghosted.contains("public boolean hurt(DamageSource source, float amount)"), "a changed return type is left alone");
        require(stoned.contains("public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion)") && stoned.contains("super.wasExploded(level, pos, explosion)"), "a narrowed type keeps the mod's name: " + stoned);
        String chunked = Files.readString(sig.resolve("Chunk.java"));
        require(chunked.contains("public PackedTicks getTicksForSerialization(long mmtLong) {"),
            "a return type the map only renamed still counts as the same, and a primitive parameter gets a legal name: " + chunked);
        // An override of a method the game removed (no rename, no new signature) loses the annotation and stays a method.
        Files.writeString(sigMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "methodsRemoved": [
                { "owner": "net.minecraft.world.item.crafting.Recipe", "from": "canCraftInDimensions", "desc": "(II)Z" }
              ]
            }
            """);
        String recipe = "class Mix implements Recipe<CraftingInput> {\n    @Override\n    public boolean canCraftInDimensions(int w, int h) {\n        return w * h >= 2;\n    }\n}\n";
        Files.writeString(sig.resolve("Mix.java"), recipe);
        Files.writeString(sigLog, sig.resolve("Mix.java") + ":2" + err);
        VersionMap.SUPERS.put("Mix", Set.of("Recipe"));
        VersionMap.Fix.run(sigMap, sigLog, sig, false, null);
        VersionMap.SUPERS.clear();
        require(Files.readString(sig.resolve("Mix.java")).equals(recipe.replace("    @Override\n", "\n")), "the removed method's @Override goes, its line kept: " + Files.readString(sig.resolve("Mix.java")));

        // Parameters the game dropped go from the declaration only when the body never uses them.
        Files.writeString(sigMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "signatures": [
                { "owner": "net.minecraft.world.level.block.state.BlockBehaviour", "from": "propagatesSkylightDown", "desc": "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z", "to": "propagatesSkylightDown", "newDesc": "(Lnet/minecraft/world/level/block/state/BlockState;)Z" }
              ]
            }
            """);
        String lamp = "class Lamp extends Block {\n  @Override\n  public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {\n    return !state.getValue(LIT);\n  }\n}\n";
        String glass = "class Glass extends Block {\n  @Override\n  public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {\n    return level.getBlockState(pos.above()).isAir();\n  }\n}\n";
        Files.writeString(sig.resolve("Lamp.java"), lamp);
        Files.writeString(sig.resolve("Glass.java"), glass);
        Files.writeString(sigLog, sig.resolve("Lamp.java") + ":2" + err + sig.resolve("Glass.java") + ":2" + err);
        VersionMap.SUPERS.put("Lamp", Set.of("Block", "BlockBehaviour"));
        VersionMap.SUPERS.put("Glass", Set.of("Block", "BlockBehaviour"));
        VersionMap.Fix.run(sigMap, sigLog, sig, false, null);
        VersionMap.SUPERS.clear();
        require(Files.readString(sig.resolve("Lamp.java")).contains("public boolean propagatesSkylightDown(BlockState state) {"), "unused dropped parameters go");
        require(Files.readString(sig.resolve("Glass.java")).equals(glass), "a body that still uses a dropped parameter is left to the compiler");

        // A class that lost its type parameters: the arguments after it on the reported line go, nested ones included.
        Path tp = Files.createDirectories(scratch.resolve("type-params"));
        String ghostModel = "class GhostRenderer {\n  PlayerModel<AbstractClientPlayer> model;\n  Map<String, PlayerModel<Map<String, Integer>>> byName;\n}\n";
        Files.writeString(tp.resolve("GhostRenderer.java"), ghostModel);
        Path tpLog = scratch.resolve("type-params.log");
        Files.writeString(tpLog, tp.resolve("GhostRenderer.java") + ":2: error: type PlayerModel does not take parameters\n  PlayerModel<AbstractClientPlayer> model;\n             ^\n"
            + tp.resolve("GhostRenderer.java") + ":3: error: type PlayerModel does not take parameters\n  Map<String, PlayerModel<Map<String, Integer>>> byName;\n                         ^\n");
        VersionMap.Fix.run(map, tpLog, tp, false, null);
        String typed = Files.readString(tp.resolve("GhostRenderer.java"));
        require(typed.contains("  PlayerModel model;") && typed.contains("Map<String, PlayerModel> byName;"), "type arguments of a class that lost them: " + typed);
        // A class that gained type parameters: a declared type gets wildcards; an extends clause is left to its transform.
        String renderers = "class Renderers {\n  private <T extends Entity> EntityRenderer<? super T> getRenderer(T entity) {\n    return null;\n  }\n}\nclass Sub extends EntityRenderer<Entity> {\n}\n";
        Files.writeString(tp.resolve("Renderers.java"), renderers);
        Files.writeString(tpLog, tp.resolve("Renderers.java") + ":2: error: wrong number of type arguments; required 2\n  private <T extends Entity> EntityRenderer<? super T> getRenderer(T entity) {\n                                           ^\n"
            + tp.resolve("Renderers.java") + ":6: error: wrong number of type arguments; required 2\nclass Sub extends EntityRenderer<Entity> {\n                                ^\n");
        VersionMap.Fix.run(map, tpLog, tp, false, null);
        String widened = Files.readString(tp.resolve("Renderers.java"));
        require(widened.contains("EntityRenderer<? super T, ?> getRenderer(T entity)") && widened.contains("class Sub extends EntityRenderer<Entity> {"), "a declared type gets a wildcard, an extends clause does not: " + widened);

        // A class the game replaced: the file that imports it switches import and uses; a mod's own class of that name stays.
        Path cr = Files.createDirectories(scratch.resolve("class-rewrites"));
        Path crMap = scratch.resolve("class-rewrites.json");
        Files.writeString(crMap, """
            { "from": "1.21.1", "to": "1.21.2", "names": "mojmap",
              "classRewrites": [
                { "step": "1.21.1 -> 1.21.2", "from": "net.minecraft.world.level.block.state.properties.DirectionProperty", "to": "net.minecraft.world.level.block.state.properties.EnumProperty", "votes": 4, "of": 4 },
                { "step": "1.21.1 -> 1.21.2", "from": "net.minecraft.world.level.storage.loot.LootContext$EntityTarget", "to": "net.minecraft.util.context.ContextKey$Target", "votes": 2, "of": 2 }
              ]
            }
            """);
        String block = "package mod;\nimport net.minecraft.world.level.block.state.properties.DirectionProperty;\n\nclass FacingBlock {\n  static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;\n  DirectionPropertyHelper helper;\n}\n";
        String own = "package mod;\n\nclass Other {\n  DirectionProperty mine;\n}\n";
        Files.writeString(cr.resolve("FacingBlock.java"), block);
        Files.writeString(cr.resolve("Other.java"), own);
        Path crLog = scratch.resolve("class-rewrites.log");
        Files.writeString(crLog, cr.resolve("FacingBlock.java") + ":2: error: cannot find symbol\nimport net.minecraft.world.level.block.state.properties.DirectionProperty;\n                                                          ^\n  symbol:   class DirectionProperty\n  location: package net.minecraft.world.level.block.state.properties\n"
            + cr.resolve("Other.java") + ":4: error: cannot find symbol\n  DirectionProperty mine;\n  ^\n  symbol:   class DirectionProperty\n  location: class Other\n");
        VersionMap.Fix.run(crMap, crLog, cr, false, null);
        String replaced = Files.readString(cr.resolve("FacingBlock.java"));
        require(replaced.contains("import net.minecraft.world.level.block.state.properties.EnumProperty;") && replaced.contains("static final EnumProperty FACING"), "import and uses switched: " + replaced);
        require(replaced.contains("DirectionPropertyHelper helper;"), "a longer name is untouched");
        require(Files.readString(cr.resolve("Other.java")).equals(own), "a file that does not import the game's class is untouched");

        System.out.println("PASS: mapping-backed preflight and guarded lookalikes");
    }

    static void lintChecks(Path scratch) throws Exception {
        Path map = scratch.resolve("lint-map.json");
        Files.writeString(map, """
            { "from": "1.12.2", "to": "1.14.4", "names": "mcp",
              "methods": [
                { "owner": "net.minecraft.block.Block", "from": "getBlockState", "to": "getStateContainer", "desc": "()Lnet/minecraft/block/state/IBlockState;", "newDesc": "()Lnet/minecraft/block/state/BlockStateContainer;" },
                { "owner": "net.minecraft.block.Block", "from": "getFoo", "to": "getFooA", "desc": "()V", "newDesc": "()V" },
                { "owner": "net.minecraft.block.Block", "from": "getFoo", "to": "getFooB", "desc": "()V", "newDesc": "()V" },
                { "owner": "net.minecraft.client.gui.FontRenderer", "from": "isKeyDown", "to": "isKeyDown", "desc": "(I)Z", "newDesc": "(II)Z" },
                { "owner": "net.minecraft.world.storage.WorldInfo", "from": "getWorldTime", "to": "worldTime", "desc": "()J", "newDesc": "()J" }
              ],
              "methodsRemoved": [
                { "owner": "net.minecraft.block.Block", "from": "deadMethod", "desc": "()V", "newDesc": null }
              ],
              "fields": [
                { "owner": "net.minecraft.block.Block", "from": "slipperiness", "to": "friction", "desc": "F", "newDesc": "F" },
                { "owner": "net.minecraft.world.EnumDifficulty", "from": "NORMAL", "to": "OVERWORLD", "desc": "I", "newDesc": "I" }
              ],
              "fieldsRemoved": [
                { "owner": "net.minecraft.block.Block", "from": "deadField", "desc": "I", "newDesc": null }
              ],
              "signatures": [
                { "owner": "net.minecraft.client.gui.FontRenderer", "from": "isKeyDown", "to": "isKeyDown", "desc": "(I)Z", "newDesc": "(II)Z" }
              ],
              "history": [
                { "kind": "method", "owner": "net.minecraft.block.Block", "name": "getBlockState", "desc": "()V", "what": "renamed", "was": "net.minecraft.block.Block.getBlockState", "to": "net.minecraft.block.Block.getStateContainer", "step": "1.12.2 -> 1.14.4" },
                { "kind": "method", "owner": "net.minecraft.block.Block", "name": "getFoo", "desc": "()V", "what": "renamed", "was": "net.minecraft.block.Block.getFoo", "to": "net.minecraft.block.Block.getFooA", "step": "1.12.2 -> 1.14.4" },
                { "kind": "method", "owner": "net.minecraft.block.Block", "name": "deadMethod", "desc": "()V", "what": "removed", "was": "net.minecraft.block.Block.deadMethod", "to": null, "step": "1.12.2 -> 1.14.4" },
                { "kind": "field", "owner": "net.minecraft.block.Block", "name": "slipperiness", "desc": "F", "what": "renamed", "was": "net.minecraft.block.Block.slipperiness", "to": "net.minecraft.block.Block.friction", "step": "1.12.2 -> 1.14.4" }
              ] }
            """);
        VersionMap.Known known = VersionMap.Known.load(map);
        String source = """
            import net.minecraft.block.Block;
            class Demo {
              Object state(Block block) {
                return block.getBlockState();
              }
              Object ambiguous(Block block) {
                return block.getFoo();
              }
              Object gone(Block block) {
                return block.deadMethod();
              }
              Object unqualified(Block block) {
                return getFoo(block);
              }
              float slip(Block block) {
                return block.slipperiness;
              }
              int dead(Block block) {
                return block.deadField;
              }
              boolean keys(Object input) {
                return input.isKeyDown(1);
              }
              boolean keys2(Object input) {
                return input.isKeyDown(1, 2);
              }
              long time(Block block) {
                return block.getWorldTime();
              }
              Object normal() {
                return Block.NORMAL;
              }
              Object chained(Block block) {
                return block.next.slipperiness;
              }
            }
            """;
        VersionMap.ApiFix.Lint lint = VersionMap.ApiFix.lintKnown(known, scratch.resolve("Demo.java"), source);
        require(lint.hits.stream().anyMatch(h -> h.auto() && h.detail().contains("1.12.2 -> 1.14.4")), "auto fixes name their update");
        String[] auto = lint.hits.stream().filter(h -> h.auto()).map(h -> h.oldName()).sorted().toArray(String[]::new);
        require(java.util.Arrays.equals(auto, new String[] {"getBlockState", "slipperiness"}), "unanimous game-owned renames auto-apply: " + String.join(",", auto));
        require(lint.hits.stream().anyMatch(h -> h.detail().contains("getFoo") && h.detail().contains("[review]")), "ambiguous rename reported");
        // A rename made on another class proves nothing about this receiver (1.21.2 renamed one class's getGameTime and Level kept its own).
        require(lint.hits.stream().anyMatch(h -> h.detail().startsWith("block.getWorldTime") && h.detail().contains("[review]")), "a rename on another owner is reported, not applied");
        require(lint.hits.stream().anyMatch(h -> h.detail().startsWith("Block.NORMAL") && h.detail().contains("[review]")), "a field renamed on another owner is reported, not applied");
        require(lint.hits.stream().noneMatch(h -> h.auto() && h.detail().startsWith("block.next.")), "a chain's receiver type is not its root's");
        require(lint.fixed.contains("block.getWorldTime()") && lint.fixed.contains("Block.NORMAL") && lint.fixed.contains("block.next.slipperiness"), "other owners' renames left as written");
        require(lint.hits.stream().anyMatch(h -> h.detail().contains("deadMethod") && h.detail().contains("[hand-fix]")), "removed method reported with its update");
        require(lint.hits.stream().anyMatch(h -> h.detail().contains("deadField") && h.detail().contains("[hand-fix]")), "removed field reported");
        require(lint.hits.stream().noneMatch(h -> h.detail().contains("unqualified call")), "plain unqualified calls stay silent: no game superclass, no static import");
        String heir = String.join("\n",
            "import net.minecraft.block.Block;",
            "class GameChild extends Block {",
            "  Object u(Block b) {",
            "    return getFoo(b);",
            "  }",
            "  float slip() {",
            "    return slipperiness;",
            "  }",
            "}");
        VersionMap.ApiFix.Lint heirLint = VersionMap.ApiFix.lintKnown(known, scratch.resolve("GameChild.java"), heir);
        require(heirLint.hits.stream().anyMatch(h -> h.detail().contains("getFoo") && h.detail().contains("[review: unqualified call in game subclass]")), "inherited game calls are reported, never auto-applied");
        require(heirLint.hits.stream().anyMatch(h -> h.detail().contains("slipperiness") && h.detail().contains("[review: unqualified field in game subclass]")), "inherited game fields are reported");
        require(heirLint.hits.stream().noneMatch(h -> h.auto()), "nothing auto-applies without a provable receiver");
        String staticImp = String.join("\n",
            "import net.minecraft.block.Block;",
            "import static net.minecraft.block.Block.getBlockState;",
            "class StaticUser {",
            "  Object state(Block block) {",
            "    return getBlockState();",
            "  }",
            "}");
        VersionMap.ApiFix.Lint staticLint = VersionMap.ApiFix.lintKnown(known, scratch.resolve("StaticUser.java"), staticImp);
        require(staticLint.hits.stream().anyMatch(h -> h.auto() && h.detail().startsWith("getBlockState -> getStateContainer")), "unambiguous static imports auto-apply");
        require(lint.hits.stream().anyMatch(h -> h.detail().contains("signature changed") && h.detail().contains("[review args]")), "arity change flagged");
        require(lint.hits.stream().noneMatch(h -> h.detail().contains("keys2")), "matching arity stays quiet");
        require(lint.fixed.contains("block.getStateContainer()") && lint.fixed.contains("block.friction"), "rewrites applied");
        require(lint.hits.stream().anyMatch(h -> h.auto() && h.detail().startsWith("block.getBlockState")), "findings name the receiver");
        require(!lint.fixed.contains("block.getBlockState()") && !lint.fixed.contains("block.slipperiness"), "old names gone");
        require(lint.fixed.contains("block.getFoo()") && lint.fixed.contains("block.deadMethod()"), "review items untouched");
        require(VersionMap.ApiFix.lintKnown(known, scratch.resolve("Demo.java"), lint.fixed).hits.stream().noneMatch(h -> h.auto()), "second pass finds nothing left to auto-apply");
        String shadowed = source.replace("class Demo {", "class Demo {\n  int getBlockState() { return 0; }");
        VersionMap.ApiFix.Lint shadowedLint = VersionMap.ApiFix.lintKnown(known, scratch.resolve("Shadow.java"), shadowed);
        require(shadowedLint.hits.stream().anyMatch(h -> h.oldName().equals("getBlockState") && h.detail().contains("[self-declared]")), "self-declared names are skipped loudly, not silently");
        require(shadowedLint.hits.stream().noneMatch(h -> h.auto() && h.oldName().equals("getBlockState")), "self-declared names never auto-apply");
        Path repo = scratch.resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("Demo.java"), source);
        int files = VersionMap.Preflight.run(map, repo, true);
        require(files == 1, "dry-run preflight reports the file without writing, got " + files);
        require(Files.readString(repo.resolve("Demo.java")).equals(source), "dry run changes nothing");
        int wrote = VersionMap.Preflight.run(map, repo, false);
        require(wrote == 1 && Files.readString(repo.resolve("Demo.java")).contains("block.getStateContainer()"), "preflight applies the map fix");
        Path backMap = scratch.resolve("back-map.json");
        Files.writeString(backMap, """
            { "from": "1.15.2", "to": "1.12.2", "names": "mojmap",
              "methods": [
                { "owner": "net.minecraft.world.level.block.Block", "from": "getStateDefinition", "to": "getBlockState", "desc": "()Lnet/minecraft/world/level/block/state/StateDefinition;", "newDesc": "()Lnet/minecraft/block/state/BlockStateContainer;" }
              ],
              "history": [
                { "kind": "method", "owner": "net.minecraft.world.level.block.Block", "name": "getStateDefinition", "desc": "()V", "what": "renamed", "was": "net.minecraft.world.level.block.Block.getStateDefinition", "to": "net.minecraft.block.Block.getBlockState", "step": "1.14.4 -> 1.14.4" }
              ] }
            """);
        String modern = """
            import net.minecraft.world.level.block.Block;
            class Modern {
              Object state(Block block) {
                return block.getStateDefinition();
              }
            }
            """;
        VersionMap.ApiFix.Lint back = VersionMap.ApiFix.lintKnown(VersionMap.Known.load(backMap), scratch.resolve("Modern.java"), modern);
        require(back.hits.size() == 1 && back.hits.get(0).auto() && back.fixed.contains("block.getBlockState()"), "backports lint the same way, Mojang names to MCP");
        VersionMap.Known other = new VersionMap.Known();
        other.fromVersion = "9";
        other.toVersion = "9";
        other.names = "mcp";
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("kind", "method");
        row.put("name", "getBlockState");
        row.put("what", "renamed");
        row.put("step", "9 -> 9");
        other.history.add(row);
        require(
            VersionMap.ApiFix.stepsFor(other, "method", "getBlockState", java.util.Set.of("renamed")).equals("9 -> 9") &&
            VersionMap.ApiFix.stepsFor(known, "method", "getBlockState", java.util.Set.of("renamed")).contains("1.12.2 -> 1.14.4"),
            "step lookup is per-map; cached steps never leak across maps"
        );
        commonWordChecks(scratch, map);
    }

    static void commonWordChecks(Path scratch, Path map) throws Exception {
        VersionMap.Known known = VersionMap.Known.load(map);
        Path repo = scratch.resolve("common-repo");
        Files.createDirectories(repo);
        StringBuilder many = new StringBuilder("import net.minecraft.block.Block;\nclass Many {\n");
        for (int i = 0; i < 15; i++) many.append("  Object m").append(i).append("(Block block) {\n    return block.deadMethod();\n  }\n");
        many.append("}\n");
        Files.writeString(repo.resolve("ManyA.java"), many.toString());
        Files.writeString(repo.resolve("ManyB.java"), many.toString().replace("class Many", "class ManyB"));
        Files.writeString(repo.resolve("Few.java"), String.join("\n",
            "import net.minecraft.block.Block;",
            "class Few {",
            "  int a(Block block) {",
            "    return block.deadField;",
            "  }",
            "  int b(Block block) {",
            "    return block.deadField;",
            "  }",
            "}"));
        String out = capture(() -> VersionMap.Preflight.run(map, repo, true));
        require(out.lines().filter(line -> line.contains("deadMethod")).count() == 1, "a removed name used 30 times prints once: " + out);
        require(out.contains("30 uses across 2 files") && out.contains("[common-word]"), "the summary names the count and files: " + out);
        require(out.lines().filter(line -> line.contains("deadField")).count() == 2, "a removed name used twice still prints per use: " + out);
        require(out.contains("3 to review"), "the review count matches the printed lines: " + out);
        require(out.contains("0 file(s)") && Files.readString(repo.resolve("ManyA.java")).equals(many.toString()), "dry run changes nothing");
        List<VersionMap.ApiFix> batch = VersionMap.ApiFix.parseAll(
            List.of(repo.resolve("ManyA.java"), repo.resolve("Few.java")),
            List.of(Files.readString(repo.resolve("ManyA.java")), Files.readString(repo.resolve("Few.java")))
        );
        require(batch.size() == 2, "batch parse returns one unit per file");
        VersionMap.ApiFix.Lint single = VersionMap.ApiFix.lintKnown(known, repo.resolve("ManyA.java"), Files.readString(repo.resolve("ManyA.java")));
        VersionMap.ApiFix.Lint batched = VersionMap.ApiFix.lintParsed(known, batch.get(0), Files.readString(repo.resolve("ManyA.java")));
        require(batched.hits.equals(single.hits) && batched.fixed.equals(single.fixed), "batch parse lints exactly like single parse");
    }

    interface Throwing {
        void run() throws Exception;
    }

    static String capture(Throwing run) throws Exception {
        java.io.PrintStream keep = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer));
        try {
            run.run();
        } finally {
            System.setOut(keep);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void require(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
