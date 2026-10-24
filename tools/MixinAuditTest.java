import java.nio.file.*;
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
		Files.writeString(stack, "package demo; public class ItemStack { public void tooltip(Item.TooltipContext context) {} }");
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
		System.out.println("PASS nested source types, wrong handler types and removed targets");
	}
	static void check(Path root, Path jar, Path mixins, String source, int expected, String finding) throws Exception {
		Files.writeString(mixins.resolve("ExampleMixin.java"), source);
		String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
		var process = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"), "MixinAudit",
				"--root", root.toString(), "--jar", jar.toString(), "--src", mixins.toString()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		if (process.waitFor() != expected || !output.contains(finding)) { throw new AssertionError(output); }
	}
}
