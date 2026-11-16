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
                { "owner": "net.minecraft.client.gui.FontRenderer", "from": "isKeyDown", "to": "isKeyDown", "desc": "(I)Z", "newDesc": "(II)Z" }
              ],
              "methodsRemoved": [
                { "owner": "net.minecraft.block.Block", "from": "deadMethod", "desc": "()V", "newDesc": null }
              ],
              "fields": [
                { "owner": "net.minecraft.block.Block", "from": "slipperiness", "to": "friction", "desc": "F", "newDesc": "F" }
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
            }
            """;
        VersionMap.ApiFix.Lint lint = VersionMap.ApiFix.lintKnown(known, scratch.resolve("Demo.java"), source);
        require(lint.hits.stream().anyMatch(h -> h.auto() && h.detail().contains("1.12.2 -> 1.14.4")), "auto fixes name their update");
        String[] auto = lint.hits.stream().filter(h -> h.auto()).map(h -> h.oldName()).sorted().toArray(String[]::new);
        require(java.util.Arrays.equals(auto, new String[] {"getBlockState", "slipperiness"}), "unanimous game-owned renames auto-apply: " + String.join(",", auto));
        require(lint.hits.stream().anyMatch(h -> h.detail().contains("getFoo") && h.detail().contains("[review]")), "ambiguous rename reported");
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
    }
    private static void require(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
