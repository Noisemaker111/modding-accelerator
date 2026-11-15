import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Every repair in pack-fixes.json passes its own test, a repair written wrong does not, and a rule reads only the
 * failure's own lines.
 * Needs no game and no network (mmt.pack.offline; mod sides are pre-cached).
 */
public final class PackRepairTests extends PackCheckFixtures {

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("mmt-pack");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("mmt.pack.offline", "true");
        try {
            List<String> repairs = PackRepairs.selfTest(harness, scratch.resolve("repairs"));
            rules(harness, scratch.resolve("rules"));
            wrongRepairs(harness, scratch.resolve("wrong"));
            expect(!repairs.isEmpty(), "every repair in pack-fixes.json passes its test: " + repairs);
            System.out.println(
                count +
                " pack repair checks passed: known faults and the repair tests (" + repairs + "), wrong repairs refused, the rules' limits"
            );
        } finally {
            System.setProperty("user.home", home);
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ a wrong repair is refused
    /**
     * A repair written wrong must not pass: its own test catches the wrong mod, a version that does not clear the fault
     * and a malformed hash; a download that does not match its recorded SHA-512 is refused; and a file a rule finds at
     * run time cannot stand in when it is for another loader or game version, or provides another mod (round 2 of the
     * sweep put LionfishAPI for NeoForge 1.21.1 into All the Mods 9, a Forge 1.20.1 pack).
     */
    static void wrongRepairs(Path harness, Path scratch) throws Exception {
        var fixes = PackRepairs.all(harness);
        Map<String, java.util.function.Consumer<Map<String, Object>>> wrong = new LinkedHashMap<>();
        wrong.put("removes nothing", fix -> Json.object(fix.get("action")).put("mod", "projectile_damage_typo"));
        wrong.put("still matches after its action", fix -> Json.object(Json.object(Json.object(fix.get("test")).get("added")).get("mods") instanceof List<?> l ? l.get(0) : Map.of()).put("version", "3.2.3+1.20.1"));
        wrong.put("is not a SHA-512", fix -> Json.object(fix.get("action")).put("sha512", "abc123"));
        for (var entry : wrong.entrySet()) {
            Path fake = scratch.resolve(entry.getKey().replace(' ', '-'));
            Files.createDirectories(fake.resolve("knowledge"));
            Files.copy(harness.resolve("knowledge/pack-loaders.json"), fake.resolve("knowledge/pack-loaders.json"));
            var broken = Json.object(Json.parse(Json.write(fixes.stream().filter(f -> f.get("id").equals("soulsweapons-pinned-projectile-damage")).findFirst().orElseThrow())));
            entry.getValue().accept(broken);
            Files.writeString(fake.resolve("knowledge/pack-fixes.json"), Json.write(Json.map("schema", 2, "fixes", List.of(broken))));
            String message;
            try {
                PackRepairs.selfTest(fake, fake.resolve("scratch"));
                message = "";
            } catch (AssertionError refused) {
                message = String.valueOf(refused.getMessage());
            }
            expect(message.contains(entry.getKey()), "a repair written wrong fails its test (" + entry.getKey() + "): " + message);
        }

        // A download that is not what its record says: served here, offline, by a local HTTP server.
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] served = "not the jar the repair names".getBytes(StandardCharsets.UTF_8);
        server.createContext("/fix.jar", exchange -> {
            exchange.sendResponseHeaders(200, served.length);
            exchange.getResponseBody().write(served);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/fix.jar";
            var action = Json.map("file", "fix.jar", "url", url, "sha512", "0".repeat(128));
            expect(
                throwsMessage(() -> PackRepairs.download(check(harness, scratch), action)).contains("does not match its recorded SHA-512") &&
                !Files.exists(PackCheck.receipts().resolve("fixes/fix.jar")),
                "a repair's download that does not match its SHA-512 is refused and not kept"
            );
            var file = Json.map("url", url, "filename", "fix.jar", "hashes", Json.map("sha512", PackCheck.hash("SHA-512", served)));
            expect(PackCheck.modrinthFile(PackCheck.receipts(), file) == null, "a fetched library comes from Modrinth's CDN only");
        } finally {
            server.stop(0);
        }

        // A file a rule finds at run time stands in only when it is a mod for this loader and game version that provides the id.
        Path mods = Files.createDirectories(scratch.resolve("candidates"));
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge");
        PackCheck.Mod cataclysm = PackRepairs.testJar(mods, Json.map("file", "cataclysm.jar", "mods", List.of(Json.map("id", "cataclysm", "version", "3.16", "depends", Json.map("lionfishapi", "[2.4,)")))), "META-INF/mods.toml");
        PackCheck.Pack pack = new PackCheck.Pack("t", "", "1.20.1", "forge", "47.4.0", List.of(cataclysm), List.of(scratch), scratch, true, "test");
        var facts = new PackRepairs.Facts(pack, PackMods.scan(List.of(cataclysm), forge.metadata("1.20.1")), forge, List.of(), null);
        Path neoforge = jar(mods.resolve("lionfishapi-3.1.jar"), Map.of("META-INF/neoforge.mods.toml", toml("lionfishapi", "3.1", "")));
        Path otherGame = jar(mods.resolve("lionfishapi-1.8.jar"), Map.of("META-INF/mods.toml", toml("lionfishapi", "1.8", "[[dependencies.lionfishapi]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.19.2]\"\nside=\"BOTH\"\n")));
        Path tooOld = jar(mods.resolve("lionfishapi-2.3.jar"), Map.of("META-INF/mods.toml", toml("lionfishapi", "2.3", "")));
        Path otherMod = jar(mods.resolve("lionfish-addon.jar"), Map.of("META-INF/mods.toml", toml("lionfishaddon", "2.7", "")));
        Path right = jar(mods.resolve("lionfishapi-2.7.jar"), Map.of("META-INF/mods.toml", toml("lionfishapi", "2.7", "")));
        expect(
            PackRepairs.fits(neoforge, facts, "lionfishapi") != null &&
            PackRepairs.fits(otherGame, facts, "lionfishapi") != null &&
            PackRepairs.fits(tooOld, facts, "lionfishapi") != null &&
            PackRepairs.fits(otherMod, facts, "lionfishapi") != null &&
            PackRepairs.fits(right, facts, "lionfishapi") == null,
            "only a Forge 1.20.1 jar of lionfishapi in the range cataclysm asks for stands in: " +
            List.of(PackRepairs.fits(neoforge, facts, "lionfishapi"), PackRepairs.fits(otherGame, facts, "lionfishapi"), PackRepairs.fits(tooOld, facts, "lionfishapi"), PackRepairs.fits(otherMod, facts, "lionfishapi"))
        );
    }

    // ------------------------------------------------------------------ the rules' limits
    /**
     * Create Astral (Fabric 1.18.2): KubeJS stopped the server because a script named a missing mod's effect, and Modrinth
     * calls KubeJS server-optional; its addons require it, so leaving it off the server is no repair. The failure lines a
     * rule reads leave out a crash report's mod table, so an id that only appears there is not "named".
     */
    static void rules(Path harness, Path scratch) throws Exception {
        PackLoaders.Loader fabric = PackLoaders.find(harness, "fabric");
        Path mods = Files.createDirectories(scratch.resolve("mods"));
        List<PackCheck.Mod> jars = List.of(
            PackRepairs.testJar(mods, Json.map("file", "kubejs.jar", "serverSide", "optional", "mods", List.of(Json.map("id", "kubejs", "version", "1802.5.5"))), "fabric.mod.json"),
            PackRepairs.testJar(mods, Json.map("file", "kubejs-create.jar", "mods", List.of(Json.map("id", "kubejs_create", "version", "1802.2.4", "depends", Json.map("kubejs", "*")))), "fabric.mod.json"),
            PackRepairs.testJar(mods, Json.map("file", "dh.jar", "serverSide", "optional", "mods", List.of(Json.map("id", "distanthorizons", "version", "2.0.1-a"))), "fabric.mod.json")
        );
        PackCheck.Pack pack = new PackCheck.Pack("t", "", "1.18.2", "fabric", "", jars, List.of(scratch), scratch, true, "test");
        PackMods.Scan scan = PackMods.scan(jars, fabric.metadata("1.18.2"));
        var rule = PackRepairs.all(harness).stream().filter(f -> f.get("id").equals("server-optional-mod-stops-server")).findFirst().orElseThrow();
        String kubejs = "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'kubejs' at 'dev.latvian.mods.kubejs.fabric.KubeJSFabric'!";
        expect(
            PackRepairs.applies(rule, new PackRepairs.Facts(pack, scan, fabric, List.of(), kubejs)).isEmpty(),
            "a server-optional mod other mods require is not left off the server"
        );
        String dh = "java.lang.RuntimeException: Could not execute entrypoint stage 'server' due to errors, provided by 'distanthorizons'!";
        expect(
            PackRepairs.applies(rule, new PackRepairs.Facts(pack, scan, fabric, List.of(), dh)).size() == 1,
            "a server-optional mod nothing requires is left off the server"
        );
        // A start whose only new lines repeat (Spark's warning each minute) is not making progress; its thread dump names the mod.
        Set<String> said = new HashSet<>();
        expect(
            PackCheck.newLines("[05:23:05] [spark-java-sampler-0-0/WARN]: Timed out waiting for world statistics\n", said) &&
            !PackCheck.newLines("[05:24:05] [spark-java-sampler-0-4/WARN]: Timed out waiting for world statistics\n", said) &&
            PackCheck.newLines("[05:24:06] [Server thread/INFO]: Preparing spawn area: 9%\n", said),
            "a line said again with another time is no progress: " + said
        );
        String report = PackCheck.stuckReport(
            "\"Server thread\" #119 prio=8 waiting on condition\n   java.lang.Thread.State: TIMED_WAITING (parking)\n\tat net.minecraft.class_3215.getChunkBlocking(class_3215.java:2776)\n" +
            "\tat appeng.debug.ChunkLoaderBlockEntity.method_10996(ChunkLoaderBlockEntity.java:45)\n\n\"spark-java-sampler-0-0\" #50\n\tat me.lucko.spark.Foo.bar(Foo.java:1)\n"
        );
        PackMods.Scan stuck = new PackMods.Scan();
        stuck.providers.put("ae2", new ArrayList<>(List.of(new PackMods.Provider("appliedenergistics2.jar", "11", false, "fabric.mod.json"))));
        stuck.providers.put("spark", new ArrayList<>(List.of(new PackMods.Provider("spark.jar", "1", false, "fabric.mod.json"))));
        stuck.packages.put("appeng.debug", "appliedenergistics2.jar");
        stuck.packages.put("me.lucko.spark", "spark.jar");
        var named = PackChecks.suspects("", List.of(report), stuck, fabric);
        expect(
            report.contains("stuck in \"Server thread\"") && !report.contains("spark") && named.stream().map(s -> s.get("mod")).toList().equals(List.of("ae2")),
            "a stuck start's thread dump names the mod holding the server thread: " + named + "\n" + report
        );
        // Enigmatica 6 and Prominence 2: an item that puts down another mod's block of the same name is a pack unifying it.
        List<Object> found = new ArrayList<>();
        var placed = new PackChecks.Context(pack, scan, fabric, found);
        placed.start = Json.map(
            "opened",
            true,
            "probe",
            Json.map(
                "different",
                List.of(
                    Json.map("item", "modern_industrialization:tin_ore", "expected", "modern_industrialization:tin_ore", "landed", "techreborn:tin_ore"),
                    Json.map("item", "powah:energy_cable_basic", "expected", "powah:energy_cable_basic", "landed", "techreborn:glassfiber_cable"),
                    Json.map("item", "supplementaries:tater_in_a_jar", "expected", "supplementaries:tater_in_a_jar", "landed", "quark:tiny_potato", "related", true)
                )
            )
        );
        PackChecks.placements(placed);
        expect(
            found.size() == 3 && "note".equals(Json.object(found.get(0)).get("severity")) && "problem".equals(Json.object(found.get(1)).get("severity")) &&
            "note".equals(Json.object(found.get(2)).get("severity")),
            "a same-named block from another mod, or the block the item's own block is built on, is a note; another block a problem: " + found
        );
        String failing = PackRepairs.failing(
            "[main/ERROR]: Failed to start\n| vinery | 1.1.4 | Vinery |\n - Mod 'AstralVinery' (astralvinery) 1.2.0 requires version 1.1.4 of vinery, which is missing!\n[main/INFO]: Loading 200 mods: vinery"
        );
        expect(
            failing.contains("requires version 1.1.4 of vinery") && !failing.contains("| vinery |") && !failing.contains("Loading 200 mods"),
            "a rule reads the failure's own lines, not a mod table or the mod list: " + failing
        );
    }
}
