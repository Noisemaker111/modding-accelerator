import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real FastLane gate. Two modules: a cached pure run must never hide a class with the same name in another module.
 * A plain project (no game on the test classpath): every test runs untagged, and an empty run says why.
 * Then the pinned server plugins in bench/server-plugins/manifest.json (ServerPluginFixtures, same directory).
 */
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
        plain(home, jar, runs);
        ServerPluginFixtures.run(home, jar);
    }

    /** No Minecraft on the test classpath: untagged tests run on the Gradle and the reuse path; zero tests is explained. */
    static void plain(Path home, Path jar, Path runs) throws Exception {
        Path root = Files.createTempDirectory(runs, "fastlane-plain-");
        Path wrapper = root.resolve("gradle/wrapper"); Files.createDirectories(wrapper);
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"));
        Files.copy(home.resolve("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fastlane-plain'\n");
        Files.writeString(root.resolve("build.gradle"), """
                apply plugin: 'java'
                repositories { mavenCentral() }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                tasks.withType(Test).configureEach { useJUnitPlatform() }
                """);
        Path test = root.resolve("src/test/java/plain/PlainTest.java"); Files.createDirectories(test.getParent());
        Files.writeString(test, "package plain; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; "
                + "class PlainTest { @Test void one() { assertEquals(2, 1 + 1); } @Test void two() { assertTrue(true); } }\n");
        Run cold = run(home, jar, root); require(cold, 0, 2, 0, "plain project, untagged tests, cold");
        if (!cold.output().contains("no Minecraft or loader on the test classpath")) throw new AssertionError("plain project did not select every test\n" + cold.output());
        require(run(home, jar, root), 0, 2, 0, "plain project, untagged tests, warm");
        Files.writeString(test, "package plain; class PlainTest { }\n");
        Run empty = run(home, jar, root);
        if (empty.exit() != 1 || !empty.output().contains("Check that they are JUnit 5 tests") || empty.output().contains("No successful logic check"))
            throw new AssertionError("an empty run must say why; got exit " + empty.exit() + "\n" + empty.output());
        System.out.println("PASS plain project with no tests says so instead of a Gradle tail");
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
