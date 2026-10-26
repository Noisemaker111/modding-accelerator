import java.nio.file.*;
import java.util.List;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Checks that nested handler types resolve, while real signature/target failures still fail. */
public final class MixinAuditTest {
	public static void main(String[] args) throws Exception {
		Path root = Path.of(args[0]).toAbsolutePath();
		Path sources = root.resolve("target/demo"), classes = root.resolve("classes"), mixins = root.resolve("mixins");
		Files.createDirectories(sources); Files.createDirectories(classes); Files.createDirectories(mixins);
		Path item = sources.resolve("Item.java"), stack = sources.resolve("ItemStack.java");
		Files.writeString(item, "package demo; public class Item { public static class TooltipContext {} public static class WrongContext {} }");
		Files.writeString(stack, "package demo; public class ItemStack { public void tooltip(Item.TooltipContext context) {} public static ItemStack of(String id) { return null; } }");
		if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), item.toString(), stack.toString()) != 0) {
			throw new AssertionError("cannot build audit fixture");
		}
		Path jar = root.resolve("target.jar");
		try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, out); out.closeEntry();
			}
		}
		String source = """
			import demo.Item;
			import demo.ItemStack;
			@Mixin(ItemStack.class)
			class ExampleMixin {
			 @Inject(method = "tooltip", at = @At("HEAD"))
			 void onTooltip(Item.TooltipContext context, CallbackInfo ci) {}
			}
			""";
		check(root, jar, mixins, source, 0, "0 dead");
		check(root, jar, mixins, source.replace("Item.TooltipContext", "demo.Item.TooltipContext"), 0, "0 dead");
		check(root, jar, mixins, source.replace("Item.TooltipContext", "Item.WrongContext"), 1, "does not match");
		check(root, jar, mixins, source.replace("method = \"tooltip\"", "method = \"removed\""), 1, "not found");
		// A static target takes a static handler with the target's own parameters (Animatica's
		// Identifier.of mixin); the handler never receives an instance.
		String statics = """
			import demo.ItemStack;
			@Mixin(ItemStack.class)
			class StaticMixin {
			 @Inject(method = "of", at = @At("TAIL"))
			 private static void onOf(String id, CallbackInfoReturnable<ItemStack> cir) {}
			}
			""";
		Files.deleteIfExists(mixins.resolve("ExampleMixin.java"));
		check(root, jar, mixins, "StaticMixin.java", statics, 0, "0 dead");
		check(root, jar, mixins, "StaticMixin.java", statics.replace("private static void", "private void"), 1, "must be static too");
		check(root, jar, mixins, "StaticMixin.java", source.replace(" void onTooltip", " static void onTooltip"), 1, "must not be static");
		// No game jar: a port (--strict) must fail with the reason; the legacy lint lane keeps its note.
		audit(List.of("--root", root.toString(), "--src", mixins.toString(), "--strict"), 3, "no Minecraft game jar");
		audit(List.of("--root", root.toString(), "--src", mixins.toString()), 0, "mixins not audited");
		Path none = Files.createDirectories(root.resolve("no-mixins"));
		Files.writeString(none.resolve("Plain.java"), "class Plain {}");
		audit(List.of("--root", root.toString(), "--src", none.toString(), "--strict"), 0, "# 0 mixins, nothing to audit");
		System.out.println("PASS nested source types, wrong handler types, static targets, removed targets and a missing game jar");
	}
	static void audit(List<String> args, int expected, String finding) throws Exception {
		List<String> command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), "MixinAudit"));
		command.addAll(args);
		var process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		if (process.waitFor() != expected || !output.contains(finding)) { throw new AssertionError(args + " -> " + output); }
	}
	static void check(Path root, Path jar, Path mixins, String source, int expected, String finding) throws Exception {
		check(root, jar, mixins, "ExampleMixin.java", source, expected, finding);
	}
	static void check(Path root, Path jar, Path mixins, String file, String source, int expected, String finding) throws Exception {
		Files.writeString(mixins.resolve(file), source);
		String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
		var process = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"), "MixinAudit",
				"--root", root.toString(), "--jar", jar.toString(), "--src", mixins.toString()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		if (process.waitFor() != expected || !output.contains(finding)) { throw new AssertionError(output); }
	}
}
