import java.nio.file.*;
import java.util.*;

/**
 * The behaviour fingerprint's comparisons, offline (FINGERPRINT.md): flattening, a same-version diff, the cross-version
 * normalisation, perf-optimize's gate (leaves two baselines disagree on are left out; a candidate that differs elsewhere
 * fails), the probe's layer choice for every version, and namespaces read from a mod's metadata and assets.
 */
public final class FingerprintTest {
    static int checks;

    static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    static Map<String, Object> record(String minecraft, String kabel, String barrel, String tickets, List<Object> blocks) {
        Map<String, Object> level = Json.map(
            "blockEntities", Json.map("1,2,3", kabel, "4,5,6", barrel),
            "tickets", Json.map("0,0", tickets),
            "blockCounts", Json.map("mymod:kabel", 1, "minecraft:stone", 10),
            "items", Json.map("minecraft:cobblestone", 768)
        );
        return Json.map(
            "schema", 1,
            "target", Json.map("minecraft", minecraft, "namespaces", List.of("mymod")),
            "registries", Json.map("minecraft:block", blocks, "recipes", List.of("mymod:kabel minecraft:crafting")),
            "placement", Json.map("placed", 1, "byItem", Json.map("mymod:kabel", "Block{mymod:kabel}")),
            "checkpoints", List.of(Json.map("tick", 0, "levels", Json.map("minecraft:overworld", level)), Json.map("tick", 20, "levels", Json.map("minecraft:overworld", level))),
            "roundTrip", Json.map("same", true),
            "mods", List.of("mymod 1.0"),
            "arguments", Json.map("out", "anywhere")
        );
    }

    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]).toAbsolutePath().normalize();
        Path scratch = Files.createTempDirectory("modaccel-fingerprint-test");
        List<Object> blocks = List.of("minecraft:stone", "mymod:kabel");
        var a = record("1.21.1", "{id:\"mymod:kabel\",x:1}", "{Items:[]}", "player 31 unknown 33", blocks);

        // Flattening: checkpoints by tick, lists of names as sets, run metadata left out.
        var flat = FingerprintDiff.flatten(a);
        expect(flat.containsKey("checkpoints/20/levels/minecraft:overworld/tickets/0,0"), "checkpoints are keyed by tick: " + flat.keySet());
        expect(flat.containsKey("registries/minecraft:block/mymod:kabel"), "a list of names is a set");
        expect(!flat.containsKey("mods/mymod 1.0") && !flat.keySet().stream().anyMatch(k -> k.startsWith("arguments")), "run metadata is not behaviour");

        // Same version: every leaf counts.
        var same = FingerprintDiff.compare(a, record("1.21.1", "{id:\"mymod:kabel\",x:1}", "{Items:[]}", "player 31 unknown 33", blocks), new FingerprintDiff.Normaliser("exact", Set.of()));
        expect(Boolean.TRUE.equals(same.get("same")), "two equal records are the same");
        var ticket = FingerprintDiff.compare(a, record("1.21.1", "{id:\"mymod:kabel\",x:1}", "{Items:[]}", "player 31", blocks), new FingerprintDiff.Normaliser("exact", Set.of()));
        expect(Boolean.FALSE.equals(ticket.get("same")) && FingerprintDiff.text(ticket).contains("tickets"), "a lost chunk ticket is a difference: " + FingerprintDiff.text(ticket));

        // Across versions: saved data and vanilla leaves are left out, the mod's own entries and the game's renames count.
        var rules = Json.read(harness.resolve("knowledge/fingerprint-normalise.json"));
        var across = FingerprintDiff.Normaliser.across(rules, Set.of("mymod"), "1.21.1 -> 1.21.4");
        var b = record("1.21.4", "{components:{},id:\"mymod:kabel\",x:1}", "{Items:[],DataVersion:4189}", "player 31", List.of("minecraft:stone", "mymod:kabel"));
        expect(Boolean.TRUE.equals(FingerprintDiff.compare(a, b, across).get("same")), "a data-format change is not a behaviour change across versions: " + FingerprintDiff.compare(a, b, across));
        var lost = record("1.21.4", "{}", "{}", "", List.of("minecraft:stone"));
        var verdict = FingerprintDiff.compare(a, lost, across);
        expect(Boolean.FALSE.equals(verdict.get("same")) && Json.write(verdict).contains("registries/minecraft:block/mymod:kabel"), "a lost registry entry of the mod fails a port: " + verdict);
        var renamed = FingerprintDiff.Normaliser.across(rules, Set.of("mymod"), "x");
        expect(renamed.rename("minecraft:grass,minecraft:grass_block").equals("minecraft:short_grass,minecraft:grass_block"), "renames are whole ids");

        // perf-optimize's gate: what baselines disagree on is left out; any other difference fails.
        Path b0 = save(scratch, "b0", record("1.21.1", "{x:1}", "{Items:[1]}", "player 31 unknown 33", blocks));
        Path b1 = save(scratch, "b1", record("1.21.1", "{x:1}", "{Items:[2]}", "player 31 unknown 33", blocks));
        Path goodC0 = save(scratch, "c0", record("1.21.1", "{x:1}", "{Items:[3]}", "player 31 unknown 33", blocks));
        Path goodC1 = save(scratch, "c1", record("1.21.1", "{x:1}", "{Items:[4]}", "player 31 unknown 33", blocks));
        var passed = FingerprintDiff.gate(List.of(b0, b1), List.of(goodC0, goodC1));
        expect(Boolean.TRUE.equals(passed.get("passed")) && ((Number) passed.get("unstable")).intValue() == 2, "a leaf two baselines disagree on is left out and counted: " + passed);
        Path badC1 = save(scratch, "c1bad", record("1.21.1", "{x:1}", "{Items:[4]}", "player 31", blocks));
        var failed = FingerprintDiff.gate(List.of(b0, b1), List.of(goodC0, badC1));
        expect(Boolean.FALSE.equals(failed.get("passed")) && Json.write(failed.get("differingSections")).contains("tickets"), "a candidate that loses a ticket fails: " + failed);
        var nothing = FingerprintDiff.gate(List.of(b0, save(scratch, "b2", Json.map("other", 1))), List.of(goodC0, goodC1));
        expect(Boolean.FALSE.equals(nothing.get("passed")), "a gate whose baselines agree on nothing decides nothing, and fails");

        // The probe's version layer: the newest at or below the game's.
        expect(Fingerprint.fingerprintLayer(harness, "1.21.1").toString().replace('\\', '/').endsWith("fingerprint/1.20.5/ModaccelFingerprintGame.java"), "1.21.1 uses the 1.20.5 layer");
        expect(Fingerprint.fingerprintLayer(harness, "26.3").toString().replace('\\', '/').endsWith("fingerprint/26.1/ModaccelFingerprintGame.java"), "26.3 uses the 26.1 layer");
        try {
            Fingerprint.fingerprintLayer(harness, "1.12.2");
            expect(false, "a game older than every layer is refused");
        } catch (java.io.IOException refused) {
            checks++;
        }

        // Namespaces: the [[mods]] ids (filled from gradle.properties) and assets folders, not the dependencies'.
        Path mod = scratch.resolve("a mod with spaces");
        Files.createDirectories(mod.resolve("src/main/templates/META-INF"));
        Files.createDirectories(mod.resolve("src/main/resources/assets/mymod"));
        Files.createDirectories(mod.resolve("src/main/resources/assets/minecraft"));
        Files.writeString(mod.resolve("gradle.properties"), "mod_id=mymod\n");
        Files.writeString(mod.resolve("src/main/templates/META-INF/neoforge.mods.toml"), "[[mods]]\nmodId=\"${mod_id}\"\n[[dependencies.${mod_id}]]\nmodId=\"somelib\"\n");
        expect(Fingerprint.modIds(mod).equals(new TreeSet<>(Set.of("mymod"))), "namespaces are the mod's own: " + Fingerprint.modIds(mod));

        // A world-check log line compares across runs: times, paths and ids replaced.
        expect(Fingerprint.normalLogLine("Took 123 ms to load C:\\Users\\x\\a b\\file.json @1a2b3c4d").equals("Took <time> to load <path> b\\file.json @<id>")
            || Fingerprint.normalLogLine("Took 123 ms to load C:\\Users\\x\\file.json @1a2b3c4d").equals("Took <time> to load <path> @<id>"), "log lines are normalised");
        System.out.println(checks + " fingerprint checks passed: flattening, same-version and cross-version diffs, the perf gate's unstable leaves, version layers, namespaces, log lines");
    }

    static Path save(Path dir, String name, Map<String, Object> record) throws Exception {
        Path file = dir.resolve(name + ".json");
        Json.save(file, record);
        return file;
    }
}
