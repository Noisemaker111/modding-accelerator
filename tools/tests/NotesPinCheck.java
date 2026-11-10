import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Notes' (MattCzyr) own features, checked in the real NeoForge 1.21.6+ client through modaccel's eval: holding its key
 * opens its notes screen, a note saved through its own code is read back from disk with the same text, and once pinned
 * its own HUD layer (notes:pinned_note) draws that text. 1.21.6 drops any GUI text whose colour has no alpha byte, so a
 * port that left 0xffffff in the layer compiles, loads and draws nothing. Not in PR CI (it starts the game); run it
 * locally on the ported checkout:
 *   java tools/tests/NotesPinCheck.java <checkout> <modaccel.jar> <Notes 1.21.6 checkout>
 * It then breaks Notes on purpose in the same checkout (the pinned note is drawn with alpha 0), requires that run to fail
 * at the comparison, and puts the source back whatever happens.
 */
public final class NotesPinCheck {

    record Run(int exit, String output, double seconds) {}

    static final String BREAK_FILE = "src/main/java/com/chaosthedude/notes/gui/PinnedNoteLayer.java", BREAK_FIND =
        "renderX, renderY, 0xffffffff)", BREAK_REPLACE = "renderX, renderY, 0x00ffffff)";
    static final String MISSING = "Notes' effects are missing";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
            "Usage: java NotesPinCheck.java <checkout> <modaccel.jar> <Notes checkout>"
        );
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize(), root = Path.of(
            args[2]
        ).toRealPath();
        Path file = root.resolve(BREAK_FILE);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.contains(BREAK_REPLACE)) throw new AssertionError(
            file +
            " still holds this check's break (" +
            BREAK_REPLACE +
            ") from a run that was killed; put back " +
            BREAK_FIND +
            " and rerun."
        );
        int at = source.indexOf(BREAK_FIND);
        if (at < 0 || source.indexOf(BREAK_FIND, at + 1) >= 0) throw new AssertionError(
            "Expected exactly one " + BREAK_FIND + " in " + file
        );

        long start = System.nanoTime();
        String passed = check(home, jar, root);
        if (passed == null) throw new AssertionError("Notes check failed on the ported mod; see the output above.");
        System.out.printf(
            Locale.ROOT,
            "PASS Notes pinned note (%.0fs): %s%n",
            (System.nanoTime() - start) / 1e9,
            passed
        );

        // The break goes into the same checkout and is always put back, as in AppleSkinHudCheck: the same build is the
        // fairer comparison.
        Files.writeString(file, source.replace(BREAK_FIND, BREAK_REPLACE), StandardCharsets.UTF_8);
        String broken;
        start = System.nanoTime();
        try {
            broken = check(home, jar, root);
        } finally {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        }
        if (broken != null) throw new AssertionError(
            "The break (pinned note drawn with alpha 0) still passed: " + broken
        );
        System.out.printf(
            Locale.ROOT,
            "PASS Notes break \"pinned note drawn with alpha 0\" fails at the comparison (%.0fs)%n",
            (System.nanoTime() - start) / 1e9
        );
    }

    /** The PASS line on success, null when one of Notes' effects was missing; anything else going wrong throws. */
    static String check(Path home, Path jar, Path root) throws Exception {
        Path snippets = home.resolve("tools/tests/notes-pin"), world = home.resolve("tools/tests/za-ai");
        modaccel(home, jar, root, "game-stop"); // a client left from an older build would be refused
        try {
            step(home, jar, root, "ui-check");
            if (!value(step(home, jar, root, "eval", "--code", "mc.level != null")).equals("true")) {
                step(home, jar, root, "eval", "--file", world.resolve("open-create.java").toString());
                step(
                    home,
                    jar,
                    root,
                    "eval",
                    "--until",
                    "mc.screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
                    "--timeout",
                    "30"
                );
                step(home, jar, root, "eval", "--file", world.resolve("press-create.java").toString());
                step(
                    home,
                    jar,
                    root,
                    "eval",
                    "--until",
                    "mc.player != null && mc.level != null && mc.screen == null && mc.getSingleplayerServer() != null",
                    "--timeout",
                    "120"
                );
            }
            step(home, jar, root, "eval", "--file", snippets.resolve("press-key.java").toString());
            Run opened = modaccel(
                home,
                jar,
                root,
                "eval",
                "--until",
                "mc.screen instanceof com.chaosthedude.notes.gui.SelectNoteScreen",
                "--timeout",
                "15"
            );
            System.out.println(
                "  key released, screen was " +
                value(step(home, jar, root, "eval", "--file", snippets.resolve("release-key.java").toString()))
            );
            if (opened.exit() != 0) {
                System.out.println(
                    "  FAIL as expected: " + MISSING + ": holding its key did not open its notes screen"
                );
                return null;
            }
            Run saved = modaccel(home, jar, root, "eval", "--file", snippets.resolve("save-pin.java").toString());
            if (missing(saved)) return null;
            System.out.println("  saved and pinned " + value(saved));
            Run drawn = modaccel(home, jar, root, "eval", "--file", snippets.resolve("check.java").toString());
            return missing(drawn) ? null : value(drawn);
        } finally {
            modaccel(home, jar, root, "eval", "--file", snippets.resolve("clean-up.java").toString());
            modaccel(home, jar, root, "game-stop");
        }
    }

    /** True when the snippet failed on one of Notes' effects; throws when it did not run at all. */
    static boolean missing(Run run) {
        if (run.exit() == 0) return false;
        if (run.output().contains(MISSING)) {
            System.out.println("  FAIL as expected: " + between(run.output(), MISSING, "\\n"));
            return true;
        }
        throw new AssertionError("The check did not run:\n" + run.output());
    }

    static Run step(Path home, Path jar, Path root, String... args) throws Exception {
        Run run = modaccel(home, jar, root, args);
        if (run.exit() != 0) throw new AssertionError(
            "modaccel " + String.join(" ", args) + " failed with exit " + run.exit() + ":\n" + run.output()
        );
        return run;
    }

    static String value(Run run) {
        return between(run.output(), "\"value\":", ",\"").replaceAll("^\"|\"$", "");
    }

    static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) return "";
        int b = text.indexOf(end, a + start.length());
        return text.substring(a + (start.endsWith(":") ? start.length() : 0), b < 0 ? text.length() : b);
    }

    static Run modaccel(Path home, Path jar, Path root, String... args) throws Exception {
        String java = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java"
        ).toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString()));
        command.addAll(List.of(args));
        command.addAll(List.of("--root", root.toString(), "--json"));
        Path output = Files.createTempFile("modaccel-notes-", ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command)
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        // Longer than modaccel's own 20-minute game start limit, so its reason is reported rather than this kill.
        if (!process.waitFor(25, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + command);
        }
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        Files.deleteIfExists(output);
        return new Run(process.exitValue(), text, (System.nanoTime() - start) / 1e9);
    }
}
