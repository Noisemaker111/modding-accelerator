import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * pack-check reads a pack: a CurseForge export unpacked once, a CurseForge app instance, a Modrinth .mrpack, a Prism
 * instance, the vanilla launcher's folder and a bare mods folder whose loader is read from its jars; Forge's mods.toml
 * with a bundled jar, Fabric's fabric.mod.json and Quilt's quilt.mod.json; and what it finds in them: duplicates,
 * missing and wrong-version dependencies, game and loader version mismatches, declared conflicts, a mod for another
 * loader, shared look files and block names.
 * Needs no game and no network (modaccel.pack.offline; mod sides are pre-cached).
 *
 * The Prehistoric World 2 case: Traverse bundles Biolith 1.0.1-alpha.1, whose saved climate list makes a world open
 * once and never again. The known fault must be found through the bundled copy, and must go away once a newer Biolith
 * sits in mods/, because Forge loads the highest version of a mod id wherever it is.
 */
public final class PackMetadataTests extends PackCheckFixtures {

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("modaccel-pack");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", scratch.resolve("home").toString());
        System.setProperty("modaccel.pack.offline", "true");
        try {
            forge(harness, scratch);
            inputs(harness, scratch);
            fabricAndQuilt(harness, scratch);
            System.out.println(
                count +
                " pack metadata checks passed: six pack inputs, Forge/Fabric/Quilt metadata with bundled mods, duplicate ids, missing and wrong-version mods," +
                " game and loader version mismatches, declared conflicts, mods for another loader, shared look files and block names, mod sides from" +
                " CurseForge and Modrinth, client-only mods and remembering them"
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
        // Windows users keep packs under folders with spaces and brackets ("Sodium Plus 2.2.11.mrpack" in the sweep): reading one
        // and writing its fixed copy (whose own name has spaces) work there, and a server-only repair marks the file in the copy.
        Path spaced = Files.createDirectories(scratch.resolve("My Packs (test) & more")).resolve("Small Pack 1.0.mrpack");
        Files.copy(mrpack, spaced);
        PackCheck spacedCheck = check(harness, scratch.resolve("receipts with spaces"));
        PackCheck.Pack fromSpaced = spacedCheck.read(spaced);
        Path fixedCopy = spacedCheck.fixedCopy(
            fromSpaced,
            new PackRepairs.Change(List.of(), List.of(), Map.of(), List.of("lithium.jar"), List.of(), List.of())
        );
        String fixedIndex;
        try (var zip = new java.util.zip.ZipFile(fixedCopy.toFile())) {
            fixedIndex = new String(zip.getInputStream(zip.getEntry("modrinth.index.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
        var lithiumEntry = Json.array(Json.object(Json.parse(fixedIndex)).get("files")).stream().map(Json::object).filter(f -> f.get("path").equals("mods/lithium.jar")).findFirst().orElseThrow();
        expect(
            fromSpaced.mods().size() == 2 &&
            fixedCopy.getFileName().toString().equals("Small Pack 1.0 (modaccel fixed).mrpack") &&
            Json.object(lithiumEntry.get("env")).get("server").equals("unsupported"),
            "a pack under a path with spaces is read, and its fixed copy marks a server-only jar: " + fixedCopy + " " + lithiumEntry
        );

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
}
