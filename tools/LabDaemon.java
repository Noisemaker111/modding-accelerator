import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * The resident fast lane for the mod lab.
 *
 * <p>One long-lived JVM that compiles the mod's own sources with javac and runs the
 * tests tagged "pure" - no Gradle, no Minecraft bootstrap. A save-to-answer cycle is
 * about a second instead of a fifteen second Gradle plus bootstrap run.
 *
 * <p>Usage: java LabDaemon <projectRoot> <classpathFile> <port>
 * Protocol: connect, send one line of JSON like {"cmd":"run"} and read one JSON line back.
 */
public final class LabDaemon {
	private static final String PURE_TAG = "pure";

	private final Path root;
	private final Path mainSrc;
	private final Path clientSrc;
	private final Path testSrc;
	private final Path outDir;
	private final String classpath;
	private final List<String> excludes;

	private LabDaemon(Path root, String classpath, List<String> excludes) {
		this.excludes = excludes;
		this.root = root;
		this.mainSrc = root.resolve("src/main/java");
		this.clientSrc = root.resolve("src/client/java");
		this.testSrc = root.resolve("src/test/java");
		this.outDir = root.resolve(".lab/classes");
		this.classpath = classpath;
	}

	public static void main(String[] args) throws Exception {
		Path root = Paths.get(args[0]).toAbsolutePath().normalize();
		String classpath = Files.readString(Paths.get(args[1]), StandardCharsets.UTF_8).trim();
		int port = Integer.parseInt(args[2]);
		// Optional: one excluded source path fragment per line (a Stonecutter node excludes its
		// other loader's sources, and compiling them here would fail for the wrong reason).
		List<String> excludes = new ArrayList<>();
		if (args.length > 3 && Files.exists(Paths.get(args[3]))) {
			for (String line : Files.readAllLines(Paths.get(args[3]), StandardCharsets.UTF_8)) {
				String trimmed = line.strip();
				if (!trimmed.isEmpty()) {
					excludes.add(trimmed.replace('\\', '/').toLowerCase(Locale.ROOT));
				}
			}
		}

		LabDaemon daemon = new LabDaemon(root, classpath, excludes);
		daemon.compile(log -> { });
		try (ServerSocket server = new ServerSocket(port, 16, InetAddress.getLoopbackAddress())) {
			System.out.println("READY " + port);
			System.out.flush();
			while (true) {
				try (Socket socket = server.accept();
						BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
						PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
					String line = in.readLine();
					if (line == null) {
						continue;
					}
					if (line.contains("\"stop\"")) {
						out.println("{\"ok\":true,\"stopped\":true}");
						return;
					}
					out.println(daemon.runOnce());
				} catch (IOException e) {
					// one bad client must not kill the daemon
				}
			}
		}
	}

	private String runOnce() {
		long started = System.nanoTime();
		List<String> errors = compile(log -> { });
		if (!errors.isEmpty()) {
			return json(false, 0, 0, 0, errors, "");
		}
		long compiled = System.nanoTime();
		try {
			SummaryGeneratingListener listener = new SummaryGeneratingListener();
			LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
					.selectors(DiscoverySelectors.selectClasspathRoots(java.util.Set.of(outDir)))
					.filters(org.junit.platform.launcher.TagFilter.includeTags(PURE_TAG))
					.build();
			Thread thread = Thread.currentThread();
			ClassLoader previous = thread.getContextClassLoader();
			long done;
			TestExecutionSummary summary;
			// A fresh loader per run: javac wrote new .class files, and classes the daemon
			// already loaded once must not be reused or the answer would be stale.
			try (java.net.URLClassLoader lane = new java.net.URLClassLoader(
					new java.net.URL[] { outDir.toUri().toURL() }, previous)) {
				thread.setContextClassLoader(lane);
				Launcher launcher = LauncherFactory.create();
				launcher.execute(request, listener);
				summary = listener.getSummary();
				done = System.nanoTime();
			} finally {
				thread.setContextClassLoader(previous);
			}
			List<String> failures = new ArrayList<>();
			summary.getFailures().forEach(f -> failures.add(f.getTestIdentifier().getDisplayName() + ": " + firstLine(f.getException())));
			return json(summary.getTotalFailureCount() == 0, (int) summary.getTestsSucceededCount(), (int) summary.getTestsFailedCount(),
					(done - started) / 1_000_000, failures, "compile=" + (compiled - started) / 1_000_000 + "ms");
		} catch (Throwable t) {
			return json(false, 0, 1, (System.nanoTime() - started) / 1_000_000, List.of(firstLine(t)), "runner crashed");
		}
	}

	private interface Log {
		void line(String text);
	}

	private List<String> compile(Log log) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return List.of("No javac in this JVM; run with a JDK, not a JRE");
		}
		List<Path> sources = new ArrayList<>();
		collect(mainSrc, sources);
		collect(clientSrc, sources);
		collect(testSrc, sources);
		if (sources.isEmpty()) {
			return List.of("No sources under src/main/java, src/client/java or src/test/java");
		}
		// Incremental: a big repo (MineColonies: 2102 files, 22s) should not pay for a full compile
		// on every save. Compile only sources modified since the last successful run; javac pulls the
		// rest from the class output directory through -cp and -sourcepath.
		Path stampFile = root.resolve(".lab/last-compile.txt");
		long lastCompile = 0L;
		try {
			if (Files.exists(stampFile)) {
				lastCompile = Long.parseLong(Files.readString(stampFile, StandardCharsets.UTF_8).trim());
			}
		} catch (IOException | NumberFormatException ignored) {
			lastCompile = 0L;
		}
		boolean hasClasses = false;
		try (Stream<Path> walk = Files.walk(outDir)) {
			hasClasses = walk.anyMatch(p -> p.toString().endsWith(".class"));
		} catch (IOException ignored) {
			hasClasses = false;
		}
		List<Path> toCompile = sources;
		if (lastCompile > 0 && hasClasses) {
			List<Path> changed = new ArrayList<>();
			for (Path source : sources) {
				try {
					if (Files.getLastModifiedTime(source).toMillis() > lastCompile) {
						changed.add(source);
					}
				} catch (IOException ignored) {
					changed.add(source);
				}
			}
			if (changed.isEmpty()) {
				return List.of();
			}
			// A large change (branch switch, new module) is cheaper as a full compile.
			if (changed.size() < sources.size() / 3) {
				toCompile = changed;
			}
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			Files.createDirectories(outDir);
			List<String> options = List.of(
					"-d", outDir.toString(),
					"-cp", outDir + java.io.File.pathSeparator + classpath,
					"-sourcepath", mainSrc + java.io.File.pathSeparator + clientSrc + java.io.File.pathSeparator + testSrc,
					"--release", String.valueOf(Runtime.version().feature()),
					"-nowarn",
					"-proc:none");
			Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(toCompile);
			StringWriter other = new StringWriter();
			boolean ok = compiler.getTask(other, files, diagnostics, options, null, units).call();
			if (!ok) {
				List<String> out = new ArrayList<>();
				for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
					if (d.getKind() != Diagnostic.Kind.ERROR) {
						continue;
					}
					String where = d.getSource() == null ? "" : Paths.get(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + " ";
					out.add(where + d.getMessage(Locale.ROOT));
					if (out.size() >= 8) {
						break;
					}
				}
				if (out.isEmpty()) {
					out.add("javac failed: " + other.toString().strip());
				}
				return out;
			}
			try {
				Files.writeString(stampFile, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
			} catch (IOException ignored) {
			}
			return List.of();
		} catch (IOException e) {
			return List.of("compile I/O error: " + e.getMessage());
		}
	}

	private void collect(Path dir, List<Path> into) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.filter(p -> p.toString().endsWith(".java") && !isExcluded(p))
					.filter(Files::isRegularFile)
					.forEach(into::add);
		} catch (IOException e) {
			// treat as no sources
		}
	}

	private boolean isExcluded(Path path) {
		if (this.excludes == null || this.excludes.isEmpty()) {
			return false;
		}
		String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
		for (String exclude : this.excludes) {
			if (normalized.contains(exclude)) {
				return true;
			}
		}
		return false;
	}

	private static String firstLine(Throwable t) {
		if (t == null) {
			return "unknown failure";
		}
		String message = t.getMessage();
		String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return text.replace('\n', ' ').replace('\r', ' ');
	}

	private static String json(boolean ok, int passed, int failed, long ms, List<String> failures, String note) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"ok\":").append(ok)
				.append(",\"passed\":").append(passed)
				.append(",\"failed\":").append(failed)
				.append(",\"ms\":").append(ms)
				.append(",\"note\":\"").append(escape(note)).append("\"")
				.append(",\"failures\":[");
		for (int i = 0; i < failures.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(escape(failures.get(i))).append('"');
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}
}




