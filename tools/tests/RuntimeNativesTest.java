import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real Gradle gate for the macOS Forge nodes: a runtime-only file that no repository has (ForgeGradle
 * asks Maven Central for lwjgl-freetype natives-macos-patch on Apple silicon) must not stop setup or
 * a compile, and the model must still name the missing coordinate for whatever needs the runtime.
 */
public final class RuntimeNativesTest {
    record Run(int exit, String output) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java RuntimeNativesTest.java <checkout> <modaccel.jar>");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IOException("Missing package: " + jar);
        Path runs = home.resolve(".runs"); Files.createDirectories(runs);
        Path root = Files.createTempDirectory(runs, "runtime-natives-");
        Path wrapper = root.resolve("gradle/wrapper"); Files.createDirectories(wrapper);
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"));
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'runtime-natives'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies { runtimeOnly 'org.lwjgl:lwjgl-freetype:3.3.3:natives-macos-patch' }
                """);
        Path source = root.resolve("src/main/java/demo/Native.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Native { }\n");

        Run setup = run(home, jar, root, "setup");
        if (setup.exit() != 0) throw new AssertionError("setup stopped on a runtime-only file:\n" + setup.output());
        String model = Files.readString(root.resolve(".modaccel/portable/model.json"), StandardCharsets.UTF_8);
        if (!model.contains("\"runtimeUnresolved\"") || !model.contains("lwjgl-freetype-3.3.3-natives-macos-patch.jar"))
            throw new AssertionError("the model does not name the missing runtime file:\n" + model);
        if (!model.contains("lwjgl-3.3.3.jar"))
            throw new AssertionError("the runtime files that did resolve were dropped:\n" + model);
        System.out.println("PASS setup records the missing runtime file and keeps the rest");
        Run compile = run(home, jar, root, "compile");
        if (compile.exit() != 0 || !Files.isRegularFile(root.resolve("build/classes/java/main/demo/Native.class")))
            throw new AssertionError("compile did not build the class:\n" + compile.output());
        System.out.println("PASS compile builds past a runtime-only file; fixture " + root);
    }

    static Run run(Path home, Path jar, Path root, String verb) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path output = Files.createTempFile(root, verb + "-", ".log");
        Process process = new ProcessBuilder(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString(),
                verb, "--root", root.toString(), "--json")).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        if (!process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new AssertionError(verb + " timed out: " + root);
        }
        return new Run(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("windows"); }
}
