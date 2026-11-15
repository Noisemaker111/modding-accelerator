import java.io.*;
import java.nio.file.*;
import java.util.*;

/** What commands report and choose without a game: help, server-check timings and plugins, results as JSON, fast's test lane, plugin-check's API line. */
public final class CommandTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    static Map<String, Object> testModel(String... runtime) {
        return Json.map(
            "projects",
            List.of(
                Json.map(
                    "sourceSets",
                    List.of(
                        Json.map("name", "main", "runtime", List.of()),
                        Json.map("name", "test", "runtime", List.of(runtime), "output", List.of())
                    )
                )
            )
        );
    }

    /** Which tests fast selects (empty-run wording is covered by ProjectSafetyTests.noTests and FastLaneIsolationTest.plain). */
    static void fastLaneChecks() throws Exception {
        String junit =
            "/home/u/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api/5.13.4/x/junit-jupiter-api-5.13.4.jar";
        check(
            FastLane.gameFree(
                testModel(
                    junit,
                    "/home/u/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.214-stable/x/paper-api-26.3.build.214-stable.jar"
                )
            ),
            "a Paper plugin's test classpath (paper-api, JUnit) is game-free, so fast runs every test"
        );
        check(FastLane.gameFree(testModel(junit)), "a plain Java test classpath is game-free");
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "C:\\Users\\u\\.gradle\\caches\\fabric-loom\\minecraftMaven\\net\\minecraft\\minecraft-merged-1.21.6\\minecraft-merged.jar",
                    "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\net.fabricmc\\fabric-loader\\0.16.14\\x\\fabric-loader-0.16.14.jar"
                )
            ),
            "a Fabric test classpath has the game, so fast keeps running only tests tagged pure"
        );
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "/p/build/neoForge/neoFormJoined/1.21.1/artifacts/neoforge-21.1.77.jar",
                    "/home/u/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.77/x/neoforge-21.1.77-universal.jar"
                )
            ),
            "a NeoForge test classpath has the game, so fast keeps running only tests tagged pure"
        );
        check(
            !FastLane.gameFree(
                testModel(
                    junit,
                    "/home/u/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0.jar"
                )
            ),
            "a Forge test classpath has the game"
        );
        check(!FastLane.gameFree(Json.map("projects", List.of())), "no test source set is never treated as game-free");
    }

    /** plugin-check picks MockBukkit by the plugin's own Paper API line, and refuses a line it has no MockBukkit for. */
    static void pluginCheckChecks(Path home) throws Exception {
        Map<String, Object> paper = Json.map(
            "projects",
            List.of(
                Json.map(
                    "compile",
                    List.of(
                        Json.map(
                            "classpath",
                            List.of(
                                "/home/u/.gradle/caches/modules-2/files-2.1/net.kyori/adventure-api/4.24.0/x/adventure-api-4.24.0.jar",
                                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\io.papermc.paper\\paper-api\\26.3.build.214-stable\\x\\paper-api-26.3.build.214-stable.jar"
                            )
                        )
                    )
                )
            )
        );
        var api = PluginCheck.api(paper);
        check(
            api != null && api.kind().equals("paper-api") && api.version().equals("26.3.build.214-stable"),
            "plugin-check reads the Paper API version from the compile class path: " + api
        );
        var spigot = PluginCheck.api(
            Json.map(
                "projects",
                List.of(
                    Json.map(
                        "compile",
                        List.of(
                            Json.map(
                                "classpath",
                                List.of(
                                    "/m2/org/spigotmc/spigot-api/1.21.4-R0.1-SNAPSHOT/spigot-api-1.21.4-R0.1-SNAPSHOT.jar"
                                )
                            )
                        )
                    )
                )
            )
        );
        check(
            spigot != null && spigot.kind().equals("spigot-api") && PluginCheck.line(spigot.version()).equals("1.21"),
            "a Spigot plugin is checked on the Paper line of its version: " + spigot
        );
        check(
            PluginCheck.api(testModel("/m2/net/fabricmc/fabric-loader-0.16.14.jar")) == null,
            "a Fabric mod has no server API, so plugin-check says it is not a plugin"
        );
        var r263 = PluginCheck.release(home, "26.3.build.214-stable");
        var r121 = PluginCheck.release(home, "1.21.10-R0.1-SNAPSHOT");
        check(
            r263 != null && r263.artifact().endsWith("mockbukkit-v26.3") && r263.java() == 25,
            "Paper 26.3 is checked on MockBukkit for 26.3: " + r263
        );
        check(
            r121 != null && r121.artifact().endsWith("mockbukkit-v1.21") && r121.java() == 21,
            "Paper 1.21.10 is checked on MockBukkit for 1.21: " + r121
        );
        check(
            PluginCheck.release(home, "1.20.6-R0.1-SNAPSHOT") == null,
            "a Paper line with no MockBukkit release in the table is not guessed (it is reported unchecked)"
        );
        String text = PluginCheck.text(
            Json.map(
                "failed",
                true,
                "plugin",
                Json.map("name", "X", "version", "1"),
                "server",
                Json.map("paperApi", "26.3"),
                "checks",
                List.of(
                    Json.map(
                        "name",
                        "commands",
                        "status",
                        "fail",
                        "details",
                        "/x has no handler.",
                        "file",
                        Path.of("/p/src/main/resources/plugin.yml").toAbsolutePath().toString(),
                        "line",
                        9
                    )
                ),
                "unchecked",
                List.of("uses NMS"),
                "warnings",
                List.of()
            ),
            Path.of("/p").toAbsolutePath()
        );
        check(
            text.startsWith("plugin-check FAILED for X 1") &&
            text.contains("FAIL commands: /x has no handler. (src/main/resources/plugin.yml:9)") &&
            text.contains("UNCHECKED uses NMS"),
            "a plugin-check failure names the check, the file relative to the project and the line: " + text
        );
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        List<String> unlisted = new ArrayList<>();
        for (String command : new TreeSet<>(Modaccel.COMMANDS)) {
            String word = "(?<![\\w-])" + java.util.regex.Pattern.quote(command) + "(?![\\w-])";
            boolean listed = java.util.regex.Pattern.compile(word).matcher(Modaccel.help()).find();
            if (!listed && !Modaccel.INTERNAL.contains(command)) unlisted.add(command);
        }
        check(unlisted.isEmpty(), "help lists every command modaccel runs; missing: " + unlisted);
        String restartTimes = ServerCheck.text(
            Json.map(
                "ok",
                true,
                "server",
                Json.map("minecraft", "26.3", "build", "1"),
                "bot",
                Json.map("version", "x"),
                "steps",
                List.of(
                    Json.map(
                        "line",
                        1,
                        "step",
                        "start-server",
                        "status",
                        "done",
                        "seconds",
                        10.0,
                        "serverStart",
                        Json.map("seconds", 9.0)
                    ),
                    Json.map(
                        "line",
                        2,
                        "step",
                        "restart-server",
                        "status",
                        "done",
                        "seconds",
                        6.0,
                        "serverRestarts",
                        List.of(Json.map("seconds", 5.0))
                    )
                ),
                "serverStopSeconds",
                1.0,
                "scenario",
                "fixture"
            ),
            scratch
        );
        check(
            restartTimes.contains("server start 14.0s, scenario 2.0s, stop 1.0s"),
            "server timings include every start and restart: " + restartTimes
        );
        Path pluginFolder = scratch.resolve("plugins, fixtures");
        Files.createDirectories(pluginFolder);
        Path pluginA = pluginFolder.resolve("a.jar"), pluginB = pluginFolder.resolve("b.jar");
        Files.writeString(pluginA, "fixture");
        Files.writeString(pluginB, "fixture");
        check(
            ServerCheck.pluginPaths(scratch, pluginFolder.toString()).size() == 2,
            "plugin folder names may contain commas"
        );
        check(
            ServerCheck.pluginPaths(scratch, pluginA + File.pathSeparator + pluginB).size() == 2,
            "individual absolute plugin jars, including commas in their paths, can be listed together"
        );
        Object json = Json.parse(
            Json.write(
                Json.map("unicode", "mod 🧱", "path", "C:\\mods with spaces\\test", "rows", List.of(1, true, "\n\""))
            )
        );
        check(
            Json.object(json).get("unicode").equals("mod 🧱"),
            "structured results preserve Unicode and quoted paths"
        );
        try {
            Json.parse("{\"ok\":true,\"ok\":false}");
            throw new AssertionError("duplicate accepted");
        } catch (IllegalArgumentException expected) {
            count++;
        }
        fastLaneChecks();
        pluginCheckChecks(home);
        System.out.println(count + " command checks passed");
    }
}
