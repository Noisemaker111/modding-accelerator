import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A run reports what really ran and never leaves the project worse: test counts, no tests, one command at a time,
 * interrupted-port undo, read-only files.
 * From the stranger playthrough (an outside modder using modaccel on Diorama and F3Nope): a message that hid the
 * cause, or a pass that should have failed. Every case runs the real code path on real files (or real tool output)
 * and checks what a person would read.
 */
public final class ProjectSafetyTests {

    static int count;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("lock")) {
            lockChild(Path.of(args[1]));
            return;
        }
        if (args.length == 1 && args[0].equals("sleep")) {
            Thread.sleep(6000);
            return;
        }
        Path scratch = Files.createTempDirectory("modaccel stranger");
        try {
            testCounts(scratch);
            noTests(scratch);
            projectLock(scratch);
            interruptedPortUndo(scratch);
            readOnly(scratch);
            System.out.println(count + " project safety checks passed: test counts, no tests, one command at a time, interrupted-port undo, read-only files");
        } finally {
            for (Path p : walk(scratch)) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** fast printed raw counts; a failing test's name and message were only in the XML. */
    static void testCounts(Path scratch) throws Exception {
        Path project = scratch.resolve("counts"), results = project.resolve("build/modaccel/test-results/run1/test");
        write(
            results.resolve("TEST-dev.PinMathTest.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="dev.PinMathTest" tests="3" skipped="1" failures="1" errors="0" timestamp="2026-10-26T12:00:00" hostname="pc" time="0.02">
              <properties/>
              <testcase name="rounds()" classname="dev.PinMathTest" time="0.001"/>
              <testcase name="clamps()" classname="dev.PinMathTest" time="0.003">
                <failure message="expected: &lt;4&gt; but was: &lt;5&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;4&gt; but was: &lt;5&gt;</failure>
              </testcase>
              <testcase name="later()" classname="dev.PinMathTest" time="0.0"><skipped/></testcase>
            </testsuite>
            """
        );
        Map<String, Object> counts = TestResults.read(
            Json.map("projects", List.of(Json.map("directory", project.toString()))),
            "test",
            "run1"
        );
        String text = TestResults.text(counts);
        require(
            text.startsWith("1 of 2 tests failed:") &&
            text.contains("dev.PinMathTest.clamps(): expected: <4> but was: <5>") &&
            text.contains("(1 skipped)"),
            "a failing test is named with its message: " + text
        );
        require(
            TestResults.text(Json.map("executed", 1, "failed", 0, "skipped", 0)).equals("1 test passed"),
            "a pass is one plain line"
        );
    }

    /** "No successful logic check." when the real cause was: no tests, or none tagged pure. */
    static void noTests(Path scratch) throws Exception {
        Path tests = scratch.resolve("notests/src/test/java");
        Map<String, Object> model = Json.map(
            "projects",
            List.of(Json.map("sourceSets", List.of(Json.map("name", "test", "java", List.of(tests.toString())))))
        );
        require(
            FastLane.noTests(model, true).startsWith("No tests ran: this project has no test files"),
            "no test files is said plainly"
        );
        write(tests.resolve("dev/PinTest.java"), "class PinTest { @org.junit.jupiter.api.Test void t() {} }");
        String untagged = FastLane.noTests(model, true);
        require(
            untagged.contains("none is tagged @Tag(\"pure\")") && untagged.contains("modaccel test"),
            "untagged tests are named as the reason, with the way out: " + untagged
        );
        require(!FastLane.noTests(model, false).contains("pure"), "test (all tests) does not talk about pure tags");
    }

    /** Two commands at once broke each other with "previous-compilation-data.bin" errors. */
    static void projectLock(Path scratch) throws Exception {
        Path root = scratch.resolve("locked mod");
        write(root.resolve("settings.gradle"), "");
        ProjectModel project = new ProjectModel(root);
        try (ProjectLock held = ProjectLock.acquire(project, "compile")) {
            String other = child("lock", project.root.toString());
            require(
                other.contains("Another modaccel command (compile, process " + ProcessHandle.current().pid()) &&
                other.contains("this one did nothing"),
                "a second command is refused and names the first: " + other
            );
        }
        require(
            child("lock", project.root.toString()).contains("acquired"),
            "the lock is free once the first command ends"
        );
        // A game or Gradle daemon started by a command outlives it: it must not keep the project locked.
        Process sleeper;
        try (ProjectLock held = ProjectLock.acquire(project, "ui-check")) {
            sleeper = new ProcessBuilder(
                java(),
                "-cp",
                System.getProperty("java.class.path"),
                "ProjectSafetyTests",
                "sleep"
            ).start();
        }
        try {
            require(
                child("lock", project.root.toString()).contains("acquired"),
                "a process the command started does not inherit its lock"
            );
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor();
        }
    }

    static void lockChild(Path root) throws Exception {
        try (ProjectLock lock = ProjectLock.acquire(new ProjectModel(root), "fast")) {
            System.out.println("acquired");
        } catch (java.io.IOException refused) {
            System.out.println(refused.getMessage());
        }
    }

    /** A port killed part-way printed no undo id; --undo alone said "Missing value"; a second port stacked on the half-ported tree. */
    static void interruptedPortUndo(Path scratch) throws Exception {
        Path root = scratch.resolve("f3 nope");
        write(root.resolve("settings.gradle"), "");
        write(root.resolve("gradle.properties"), "minecraft_version=1.21.6\n");
        ProjectModel project = new ProjectModel(root);
        Path transaction = project.state().resolve("ports/2026-10-26T12-47-41.5Z-2bd1a0ab");
        write(transaction.resolve("before/gradle.properties"), "minecraft_version=1.21.5\n");
        Json.save(
            transaction.resolve("transaction.json"),
            Json.map(
                "root",
                project.root.toString(),
                "status",
                "in-progress",
                "before",
                Json.map(
                    "gradle.properties",
                    Fingerprints.bytes("minecraft_version=1.21.5\n".getBytes(StandardCharsets.UTF_8))
                ),
                "plan",
                Json.map("edits", List.of("gradle.properties"), "removals", List.of())
            )
        );
        String warning = Port.interruptedText(project);
        require(
            warning != null &&
            warning.contains("stopped part-way (2026-10-26 at 12:47:41 UTC)") &&
            warning.contains("gradle.properties"),
            "the next command warns about the half-finished port: " + warning
        );
        require(
            new Modaccel.Options(new String[] { "port", "--root", ".", "--undo" }).get("undo", "").equals("latest"),
            "--undo alone means the newest port"
        );
        Map<String, Object> undone = Port.undo(project, "latest");
        require(
            Boolean.TRUE.equals(undone.get("ok")) &&
            Files.readString(root.resolve("gradle.properties")).equals("minecraft_version=1.21.5\n"),
            "--undo latest finds the interrupted port and puts its files back: " + undone.get("details")
        );
        require(Port.interruptedText(project) == null, "an undone port warns no more");
    }

    /** A read-only gradle.properties stopped a port with only its path, and an "Undo this port" for a port that changed nothing. */
    static void readOnly(Path scratch) throws Exception {
        Path file = scratch.resolve("read only/gradle.properties");
        write(file, "minecraft_version=1.21.5\n");
        file.toFile().setReadOnly();
        // An administrator (root in a container) can write a read-only file, so there is nothing to refuse.
        if (Files.isWritable(file)) {
            System.out.println("SKIP a read-only file stops the port: this user can write read-only files");
            return;
        }
        try {
            Target.requireWritable(
                List.of(new Target.Edit(file, "minecraft_version=1.21.5\n", "minecraft_version=1.21.6\n"))
            );
            throw new AssertionError("a read-only file was accepted");
        } catch (java.io.IOException expected) {
            require(
                expected.getMessage().contains("read-only") && expected.getMessage().contains("Nothing was changed"),
                "a read-only file stops the port before any write: " + expected.getMessage()
            );
        }
        require(Files.readString(file).equals("minecraft_version=1.21.5\n"), "nothing was written");
        String plain = Modaccel.plainError(
            new java.io.IOException("Port failed", new java.nio.file.AccessDeniedException(file.toString()))
        );
        require(
            plain.startsWith("Cannot write " + file + ": access was denied"),
            "an access-denied error is a sentence, not a path: " + plain
        );
    }

    static String child(String... args) throws Exception {
        List<String> command = new ArrayList<>(
            List.of(java(), "-cp", System.getProperty("java.class.path"), "ProjectSafetyTests")
        );
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    static String java() {
        return ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static List<Path> walk(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.sorted(Comparator.reverseOrder()).toList();
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }
}
