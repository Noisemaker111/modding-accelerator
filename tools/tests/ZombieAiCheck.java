import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Zombie Apocalypse Addon's own effect on a zombie, checked in the real NeoForge 1.21.4 client through modaccel's eval:
 * a zombie summoned by a command gets the mod's AI goals and scaling tag, and hunts a pig three blocks away (a vanilla
 * zombie never targets a pig). Not in PR CI (it starts the game); run it locally on the ported checkout:
 *   java tools/tests/ZombieAiCheck.java <checkout> <modaccel.jar> <ZombieApocalypseAddon 1.21.4 checkout>
 * It then breaks the mod on purpose in a copy (EventHandler no longer injects its goals) and requires that run to fail.
 */
public final class ZombieAiCheck {
    record Run(int exit, String output, double seconds) { }
    static final String BREAK_FILE = "src/main/java/com/rique/zombieapocalypse/EventHandler.java",
            BREAK_FIND = "ZombieHunt.injectGoals(huntZombie);", BREAK_REPLACE = "/* broken on purpose */";
    /** spawn.java leaves this check in vars; the condition has no quotes, so it passes through any shell or command line. */
    static final String HUNTING = "((java.util.function.BooleanSupplier) vars.get(String.valueOf(new char[] {104, 117, 110, 116, 105, 110, 103}))).getAsBoolean()";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: java ZombieAiCheck.java <checkout> <modaccel.jar> <ZombieApocalypseAddon checkout>");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize(), root = Path.of(args[2]).toRealPath();
        Path snippets = home.resolve("tools/tests/za-ai");
        long start = System.nanoTime();
        String passed = check(home, jar, root, snippets);
        if (passed == null) throw new AssertionError("Zombie AI check failed on the ported mod; see the output above.");
        System.out.printf(Locale.ROOT, "PASS Zombie Apocalypse Addon AI (%.0fs): %s%n", (System.nanoTime() - start) / 1e9, passed);

        Path copy = Files.createTempDirectory(home.resolve(".runs"), "za-broken-").resolve("za");
        copyTree(root, copy);
        Path file = copy.resolve(BREAK_FILE);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int at = source.indexOf(BREAK_FIND);
        if (at < 0 || source.indexOf(BREAK_FIND, at + 1) >= 0) throw new AssertionError("Expected exactly one " + BREAK_FIND + " in " + file);
        Files.writeString(file, source.replace(BREAK_FIND, BREAK_REPLACE), StandardCharsets.UTF_8);
        start = System.nanoTime();
        String broken = check(home, jar, copy, snippets);
        if (broken != null) throw new AssertionError("The break (no AI goals injected) still passed: " + broken);
        System.out.printf(Locale.ROOT, "PASS Zombie Apocalypse Addon break \"no AI goals injected\" fails at the check (%.0fs)%n", (System.nanoTime() - start) / 1e9);
    }

    /** The PASS line on success, null when the mod's effects were missing; anything else going wrong throws. */
    static String check(Path home, Path jar, Path root, Path snippets) throws Exception {
        modaccel(home, jar, root, "game-stop"); // a client left from an older build would be refused
        try {
            // A never-launched copy's first launch writes files its own classpath names, and ui-check then refuses it as
            // "Project changed during verification" (a modaccel issue, seen on NeoForge). The second launch is the stable build.
            Run first = modaccel(home, jar, root, "ui-check");
            if (first.exit() != 0) {
                if (!first.output().contains("Project changed during verification")) throw new AssertionError("modaccel ui-check failed with exit " + first.exit() + ":\n" + first.output());
                System.out.println("  first launch of this copy was refused as \"Project changed during verification\"; launching again");
                modaccel(home, jar, root, "game-stop");
                step(home, jar, root, "ui-check");
            }
            if (!value(step(home, jar, root, "eval", "--code", "mc.level != null")).equals("true")) {
                step(home, jar, root, "eval", "--file", snippets.resolve("open-create.java").toString());
                step(home, jar, root, "eval", "--until", "mc.screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", "--timeout", "30");
                step(home, jar, root, "eval", "--file", snippets.resolve("press-create.java").toString());
                step(home, jar, root, "eval", "--until", "mc.player != null && mc.level != null && mc.screen == null && mc.getSingleplayerServer() != null", "--timeout", "120");
            }
            System.out.println("  " + value(step(home, jar, root, "eval", "--file", snippets.resolve("spawn.java").toString())));
            Run hunting = modaccel(home, jar, root, "eval", "--until", HUNTING, "--timeout", "30");
            if (hunting.exit() != 0) System.out.println("  the zombie targeted nothing within 30 s: " + between(hunting.output(), "\"error\":", "\\n"));
            Run check = modaccel(home, jar, root, "eval", "--file", snippets.resolve("check.java").toString());
            if (check.exit() == 0) return value(check);
            if (check.output().contains("effects are missing")) { System.out.println("  FAIL as expected: " + between(check.output(), "Zombie Apocalypse Addon's effects are missing", "\\n")); return null; }
            throw new AssertionError("The check did not run:\n" + check.output());
        } finally {
            modaccel(home, jar, root, "game-stop");
        }
    }
    static Run step(Path home, Path jar, Path root, String... args) throws Exception {
        Run run = modaccel(home, jar, root, args);
        if (run.exit() != 0) throw new AssertionError("modaccel " + String.join(" ", args) + " failed with exit " + run.exit() + ":\n" + run.output());
        return run;
    }
    static String value(Run run) { return between(run.output(), "\"value\":", ",\"").replaceAll("^\"|\"$", ""); }
    static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) return "";
        int b = text.indexOf(end, a + start.length());
        return text.substring(a + (start.endsWith(":") ? start.length() : 0), b < 0 ? text.length() : b);
    }
    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && Set.of(".git", ".gradle", "build", ".modaccel", ".runs", "run", "runs").contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    static Run modaccel(Path home, Path jar, Path root, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString()));
        command.addAll(List.of(args)); command.addAll(List.of("--root", root.toString(), "--json"));
        Path output = Files.createTempFile("modaccel-za-", ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw new AssertionError("Timed out: " + command); }
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8); Files.deleteIfExists(output);
        return new Run(process.exitValue(), text, (System.nanoTime() - start) / 1e9);
    }
}
