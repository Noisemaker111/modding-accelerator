package dev.modlab.client;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Recompiles the mod's own sources and gets the new code into the running client.
 *
 * <p>Main classes are redefined in place through {@link LabAgent}; client classes are
 * loaded fresh from a scratch directory. Nothing outside the mod is touched.
 */
final class HotUi {
	private static final int AGENT_PORT = Integer.getInteger("prismlab.agent.port", 27398);
	private static final java.util.Map<String, Integer> APPLIED = new java.util.HashMap<>();

	private static long lastCompile;
	private static Path mainOut;
	private static Path clientOut;
	private static String depsClasspath;

	private HotUi() {
	}

	/** Returns a loader that can provide fresh client-side classes. */
	static ClassLoader reload(Path root, long sourceStamp) throws Exception {
		prepare(root);
		if (sourceStamp != lastCompile) {
			compile(root);
			lastCompile = sourceStamp;
		}
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
		return redefined == 0 ? "no loaded main classes changed" : "redefined " + redefined + " main classes";
	}

	private static void prepare(Path root) throws Exception {
		if (mainOut == null) {
			mainOut = root.resolve(".modaccel/hot/main");
			clientOut = root.resolve(".modaccel/hot/client");
			Files.createDirectories(mainOut);
			Files.createDirectories(clientOut);
			depsClasspath = System.getProperty("java.class.path");
		}
	}

	private static void compile(Path root) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("No javac available; run the lab on a JDK");
		}
		List<Path> mainSources = sources(root.resolve("src/main/java"));
		List<Path> clientSources = sources(root.resolve("src/client/java"));

		StringBuilder deps = new StringBuilder();
		for (String entry : depsClasspath.split(File.pathSeparator)) {
			if (entry.contains("build" + File.separator + "classes") || entry.contains("build" + File.separator + "resources")) {
				continue;
			}
			if (!deps.isEmpty()) {
				deps.append(File.pathSeparator);
			}
			deps.append(entry);
		}

		compileInto(compiler, mainSources, mainOut, deps.toString());
		if (!clientSources.isEmpty()) {
			compileInto(compiler, clientSources, clientOut, deps + File.pathSeparator + mainOut);
		}
	}

	private static void compileInto(JavaCompiler compiler, List<Path> sources, Path out, String classpath) throws Exception {
		if (sources.isEmpty()) {
			return;
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			List<String> options = List.of("-d", out.toString(), "-cp", classpath + File.pathSeparator + out, "--release", "25", "-nowarn", "-proc:none");
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
	}

	private static List<Path> sources(Path base) throws Exception {
		if (!Files.isDirectory(base)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(base)) {
			return walk.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile).toList();
		}
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
			return "error: agent unreachable";
		}
	}

	/** Client classes come from the scratch directory; the control classes stay on the parent. */
	private static final class ClientFirst extends URLClassLoader {
		ClientFirst(URL url, ClassLoader parent) {
			super(new URL[] { url }, parent);
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


