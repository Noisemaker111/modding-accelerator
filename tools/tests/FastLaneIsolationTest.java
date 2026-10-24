import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real two-module gate: a cached pure run must never hide a class with the same name in another module. */
public final class FastLaneIsolationTest {
    record Run(int exit, String output) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java FastLaneIsolationTest.java <checkout> <modaccel.jar>");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IOException("Missing package: " + jar);
        Path runs = home.resolve(".runs"); Files.createDirectories(runs);
        Path root = Files.createTempDirectory(runs, "fastlane-isolation-");
        Path wrapper = root.resolve("gradle/wrapper"); Files.createDirectories(wrapper);
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"));
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fastlane-isolation'\ninclude 'a', 'b'\n");
        Files.writeString(root.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    repositories { mavenCentral() }
                    dependencies {
                        testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                    }
                    tasks.withType(Test).configureEach { useJUnitPlatform() }
                }
                """);
        Path a = root.resolve("a/src/test/java/dup/SameTest.java"), b = root.resolve("b/src/test/java/dup/SameTest.java");
        Files.createDirectories(a.getParent()); Files.createDirectories(b.getParent());
        Files.writeString(a, test("fromA", true)); Files.writeString(b, test("fromB", true));

        require(run(home, jar, root), 0, 2, 0, "cold, both modules");
        require(run(home, jar, root), 0, 2, 0, "warm, both modules");
        Files.writeString(b, test("fromB", false));
        require(run(home, jar, root), 1, 2, 1, "module B failure remains visible");
        Files.writeString(b, test("fromB", true));
        require(run(home, jar, root), 0, 2, 0, "restored module B");
        System.out.println("PASS FastLane module isolation; fixture " + root);
    }

    static String test(String name, boolean pass) {
        return "package dup; import org.junit.jupiter.api.Tag; import org.junit.jupiter.api.Test; "
                + "import static org.junit.jupiter.api.Assertions.*; class SameTest { "
                + "@Test @Tag(\"pure\") void " + name + "() { assertTrue(" + pass + "); } }\n";
    }

    static Run run(Path home, Path jar, Path root) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path output = Files.createTempFile(root, "fastlane-run-", ".log");
        Process process = new ProcessBuilder(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString(),
                "fast", "--root", root.toString(), "--json")).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        if (!process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new AssertionError("FastLane fixture timed out: " + root);
        }
        return new Run(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    static void require(Run run, int exit, int executed, int failed, String label) {
        String counts = "\"executed\":" + executed + ",\"failed\":" + failed;
        if (run.exit() != exit || !run.output().contains(counts))
            throw new AssertionError(label + ": expected exit " + exit + " and " + counts + "; got exit "
                    + run.exit() + "\n" + run.output());
        System.out.println("PASS " + label + ": " + executed + " executed, " + failed + " failed");
    }

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("windows"); }
}
