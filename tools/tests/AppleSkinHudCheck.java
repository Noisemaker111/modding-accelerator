import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AppleSkin's own HUD value, checked in the real client through modaccel's eval: the health AppleSkin estimates a
 * golden carrot will restore (the flashing hearts its HUD draws) must equal what the game's natural regeneration
 * really heals once the player eats it. Not in PR CI (it starts the game); run it locally:
 *   java tools/tests/AppleSkinHudCheck.java <checkout> <modaccel.jar> <AppleSkin Fabric 1.21.6 checkout>
 * It then breaks AppleSkin on purpose in a copy (its slow-regeneration estimate heals 1.5 instead of 1) and requires
 * that run to fail at the comparison. Both runs stop their client at the end.
 */
public final class AppleSkinHudCheck {
    record Run(int exit, String output, double seconds) { }
    static final String BREAK_FILE = "java/squeek/appleskin/helpers/FoodHelper.java", BREAK_FIND = "health += 1;", BREAK_REPLACE = "health += 1.5F;";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: java AppleSkinHudCheck.java <checkout> <modaccel.jar> <AppleSkin checkout>");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize(), root = Path.of(args[2]).toRealPath();
        Path snippets = home.resolve("tools/tests/appleskin-hud");

        long start = System.nanoTime();
        String passed = check(home, jar, root, snippets);
        if (passed == null) throw new AssertionError("AppleSkin HUD check failed on the untouched mod; see the output above.");
        System.out.printf(Locale.ROOT, "PASS AppleSkin HUD (%.0fs): %s%n", (System.nanoTime() - start) / 1e9, passed);

        Path copy = Files.createTempDirectory(home.resolve(".runs"), "appleskin-broken-").resolve("appleskin");
        copyTree(root, copy);
        Path file = copy.resolve(BREAK_FILE);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        int at = source.indexOf(BREAK_FIND);
        if (at < 0 || source.indexOf(BREAK_FIND, at + 1) >= 0) throw new AssertionError("Expected exactly one " + BREAK_FIND + " in " + file);
        Files.writeString(file, source.replace(BREAK_FIND, BREAK_REPLACE), StandardCharsets.UTF_8);
        start = System.nanoTime();
        String broken = check(home, jar, copy, snippets);
        if (broken != null) throw new AssertionError("The break (slow regeneration estimated as +1.5) still passed: " + broken);
        System.out.printf(Locale.ROOT, "PASS AppleSkin HUD break \"slow regeneration estimated as +1.5 health\" fails at the comparison (%.0fs)%n", (System.nanoTime() - start) / 1e9);
    }

    /** The PASS line on success, null when the comparison failed; anything else going wrong throws. */
    static String check(Path home, Path jar, Path root, Path snippets) throws Exception {
        modaccel(home, jar, root, "game-stop"); // a client left from an older build would be refused
        try {
            step(home, jar, root, "ui-check");
            if (!value(step(home, jar, root, "eval", "--code", "mc.world != null")).equals("true")) {
                step(home, jar, root, "eval", "--file", snippets.resolve("open-create.java").toString());
                step(home, jar, root, "eval", "--until", "mc.currentScreen instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen", "--timeout", "30");
                step(home, jar, root, "eval", "--file", snippets.resolve("press-create.java").toString());
                step(home, jar, root, "eval", "--until", "mc.player != null && mc.world != null && mc.currentScreen == null", "--timeout", "120");
            }
            step(home, jar, root, "eval", "--file", snippets.resolve("setup.java").toString());
            step(home, jar, root, "eval", "--until", "mc.player.getHealth() == 2.0F && mc.player.getHungerManager().getFoodLevel() == 17 && mc.player.getHungerManager().getSaturationLevel() == 0.0F", "--timeout", "20");
            System.out.println("  " + value(step(home, jar, root, "eval", "--file", snippets.resolve("estimate.java").toString())));
            step(home, jar, root, "eval", "--until", "mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid()).getHungerManager().getFoodLevel() < 18", "--timeout", "90");
            Run measure = modaccel(home, jar, root, "eval", "--file", snippets.resolve("measure.java").toString());
            if (measure.exit() == 0) return value(measure);
            if (measure.output().contains("AppleSkin's estimate is wrong")) { System.out.println("  FAIL as expected: " + between(measure.output(), "AppleSkin's estimate is wrong", "\"")); return null; }
            throw new AssertionError("The comparison did not run:\n" + measure.output());
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
                if (rel.getNameCount() > 0 && Set.of(".git", ".gradle", "build", ".modaccel", ".runs", "run").contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    static Run modaccel(Path home, Path jar, Path root, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString()));
        command.addAll(List.of(args)); command.addAll(List.of("--root", root.toString(), "--json"));
        Path output = Files.createTempFile("modaccel-appleskin-", ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw new AssertionError("Timed out: " + command); }
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8); Files.deleteIfExists(output);
        return new Run(process.exitValue(), text, (System.nanoTime() - start) / 1e9);
    }
}
