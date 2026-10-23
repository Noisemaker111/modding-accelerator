package dev.modlab.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Recompiles this node's mod sources (node overrides plus the parent files they shadow)
 * and swaps the new class bodies into the running NeoForge client through {@code LabAgent}.
 *
 * <p>NeoForge has no split client source set: every mod class, the screen included, is
 * loaded by one transforming loader, so a body edit is a plain {@code redefineClasses}.
 * Only bodies are replaced — a new field or method still needs a restart, and the harness
 * classes under {@code dev.modlab} are never redefined while they are running the request.
 */
final class HotUi {
	private static final int AGENT_PORT = Integer.getInteger("prismlab.agent.port", 27598);
	private static final Map<String, Integer> APPLIED = new HashMap<>();

	private static long lastCompile;
	private static Path out;
	private static String deps;

	private HotUi() {
	}

	/** The client classes come from the same loader here; a reload only means "compiled". */
	static ClassLoader reload(Path root, long sourceStamp) throws Exception {
		compile(root, sourceStamp);
		return HotUi.class.getClassLoader();
	}

	static String redefineMain(Path root, long sourceStamp) throws Exception {
		compile(root, sourceStamp);
		int redefined = 0;
		int skipped = 0;
		List<String> problems = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(out)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				String name = out.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
				if (name.startsWith("dev.modlab.")) {
					// The harness is answering this request; never redefine it under itself.
					continue;
				}
				byte[] bytes = Files.readAllBytes(file);
				int hash = java.util.Arrays.hashCode(bytes);
				if (APPLIED.getOrDefault(name, 0) == hash) {
					continue;
				}
				String reply = ask(name, bytes);
				if (reply.equals("ok")) {
					APPLIED.put(name, hash);
					redefined++;
				}
				else if (reply.equals("skip")) {
					skipped++;
				}
				else {
					problems.add(name.substring(name.lastIndexOf('.') + 1) + " (" + reply.replaceFirst("^error:\\s*", "") + ")");
				}
			}
		}
		if (!problems.isEmpty()) {
			return "needs restart: " + String.join(", ", problems);
		}
		if (redefined == 0) {
			return skipped == 0 ? "no mod classes changed" : "no loaded mod classes changed (" + skipped + " not loaded)";
		}
		return "redefined " + redefined + " class" + (redefined == 1 ? "" : "es");
	}

	private static void compile(Path root, long sourceStamp) throws Exception {
		if (out == null) {
			out = root.resolve(".modaccel/hot/classes");
			deps = classpath(root);
		}
		if (sourceStamp == lastCompile) {
			return;
		}
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("No javac available; the game must run on a JDK");
		}
		List<Path> sources = sources(root);
		// A clean output directory, so a class deleted from the sources cannot be redefined.
		deleteTree(out);
		Files.createDirectories(out);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			List<String> options = List.of("-d", out.toString(), "-cp", deps, "--release", "21", "-g", "-nowarn", "-proc:none", "-encoding", "UTF-8");
			boolean ok = compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources)).call();
			if (!ok) {
				StringBuilder message = new StringBuilder("compile failed: ");
				for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
					if (d.getKind() != Diagnostic.Kind.ERROR) {
						continue;
					}
					String where = d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + " ";
					message.append(where).append(d.getMessage(Locale.ROOT)).append("; ");
					if (message.length() > 400) {
						break;
					}
				}
				throw new IllegalStateException(message.toString());
			}
		}
		lastCompile = sourceStamp;
	}

	/**
	 * The node's own sources first, then every parent source whose relative path the node
	 * does not override — the same layering {@code build.gradle} compiles.
	 */
	private static List<Path> sources(Path root) throws Exception {
		Path parent = root.resolve("../..").normalize();
		List<Path> sources = new ArrayList<>();
		Set<String> overridden = new HashSet<>();
		for (String tree : List.of("src/main/java", "src/client/java")) {
			List<Path> own = java(root.resolve(tree));
			sources.addAll(own);
			for (Path file : own) {
				overridden.add(root.resolve(tree).relativize(file).toString().replace('\\', '/'));
			}
		}
		for (String tree : List.of("src/main/java", "src/client/java")) {
			Path base = parent.resolve(tree);
			for (Path file : java(base)) {
				if (overridden.contains(base.relativize(file).toString().replace('\\', '/'))) {
					continue;
				}
				if (file.getFileName().toString().equals("LabEntry.java")) {
					// Fabric entrypoint stub the node replaces; not part of a NeoForge build.
					continue;
				}
				sources.add(file);
			}
		}
		return sources;
	}

	private static List<Path> java(Path base) throws Exception {
		if (!Files.isDirectory(base)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(base)) {
			return walk.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile).toList();
		}
	}

	private static void deleteTree(Path dir) throws Exception {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	/** The game's classpath without the mod's own build output, so javac reads the sources. */
	private static String classpath(Path root) throws Exception {
		String running = System.getProperty("java.class.path", "");
		StringBuilder kept = new StringBuilder();
		boolean sawGame = false;
		for (String entry : running.split(File.pathSeparator)) {
			if (entry.isBlank()) {
				continue;
			}
			if (entry.contains("build" + File.separator + "classes") || entry.contains("build" + File.separator + "resources")) {
				continue;
			}
			if (entry.contains("neoforge-")) {
				sawGame = true;
			}
			if (!kept.isEmpty()) {
				kept.append(File.pathSeparator);
			}
			kept.append(entry);
		}
		if (sawGame) {
			return kept.toString();
		}
		// BootstrapLauncher can trim java.class.path; the launcher saves the same list.
		Path saved = root.resolve(".modaccel/hot-cp.txt");
		if (Files.isRegularFile(saved)) {
			return Files.readString(saved, StandardCharsets.UTF_8).trim();
		}
		throw new IllegalStateException("no game classpath: java.class.path has no neoforge entry and .modaccel/hot-cp.txt is missing");
	}

	private static String ask(String className, byte[] bytes) {
		// LabAgent binds InetAddress.getLoopbackAddress(), which is IPv6 ::1 on this PC;
		// "127.0.0.1" is refused here.
		try (Socket socket = new Socket(java.net.InetAddress.getLoopbackAddress(), AGENT_PORT);
				PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
			out.println("redefine " + className + " " + java.util.Base64.getEncoder().encodeToString(bytes));
			String reply = in.readLine();
			return reply == null ? "skip" : reply;
		}
		catch (Exception e) {
			return "error: agent unreachable on " + AGENT_PORT;
		}
	}
}
