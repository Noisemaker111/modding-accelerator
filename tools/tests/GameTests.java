import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Game sessions without a game: saved launches, quiet options and preferences, the visual and save gates, data-check verdicts and resource copies. */
public final class GameTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        Path existingArgs = scratch.resolve("existing loom args.txt");
        Files.writeString(existingArgs, "-Dmmt.launchProbe=existing-file\n");
        String classpath =
            System.getProperty("java.class.path") +
            (File.pathSeparator + scratch.resolve("nonexistent # ' path")).repeat(1100);
        List<String> launch = List.of(
            Path.of(System.getProperty("java.home"), "bin", Runtimes.WINDOWS ? "java.exe" : "java").toString(),
            "@" + existingArgs.toAbsolutePath(),
            "-cp",
            classpath,
            "GameTests$LaunchProbe",
            "@literal",
            "spaces and slash\\"
        );
        List<String> saved = Game.savedCommand(launch, scratch.resolve("saved classpath.args"));
        Process child = new ProcessBuilder(saved).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(
            child.waitFor() == 0 && output.contains("LAUNCH_OK"),
            "saved launches handle classpaths beyond Windows limits, existing @files and literal program arguments: " +
            output.strip()
        );
        check(
            saved.stream().mapToInt(String::length).sum() < 8000 && saved.contains(launch.get(1)),
            "saved launches keep existing argument files at the top level"
        );
        Map<String, Object> hiddenGames = Json.map("background", true, "quiet", true);
        check(
            Game.c1(hiddenGames, "ui-check") &&
            !Game.c1(hiddenGames, "perf") &&
            !Game.c1(Json.map("background", false, "quiet", true), "ui-check"),
            "hidden test sessions compile with C1 alone; perf, hidden or not, and visible games use the full JIT"
        );
        List<String> recordedHidden = List.of("-Xmx2G", "-XX:TieredStopAtLevel=1", "-Dmmt.token=old", "Main");
        check(
            Game.savedArguments(recordedHidden, true, false, false, "new").equals(List.of("-Xmx2G", "-Dmmt.token=new", "Main")) &&
            Game.savedArguments(List.of("-Xmx2G", "Main"), false, false, true, "new").equals(List.of("-XX:TieredStopAtLevel=1", "-Xmx2G", "Main")) &&
            Game.savedArguments(recordedHidden, false, false, false, "new").contains("-XX:TieredStopAtLevel=1"),
            "a saved launch takes this launch's compiler, not the recorded one, and keeps a build's own flag"
        );
        Path quietDir = scratch.resolve("quiet game");
        Files.createDirectories(quietDir);
        Files.writeString(
            quietDir.resolve("options.txt"),
            "version:5023\r\nnarrator:2\r\nguiScale:2\r\nsoundCategory_master:1.0\r\nonboardAccessibility:true\r\nkey_key.jump:key.keyboard.space\r\n"
        );
        QuietOptions.seedArgs(
            new String[] { "--username", "Dev", "--gameDir", quietDir.toAbsolutePath().toString(), "--assetsDir", "x" }
        );
        Path quietFile = quietDir.resolve("options.txt");
        List<String> quiet = Files.readAllLines(quietFile);
        check(
            quiet.containsAll(
                List.of(
                    "narrator:0",
                    "onboardAccessibility:false",
                    "soundCategory_master:0.0",
                    "tutorialStep:none",
                    "skipMultiplayerWarning:true",
                    "joinedFirstServer:true"
                )
            ),
            "seeded options.txt has no narrator, no sound, no tutorial or first-server prompts: " + quiet
        );
        check(
            quiet.containsAll(List.of("version:5023", "guiScale:2", "key_key.jump:key.keyboard.space")) &&
            quiet.indexOf("version:5023") == 0 &&
            quiet.size() == 9,
            "seeding options.txt keeps every other key and its order"
        );
        check(
            Files.readAllLines(quietDir.resolve(QuietOptions.SAVED)).equals(
                List.of(
                    "narrator:2",
                    "onboardAccessibility:true",
                    "soundCategory_master:1.0",
                    "tutorialStep",
                    "skipMultiplayerWarning",
                    "joinedFirstServer"
                )
            ),
            "a quiet launch keeps the options it replaces: " + Files.readAllLines(quietDir.resolve(QuietOptions.SAVED))
        );
        // The game rewrites options.txt; in the quiet session the user turns the narrator back on and changes the GUI scale.
        Files.writeString(
            quietFile,
            Files.readString(quietFile).replace("narrator:0", "narrator:1").replace("guiScale:2", "guiScale:3")
        );
        QuietOptions.launchArgs(new String[] { "--gameDir", quietDir.toAbsolutePath().toString() }, true);
        Files.writeString(quietFile, Files.readString(quietFile).replace("narrator:0", "narrator:1"));
        String[] quietDirArgs = new String[] { "--gameDir", quietDir.toAbsolutePath().toString() };
        QuietOptions.launchArgs(quietDirArgs, false);
        List<String> restored = Files.readAllLines(quietFile);
        check(
            restored.equals(
                List.of(
                    "version:5023",
                    "narrator:1",
                    "guiScale:3",
                    "soundCategory_master:1.0",
                    "onboardAccessibility:true",
                    "key_key.jump:key.keyboard.space"
                )
            ) && !Files.exists(quietDir.resolve(QuietOptions.SAVED)),
            "turning quiet off restores the sound and prompts the user had, keeps what they changed since and every other key: " +
            restored
        );
        QuietOptions.launchArgs(quietDirArgs, false);
        check(Files.readAllLines(quietFile).equals(restored), "a second launch that is not quiet changes nothing");
        Path freshQuiet = QuietOptions.seed(scratch.resolve("fresh quiet"));
        check(
            Files.readAllLines(freshQuiet).equals(
                List.of(
                    "narrator:0",
                    "onboardAccessibility:false",
                    "soundCategory_master:0.0",
                    "tutorialStep:none",
                    "skipMultiplayerWarning:true",
                    "joinedFirstServer:true"
                )
            ),
            "a first launch gets a quiet options.txt"
        );
        Files.writeString(freshQuiet, Files.readString(freshQuiet) + "fov:0.5\n");
        QuietOptions.restore(freshQuiet.getParent());
        check(
            Files.readAllLines(freshQuiet).equals(List.of("fov:0.5")),
            "options quiet added to a new game are removed for the game's defaults when quiet is off"
        );
        Path blockedQuiet = scratch.resolve("not a directory");
        Files.writeString(blockedQuiet, "file");
        try {
            QuietOptions.seedArgs(new String[] { "--gameDir", blockedQuiet.toAbsolutePath().toString() });
            throw new AssertionError("Minecraft must not start when its quiet options cannot be written");
        } catch (IllegalStateException expected) {
            check(
                expected.getMessage().contains("Cannot start Minecraft quietly"),
                "quiet setup reports why the game cannot start"
            );
        }
        Path prefs = scratch.resolve("user/preferences.json");
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")) &&
            !Files.exists(prefs),
            "a new user gets a normal window and a muted game without creating a preference file"
        );
        Preferences.change(
            prefs,
            new Mmt.Options(new String[] { "preferences", "--background", "on", "--quiet", "on" })
        );
        check(
            Boolean.TRUE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
            "personal background and quiet choices survive a reload"
        );
        Preferences.change(prefs, new Mmt.Options(new String[] { "preferences", "--background", "off" }));
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("background")) &&
            Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
            "each personal choice can change without changing the other"
        );
        try {
            Preferences.change(prefs, new Mmt.Options(new String[] { "preferences", "--quiet", "maybe" }));
            throw new AssertionError("invalid preference accepted");
        } catch (IllegalArgumentException expected) {
            check(
                Boolean.TRUE.equals(Preferences.read(prefs).get("quiet")),
                "an invalid choice leaves the saved preference intact"
            );
        }
        Preferences.change(prefs, new Mmt.Options(new String[] { "preferences", "--quiet", "off" }));
        check(
            Boolean.FALSE.equals(Preferences.read(prefs).get("quiet")),
            "a user can opt out of the muted game and the choice is kept"
        );
        // The old name's folder (~/.modaccel) is copied into ~/.mmt once; preferences keep their exact bytes.
        Path oldHome = scratch.resolve("old home/.modaccel"), newHome = scratch.resolve("old home/.mmt");
        byte[] saved = "{\n  \"background\": true,\n  \"quiet\": true\n}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(oldHome.resolve("jdks/temurin-21"));
        Files.write(oldHome.resolve("preferences.json"), saved);
        Files.write(oldHome.resolve("jdks/temurin-21/big.bin"), new byte[(int) UserState.LINK_FROM_BYTES + 7]);
        check(
            UserState.migrate(oldHome, newHome) != null &&
            Arrays.equals(Files.readAllBytes(newHome.resolve("preferences.json")), saved) &&
            Boolean.TRUE.equals(Preferences.read(newHome.resolve("preferences.json")).get("background")) &&
            Files.size(newHome.resolve("jdks/temurin-21/big.bin")) == UserState.LINK_FROM_BYTES + 7 &&
            Arrays.equals(Files.readAllBytes(oldHome.resolve("preferences.json")), saved) &&
            Files.isRegularFile(newHome.resolve(UserState.MARKER)),
            "the first run copies ~/.modaccel into ~/.mmt, preferences byte for byte, keeping the old folder"
        );
        Files.writeString(newHome.resolve("preferences.json"), "{\"background\": false, \"quiet\": false}");
        check(
            UserState.migrate(oldHome, newHome) == null &&
            Boolean.FALSE.equals(Preferences.read(newHome.resolve("preferences.json")).get("quiet")),
            "the copy happens once: a later choice in ~/.mmt is never overwritten by the old folder"
        );
        Path oldProject = Files.createDirectories(scratch.resolve("old project/.modaccel/portable"));
        UserState.migrateProject(scratch.resolve("old project"));
        check(
            Files.isDirectory(scratch.resolve("old project/.mmt/portable")) && !Files.exists(oldProject),
            "a project's .modaccel folder (its undo records) becomes .mmt"
        );
        Path dark = scratch.resolve("frame dark.png"), half = scratch.resolve("frame half.png");
        BufferedImage left = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), right = new BufferedImage(
            10,
            10,
            BufferedImage.TYPE_INT_RGB
        );
        for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) right.setRGB(x, y, 0xFFFFFF);
        ImageIO.write(left, "png", dark.toFile());
        ImageIO.write(right, "png", half.toFile());
        check(
            Boolean.TRUE.equals(Game.compare(dark, half, 40).get("passed")),
            "visual gate accepts a real frame change (50% vs 40% required)"
        );
        try {
            Game.compare(dark, half, 60);
            throw new AssertionError("small change accepted");
        } catch (IOException expected) {
            check(
                expected.getMessage().contains("no visible result"),
                "visual gate rejects an invisible result (50% vs 60% required)"
            );
        }
        try {
            Game.compare(dark, dark, 1);
            throw new AssertionError("identical frames accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("only 0.000%"), "identical frames are reported as no visible change");
        }
        Map<String, Object> positionA = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionB = Json.map(
            "x",
            1.5,
            "y",
            64.0,
            "z",
            -3.5
        ), positionC = Json.map("x", 1.56, "y", 64.0, "z", -3.5);
        check(Game.samePosition(positionA, positionB, 0.05), "save round-trip accepts an unchanged position");
        check(
            !Game.samePosition(positionA, positionC, 0.05),
            "save round-trip rejects a moved position beyond tolerance"
        );
        Map<String, Object> hidden = Json.map("background", true, "log", "game.log"), shown = Json.map(
            "background",
            false,
            "log",
            "game.log"
        );
        check(
            Game.hiddenWindowFailure(
                hidden,
                Json.map("backgroundApplied", "org/lwjgl/sdl/SDLVideo,org/lwjgl/sdl/SDLMouse", "backgroundError", "")
            ) ==
            null,
            "a hidden window that was rewritten lets the check run"
        );
        check(
            String.valueOf(
                Game.hiddenWindowFailure(
                    hidden,
                    Json.map(
                        "backgroundApplied",
                        "org/lwjgl/sdl/SDLVideo",
                        "backgroundError",
                        "Unsupported org/lwjgl/sdl/SDLMouse window controls"
                    )
                )
            ).contains("SDLMouse window controls"),
            "a failed rewrite stops the check and names the library"
        );
        check(
            String.valueOf(
                Game.hiddenWindowFailure(hidden, Json.map("backgroundApplied", "", "backgroundError", ""))
            ).contains("no window library was rewritten"),
            "nothing rewritten stops the check"
        );
        check(
            Game.hiddenWindowFailure(shown, Json.map("backgroundApplied", "", "backgroundError", "")) == null,
            "a visible window by choice is not a failure"
        );
        List<Map<String, Object>> loaded = new ArrayList<>(
            List.of(
                Json.map("event", "start"),
                Json.map("event", "mods", "ours", List.of("demo")),
                Json.map("event", "registered", "mod", "demo", "counts", Json.map("item", 1, "loot_table", 1)),
                Json.map(
                    "event",
                    "item",
                    "id",
                    "demo:gem",
                    "name",
                    "Gem",
                    "maxStack",
                    16,
                    "maxDamage",
                    0,
                    "rarity",
                    "common"
                ),
                Json.map("event", "done", "cpu", 17.0)
            )
        );
        var dataOk = DataCheck.verdict(loaded, 0, "log");
        check(
            Boolean.TRUE.equals(dataOk.get("ok")) &&
            String.valueOf(dataOk.get("details")).contains("demo:gem \"Gem\": stacks to 16"),
            "data-check passes a clean load and shows each item: " + dataOk.get("details")
        );
        loaded.add(
            3,
            Json.map("event", "log", "level", "ERROR", "logger", "Minecraft", "message", "Couldn't parse demo:broken")
        );
        check(
            !Boolean.TRUE.equals(DataCheck.verdict(loaded, 0, "log").get("ok")),
            "an error the game logged fails data-check"
        );
        var stopped = DataCheck.verdict(
            List.of(
                Json.map("event", "start"),
                Json.map(
                    "event",
                    "log",
                    "level",
                    "ERROR",
                    "logger",
                    "Minecraft",
                    "message",
                    "Errors in element demo:broken"
                ),
                Json.map("event", "error", "phase", "probe", "message", "Registry Loading")
            ),
            4,
            "log"
        );
        check(
            !Boolean.TRUE.equals(stopped.get("ok")) &&
            String.valueOf(stopped.get("details")).contains("Errors in element demo:broken"),
            "a load that stops shows what the game said first"
        );
        check(
            !Boolean.TRUE.equals(
                DataCheck.verdict(
                    List.of(
                        Json.map("event", "start"),
                        Json.map("event", "mods", "ours", List.of()),
                        Json.map("event", "done")
                    ),
                    0,
                    "log"
                ).get("ok")
            ),
            "nothing of the project loaded is not a pass"
        );
        var again = DataCheck.verdict(
            List.of(
                Json.map("event", "start", "cpu", 40.0, "first", false),
                Json.map("event", "mods", "ours", List.of("demo")),
                Json.map("event", "done", "cpu", 42.2)
            ),
            0,
            "log"
        );
        check(
            Double.valueOf(2.2).equals(again.get("cpuSeconds")) && Boolean.FALSE.equals(again.get("firstInJvm")),
            "a check in the loaded game counts only its own CPU: " + again.get("cpuSeconds")
        );
        // data-check's spare: it may take changed code only when nothing it has already read changed.
        Path out = scratch.resolve("spare-out");
        String initClass = out.resolve("demo/Demo.class").toString(), items = out
            .resolve("demo/Items.class")
            .toString(), mixin = out.resolve("demo/mixin/M.class").toString(), newMixin = out
            .resolve("demo/mixin/N.class")
            .toString(), mod = out.resolve("fabric.mod.json").toString();
        Map<String, Object> spare = Json.map(
            "files",
            Json.map(initClass, "a", items, "b", mixin, "c", mod, "d"),
            "early",
            List.of(mod),
            "mixinPackages",
            List.of(out.resolve("demo/mixin").toString())
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "A", items, "B", mixin, "c", mod, "d"),
                List.of()
            ).isEmpty(),
            "a spare takes changed classes it has not read yet"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "A", items, "b", mixin, "c", mod, "d"),
                List.of(items)
            ).isEmpty(),
            "a spare takes changed code when the classes it read are unchanged"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "a", items, "B", mixin, "c", mod, "d"),
                List.of(items)
            ).equals(List.of("Items.class")),
            "a spare refuses code when a class it has defined changed"
        );
        check(
            DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "C", mod, "d"), List.of()).equals(
                List.of("M.class")
            ),
            "a spare refuses code when a mixin class changed"
        );
        check(
            DataCheck.changedReads(
                spare,
                Map.of(initClass, "a", items, "b", mixin, "c", newMixin, "e", mod, "d"),
                List.of()
            ).equals(List.of("N.class")),
            "a spare refuses code when a mixin class appeared"
        );
        check(
            DataCheck.changedReads(spare, Map.of(initClass, "a", items, "b", mixin, "c", mod, "D"), List.of()).equals(
                List.of("fabric.mod.json")
            ),
            "a spare refuses code when loader metadata changed"
        );
        // A changed resource file Gradle copies as it is: copied without Gradle; anything else waits for Gradle.
        Path res = scratch.resolve("rc/src/main/resources"), built = scratch.resolve("rc/build/resources/main"), code =
            scratch.resolve("rc/src/main/java/A.java");
        Files.createDirectories(res.resolve("data"));
        Files.createDirectories(built.resolve("data"));
        Files.createDirectories(code.getParent());
        Files.writeString(res.resolve("data/r.json"), "{\"v\":1}");
        Files.writeString(built.resolve("data/r.json"), "{\"v\":1}");
        Files.writeString(res.resolve("data/gone.json"), "{}");
        Files.writeString(built.resolve("data/gone.json"), "{}");
        Files.writeString(res.resolve("fabric.mod.json"), "{\"version\":\"${version}\"}");
        Files.writeString(built.resolve("fabric.mod.json"), "{\"version\":\"1.0\"}");
        Map<String, Object> rcModel = Json.map(
            "projects",
            List.of(
                Json.map(
                    "sourceSets",
                    List.of(
                        Json.map(
                            "resources",
                            List.of(res.toString()),
                            "output",
                            List.of(scratch.resolve("rc/build/classes/java/main").toString(), built.toString())
                        )
                    )
                )
            )
        );
        List<List<String>> rcRoots = ResourceCopy.roots(rcModel);
        String r = res.resolve("data/r.json").toString(), gone = res.resolve("data/gone.json").toString(), meta = res
            .resolve("fabric.mod.json")
            .toString(), a = code.toString();
        Map<String, Object> rcRecord = Json.map(
            "inputs",
            Json.map(r, "1", gone, "2", meta, "3", a, "4"),
            "resourceRoots",
            rcRoots,
            "filtered",
            ResourceCopy.filtered(rcRoots)
        );
        check(
            rcRoots.equals(List.of(List.of(res.toString(), built.toString()))) &&
            ProjectModel.strings(rcRecord.get("filtered")).equals(List.of(meta)),
            "resource folders map to their output and filtered files are known: " +
            rcRoots +
            " " +
            rcRecord.get("filtered")
        );
        check(
            ResourceCopy.copy(Json.map("task", ":runClient"), Map.of(r, "9")) == -1,
            "a launch recorded without file hashes runs Gradle"
        );
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "3", a, "5")) == -1,
            "a changed class source runs Gradle"
        );
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "1", gone, "2", meta, "6", a, "4")) == -1,
            "a resource Gradle filtered runs Gradle"
        );
        Files.writeString(res.resolve("data/r.json"), "{\"v\":\"${v}\"}");
        check(
            ResourceCopy.copy(rcRecord, Map.of(r, "7", gone, "2", meta, "3", a, "4")) == -1,
            "a placeholder runs Gradle"
        );
        Files.writeString(res.resolve("data/r.json"), "{\"v\":2}");
        Files.delete(res.resolve("data/gone.json"));
        int copiedFiles = ResourceCopy.copy(rcRecord, Map.of(r, "8", gone, "missing", meta, "3", a, "4"));
        check(
            copiedFiles == 2 &&
            Files.readString(built.resolve("data/r.json")).equals("{\"v\":2}") &&
            !Files.exists(built.resolve("data/gone.json")),
            "changed and deleted data files are copied and removed without Gradle: " + copiedFiles
        );
        System.out.println(count + " game checks passed");
    }

    public static final class LaunchProbe {

        public static void main(String[] args) {
            if (
                !"existing-file".equals(System.getProperty("mmt.launchProbe")) ||
                !Arrays.equals(args, new String[] { "@literal", "spaces and slash\\" })
            ) throw new AssertionError(
                "launch arguments changed: " + System.getProperty("mmt.launchProbe") + " " + Arrays.toString(args)
            );
            System.out.println("LAUNCH_OK");
        }
    }
}
