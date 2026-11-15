import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * pack-check's parts that need no game, on jars built here, with no network (modaccel.pack.offline; mod sides are
 * pre-cached). Inputs: a CurseForge export unpacked once, a CurseForge app instance, a Modrinth .mrpack, a Prism
 * instance, the vanilla launcher's folder and a bare mods folder whose loader is read from its jars. Metadata: Forge's
 * mods.toml with a bundled jar, Fabric's fabric.mod.json and Quilt's quilt.mod.json. Checks: duplicates, missing and
 * wrong-version dependencies, game and loader version mismatches, declared conflicts, a mod for another loader, shared
 * look files and block names. Logs: the words a failed start is reported with, client-only mods, and the mod a crash
 * names, for Forge and Fabric. Loaders: every adapter in pack-loaders.json loads, picks its metadata by game version and
 * writes its launch arguments. Repairs: every entry in pack-fixes.json passes its own test.
 *
 * The Prehistoric World 2 case: Traverse bundles Biolith 1.0.1-alpha.1, whose saved climate list makes a world open
 * once and never again. The known fault must be found through the bundled copy, and must go away once a newer Biolith
 * sits in mods/, because Forge loads the highest version of a mod id wherever it is.
 */
public final class PackCheckTest {

    static int count;

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("modaccel-pack");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("modaccel.pack.offline", "true");
        try {
            forge(harness, scratch);
            inputs(harness, scratch);
            fabricAndQuilt(harness, scratch);
            logs(harness, scratch);
            culprits(harness, scratch);
            serverMods(harness, scratch);
            loaders(harness, scratch);
            List<String> repairs = PackRepairs.selfTest(harness, scratch.resolve("repairs"));
            expect(!repairs.isEmpty(), "every repair in pack-fixes.json passes its test: " + repairs);
            System.out.println(
                count +
                " pack-check checks passed: six pack inputs, Forge/Fabric/Quilt metadata with bundled mods, duplicate ids, missing and wrong-version mods," +
                " game and loader version mismatches, declared conflicts, mods for another loader, shared look files and block names, mod sides from" +
                " CurseForge and Modrinth, known faults and the repair tests (" + repairs + "), client-only mods and remembering them, which jars a server gets (client-side jars a kept mod requires, dependants of client-only ones), a library jar's bundled mods, the mod a crash names (only the failure's own lines; the Java build when the JDK lacks what the loader calls; only the mod reaching a client class), client-only code read from server entrypoints, the Java build a loader needs," +
                " failed-start words, four loader adapters, a port lost to the other server, a free voice chat port"
            );
        } finally {
            System.setProperty("user.home", home);
            try (var paths = Files.walk(scratch)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ Forge 1.20.1: the original case
    static void forge(Path harness, Path scratch) throws Exception {
        Path mods = Files.createDirectories(scratch.resolve("instance/mods"));
        Path biolithAlpha = jar(scratch.resolve("biolith-alpha.jar"), Map.of("META-INF/mods.toml", toml("biolith", "1.0.1-alpha.1", "")));
        jar(
            mods.resolve("traverse.jar"),
            Map.of(
                "META-INF/mods.toml",
                toml("traverse", "7.0.12", dependency("terrablender", "true", "[1,)") + dependency("minecraft", "true", "[1.20,1.21)")),
                "META-INF/jarjar/metadata.json",
                "{\"jars\":[{\"path\":\"META-INF/jarjar/biolith.jar\"}]}",
                "META-INF/jarjar/biolith.jar",
                biolithAlpha,
                "assets/minecraft/blockstates/oak_log.json",
                "{}",
                "assets/minecraft/models/item/oak_log.json",
                "{}",
                "assets/traverse/lang/en_us.json",
                "{\"block.traverse.fir_planks\":\"Fir Planks\",\"block.minecraft.banner.base.white\":\"Fully White Field\"}",
                "com/terraformersmc/traverse/Traverse.class",
                "x"
            )
        );
        jar(
            mods.resolve("logs-a.jar"),
            Map.of(
                "META-INF/mods.toml",
                toml("sameid", "1", dependency("traverse", "true", "[7,)") + dependency("minecraft", "true", "[1.21,)")),
                "assets/minecraft/blockstates/oak_log.json",
                "{}",
                "assets/minecraft/models/item/oak_log.json",
                "{}",
                "assets/sameid/lang/en_us.json",
                "{\"block.sameid.fir_planks\":\"Fir planks\",\"block.sameid.white\":\"Fully White Field\"}"
            )
        );
        jar(mods.resolve("logs-b.jar"), Map.of("META-INF/mods.toml", toml("sameid", "2", dependency("traverse", "true", "[8,)"))));
        jar(mods.resolve("sodium-fabric.jar"), Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"0.5\"}"));
        jar(mods.resolve("kotlin-stdlib.jar"), Map.of("kotlin/Unit.class", "x"));
        Files.writeString(scratch.resolve("instance/minecraftinstance.json"), "{\"gameVersion\":\"1.20.1\",\"baseModLoader\":{\"name\":\"forge-47.4.20\"}}");

        PackCheck check = check(harness, scratch);
        PackCheck.Pack pack = check.read(scratch.resolve("instance"));
        expect(
            pack.minecraft().equals("1.20.1") &&
            pack.loader().equals("forge") &&
            pack.loaderVersion().equals("47.4.20") &&
            pack.mods().size() == 5 &&
            pack.instance() &&
            pack.format().equals("curseforge-instance"),
            "a CurseForge app instance is read from minecraftinstance.json and mods/: " + pack
        );
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge");
        PackMods.Scan scan = statics(check, pack, forge);
        expect(
            scan.providers.get("biolith") != null && scan.providers.get("biolith").get(0).bundled() && scan.providers.get("biolith").get(0).version().equals("1.0.1-alpha.1"),
            "a mod bundled inside another jar is found with its version: " + scan.providers.get("biolith")
        );
        expect(kinds(check).contains("duplicate-mod") && text(check, "duplicate-mod").contains("sameid"), "one mod id in two jars is a problem: " + check.findings);
        expect(
            text(check, "missing-mod").contains("terrablender") && text(check, "missing-mod").contains("traverse.jar"),
            "a missing required mod names who needs it: " + check.findings
        );
        expect(
            !text(check, "missing-mod").contains("mod id traverse") && !text(check, "missing-mod").contains("minecraft"),
            "a required mod that is in the pack, or the game itself, is not missing: " + check.findings
        );
        expect(
            text(check, "dependency-version").contains("logs-b.jar needs traverse [8,)") && !text(check, "dependency-version").contains("logs-a.jar"),
            "a required mod in a version its dependant does not accept is a problem: " + check.findings
        );
        expect(
            text(check, "minecraft-mismatch").contains("logs-a.jar is for Minecraft [1.21,)") && !text(check, "minecraft-mismatch").contains("traverse"),
            "a mod for another game version is a problem: " + check.findings
        );
        expect(
            text(check, "loader-mismatch").contains("sodium-fabric.jar") && text(check, "loader-mismatch").contains("fabric.mod.json"),
            "a Fabric jar in a Forge pack is named: " + check.findings
        );
        expect(text(check, "not-a-mod").contains("kotlin-stdlib.jar"), "a jar with no loader metadata is noted: " + check.findings);
        expect(
            text(check, "same-block-file").contains("assets/minecraft/blockstates/oak_log.json") &&
            text(check, "same-block-file").contains("models/item/oak_log.json") &&
            count(check, "same-block-file") == 1,
            "look files two jars ship are one note for that pair: " + check.findings
        );
        expect(
            text(check, "same-block-name").contains("fir planks") &&
            text(check, "same-block-name").contains("traverse:fir_planks") &&
            text(check, "same-block-name").contains("sameid:fir_planks") &&
            !text(check, "same-block-name").contains("fully white field"),
            "one name on blocks from two mods is noted, and a lang key with dots is not another mod's block: " + check.findings
        );
        expect(
            scan.packages.get("com.terraformersmc.traverse").equals("traverse.jar"),
            "a class package is traced to its jar: " + scan.packages
        );
        var faults = PackRepairs.match(harness, pack, scan, check.findings);
        expect(
            faults.size() == 1 && "biolith-saved-climate-list".equals(faults.get(0).get("id")) && text(check, "known-fault").contains("1.0.1-alpha.1"),
            "the bundled Biolith alpha matches the proven repair: " + faults
        );

        // Forge picks the highest version: a newer standalone Biolith replaces the bundled one, so the fault is gone.
        jar(mods.resolve("biolith-forge-1.0.1-beta.1.jar"), Map.of("META-INF/mods.toml", toml("biolith", "1.0.1-beta.1", "")));
        PackCheck fixed = check(harness, scratch);
        PackCheck.Pack fixedPack = fixed.read(scratch.resolve("instance"));
        PackMods.Scan fixedScan = statics(fixed, fixedPack, forge);
        expect(
            PackRepairs.match(harness, fixedPack, fixedScan, fixed.findings).isEmpty() && !text(fixed, "duplicate-mod").contains("biolith"),
            "Biolith beta in mods/ clears the fault, and a bundled copy beside a standalone jar is not a duplicate: " + fixed.findings
        );
        PackCheck.Pack newer = new PackCheck.Pack("p", "", "1.21.1", "forge", "52", pack.mods(), pack.layers(), pack.input(), true, "x");
        expect(PackRepairs.match(harness, newer, scan, new ArrayList<>()).isEmpty(), "the repair is not offered for another game version");
    }

    // ------------------------------------------------------------------ the shapes a pack arrives in
    static void inputs(Path harness, Path scratch) throws Exception {
        Path modrinth = Files.createDirectories(scratch.resolve("home/.modaccel/packs/modrinth"));
        // A CurseForge app instance lists each mod's project and file; their sides come from the (pre-cached) file API. A jar added by hand stays unknown.
        Path app = scratch.resolve("app"), appMods = Files.createDirectories(app.resolve("mods"));
        jar(appMods.resolve("blur-forge-3.1.1.jar"), Map.of("META-INF/mods.toml", toml("blur", "3.1.1", "")));
        jar(appMods.resolve("traverse-forge-7.0.12.jar"), Map.of("META-INF/mods.toml", toml("traverse", "7.0.12", "")));
        jar(appMods.resolve("byhand.jar"), Map.of("META-INF/mods.toml", toml("byhand", "1", "")));
        Files.writeString(
            app.resolve("minecraftinstance.json"),
            "{\"gameVersion\":\"1.20.1\",\"baseModLoader\":{\"name\":\"forge-47.4.20\"},\"installedAddons\":[" +
            "{\"addonID\":11,\"fileNameOnDisk\":\"blur-forge-3.1.1.jar\",\"installedFile\":{\"id\":111}}," +
            "{\"addonID\":22,\"fileNameOnDisk\":\"traverse-forge-7.0.12.jar\",\"installedFile\":{\"id\":222}}]}"
        );
        for (String[] file : List.of(
            new String[] { "11-111", "blur-forge-3.1.1.jar", "\"1.20.1\",\"Forge\",\"Client\"" },
            new String[] { "22-222", "traverse-forge-7.0.12.jar", "\"1.20.1\",\"Forge\",\"Client\",\"Server\"" }
        )) {
            Path meta = Files.createDirectories(scratch.resolve("home/.modaccel/packs/curseforge/" + file[0])).resolve("file.json");
            Files.writeString(meta, "{\"data\":{\"fileName\":\"" + file[1] + "\",\"fileLength\":1,\"gameVersions\":[" + file[2] + "]}}");
        }
        jar(appMods.resolve("zoomify.jar"), Map.of("fabric.mod.json", "{\"id\":\"zoomify\",\"version\":\"2\"}"));
        Files.writeString(
            modrinth.resolve(PackCheck.sha1(appMods.resolve("zoomify.jar")) + ".json"),
            "{\"project\":\"w7ThoJFB\",\"slug\":\"zoomify\",\"client_side\":\"required\",\"server_side\":\"unsupported\"}"
        );
        PackCheck.Pack listed = check(harness, scratch).read(app);
        Map<String, String> sides = new TreeMap<>();
        for (PackCheck.Mod mod : listed.mods()) sides.put(mod.file(), mod.side());
        expect(
            sides.equals(Map.of("blur-forge-3.1.1.jar", "client", "traverse-forge-7.0.12.jar", "both", "zoomify.jar", "client", "byhand.jar", "unknown")),
            "every mod gets its sides from CurseForge or Modrinth: " + sides
        );

        // A CurseForge export is unpacked once, by its hash, and read from there on later runs.
        Path export = scratch.resolve("export.zip"), extra = jar(scratch.resolve("extra.jar"), Map.of("META-INF/mods.toml", toml("extra", "1", "")));
        zip(
            export,
            Map.of(
                "manifest.json",
                "{\"name\":\"P\",\"version\":\"1\",\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"forge-47.4.20\",\"primary\":true}]},\"files\":[],\"overrides\":\"overrides\"}",
                "overrides/mods/extra.jar",
                extra
            )
        );
        PackCheck.Pack unpacked = check(harness, scratch).read(export);
        Path unpackedAt = unpacked.overrides().getParent();
        expect(
            unpacked.mods().size() == 1 &&
            unpacked.format().equals("curseforge-export") &&
            unpackedAt.startsWith(scratch.resolve("home/.modaccel/packs/exports")) &&
            Files.isRegularFile(unpackedAt.resolve(".modaccel-unpacked")),
            "an export is unpacked into the cache: " + unpackedAt
        );
        Files.writeString(unpackedAt.resolve("seen-before.txt"), "x");
        PackCheck.Pack again = check(harness, scratch).read(export);
        expect(again.overrides().equals(unpacked.overrides()) && Files.isRegularFile(unpackedAt.resolve("seen-before.txt")), "the same export is not unpacked again");

        // A Modrinth .mrpack: files come by hash (here already in the cache), sides from env, configs from overrides then server-overrides.
        Path lithium = jar(scratch.resolve("lithium.jar"), Map.of("fabric.mod.json", "{\"id\":\"lithium\",\"version\":\"0.15\"}"));
        Path sodium = jar(scratch.resolve("sodium.jar"), Map.of("fabric.mod.json", "{\"id\":\"sodium\",\"version\":\"0.6\"}"));
        for (Path j : List.of(lithium, sodium)) {
            Path cached = Files.createDirectories(scratch.resolve("home/.modaccel/packs/modrinth-files").resolve(PackCheck.sha1(j))).resolve(j.getFileName());
            Files.copy(j, cached);
        }
        String index = Json.write(
            Json.map(
                "formatVersion",
                1,
                "game",
                "minecraft",
                "name",
                "Small",
                "versionId",
                "1.0",
                "dependencies",
                Json.map("minecraft", "1.21.1", "fabric-loader", "0.16.7"),
                "files",
                List.of(
                    Json.map("path", "mods/lithium.jar", "hashes", Json.map("sha1", PackCheck.sha1(lithium)), "downloads", List.of("https://cdn.modrinth.com/x/lithium.jar")),
                    Json.map(
                        "path",
                        "mods/sodium.jar",
                        "hashes",
                        Json.map("sha1", PackCheck.sha1(sodium)),
                        "env",
                        Json.map("client", "required", "server", "unsupported"),
                        "downloads",
                        List.of("https://cdn.modrinth.com/x/sodium.jar")
                    ),
                    Json.map("path", "resourcepacks/look.zip", "hashes", Json.map("sha1", "0"), "downloads", List.of())
                )
            )
        );
        Files.writeString(modrinth.resolve(PackCheck.sha1(lithium) + ".json"), "{\"project\":\"\"}");
        Path mrpack = scratch.resolve("small.mrpack");
        zip(mrpack, Map.of("modrinth.index.json", index, "overrides/config/a.toml", "a=1", "server-overrides/config/b.toml", "b=1"));
        PackCheck.Pack small = check(harness, scratch).read(mrpack);
        Map<String, String> mrSides = new TreeMap<>();
        for (PackCheck.Mod mod : small.mods()) mrSides.put(mod.file(), mod.side());
        expect(
            small.format().equals("modrinth-mrpack") &&
            small.loader().equals("fabric") &&
            small.loaderVersion().equals("0.16.7") &&
            small.minecraft().equals("1.21.1") &&
            small.name().equals("Small") &&
            mrSides.equals(Map.of("lithium.jar", "unknown", "sodium.jar", "client")) &&
            small.layers().size() == 2 &&
            small.layers().get(1).getFileName().toString().equals("server-overrides"),
            "an .mrpack is read from modrinth.index.json, its sides from env and its layers in order: " + small + " " + mrSides
        );
        Path bad = scratch.resolve("bad.mrpack");
        zip(
            bad,
            Map.of(
                "modrinth.index.json",
                index.replace("https://cdn.modrinth.com/x/lithium.jar", "https://evil.example/lithium.jar").replace(PackCheck.sha1(lithium), "1".repeat(40))
            )
        );
        expect(throwsMessage(() -> check(harness, scratch).read(bad)).contains("downloads only from"), "an .mrpack download from another host is refused");

        // A Prism instance: mmc-pack.json names the components; the game folder holds mods/.
        Path prism = scratch.resolve("prism"), prismMods = Files.createDirectories(prism.resolve(".minecraft/mods"));
        Files.copy(lithium, prismMods.resolve("lithium.jar"));
        Files.writeString(
            prism.resolve("mmc-pack.json"),
            "{\"components\":[{\"uid\":\"org.lwjgl3\",\"version\":\"3.3.3\"},{\"uid\":\"net.minecraft\",\"version\":\"1.21.1\"},{\"uid\":\"net.neoforged\",\"version\":\"21.1.77\"}]}"
        );
        PackCheck.Pack fromPrism = check(harness, scratch).read(prism);
        expect(
            fromPrism.format().equals("prism-instance") &&
            fromPrism.loader().equals("neoforge") &&
            fromPrism.loaderVersion().equals("21.1.77") &&
            fromPrism.overrides().getFileName().toString().equals(".minecraft") &&
            fromPrism.mods().size() == 1,
            "a Prism instance is read from mmc-pack.json and .minecraft/mods: " + fromPrism
        );

        // The vanilla launcher's folder: the loader profile in versions/ names the game and loader.
        Path launcher = scratch.resolve("dot-minecraft"), launcherMods = Files.createDirectories(launcher.resolve("mods"));
        Files.copy(lithium, launcherMods.resolve("lithium.jar"));
        Path profile = Files.createDirectories(launcher.resolve("versions/fabric-loader-0.16.7-1.21.1"));
        Files.writeString(
            profile.resolve("fabric-loader-0.16.7-1.21.1.json"),
            "{\"id\":\"fabric-loader-0.16.7-1.21.1\",\"inheritsFrom\":\"1.21.1\",\"libraries\":[{\"name\":\"org.ow2.asm:asm:9.6\"},{\"name\":\"net.fabricmc:fabric-loader:0.16.7\"}]}"
        );
        PackCheck.Pack fromLauncher = check(harness, scratch).read(launcher);
        expect(
            fromLauncher.format().equals("launcher-folder") && fromLauncher.loader().equals("fabric") && fromLauncher.loaderVersion().equals("0.16.7") && fromLauncher.minecraft().equals("1.21.1"),
            "the launcher folder's loader profile names the game and loader: " + fromLauncher
        );

        // A bare mods folder: the loader comes from the jars; the game version from --minecraft.
        Path bare = scratch.resolve("bare"), bareMods = Files.createDirectories(bare.resolve("mods"));
        Files.copy(lithium, bareMods.resolve("lithium.jar"));
        Files.copy(sodium, bareMods.resolve("sodium.jar"));
        PackCheck.Pack fromBare = check(harness, scratch, "--minecraft", "1.21.1").read(bare);
        expect(
            fromBare.format().equals("mods-folder") && fromBare.loader().equals("fabric") && fromBare.loaderVersion().isEmpty(),
            "a bare folder of Fabric jars is a Fabric pack, its loader version left for the loader's metadata: " + fromBare
        );
        PackCheck.Pack overridden = check(harness, scratch, "--minecraft", "1.21.1", "--loader", "quilt-0.29.1").read(bare);
        expect(overridden.loader().equals("quilt") && overridden.loaderVersion().equals("0.29.1"), "--loader names a loader and its version: " + overridden);

        // A jar one start showed to be client-only is left out of the next run's starts from the beginning.
        check(harness, scratch).drop("traverse-forge-7.0.12.jar", "traverse");
        PackCheck next = check(harness, scratch);
        expect(next.dropped.isEmpty(), "nothing is dropped before the remembered list is read");
        next.readDropped();
        expect(
            next.dropped.equals(Set.of("traverse-forge-7.0.12.jar")) && Json.read(next.clientOnlyFile()).get("traverse-forge-7.0.12.jar").toString().contains("traverse"),
            "a client-only jar is remembered with its mod id for the next run: " + next.dropped
        );
    }

    // ------------------------------------------------------------------ Fabric and Quilt metadata
    static void fabricAndQuilt(Path harness, Path scratch) throws Exception {
        // Astral Vinery 1.2.0: a line break inside its description string; Fabric reads it, so pack-check must too.
        var raw = Json.object(PackMods.lenient("{\"id\": \"astralvinery\", \"description\": \"one\nand \\\"two\\\"\tthree\", \"depends\": {\"vinery\": \"1.1.4\"}}"));
        expect(
            "astralvinery".equals(raw.get("id")) && Json.object(raw.get("depends")).get("vinery").equals("1.1.4") && String.valueOf(raw.get("description")).contains("one\nand \"two\""),
            "a raw line break inside a fabric.mod.json string is read the way Fabric reads it: " + raw
        );
        Path mods = Files.createDirectories(scratch.resolve("fabric/mods"));
        Path inner = jar(scratch.resolve("inner.jar"), Map.of("fabric.mod.json", "{\"id\":\"cloth-config\",\"version\":\"15.0.140\"}"));
        jar(
            mods.resolve("rei.jar"),
            Map.of(
                "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"rei\",\"version\":\"16.0.1\",\"environment\":\"*\",\"jars\":[{\"file\":\"META-INF/jars/cloth.jar\"}]," +
                "\"mixins\":[\"rei.mixins.json\",{\"config\":\"rei.client.mixins.json\",\"environment\":\"client\"}]," +
                "\"depends\":{\"minecraft\":\"~1.21\",\"fabricloader\":\">=0.15.0\",\"fabric-api\":\"*\",\"architectury\":[\">=13.0.0\"]},\"breaks\":{\"roughlyenoughitems-old\":\"*\",\"modmenu\":\"<11\"}}",
                "META-INF/jars/cloth.jar",
                inner,
                "me/shedaniel/rei/RoughlyEnoughItemsCore.class",
                "x"
            )
        );
        jar(mods.resolve("fabric-api.jar"), Map.of("fabric.mod.json", "{\"id\":\"fabric-api\",\"version\":\"0.105.0+1.21.1\",\"provides\":[\"fabric\"]}"));
        jar(mods.resolve("architectury.jar"), Map.of("fabric.mod.json", "{\"id\":\"architectury\",\"version\":\"12.1.4\"}"));
        jar(mods.resolve("modmenu.jar"), Map.of("fabric.mod.json", "{\"id\":\"modmenu\",\"version\":\"10.0.0\",\"environment\":\"client\"}"));
        jar(mods.resolve("old.jar"), Map.of("fabric.mod.json", "{\"id\":\"old\",\"version\":\"1\",\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.17\"}}"));
        jar(mods.resolve("forge-only.jar"), Map.of("META-INF/mods.toml", toml("forgeonly", "1", "")));
        PackCheck check = check(harness, scratch, "--minecraft", "1.21.1", "--loader", "fabric-0.16.7");
        PackCheck.Pack pack = check.read(scratch.resolve("fabric"));
        PackLoaders.Loader fabric = PackLoaders.find(harness, "fabric");
        PackMods.Scan scan = statics(check, pack, fabric);
        expect(
            scan.providers.containsKey("cloth-config") && scan.providers.get("cloth-config").get(0).bundled() && scan.providers.containsKey("fabric"),
            "Fabric's bundled jars and provided ids count as mods: " + scan.providers.keySet()
        );
        expect(scan.sides.get("modmenu.jar").equals("client") && PackCheck.sides(pack.mods(), scan).stream().anyMatch(m -> m.file().equals("modmenu.jar") && m.side().equals("client")), "Fabric's environment marks a client-only jar");
        expect(scan.mixins.get("rei.mixins.json").equals("rei.jar") && scan.mixins.get("rei.client.mixins.json").equals("rei.jar"), "mixin configs are traced to their jar: " + scan.mixins);
        expect(
            text(check, "dependency-version").contains("rei.jar needs architectury >=13.0.0") && !text(check, "dependency-version").contains("fabric-api"),
            "a Fabric range that the present version misses is a problem: " + check.findings
        );
        expect(
            text(check, "minecraft-mismatch").contains("old.jar is for Minecraft 1.20.1") && !text(check, "minecraft-mismatch").contains("rei.jar"),
            "a Fabric mod for another game version is a problem, ~1.21 accepts 1.21.1: " + check.findings
        );
        expect(
            text(check, "loader-version").contains("old.jar needs fabricloader >=0.17") && !text(check, "loader-version").contains("rei.jar"),
            "a mod that needs a newer Fabric Loader is a problem: " + check.findings
        );
        expect(
            text(check, "declared-conflict").contains("rei.jar says it breaks modmenu <11") && !text(check, "declared-conflict").contains("roughlyenoughitems-old"),
            "a declared break of a present mod is a problem: " + check.findings
        );
        expect(text(check, "loader-mismatch").contains("forge-only.jar"), "a Forge jar in a Fabric pack is named: " + check.findings);
        expect(!kinds(check).contains("missing-mod"), "the loader, the game and provided ids are not missing: " + check.findings);

        // Quilt reads quilt.mod.json first and Fabric's file too.
        Path quiltMods = Files.createDirectories(scratch.resolve("quilt/mods"));
        jar(
            quiltMods.resolve("qsl.jar"),
            Map.of(
                "quilt.mod.json",
                "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"qsl\",\"version\":\"10.0.0\",\"provides\":[\"quilted_fabric_api\"],\"depends\":[\"minecraft\",{\"id\":\"quilt_loader\",\"versions\":\">=0.26\"},{\"id\":\"needed\",\"versions\":\">=2\"},{\"id\":\"maybe\",\"optional\":true}]}}"
            )
        );
        jar(quiltMods.resolve("needed.jar"), Map.of("fabric.mod.json", "{\"id\":\"needed\",\"version\":\"1.5\"}"));
        PackCheck quilt = check(harness, scratch, "--minecraft", "1.21.1", "--loader", "quilt-0.29.1");
        PackCheck.Pack quiltPack = quilt.read(scratch.resolve("quilt"));
        PackMods.Scan quiltScan = statics(quilt, quiltPack, PackLoaders.find(harness, "quilt"));
        expect(
            quiltScan.providers.containsKey("qsl") && quiltScan.providers.containsKey("quilted_fabric_api") && quiltScan.providers.containsKey("needed"),
            "Quilt reads quilt.mod.json and fabric.mod.json: " + quiltScan.providers.keySet()
        );
        expect(
            text(quilt, "dependency-version").contains("qsl.jar needs needed >=2") && !kinds(quilt).contains("missing-mod") && !kinds(quilt).contains("loader-version"),
            "Quilt's dependency objects are read, optional ones skipped: " + quilt.findings
        );
    }

    // ------------------------------------------------------------------ logs
    static void logs(Path harness, Path scratch) throws Exception {
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge"), fabric = PackLoaders.find(harness, "fabric");
        // A failed start is told in the game's own words.
        String log = String.join(
            "\n",
            "[main/INFO] Preparing level \"world\"",
            "[main/ERROR] Failed to load datapacks, can't proceed with server load",
            "java.lang.IllegalStateException: Failed to load datapacks",
            "Caused by: java.lang.IllegalStateException: Overworld settings missing",
            "\tat x.y(Z.java:1)"
        );
        String failure = PackChecks.failure(log, forge.failed());
        expect(
            failure.contains("Overworld settings missing") && failure.contains("Failed to load datapacks") && !failure.contains("at x.y"),
            "a failed start keeps the cause: " + failure
        );
        // Client-only mods stopping a server, in Forge 47's own lines: Blur's cause is nested; a mod failing for another reason is kept.
        PackMods.Scan forgeScan = new PackMods.Scan();
        String clientOnly = String.join(
            "\n",
            "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: blur, class com.tterrag.blur.Blur",
            "java.lang.ExceptionInInitializerError: null",
            "\tat x.y(Z.java:1)",
            "Caused by: java.lang.RuntimeException: Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER",
            "[17:06:43] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to register automatic subscribers. ModID: buildpaste, class com.mistrx.buildpaste.BuildPasteMod",
            "java.lang.RuntimeException: Attempted to load class net/minecraft/client/renderer/LevelRenderer for invalid dist DEDICATED_SERVER",
            "[17:06:44] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: citresewn, class shcm.shsupercm.forge.citresewn.CITResewn",
            "java.lang.NoClassDefFoundError: net/minecraft/client/renderer/texture/atlas/SpriteSource",
            "	at java.lang.ClassLoader.defineClass1(Native Method) ~[?:?]",
            "Caused by: java.lang.ClassNotFoundException: net.minecraft.client.renderer.texture.atlas.SpriteSource",
            "[17:06:44] [modloading-worker-0/ERROR] [ne.mi.fm.ja.FMLModContainer/LOADING]: Failed to create mod instance. ModID: brokenmod, class a.B",
            "java.lang.NullPointerException: config was null",
            "[17:06:44] [main/INFO] [STDERR/]: [Rhino] Failed to get declared methods: Attempted to load class net/minecraft/client/gui/GuiGraphics for invalid dist DEDICATED_SERVER",
            "[17:06:56] [main/FATAL] [ne.mi.fm.ModLoader/LOADING]: Failed to complete lifecycle event CONSTRUCT, 3 errors found",
            "java.lang.Exception: Mod Loading has failed"
        );
        expect(
            PackChecks.clientOnly(clientOnly, forge, forgeScan).equals(new LinkedHashSet<>(List.of("blur", "buildpaste", "citresewn"))),
            "client-only mods are read from Forge's failure blocks (a client class reached, or missing from the server jar), and only those: " + PackChecks.clientOnly(clientOnly, forge, forgeScan)
        );
        String started = clientOnly.replace("Mod Loading has failed", "Done").replace("Failed to complete lifecycle event", "Done").replace("java.lang.", "");
        expect(PackChecks.clientOnly(started, forge, forgeScan).isEmpty(), "a server that started has no client-only mods to remove");

        // Fabric names no mod for client code reached on a server: the stack's first frame in a pack jar does.
        PackMods.Scan scan = new PackMods.Scan();
        scan.providers.put("zoomify", new ArrayList<>(List.of(new PackMods.Provider("Zoomify.jar", "2.14", false, "fabric.mod.json"))));
        scan.providers.put("iris", new ArrayList<>(List.of(new PackMods.Provider("iris.jar", "1.7", false, "fabric.mod.json"))));
        scan.packages.put("dev.isxander.zoomify", "Zoomify.jar");
        scan.packages.put("net.irisshaders.iris.mixin", "iris.jar");
        scan.mixins.put("mixins.iris.json", "iris.jar");
        String fabricLog = String.join(
            "\n",
            "[17:42:33] [main/ERROR]: Failed to start Minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'zoomify' at 'dev.isxander.zoomify.Zoomify'!",
            "\tat knot//net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:403)",
            "Caused by: java.lang.RuntimeException: Cannot load class net.minecraft.class_310 in environment type SERVER",
            "\tat knot//net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.loadClass(KnotClassDelegate.java:1)",
            "\tat knot//dev.isxander.zoomify.Zoomify.onInitialize(Zoomify.kt:20)",
            "[17:42:34] [main/INFO]: Stopping"
        );
        expect(PackChecks.clientOnly(fabricLog, fabric, scan).equals(Set.of("zoomify")), "Fabric: a client-only mod is found by its frame: " + PackChecks.clientOnly(fabricLog, fabric, scan));
        // Quilt 1.20.1 (SodiumPlus, from the pack sweep): Lilac reaches Fabric API's client-only classes on a server.
        PackLoaders.Loader quilt = PackLoaders.all(harness).get("quilt");
        scan.providers.put("lilac", new ArrayList<>(List.of(new PackMods.Provider("lilac.jar", "1.0", false, "fabric.mod.json"))));
        String quiltLog = String.join(
            "\n",
            "[20:55:45] [main/ERROR]: Failed to start the minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'lilac'!",
            "\tat org.quiltmc.loader.impl.entrypoint.EntrypointUtils.lambda$invoke0$2(EntrypointUtils.java:66) ~[quilt-loader-0.24.0.jar:?]",
            "Caused by: org.quiltmc.loader.impl.entrypoint.QuiltEntrypointException: Exception while loading entries for entrypoint 'main' provided by 'lilac'",
            "Caused by: java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndWorldTick"
        );
        expect(PackChecks.clientOnly(quiltLog, quilt, scan).equals(Set.of("lilac")), "Quilt: a mod reaching Fabric API's client classes is client-only: " + PackChecks.clientOnly(quiltLog, quilt, scan));
        var suspects = PackChecks.suspects(fabricLog, List.of(), scan, fabric);
        expect(
            suspects.size() == 1 && suspects.get(0).get("mod").equals("zoomify") && suspects.get(0).get("jar").equals("Zoomify.jar") && Json.array(suspects.get(0).get("why")).size() == 2,
            "Fabric: the crash names zoomify, by the loader and by its code in the stack: " + suspects
        );
        String mixinLog = String.join(
            "\n",
            "[main/ERROR]: Mixin apply failed mixins.iris.json:MixinLevelRenderer -> net.minecraft.class_761: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException",
            "Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: boom",
            "\tat knot//net.irisshaders.iris.mixin.Foo.bar(Foo.java:1)"
        );
        var mixinSuspects = PackChecks.suspects(mixinLog, List.of(), scan, fabric);
        expect(mixinSuspects.size() == 1 && mixinSuspects.get(0).get("mod").equals("iris"), "a mixin config in the failure names its mod: " + mixinSuspects);
        // Forge's module path names the mod in each frame; a crash report's Suspected Mods are read too.
        PackMods.Scan neo = new PackMods.Scan();
        neo.providers.put("create", new ArrayList<>(List.of(new PackMods.Provider("create.jar", "6", false, "META-INF/neoforge.mods.toml"))));
        neo.providers.put("flywheel", new ArrayList<>(List.of(new PackMods.Provider("create.jar!flywheel.jar", "1", true, "META-INF/neoforge.mods.toml"))));
        String report = "---- Minecraft Crash Report ----\nDescription: Exception in server tick loop\n\njava.lang.NullPointerException\n\tat TRANSFORMER/create@6.0.0/com.simibubi.create.Foo.tick(Foo.java:1)\n\nSuspected Mods:\n\tFlywheel (flywheel), Version: 1\n\n";
        var neoSuspects = PackChecks.suspects("", List.of(report), neo, PackLoaders.find(harness, "neoforge"));
        expect(
            neoSuspects.stream().map(s -> s.get("mod")).toList().equals(List.of("flywheel", "create")) && neoSuspects.get(0).get("jar").equals("create.jar!flywheel.jar"),
            "a crash report's suspected mod and a module frame name mods: " + neoSuspects
        );
        expect(PackChecks.names(neoSuspects).equals("flywheel (create.jar!flywheel.jar), create (create.jar)"), "suspects read as a list of mods and jars");
        // Found by an independent rerun of Optimized FPS (Forge 1.16.5): the probe never found the server, and pack-check blamed
        // ModernFix by quoting a successful start-up log. A server that logged Done blames nobody for what came before it.
        String startedLog = String.join(
            "\n",
            "[main/INFO] [mixin/]: Mixing mixins.iris.json:MixinLevelRenderer",
            "[Server thread/INFO] [minecraft/DedicatedServer]: Done (12.345s)! For help, type \"help\""
        );
        var startedSuspects = PackChecks.suspects(startedLog, List.of(), scan, fabric);
        String startedFailure = PackChecks.failure(startedLog, fabric.failed());
        expect(
            startedSuspects.isEmpty() && startedFailure.contains("finished starting") && startedFailure.contains("not of a mod"),
            "a log with a finished start names no mod, and says the probe never reported: " + startedSuspects + " " + startedFailure
        );
        var crashedAfter = PackChecks.suspects(startedLog + "\n" + mixinLog, List.of(), scan, fabric);
        expect(crashedAfter.size() == 1 && crashedAfter.get(0).get("mod").equals("iris"), "a failure after the start still names its mod: " + crashedAfter);
        String probe = Files.readString(harness.resolve("tools/packcheck/ModaccelPackProbe.java"));
        expect(
            probe.contains("\"net.minecraftforge.fml.server.ServerLifecycleHooks\"") && probe.contains("for (String hooks : SERVER_HOOKS)"),
            "the probe looks for the server where Forge 1.16 keeps it (net.minecraftforge.fml.server.ServerLifecycleHooks)"
        );

        // Simple Voice Chat's fixed UDP port, taken by the other lane's server: that start runs again alone.
        String lost = String.join(
            "\n",
            "[16:46:12] [VoiceChatServerThread/ERROR] [voicechat/]: [voicechat] Voice chat server error",
            "java.net.BindException: Address already in use: bind",
            "[16:46:12] [Server thread/INFO] [minecraft/MinecraftServer]: Stopping server"
        );
        expect(PackCheck.lostPort(lost) && !PackCheck.lostPort(log) && !PackCheck.lostPort(clientOnly), "a port lost to another server is told apart from other failed starts");
        Path server = Files.createDirectories(scratch.resolve("server/mods")).getParent();
        Path voice = Files.createDirectories(server.resolve("config/voicechat")).resolve("voicechat-server.properties");
        Files.writeString(voice, "# Simple Voice Chat server config\n# Set this to '-1' to use the same port\nport=24454\nmax_voice_distance=48.0\n");
        PackCheck.ServerLane.voiceChatPort(server);
        expect(Files.readString(voice).contains("port=24454"), "a server without Simple Voice Chat keeps its config: " + Files.readString(voice));
        Files.writeString(server.resolve("mods/voicechat-forge-1.20.1-2.6.22.jar"), "");
        PackCheck.ServerLane.voiceChatPort(server);
        String rewritten = Files.readString(voice);
        expect(
            !rewritten.contains("port=24454") && rewritten.matches("(?s).*\nport=[1-9][0-9]*\n.*") && rewritten.contains("max_voice_distance=48.0"),
            "Simple Voice Chat's fixed port is replaced by a free one, nothing else changed: " + rewritten
        );
        expect(PackCheck.doneSeconds("[Server thread/INFO] Done (41.250s)! For help, type \"help\"") == 41.25, "the start time is read");
        expect(Json.write(Json.map("probeFile", scratch.resolve("probe-place.json"))).contains("probe-place.json"), "a path in a result is written as text");
    }

    // ------------------------------------------------------------------ which jars a server gets
    static void serverMods(Path harness, Path scratch) throws Exception {
        // Prominence II (Fabric 1.20.1, from the pack sweep): Chipped needs Athena, Simply Swords needs Simply Tooltips and
        // Forge Config Screens needs Mod Menu, each marked client-side by Modrinth, CurseForge or its own environment. Leaving
        // them out stopped the server ("requires athena, which is missing"); kept, Fabric decides (it keeps an
        // environment-client jar out of the game and treats a dependency on it as met).
        Path fabricMods = Files.createDirectories(scratch.resolve("sides-fabric/mods"));
        List<PackCheck.Mod> fabric = new ArrayList<>();
        for (String[] m : List.of(
            new String[] { "chipped.jar", "unknown", "{\"id\":\"chipped\",\"version\":\"3.0.7\",\"depends\":{\"athena\":\">=3.1.1\",\"minecraft\":\">=1.20.1\"}}" },
            new String[] { "athena.jar", "client", "{\"id\":\"athena\",\"version\":\"3.1.2\"}" },
            new String[] { "simplyswords.jar", "both", "{\"id\":\"simplyswords\",\"version\":\"1.70.2\",\"depends\":{\"simplytooltips\":\"*\"}}" },
            new String[] { "simplytooltips.jar", "client", "{\"id\":\"simplytooltips\",\"version\":\"0.1.5\",\"depends\":{\"fzzy_config\":\"*\"}}" },
            new String[] { "fzzy.jar", "client", "{\"id\":\"fzzy_config\",\"version\":\"0.6.6\"}" },
            new String[] { "forgeconfigscreens.jar", "unknown", "{\"id\":\"forgeconfigscreens\",\"version\":\"8\",\"depends\":{\"modmenu\":\"*\"}}" },
            new String[] { "modmenu.jar", "unknown", "{\"id\":\"modmenu\",\"version\":\"7.2.2\",\"environment\":\"client\"}" },
            new String[] { "sodium.jar", "client", "{\"id\":\"sodium\",\"version\":\"0.5\"}" }
        )) fabric.add(new PackCheck.Mod(m[0], jar(fabricMods.resolve(m[0]), Map.of("fabric.mod.json", m[2])), m[1], "test"));
        PackMods.Scan fabricScan = PackMods.scan(fabric, List.of("fabric.mod.json"));
        fabric = PackCheck.sides(fabric, fabricScan);
        PackCheck.ServerMods kept = PackCheck.serverMods(fabric, fabricScan, Set.of(), List.of());
        PackMods.Scan chippedAlone = PackMods.scan(fabric.subList(0, 1), List.of("fabric.mod.json"));
        expect(
            PackRepairs.dependencyMissing(Map.of("mod", "chipped", "dependency", "athena"), chippedAlone).contains("requires athena >=3.1.1, which no jar") &&
            PackRepairs.dependencyMissing(Map.of("mod", "chipped", "dependency", "athena"), fabricScan) == null,
            "the dependency-missing repair match holds only while no jar provides the dependency"
        );
        expect(
            kept.kept().containsAll(List.of("chipped.jar", "athena.jar", "simplyswords.jar", "simplytooltips.jar", "fzzy.jar", "forgeconfigscreens.jar", "modmenu.jar")) &&
            kept.leftOut().keySet().equals(Set.of("sodium.jar")) &&
            kept.required().get("athena.jar").equals("chipped (chipped.jar) requires athena") &&
            kept.required().get("fzzy.jar").equals("simplytooltips (simplytooltips.jar) requires fzzy_config"),
            "a client-side jar a kept mod requires is kept, through its own dependencies; one nothing requires is left out: " + kept
        );

        // Optimized FPS (Forge 1.16.5, from the pack sweep): Embeddium Extension needs Embeddium, which Modrinth marks
        // client-only. Kept, Embeddium is tried; once a start shows it reaching client-only code, it and every mod that
        // requires it (here, through a second mod) are left out.
        Path forgeMods = Files.createDirectories(scratch.resolve("sides-forge/mods"));
        List<PackCheck.Mod> forge = List.of(
            new PackCheck.Mod("embeddium.jar", jar(forgeMods.resolve("embeddium.jar"), Map.of("META-INF/mods.toml", toml("embeddium", "0.3.18", ""))), "client", "test"),
            new PackCheck.Mod(
                "embeddiumextension.jar",
                jar(forgeMods.resolve("embeddiumextension.jar"), Map.of("META-INF/mods.toml", toml("embeddiumextension", "0.1.1", dependency("embeddium", "true", "[0.1.14,)")))),
                "unknown",
                "test"
            ),
            new PackCheck.Mod(
                "addon.jar",
                jar(forgeMods.resolve("addon.jar"), Map.of("META-INF/mods.toml", toml("addon", "1", dependency("embeddiumextension", "true", "[0,)")))),
                "unknown",
                "test"
            ),
            new PackCheck.Mod("create.jar", jar(forgeMods.resolve("create.jar"), Map.of("META-INF/mods.toml", toml("create", "6", dependency("forge", "true", "[47,)")))), "both", "test")
        );
        PackMods.Scan forgeScan = PackMods.scan(forge, List.of("META-INF/mods.toml"));
        PackCheck.ServerMods tried = PackCheck.serverMods(forge, forgeScan, Set.of(), List.of());
        expect(
            tried.kept().equals(Set.of("embeddium.jar", "embeddiumextension.jar", "addon.jar", "create.jar")) && tried.required().containsKey("embeddium.jar"),
            "Forge: a client-side library a kept mod requires is tried on the server: " + tried
        );
        PackCheck.ServerMods shown = PackCheck.serverMods(forge, forgeScan, Set.of("embeddium.jar"), List.of());
        expect(
            shown.kept().equals(Set.of("create.jar")) &&
            shown.leftOut().get("embeddiumextension.jar").equals("it requires embeddium, and embeddium.jar cannot run on a server") &&
            shown.leftOut().get("addon.jar").startsWith("it requires embeddiumextension") &&
            shown.leftOut().get("embeddium.jar").contains("client-only code"),
            "a mod whose required mod cannot run on a server is left out too, through its dependants: " + shown
        );
        expect(
            PackCheck.serverMods(forge, forgeScan, Set.of(), List.of("embeddiumextension.jar")).kept().equals(Set.of("create.jar", "addon.jar")),
            "a jar a repair removes is not on the server, and nothing is kept for it: " + PackCheck.serverMods(forge, forgeScan, Set.of(), List.of("embeddiumextension.jar"))
        );

        // Create+ (Forge 1.19.2, from the pack sweep): Kotlin for Forge 3.12 is a LIBRARY jar whose mod is in its jarjar list.
        Path kffMod = jar(scratch.resolve("kffmod.jar"), Map.of("META-INF/mods.toml", toml("kotlinforforge", "3.12.0", "")));
        Path kff = jar(
            forgeMods.resolve("kotlinforforge-3.12.0-all.jar"),
            Map.of(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nFMLModType: LIBRARY\n",
                "META-INF/jarjar/metadata.json",
                "{\"jars\":[{\"identifier\":{\"group\":\"thedarkcolour\",\"artifact\":\"kffmod\"},\"path\":\"META-INF/jarjar/kffmod-3.12.0.jar\"}]}",
                "META-INF/jarjar/kffmod-3.12.0.jar",
                kffMod
            )
        );
        PackMods.Scan kotlin = PackMods.scan(List.of(new PackCheck.Mod("kotlinforforge-3.12.0-all.jar", kff, "unknown", "test")), List.of("META-INF/mods.toml"));
        expect(
            kotlin.providers.containsKey("kotlinforforge") && kotlin.plugins.contains("kotlinforforge-3.12.0-all.jar"),
            "a loader library's mods in its jarjar list are read: " + kotlin.providers.keySet()
        );
    }

    // ------------------------------------------------------------------ loaders
    static void loaders(Path harness, Path scratch) throws Exception {
        var all = PackLoaders.all(harness);
        expect(all.keySet().equals(new LinkedHashSet<>(List.of("forge", "neoforge", "fabric", "quilt"))), "pack-loaders.json has the four loaders: " + all.keySet());
        PackLoaders.Loader neo = all.get("neoforge");
        expect(
            neo.metadata("1.21.1").equals(List.of("META-INF/neoforge.mods.toml")) && neo.metadata("1.20.4").equals(List.of("META-INF/mods.toml")),
            "NeoForge reads neoforge.mods.toml from 1.20.5 and mods.toml before"
        );
        expect(all.get("quilt").metadata("1.21.1").equals(List.of("quilt.mod.json", "fabric.mod.json")), "Quilt reads its own metadata, then Fabric's");
        // Found by the pack sweep (SodiumPlus, Quilt 1.20.1): QSL modules need quilt_base >=6.1.2+1.20.1 and the pack has
        // 6.1.2+1.20.1. Fabric and Quilt compare SemVer without build metadata, on both sides.
        PackMods.Dependency qsl = new PackMods.Dependency("block_content_registry", "quilt_base", List.of(">=6.1.2+1.20.1"), "quilt.mod.json", "depends");
        // From the pack sweep (Additive, Fabric 1.19.2): the world opened but the probe stopped before placing blocks; the
        // placement check names that as unchecked instead of failing pack-check.
        List<Object> stopped = new ArrayList<>();
        PackChecks.Context placed = new PackChecks.Context(null, new PackMods.Scan(), PackLoaders.all(harness).get("fabric"), stopped);
        placed.start = Map.of("opened", true, "probe", Map.of("error", "java.lang.IllegalStateException: no way to make a player\n\tat x.y", "ok", false));
        PackChecks.placements(placed);
        expect(
            stopped.size() == 1 &&
            "not-checked".equals(Json.object(stopped.get(0)).get("kind")) &&
            "note".equals(Json.object(stopped.get(0)).get("severity")),
            "a probe that stopped before placing is a note that placement went unchecked: " + stopped
        );
        // Optimized FPS (Forge 1.16.5, from the pack sweep): a probe that failed before reading the world wrote only its error.
        List<Object> early = new ArrayList<>();
        PackChecks.Context failed = new PackChecks.Context(null, new PackMods.Scan(), PackLoaders.all(harness).get("forge"), early);
        failed.start = Map.of("opened", true, "probe", Map.of("error", "java.lang.NoSuchMethodError: x", "ok", false));
        PackChecks.dimensions(failed);
        PackChecks.changedBySave(failed);
        PackChecks.placements(failed);
        expect(
            early.size() == 1 && "not-checked".equals(Json.object(early.get(0)).get("kind")),
            "a probe result with only its error is a gap of the check, not a pack-check crash: " + early
        );
        // SodiumPlus (Quilt 1.20.1, the pack sweep): QFAPI accepts Minecraft {"all": [">=1.20-", "<1.20.2-"]}, which 1.20.1 satisfies.
        List<String> allOf = PackMods.quiltRanges(Map.of("all", List.of(">=1.20-", "<1.20.2-")));
        PackMods.Dependency qfapi = new PackMods.Dependency("qfapi", "minecraft", allOf, "quilt.mod.json", "required");
        expect(
            allOf.equals(List.of(">=1.20- <1.20.2-")) &&
            PackMods.accepts(qfapi, "1.20.1") &&
            !PackMods.accepts(qfapi, "1.20.2") &&
            PackMods.quiltRanges(Map.of("any", List.of("1.20", "1.20.1"))).equals(List.of("1.20", "1.20.1")),
            "a Quilt range object: all of its constraints, or any of its alternatives: " + allOf
        );
        // And on Vanilla Perfected 1.0.0+1.21.1 (an independent check): an exact citresewn 1.2.1+1.21, and YACL >=3.5.0+1.21-fabric.
        PackMods.Dependency cit = new PackMods.Dependency("cit-addon", "citresewn", List.of("1.2.1+1.21"), "fabric.mod.json", "depends");
        PackMods.Dependency yacl = new PackMods.Dependency("some-mod", "yet_another_config_lib_v3", List.of(">=3.5.0+1.21-fabric"), "fabric.mod.json", "depends");
        expect(
            PackMods.accepts(qsl, "6.1.2+1.20.1") && PackMods.accepts(qsl, "6.1.3+1.20.1") && !PackMods.accepts(qsl, "6.1.1+1.20.1") &&
            PackMods.accepts(cit, "1.2.1+1.21") && !PackMods.accepts(cit, "1.2.0+1.21") &&
            PackMods.accepts(yacl, "3.5.0+1.21-fabric") && PackMods.accepts(yacl, "3.6.1+1.21.1-fabric") && !PackMods.accepts(yacl, "3.4.4+1.21-fabric"),
            "a Fabric or Quilt range with build metadata accepts the same version with any build metadata"
        );
        Map<String, String> layers = new LinkedHashMap<>();
        for (String v : List.of("1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.20.1", "1.21.1", "1.21.4", "1.21.5", "1.21.10", "1.21.11", "26.1.2", "26.3")) layers.put(
            v,
            PackLoaders.gameLayer(harness, v).getParent().getFileName().toString()
        );
        expect(
            layers.equals(
                Map.ofEntries(
                    Map.entry("1.16.5", "1.16"),
                    Map.entry("1.17.1", "1.16"),
                    Map.entry("1.18.2", "1.18"),
                    Map.entry("1.19.2", "1.18"),
                    Map.entry("1.19.3", "1.19.3"),
                    Map.entry("1.20.1", "1.19.3"),
                    Map.entry("1.21.1", "1.19.3"),
                    Map.entry("1.21.4", "1.21.2"),
                    Map.entry("1.21.5", "1.21.5"),
                    Map.entry("1.21.10", "1.21.5"),
                    Map.entry("1.21.11", "1.21.11"),
                    Map.entry("26.1.2", "26.1"),
                    Map.entry("26.3", "26.1")
                )
            ),
            "the probe compiles with the newest game layer at or below the pack's version: " + layers
        );
        boolean refused = false;
        try {
            PackLoaders.gameLayer(harness, "1.15.2");
        } catch (IOException none) {
            refused = none.getMessage().contains("no game layer");
        }
        expect(refused, "a version older than every game layer is named, not compiled");
        expect(
            PackLoaders.neoforgeLine("1.21.1").equals("21.1") && PackLoaders.neoforgeLine("1.21").equals("21.0") && PackLoaders.neoforgeLine("26.1").equals("26.1"),
            "NeoForge's version line follows the game version"
        );
        var style = (PackLoaders.Style) neo;
        expect(
            style.setting("1.20.1", "installer").contains("net/neoforged/forge/1.20.1-") && style.setting("1.21.1", "installer").contains("net/neoforged/neoforge/"),
            "NeoForge for 1.20.1 comes from its own coordinates, other versions from neoforge's"
        );
        expect(
            PackLoaders.find(harness, "net.fabricmc.fabric-loader").id().equals("fabric") && PackLoaders.find(harness, "net.neoforged").id().equals("neoforge"),
            "loaders are found by the names packs use"
        );
        // The args-file style: the installer's argument file, its library paths made absolute.
        Path install = scratch.resolve("loader");
        Files.createDirectories(install.resolve("libraries/net/neoforged/neoforge/21.1.77"));
        String name = Runtimes.WINDOWS ? "win_args.txt" : "unix_args.txt";
        Files.writeString(install.resolve("libraries/net/neoforged/neoforge/21.1.77/" + name), "-p libraries/a.jar\n-DlibraryDirectory=libraries\n--launchTarget forgeserver\n");
        Path instance = Files.createDirectories(scratch.resolve("loader-instance"));
        List<String> launch = neo.launch(install, instance, "1.21.1", "21.1.77");
        String args = Files.readString(instance.resolve("modaccel-args.txt"));
        String libraries = install.resolve("libraries").toString().replace('\\', '/');
        expect(
            launch.equals(List.of("@" + instance.resolve("modaccel-args.txt"))) && args.contains("-p " + libraries + "/a.jar") && args.contains("-DlibraryDirectory=" + libraries),
            "the args file's library paths are made absolute: " + args
        );
        // The launch-jar style: the launcher is told where the vanilla server is.
        List<String> fabricLaunch = all.get("fabric").launch(install, instance, "1.21.1", "0.16.7");
        expect(
            fabricLaunch.equals(List.of("-jar", install.resolve("fabric-server-launch.jar").toString())) &&
            Files.readString(instance.resolve("fabric-server-launcher.properties")).equals("serverJar=" + install.resolve("server.jar").toAbsolutePath().toString().replace('\\', '/') + "\n"),
            "Fabric's launcher is pointed at the installed vanilla server"
        );
    }

    // ------------------------------------------------------------------ helpers
    static PackMods.Scan statics(PackCheck check, PackCheck.Pack pack, PackLoaders.Loader loader) throws Exception {
        PackMods.Scan scan = PackMods.scan(pack.mods(), loader.metadata(pack.minecraft()));
        PackChecks.run("static", new PackChecks.Context(pack, scan, loader, check.findings));
        return scan;
    }

    static PackCheck check(Path harness, Path scratch, String... options) {
        List<String> args = new ArrayList<>(List.of("pack-check"));
        args.addAll(List.of(options));
        return new PackCheck(harness, new Modaccel.Options(args.toArray(String[]::new)), new Modaccel.Report(scratch, "pack-check"));
    }

    static String toml(String id, String version, String dependencies) {
        // Forge's template comments its section lines; the parser must read past that.
        return "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]] #mandatory\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n" + dependencies;
    }

    static String dependency(String id, String mandatory, String range) {
        return "[[dependencies.x]] #optional\nmodId=\"" + id + "\"\nmandatory=" + mandatory + "\nversionRange=\"" + range + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
    }

    /**
     * Found by an independent check of the pack sweep. Create: Above and Beyond (Forge 1.16.5 36.2.20) on Temurin 8u512
     * stopped in ModLauncher with NoSuchMethodError on ManifestEntryVerifier(Manifest), and pack-check blamed antiqueatlas,
     * whose mixin config was named on the log line above. XPlus 2.0 (Fabric 1.17.1) kept mcwifipnp, whose main entrypoint
     * reaches Screen (class_437), and blamed iceberg and worldedit too, for mixin configs in ordinary start-up lines.
     */
    static void culprits(Path harness, Path scratch) throws Exception {
        PackLoaders.Loader forge = PackLoaders.find(harness, "forge"), fabric = PackLoaders.find(harness, "fabric");
        String stderr = "[02:56:35] [main/INFO] [STDERR/]: [java.lang.ThreadGroup:uncaughtException:1052]: ";
        String cab = String.join(
            "\n",
            "[02:56:33] [main/INFO] [mixin/]: SpongePowered MIXIN Subsystem Version=0.8.4 Service=ModLauncher Env=SERVER",
            "[02:56:35] [main/ERROR] [mixin/]: Mixin config antiqueatlas.mixins.json does not specify \"minVersion\" property",
            "Exception in thread \"main\" " + stderr + "java.lang.NoSuchMethodError: sun.security.util.ManifestEntryVerifier.<init>(Ljava/util/jar/Manifest;)V",
            stderr + "\tat cpw.mods.modlauncher.SecureJarHandler.createCodeSource(SecureJarHandler.java:66)",
            stderr + "\tat cpw.mods.modlauncher.TransformingClassLoader$DelegatedClassLoader.findClass(TransformingClassLoader.java:275)",
            stderr + "\tat net.minecraftforge.server.ServerMain.main(ServerMain.java:57)"
        );
        PackMods.Scan cabScan = new PackMods.Scan();
        cabScan.providers.put("antiqueatlas", new ArrayList<>(List.of(new PackMods.Provider("antiqueatlas.jar", "6.0.1", false, "META-INF/mods.toml"))));
        cabScan.mixins.put("antiqueatlas.mixins.json", "antiqueatlas.jar");
        cabScan.packages.put("hunternif.mc.impl.atlas", "antiqueatlas.jar");
        var cabSuspects = PackChecks.suspects(cab, List.of(), cabScan, forge);
        expect(
            cabSuspects.size() == 1 && Boolean.TRUE.equals(cabSuspects.get(0).get("runtime")) && PackChecks.names(cabSuspects).equals("the Java build") &&
            Json.array(cabSuspects.get(0).get("why")).get(0).toString().contains("ManifestEntryVerifier.<init>(Ljava/util/jar/Manifest;)V"),
            "a JDK member missing under the loader's own code blames the Java build, not the mod on the line above: " + cabSuspects
        );
        expect(PackChecks.failure(cab, forge.failed()).contains("NoSuchMethodError: sun.security.util.ManifestEntryVerifier"), "the evidence keeps the uncaught error");
        // The same missing member called from a mod's own code is that mod's.
        String modCalls = cab.replace("cpw.mods.modlauncher.SecureJarHandler.createCodeSource", "hunternif.mc.impl.atlas.Foo.bar");
        var modSuspects = PackChecks.suspects(modCalls, List.of(), cabScan, forge);
        expect(modSuspects.size() == 1 && modSuspects.get(0).get("mod").equals("antiqueatlas"), "a mod calling a JDK member this Java lacks is that mod: " + modSuspects);
        // A mixin config named only in an ordinary line blames nobody; named in the failure, it does.
        String calm = "[main/WARN]: Method overwrite conflict for getLanguage in worldedit.mixins.json:Mixin from mod worldedit. Skipping method.\n" +
            "[main/FATAL]: Failed to start the minecraft server\njava.lang.IllegalStateException: boom\n\tat a.b.C.d(C.java:1)";
        PackMods.Scan xScan = new PackMods.Scan();
        xScan.mixins.put("worldedit.mixins.json", "worldedit.jar");
        xScan.mixins.put("iceberg.mixins.json", "iceberg.jar");
        for (String id : List.of("worldedit", "iceberg", "mcwifipnp")) xScan.providers.put(
            id,
            new ArrayList<>(List.of(new PackMods.Provider(id + ".jar", "1", false, "fabric.mod.json")))
        );
        expect(PackChecks.suspects(calm, List.of(), xScan, fabric).isEmpty(), "a mixin config in a start-up warning is not in the failure");
        String xplus = String.join(
            "\n",
            "[02:55:59] [main/INFO]: Mixing EntityMixin from iceberg.mixins.json into net.minecraft.class_1297",
            "[02:55:59] [main/WARN]: Method overwrite conflict for getLanguage in worldedit.mixins.json:MixinServerPlayerEntity from mod worldedit, previously written by carpet. Skipping method.",
            "[02:56:07] [main/FATAL]: Failed to start the minecraft server",
            "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'mcwifipnp'!",
            "\tat net.fabricmc.loader.impl.entrypoint.EntrypointUtils.lambda$invoke0$0(EntrypointUtils.java:51) ~[fabric-loader-0.14.21.jar:?]",
            "Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_437",
            "\tat io.github.satxm.mcwifipnp.MCWiFiPnP.onInitialize(MCWiFiPnP.java:30) ~[mcwifipnp.jar:?]",
            "Caused by: java.lang.ClassNotFoundException: net.minecraft.class_437"
        );
        java.util.function.Predicate<String> client = type -> type.equals("net/minecraft/class_437");
        var xSuspects = PackChecks.suspects(xplus, List.of(), xScan, fabric, client);
        expect(
            xSuspects.size() == 1 && xSuspects.get(0).get("mod").equals("mcwifipnp") && Json.array(xSuspects.get(0).get("why")).get(0).toString().contains("class_437"),
            "a client game class missing on the server blames only the mod that reached it: " + xSuspects
        );
        expect(PackChecks.clientOnly(xplus, fabric, xScan, client).equals(Set.of("mcwifipnp")), "a server-side NoClassDefFoundError on a client class is client-only");
        expect(PackChecks.clientOnly(xplus, fabric, xScan).isEmpty(), "a game class the server has, or an unknown one, is not taken for client code");

        // Static: a server entrypoint that reaches a client class as it initialises is client-only; one that only names it elsewhere is not.
        Path src = Files.createDirectories(scratch.resolve("client-code/src")), out = Files.createDirectories(scratch.resolve("client-code/out"));
        Files.createDirectories(src.resolve("net/minecraft"));
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("x"));
        Files.writeString(src.resolve("net/minecraft/class_437.java"), "package net.minecraft; public class class_437 { }");
        Files.writeString(src.resolve("net/minecraft/class_1937.java"), "package net.minecraft; public class class_1937 { }");
        Files.writeString(src.resolve("a/Reaches.java"),
            "package a; public class Reaches { static void after(net.minecraft.class_437 s) { } " +
            "public void onInitialize() { java.util.function.Consumer<net.minecraft.class_437> c = Reaches::after; } }");
        Files.writeString(src.resolve("a/Guarded.java"),
            "package a; public class Guarded { public void onInitialize() { new net.minecraft.class_1937(); } " +
            "public static void client() { new net.minecraft.class_437(); } }");
        Files.writeString(src.resolve("x/V.java"), "package x; public class V { public V(java.util.jar.Manifest m) { } }");
        Files.writeString(src.resolve("x/Caller.java"), "package x; public class Caller { Object make() { return new V(new java.util.jar.Manifest()); } }");
        List<String> javac = new ArrayList<>(List.of("-d", out.toString()));
        try (var walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> javac.add(p.toString()));
        }
        expect(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, javac.toArray(String[]::new)) == 0, "client-code fixtures compile");
        java.util.function.Predicate<String> game = new PackLoaders.GameClasses(
            Set.of("net/minecraft/class_1937"),
            Set.of("net/minecraft/class_1937", "net/minecraft/class_437")
        )::clientOnly;
        Path reaches = jar(scratch.resolve("reaches.jar"), Map.of(
            "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"reaches\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"a.Reaches\"]}}",
            "a/Reaches.class", out.resolve("a/Reaches.class")
        ));
        Path guarded = jar(scratch.resolve("guarded.jar"), Map.of(
            "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"guarded\",\"version\":\"1\",\"entrypoints\":{\"main\":[{\"adapter\":\"default\",\"value\":\"a.Guarded\"}]}}",
            "a/Guarded.class", out.resolve("a/Guarded.class")
        ));
        String why = PackMods.clientCode(reaches, game);
        expect(why != null && why.contains("main entrypoint a.Reaches") && why.contains("net/minecraft/class_437"), "an entrypoint whose onInitialize reaches Screen is client-only: " + why);
        expect(PackMods.clientCode(guarded, game) == null, "a client class named only in a method the server never runs is not client-only");
        List<PackCheck.Mod> sided = PackCheck.clientCode(
            List.of(new PackCheck.Mod("reaches.jar", reaches, "unknown", "folder"), new PackCheck.Mod("guarded.jar", guarded, "unknown", "folder")),
            new PackLoaders.GameClasses(Set.of("net/minecraft/class_1937"), Set.of("net/minecraft/class_1937", "net/minecraft/class_437"))
        );
        expect(
            sided.get(0).side().equals("client") && sided.get(0).source().contains("reaches client-only game classes") && sided.get(1).side().equals("unknown"),
            "a side-unknown jar is client-only by its code: " + sided
        );
        expect(PackLoaders.classMap(writeText(scratch.resolve("i.tiny"), "tiny\t2\t0\tofficial\tintermediary\nc\tdyn\tnet/minecraft/class_437\n\tm\t()V\ta\tmethod_1\n"))
            .equals(Map.of("dyn", "net/minecraft/class_437")), "Tiny v2 classes map official to intermediary names");
        expect(PackLoaders.classMap(writeText(scratch.resolve("j.tsrg"), "a net/minecraft/util/A\n\tb func_1_a\nc/ net/minecraft/\n"))
            .equals(Map.of("a", "net/minecraft/util/A")), "TSRG classes map official to Forge's names");

        // The Java build: the loader's jars call a JDK member; a Java whose class lacks it needs the rule's release.
        String call = "x/V.<init>:(Ljava/util/jar/Manifest;)V";
        Path libraries = Files.createDirectories(scratch.resolve("loader/libraries/cpw/mods/modlauncher/8.0.9"));
        jar(libraries.resolve("modlauncher-8.0.9.jar"), Map.of("x/Caller.class", out.resolve("x/Caller.class")));
        expect(PackLoaders.caller(scratch.resolve("loader/libraries/cpw/mods/modlauncher"), call) != null, "the loader's jars are read for the JDK call");
        expect(PackLoaders.caller(scratch.resolve("loader/libraries/cpw/mods/modlauncher"), "x/V.<init>:(Ljava/util/jar/Manifest;Ljava/lang/String;)V") == null, "only that exact call");
        Path oldJdk = Files.createDirectories(scratch.resolve("jdk-old/jre/lib")), newJdk = Files.createDirectories(scratch.resolve("jdk-new/jre/lib"));
        jar(oldJdk.resolve("rt.jar"), Map.of("x/V.class", out.resolve("x/V.class")));
        Files.writeString(src.resolve("x/V.java"), "package x; public class V { public V(java.util.jar.Manifest m, String name) { } }");
        Path newOut = Files.createDirectories(scratch.resolve("client-code/new"));
        expect(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", newOut.toString(), src.resolve("x/V.java").toString()) == 0, "new V compiles");
        jar(newJdk.resolve("rt.jar"), Map.of("x/V.class", newOut.resolve("x/V.class")));
        expect(PackLoaders.jdkDeclares(scratch.resolve("jdk-old"), call) && !PackLoaders.jdkDeclares(scratch.resolve("jdk-new"), call), "a JDK's rt.jar says whether it has the member");
        var rule = Json.object(Json.array(PackLoaders.data(harness).get("javaBuilds")).get(0));
        expect(
            rule.get("call").equals("sun/security/util/ManifestEntryVerifier.<init>:(Ljava/util/jar/Manifest;)V") && rule.get("release").equals("jdk8u312-b07"),
            "Forge 1.16.5's old ModLauncher runs on Temurin 8u312, the last Java 8 with ManifestEntryVerifier(Manifest)"
        );
        String answer = "{\"release_name\":\"jdk8u312-b07\",\"binaries\":[{\"package\":{\"link\":\"https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u312-b07/a.zip\"," +
            "\"checksum\":\"" + "d".repeat(64) + "\",\"checksum_link\":\"https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u312-b07/a.zip.sha256.txt\"}}]}";
        var asset = JdkDownloads.releaseAsset(answer, "jdk8u312-b07", "windows/x64");
        expect(asset.sha256().equals("d".repeat(64)) && asset.checksumFile().toString().endsWith(".sha256.txt"), "a pinned release comes with the vendor's checksum and checksum file");
        expect(
            throwsMessage(() -> JdkDownloads.releaseAsset(answer.replace("https://github.com", "http://example.com"), "jdk8u312-b07", "windows/x64")).contains("No published"),
            "a release download off Adoptium's or GitHub's https is refused"
        );
    }

    static Path writeText(Path file, String text) throws IOException {
        Files.writeString(file, text);
        return file;
    }

    static Path jar(Path target, Map<String, Object> entries) throws IOException {
        zip(target, entries);
        return target;
    }

    static void zip(Path target, Map<String, Object> entries) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue() instanceof Path p ? Files.readAllBytes(p) : String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    interface Action {
        void run() throws Exception;
    }

    static String throwsMessage(Action action) {
        try {
            action.run();
            return "";
        } catch (Exception failed) {
            return String.valueOf(failed.getMessage());
        }
    }

    static long count(PackCheck check, String kind) {
        return check.findings.stream().filter(f -> kind.equals(Json.object(f).get("kind"))).count();
    }

    static Set<String> kinds(PackCheck check) {
        Set<String> kinds = new HashSet<>();
        for (Object f : check.findings) kinds.add(String.valueOf(Json.object(f).get("kind")));
        return kinds;
    }

    static String text(PackCheck check, String kind) {
        StringBuilder out = new StringBuilder();
        for (Object f : check.findings) if (kind.equals(Json.object(f).get("kind"))) out
            .append(Json.object(f).get("summary"))
            .append(' ')
            .append(Json.object(f).get("jars"))
            .append('\n');
        return out.toString();
    }

    static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        count++;
    }
}
