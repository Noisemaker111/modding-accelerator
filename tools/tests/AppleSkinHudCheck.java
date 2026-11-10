import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AppleSkin's own HUD value, checked in the real client through modaccel's eval: the health AppleSkin estimates a
 * golden carrot will restore (the flashing hearts its HUD draws) must equal what the game's natural regeneration
 * really heals once the player eats it. Not in PR CI (it starts the game); run it locally:
 *   java tools/tests/AppleSkinHudCheck.java <checkout> <modaccel.jar> <AppleSkin checkout>
 * The checkout is the Fabric 1.21.6 one (yarn names, snippets in appleskin-hud-yarn) or any Mojang-named one such as
 * the NeoForge 26.3 port (appleskin-hud-mojmap, written for 26.3); a yarn port of AppleSkin's 1.20.1 code, which keeps
 * its older FoodValues API, is read with estimate-foodvalues.java. It then breaks AppleSkin on purpose in the same
 * checkout (its slow-regeneration estimate heals 1.5 instead of 1), requires that run to fail at the comparison, and
 * puts the source back whatever happens. Both runs stop their client at the end.
 */
public final class AppleSkinHudCheck {

    record Run(int exit, String output, double seconds) {}

    /** The game names one checkout's snippets and wait conditions are written in. */
    record Names(String dir, String inWorld, String createOpen, String entered, String setUp, String foodDropped) {}

    static final Names YARN = new Names(
        "appleskin-hud-yarn",
        "mc.world != null",
        "mc.currentScreen instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen",
        "mc.player != null && mc.world != null && mc.currentScreen == null",
        "mc.player.getHealth() == 2.0F && mc.player.getHungerManager().getFoodLevel() == 17 && mc.player.getHungerManager().getSaturationLevel() == 0.0F",
        "mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid()).getHungerManager().getFoodLevel() < 18"
    );
    static final Names MOJMAP = new Names(
        "appleskin-hud-mojmap",
        "mc.level != null",
        "mc.gui.screen() instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
        "mc.player != null && mc.level != null && mc.gui.screen() == null && mc.getSingleplayerServer() != null",
        "mc.player.getHealth() == 2.0F && mc.player.getFoodData().getFoodLevel() == 17 && mc.player.getFoodData().getSaturationLevel() == 0.0F",
        "mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).getFoodData().getFoodLevel() < 18"
    );
    static final String BREAK_FILE = "java/squeek/appleskin/helpers/FoodHelper.java", BREAK_FIND =
        "health += 1;", BREAK_REPLACE = "health += 1.5F;";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
            "Usage: java AppleSkinHudCheck.java <checkout> <modaccel.jar> <AppleSkin checkout>"
        );
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize(), root = Path.of(
            args[2]
        ).toRealPath();
        Path properties = root.resolve("gradle.properties");
        Names names = Files.isRegularFile(properties) &&
            Files.readString(properties).matches("(?s).*\\byarn_mappings\\s*=.*")
            ? YARN
            : MOJMAP;
        System.out.println("  names: " + names.dir());

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
        String passed = check(home, jar, root, names);
        if (passed == null) throw new AssertionError(
            "AppleSkin HUD check failed on the untouched mod; see the output above."
        );
        System.out.printf(Locale.ROOT, "PASS AppleSkin HUD (%.0fs): %s%n", (System.nanoTime() - start) / 1e9, passed);

        // The break goes into the same checkout and is always put back: a copy would rebuild the game from nothing,
        // which for NeoForge (its NeoForm decompile) takes an hour, and the same build is the fairer comparison.
        Files.writeString(file, source.replace(BREAK_FIND, BREAK_REPLACE), StandardCharsets.UTF_8);
        String broken;
        start = System.nanoTime();
        try {
            broken = check(home, jar, root, names);
        } finally {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        }
        if (broken != null) throw new AssertionError(
            "The break (slow regeneration estimated as +1.5) still passed: " + broken
        );
        System.out.printf(
            Locale.ROOT,
            "PASS AppleSkin HUD break \"slow regeneration estimated as +1.5 health\" fails at the comparison (%.0fs)%n",
            (System.nanoTime() - start) / 1e9
        );
    }

    /** The PASS line on success, null when the comparison failed; anything else going wrong throws. */
    static String check(Path home, Path jar, Path root, Names names) throws Exception {
        Path snippets = home.resolve("tools/tests").resolve(names.dir());
        modaccel(home, jar, root, "game-stop"); // a client left from an older build would be refused
        try {
            step(home, jar, root, "ui-check");
            if (!value(step(home, jar, root, "eval", "--code", names.inWorld())).equals("true")) {
                step(home, jar, root, "eval", "--file", snippets.resolve("open-create.java").toString());
                step(home, jar, root, "eval", "--until", names.createOpen(), "--timeout", "30");
                step(home, jar, root, "eval", "--file", snippets.resolve("press-create.java").toString());
                step(home, jar, root, "eval", "--until", names.entered(), "--timeout", "120");
            }
            step(home, jar, root, "eval", "--file", snippets.resolve("setup.java").toString());
            step(home, jar, root, "eval", "--until", names.setUp(), "--timeout", "20");
            // A port of AppleSkin's 1.20.1 code keeps its older FoodValues API; the author's own later versions use ConsumableFood.
            String estimate = Files.readString(root.resolve(BREAK_FILE)).contains(
                    "FoodValues getModifiedFoodValues("
                ) &&
                Files.isRegularFile(snippets.resolve("estimate-foodvalues.java"))
                ? "estimate-foodvalues.java"
                : "estimate.java";
            System.out.println(
                "  " + value(step(home, jar, root, "eval", "--file", snippets.resolve(estimate).toString()))
            );
            step(home, jar, root, "eval", "--until", names.foodDropped(), "--timeout", "90");
            Run measure = modaccel(home, jar, root, "eval", "--file", snippets.resolve("measure.java").toString());
            if (measure.exit() == 0) return value(measure);
            if (measure.output().contains("AppleSkin's estimate is wrong")) {
                System.out.println(
                    "  FAIL as expected: " + between(measure.output(), "AppleSkin's estimate is wrong", "\"")
                );
                return null;
            }
            throw new AssertionError("The comparison did not run:\n" + measure.output());
        } finally {
            modaccel(home, jar, root, "game-stop");
        }
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
        Path output = Files.createTempFile("modaccel-appleskin-", ".log");
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
