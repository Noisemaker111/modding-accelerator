import java.nio.file.*;
import java.util.*;

/** Regression checks for applying learned ports without borrowing another mod's implementation. */
public final class VersionMapTest {
	public static void main(String[] args) throws Exception {
		Path scratch = Path.of(args[0]).toAbsolutePath();
		Files.createDirectories(scratch);
		Path map = scratch.resolve("map.json");
		Files.writeString(map, """
			{
			  "methods": [
			    { "owner": "net.minecraft.data.tag.ValueLookupTagProvider", "from": "getOrCreateTagBuilder", "to": "builder" }
			  ],
			  "signatures": [
			    { "owner": "net.minecraft.client.RenderLayer", "from": "getGuiTextured", "to": "getGuiTextured", "newDesc": "()V" }
			  ],
			  "fieldsRemoved": [
			    { "owner": "net.minecraft.client.MinecraftClient", "from": "IS_SYSTEM_MAC" }
			  ]
			}
			""");
		List<String> failures = new ArrayList<>();
		Path diff = scratch.resolve("port.diff"), rules = scratch.resolve("learned.json");
		Files.writeString(diff, """
			diff --git a/Screen.java b/Screen.java
			--- a/Screen.java
			+++ b/Screen.java
			@@ -1,5 +1,6 @@
			 import net.minecraft.client.render.RenderLayer;
			+import net.minecraft.client.gl.RenderPipelines;
			-draw(RenderLayer::getGuiTextured, texture, x, y, width, height);
			+draw(RenderPipelines.GUI_TEXTURED, texture, x, y, width, height);
			-use(MinecraftClient.IS_SYSTEM_MAC);
			+use(IS_MACOS);
			-use(MinecraftClient.IS_SYSTEM_MAC);
			+use(this.IS_MACOS);
			""");
		VersionMap.Learn.run(map, List.of(diff.toString()), rules);
		String learned = Files.readString(rules);
		check(learned.contains("RenderPipelines.GUI_TEXTURED"), "keep reusable pipeline rewrite", failures);
		check(!learned.contains("IS_MACOS"), "reject a different mod's local field", failures);
		Files.writeString(rules, "{ \"from\": \"RenderLayer::getGuiTextured\", \"to\": \"RenderPipelines.GUI_TEXTURED\", \"imports\": [\"net.minecraft.client.gl.RenderPipelines\"] }\n");

		Path repo = scratch.resolve("repo");
		Files.createDirectories(repo);
		Path source = repo.resolve("Screen.java");
		Files.writeString(source, "package demo;\nimport java.util.List;\nclass Screen {\n  Object a = RenderLayer::getGuiTextured;\n  Object b = RenderLayer::getGuiTextured;\n}\n");
		Path log = scratch.resolve("build.log");
		String errorA = source + ":4: error: incompatible types: old cannot be converted to new\n  Object a = RenderLayer::getGuiTextured;\n             ^\n";
		String errorB = source + ":5: error: incompatible types: old cannot be converted to new\n  Object b = RenderLayer::getGuiTextured;\n             ^\n";
		Files.writeString(log, errorA + errorB);
		VersionMap.Fix.run(map, log, repo, false, rules);
		String fixed = Files.readString(source);
		check(!fixed.contains("RenderLayer::getGuiTextured"), "imports must not move subsequent repair lines", failures);
		check(fixed.lines().filter(s -> s.equals("import net.minecraft.client.gl.RenderPipelines;")).count() == 1,
				"add each import once", failures);
		Files.writeString(source, "class Screen {\n  void test() { getOrCreateTagBuilder(tag); }\n}\n");
		Files.writeString(log, source + ":2: error: cannot find symbol\n  void test() { getOrCreateTagBuilder(tag); }\n                ^\n  symbol:   method getOrCreateTagBuilder(TagKey)\n  location: class Screen\n");
		VersionMap.Fix.run(map, log, repo, false, null);
		check(Files.readString(source).contains("getOrCreateTagBuilder"), "do not apply a vanilla method rename to an unrelated mod type", failures);
		String exactOwner = Files.readString(log).replace("location: class Screen", "location: class ValueLookupTagProvider");
		Files.writeString(log, exactOwner);
		VersionMap.Fix.run(map, log, repo, false, null);
		check(Files.readString(source).contains("builder(tag)"), "retain renames for the matching declaring type", failures);
		Path outside = scratch.resolve("Outside.java");
		Files.writeString(outside, "class Outside {\n  void test() { getOrCreateTagBuilder(tag); }\n}\n");
		Files.writeString(log, exactOwner.replace(source.toString(), outside.toString()));
		VersionMap.Fix.run(map, log, repo, false, null);
		check(Files.readString(outside).contains("getOrCreateTagBuilder"), "a supplied log cannot edit a file outside the requested repo", failures);

		String colA = source + ":4: error: incompatible types: long cannot be converted to Window\n  one(handle) || two(handle);\n      ^\n";
		String colB = source + ":4: error: incompatible types: long cannot be converted to Window\n  one(handle) || two(handle);\n                     ^\n";
		String echoed = (colA + colB).lines().map(s -> "  " + s).collect(java.util.stream.Collectors.joining("\n")) + "\n";
		Files.writeString(log, colA + colB + echoed);
		check(VersionMap.readErrors(log).size() == 2, "deduplicate Gradle echoes while preserving separate columns", failures);
		Path backup = repo.resolve(".modaccel/portable/ports/example/before/Screen.java");
		Files.createDirectories(backup.getParent()); Files.writeString(backup, "import net.minecraft.Old;\nclass Screen {}\n");
		Path dependency = repo.resolve("dependency"); Files.createDirectories(dependency.resolve(".git"));
		Files.writeString(dependency.resolve("Dependency.java"), "class Dependency {}\n");
		check(!VersionMap.Fix.sourceFiles(repo).contains(backup), "exclude immutable undo backups from mapping sweeps", failures);
		check(!VersionMap.Fix.sourceFiles(repo).contains(dependency.resolve("Dependency.java")), "exclude nested repositories from mapping sweeps", failures);
		check(VersionMap.Fix.sourcePath(new VersionMap.Err(backup.toString(), 1, "error", "", 0, null, null), repo) == null,
				"compiler diagnostics cannot direct a repair into undo backups", failures);
		apiChecks(scratch, failures);
		if (!failures.isEmpty()) { throw new AssertionError(String.join("; ", failures)); }
		System.out.println("PASS: learner safety, import positions, unique diagnostics");
	}
	static void apiChecks(Path scratch, List<String> failures) throws Exception {
		VersionMap.Known k = new VersionMap.Known();
		k.names = "yarn"; k.toVersion = "1.21.9";
		k.sigTo.put("InputUtil.isKeyPressed", Set.of("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z"));
		k.sigTo.put("DrawContext.getMatrices", Set.of("getMatrices()Lorg/joml/Matrix3x2fStack;"));
		k.fieldGone.add("IS_SYSTEM_MAC"); k.methodGone.addAll(List.of("getWorld", "getEntityWorld", "setShaderColor"));
		Path file = scratch.resolve("ApiDemo.java");
		String input = """
			import net.minecraft.client.MinecraftClient;
			import net.minecraft.client.util.InputUtil;
			class ApiDemo {
			 boolean input() {
			  long handle = MinecraftClient.getInstance().getWindow().getHandle();
			  return InputUtil.isKeyPressed(handle, 1) || InputUtil.isKeyPressed(handle, 2);
			 }
			}
			""";
		List<VersionMap.Err> errors = List.of(error(file, input, "handle, 1", "incompatible types: long cannot be converted to Window", null, null),
				error(file, input, "handle, 2", "incompatible types: long cannot be converted to Window", null, null));
		String fixed = VersionMap.ApiFix.apply(k, file, input, errors);
		check(fixed.contains("long handle =") && !fixed.contains("isKeyPressed(handle,"), "adapt both input calls while retaining native-handle uses", failures);
		String mutable = input.replace("  return", "  handle = anotherWindow();\n  return");
		check(VersionMap.ApiFix.apply(k, file, mutable, List.of(error(file, mutable, "handle, 1", errors.getFirst().message(), null, null))).equals(mutable),
				"never replace a reassigned handle with the current game window", failures);
		VersionMap.Err stale = new VersionMap.Err(file.toString(), errors.getFirst().line(), errors.getFirst().message(), "a stale line", errors.getFirst().caret(), null, null);
		check(VersionMap.ApiFix.apply(k, file, input, List.of(stale)).equals(input), "API fixes reject stale diagnostic positions", failures);
		String world = "import net.minecraft.entity.player.PlayerEntity;\nclass ApiDemo { void test(PlayerEntity player) { player.getWorld(); } }\n";
		VersionMap.Err worldError = error(file, world, "getWorld", "cannot find symbol", "method getWorld()", "variable player of type PlayerEntity");
		check(VersionMap.ApiFix.apply(k, file, world, List.of(worldError)).contains("player.getEntityWorld()"), "use the target entity world accessor", failures);
		VersionMap.Err other = new VersionMap.Err(file.toString(), worldError.line(), worldError.message(), worldError.source(), worldError.caret(), worldError.symbol(), "variable player of type ModPlayer");
		check(VersionMap.ApiFix.apply(k, file, world, List.of(other)).equals(world), "leave another mod's world accessor unchanged", failures);
		String impostor = world.replace("net.minecraft.entity.player.PlayerEntity", "another.mod.PlayerEntity");
		check(VersionMap.ApiFix.apply(k, file, impostor, List.of(worldError)).equals(impostor), "reject another mod with the same entity type name", failures);
		String mac = "import net.minecraft.client.MinecraftClient;\nclass ApiDemo { boolean mac() { return MinecraftClient.IS_SYSTEM_MAC; } }\n";
		check(VersionMap.ApiFix.apply(k, file, mac, List.of(error(file, mac, "IS_SYSTEM_MAC", "cannot find symbol", "variable IS_SYSTEM_MAC", "class MinecraftClient")))
				.contains("net.minecraft.client.input.SystemKeycodes.IS_MAC_OS"), "use the replacement platform flag without a mod-local helper", failures);
		String gui = """
			import net.minecraft.client.gui.DrawContext;
			import net.minecraft.client.util.math.MatrixStack;
			import com.mojang.blaze3d.systems.RenderSystem;
			class ApiDemo {
			 void draw(DrawContext context, int depth) {
			  MatrixStack matrices;
			  matrices = context.getMatrices();
			  matrices.push();
			  matrices.translate(2, 3, depth);
			  matrices.scale(0.75f, 0.75f, 0.75f);
			  matrices.pop();
			  RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			 }
			 void world(MatrixStack matrices) { matrices.push(); matrices.translate(1, 2, 3); matrices.pop(); }
			}
			""";
		VersionMap.Err matrixError = error(file, gui, "getMatrices()", "incompatible types: Matrix3x2fStack cannot be converted to MatrixStack", null, null);
		VersionMap.Err colorError = error(file, gui, "setShaderColor", "cannot find symbol", "method setShaderColor(float,float,float,float)", "class RenderSystem");
		fixed = VersionMap.ApiFix.apply(k, file, gui, List.of(matrixError, colorError));
		check(fixed.contains("org.joml.Matrix3x2fStack matrices;") && fixed.contains("matrices.pushMatrix()") && fixed.contains("matrices.popMatrix()")
				&& fixed.contains("matrices.translate(2, 3)") && !fixed.contains("RenderSystem.setShaderColor"), "migrate a local GUI stack and its white shader reset together", failures);
		check(fixed.contains("void world(MatrixStack matrices) { matrices.push(); matrices.translate(1, 2, 3); matrices.pop(); }"), "keep 3D stacks in other methods intact", failures);
		String colored = gui.replace("1f, 1f, 1f, 1f", "0.5f, 1f, 1f, 1f");
		check(VersionMap.ApiFix.apply(k, file, colored, List.of(error(file, colored, "setShaderColor", colorError.message(), colorError.symbol(), colorError.location()))).equals(colored),
				"leave non-white shader changes for a guided port", failures);
		String escaping = gui.replace("  matrices.pop();", "  consume(matrices);\n  matrices.pop();");
		check(VersionMap.ApiFix.apply(k, file, escaping, List.of(error(file, escaping, "getMatrices()", matrixError.message(), null, null))).equals(escaping),
				"refuse the whole GUI rewrite when the stack escapes", failures);
		String effect = gui.replace("2, 3, depth", "2, 3, nextDepth()");
		check(VersionMap.ApiFix.apply(k, file, effect, List.of(error(file, effect, "getMatrices()", matrixError.message(), null, null))).equals(effect),
				"never discard a Z expression with side effects", failures);
		VersionMap.Known back = new VersionMap.Known();
		back.names = "yarn"; back.toVersion = "1.21.5";
		back.sigTo.put("DrawContext.drawGuiTexture", Set.of("drawGuiTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIII)V"));
		back.sigTo.put("DrawContext.getMatrices", Set.of("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;"));
		String reverse = """
			import net.minecraft.client.gui.DrawContext;
			import net.minecraft.client.gl.RenderPipelines;
			import org.joml.Matrix3x2fStack;
			class ApiDemo { void draw(DrawContext context) {
			 Matrix3x2fStack matrices;
			 matrices = context.getMatrices();
			 matrices.pushMatrix(); matrices.translate(x(), y()); matrices.scale(.75f, .75f); matrices.popMatrix();
			 context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon, 0, 0, 9, 9);
			} }
			""";
		String pipelineError = "incompatible types: RenderPipeline cannot be converted to Function<Identifier,RenderLayer>";
		String stackError = "incompatible types: MatrixStack cannot be converted to Matrix3x2fStack";
		fixed = VersionMap.ApiFix.apply(back, file, reverse, List.of(error(file, reverse, "RenderPipelines.GUI_TEXTURED, icon", pipelineError, null, null),
				error(file, reverse, "getMatrices()", stackError, null, null)));
		check(fixed.contains("RenderLayer::getGuiTextured") && fixed.contains("net.minecraft.client.util.math.MatrixStack matrices")
				&& fixed.contains("matrices.translate(x(), y(), 0)") && fixed.contains("matrices.scale(.75f, .75f, 1)"),
				"backport typed GUI texture and local matrix operations", failures);
		String wrong = reverse.replace("net.minecraft.client.gui.DrawContext", "other.mod.DrawContext");
		check(VersionMap.ApiFix.apply(back, file, wrong, List.of(error(file, wrong, "RenderPipelines.GUI_TEXTURED, icon", pipelineError, null, null))).equals(wrong),
				"reject another DrawContext type", failures);
		VersionMap.Known unavailable = new VersionMap.Known(); unavailable.names = "yarn"; unavailable.toVersion = "1.21.5";
		check(VersionMap.ApiFix.apply(unavailable, file, reverse, List.of(error(file, reverse, "RenderPipelines.GUI_TEXTURED, icon", pipelineError, null, null))).equals(reverse),
				"refuse GUI rewrite without target API evidence", failures);
		String escaped = reverse.replace(" matrices.pushMatrix();", " consume(matrices); matrices.pushMatrix();");
		check(VersionMap.ApiFix.apply(back, file, escaped, List.of(error(file, escaped, "getMatrices()", stackError, null, null))).equals(escaped),
				"refuse matrix backport when the stack escapes", failures);
	}
	static VersionMap.Err error(Path file, String source, String at, String message, String symbol, String location) {
		int offset = source.indexOf(at), lineStart = source.lastIndexOf('\n', offset) + 1, lineEnd = source.indexOf('\n', offset);
		if (offset < 0) { throw new AssertionError(at); }
		if (lineEnd < 0) { lineEnd = source.length(); }
		int line = (int) source.substring(0, lineStart).chars().filter(c -> c == '\n').count() + 1;
		return new VersionMap.Err(file.toString(), line, message, source.substring(lineStart, lineEnd), offset - lineStart, symbol, location);
	}
	static void check(boolean ok, String name, List<String> failures) {
		System.out.println((ok ? "PASS " : "FAIL ") + name);
		if (!ok) { failures.add(name); }
	}
}
