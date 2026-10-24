package dev.modlab.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * 1.21.11 build of the hot reload lane.
 *
 * <p>The parent's version assumes one source tree at the project root. This node compiles
 * the parent's {@code ..\..\src} tree plus the overrides in this directory's {@code src},
 * with an override winning at the same relative path, exactly as {@code build.gradle}'s
 * {@code layeredSources} does for the Gradle build.
 *
 * <p>Main classes are redefined in place through {@link LabAgent}; client classes are
 * loaded fresh from a scratch directory. Nothing outside the mod is touched.
 */
final class HotUi {
	private static final int AGENT_PORT = Integer.getInteger("prismlab.agent.port", 27498);
	private static final Map<String, Integer> APPLIED = new java.util.HashMap<>();

	private static long lastCompile;
	private static long compileMillis = -1;
	private static long mainStamp = -1;
	private static long clientStamp = -1;
	private static Path mainOut;
	private static Path clientOut;
	private static String depsClasspath;
	private static Path parentRoot;
	private static String javacPath;

	private HotUi() {
	}

	/** Returns a loader that can provide fresh client-side classes. */
	static ClassLoader reload(Path root, long sourceStamp) throws Exception {
		prepare(root);
		if (sourceStamp == lastCompile) {
			compileMillis = -1;
			return new ClientFirst(clientOut.toUri().toURL(), HotUi.class.getClassLoader());
		}
		long started = System.nanoTime();
		// Only the side that changed is recompiled: a screen edit must not pay for the
		// main classes, and the other way round. javac over this classpath is the cost.
		long main = newest(root.resolve("src/main/java"), parentRoot.resolve("src/main/java"));
		if (main != mainStamp) {
			compileInto(mainOut, layered(root.resolve("src/main/java"), parentRoot.resolve("src/main/java")), classpath(false));
			mainStamp = main;
		}
		long client = newest(root.resolve("src/client/java"), parentRoot.resolve("src/client/java"));
		if (client != clientStamp) {
			compileInto(clientOut, controlFree(layered(root.resolve("src/client/java"), parentRoot.resolve("src/client/java"))), classpath(true));
			clientStamp = client;
		}
		lastCompile = sourceStamp;
		compileMillis = Math.round((System.nanoTime() - started) / 1_000_000.0);
		return new ClientFirst(clientOut.toUri().toURL(), HotUi.class.getClassLoader());
	}

	/**
	 * Asks the agent to replace the bodies of already-loaded main classes, so a behaviour
	 * edit lands without a restart.
	 */
	static String redefineMain(Path root, long sourceStamp) throws Exception {
		reload(root, sourceStamp);
		int redefined = 0;
		List<String> problems = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(mainOut)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				String name = mainOut.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
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
				else if (reply.startsWith("error:")) {
					problems.add(name.substring(name.lastIndexOf('.') + 1) + " (" + reply.substring(7).trim() + ")");
				}
			}
		}
		if (!problems.isEmpty()) {
			return "needs restart: " + String.join(", ", problems);
		}
		String took = compileMillis < 0 ? "" : " (javac " + compileMillis + " ms)";
		return redefined == 0 ? "no loaded main classes changed" + took : "redefined " + redefined + " main classes" + took;
	}

	private static void prepare(Path root) throws Exception {
		if (mainOut != null) {
			return;
		}
		mainOut = root.resolve(".modaccel/hot/main");
		clientOut = root.resolve(".modaccel/hot/client");
		Files.createDirectories(mainOut);
		Files.createDirectories(clientOut);
		parentRoot = root.getParent() == null ? root : root.getParent().getParent();
		depsClasspath = System.getProperty("java.class.path");
	}

	/** Newest source mtime under these bases; the reload gate for one side of the mod. */
	private static long newest(Path... bases) throws Exception {
		long stamp = -1L;
		for (Path base : bases) {
			for (Path file : javaSources(base)) {
				stamp = Math.max(stamp, Files.getLastModifiedTime(file).toMillis());
			}
		}
		return stamp;
	}

	/**
	 * The game's own classpath, minus Gradle's build output (which would win over freshly
	 * compiled code) and plus the scratch directory for this side.
	 */
	private static String classpath(boolean includeMainOut) {
		StringBuilder out = new StringBuilder();
		for (String entry : depsClasspath.split(File.pathSeparator)) {
			if (entry.contains("build" + File.separator + "classes") || entry.contains("build" + File.separator + "resources")) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(File.pathSeparator);
			}
			out.append(entry);
		}
		if (includeMainOut) {
			out.append(File.pathSeparator).append(mainOut);
		}
		return out.toString();
	}

	/**
	 * The control classes ({@code dev.modlab.*}) never come from the fresh loader, so
	 * compiling them would only cost time.
	 */
	private static List<Path> controlFree(List<Path> sources) {
		return sources.stream().filter(p -> !p.toString().replace('\\', '/').contains("dev/modlab/")).toList();
	}

	/**
	 * Overrides first, then every parent file whose relative path is not overridden, so a
	 * class is never defined twice.
	 */
	private static List<Path> layered(Path overrideRoot, Path parentRoot) throws Exception {
		Map<String, Path> byPath = new LinkedHashMap<>();
		List<Path> overrides = javaSources(overrideRoot);
		for (Path file : overrides) {
			byPath.put(overrideRoot.relativize(file).toString().replace('\\', '/'), file);
		}
		for (Path file : javaSources(parentRoot)) {
			byPath.putIfAbsent(parentRoot.relativize(file).toString().replace('\\', '/'), file);
		}
		return new ArrayList<>(byPath.values());
	}

	private static List<Path> javaSources(Path base) throws Exception {
		if (!Files.isDirectory(base)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(base)) {
			return walk.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile).toList();
		}
	}

	private static void compileInto(Path out, List<Path> sources, String classpath) throws Exception {
		if (sources.isEmpty()) {
			return;
		}
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		List<String> options = List.of("-d", out.toString(), "-cp", classpath + File.pathSeparator + out,
				"--release", "25", "-nowarn", "-proc:none", "-encoding", "UTF-8");
		if (compiler == null) {
			compileInProcessless(sources, options);
			return;
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		boolean ok;
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			ok = compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources)).call();
		}
		if (!ok) {
			throw new IllegalStateException(compileFailure(diagnostics));
		}
	}

	/**
	 * JBR carries the game's vmArgs; if it ships without {@code jdk.compiler}, the same
	 * javac from {@code ..\..\.tools\jdk-*} does the job in a child process instead.
	 */
	private static void compileInProcessless(List<Path> sources, List<String> options) throws Exception {
		if (javacPath == null) {
			Path tools = parentRoot.resolve(".tools");
			try (Stream<Path> walk = Files.list(tools)) {
				javacPath = walk.filter(Files::isDirectory).map(p -> p.resolve("bin/javac.exe"))
						.filter(Files::isRegularFile).map(Path::toString).sorted().reduce((a, b) -> b).orElse(null);
			}
		}
		if (javacPath == null) {
			throw new IllegalStateException("no javac: neither the game JVM nor .tools\\jdk-* has one");
		}
		List<String> command = new ArrayList<>();
		command.add(javacPath);
		command.addAll(options);
		for (Path source : sources) {
			command.add(source.toString());
		}
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IllegalStateException("compile failed: " + output.replace('\n', ' ').replace('\r', ' ').trim());
		}
	}

	private static String compileFailure(DiagnosticCollector<JavaFileObject> diagnostics) {
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
		return message.toString();
	}

	private static String ask(String className, byte[] bytes) {
		try (java.net.Socket socket = new java.net.Socket("127.0.0.1", AGENT_PORT);
				java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
				java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
			out.println("redefine " + className + " " + java.util.Base64.getEncoder().encodeToString(bytes));
			String reply = in.readLine();
			return reply == null ? "skip" : reply;
		}
		catch (Exception e) {
			return "error: agent unreachable on port " + AGENT_PORT;
		}
	}

	/** Client classes come from the scratch directory; the control classes stay on the parent. */
	private static final class ClientFirst extends java.net.URLClassLoader {
		ClientFirst(java.net.URL url, ClassLoader parent) {
			super(new java.net.URL[] { url }, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("dev.modlab.") || name.startsWith("java.") || name.startsWith("javax.")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					try {
						loaded = findClass(name);
					}
					catch (ClassNotFoundException missing) {
						loaded = super.loadClass(name, false);
					}
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}
}
