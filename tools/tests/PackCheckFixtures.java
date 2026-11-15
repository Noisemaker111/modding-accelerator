import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** What the pack-check suites share: their check count, a PackCheck on the harness, and jars and findings built and read here. */
class PackCheckFixtures {

    static int count;

    static PackMods.Scan statics(PackCheck check, PackCheck.Pack pack, PackLoaders.Loader loader) throws Exception {
        PackMods.Scan scan = PackMods.scan(pack.mods(), loader.metadata(pack.minecraft()));
        PackChecks.run("static", new PackChecks.Context(pack, scan, loader, check.findings));
        return scan;
    }

    static PackCheck check(Path harness, Path scratch, String... options) {
        List<String> args = new ArrayList<>(List.of("pack-check"));
        args.addAll(List.of(options));
        return new PackCheck(harness, new Mmt.Options(args.toArray(String[]::new)), new Mmt.Report(scratch, "pack-check"));
    }

    static String toml(String id, String version, String dependencies) {
        // Forge's template comments its section lines; the parser must read past that.
        return "modLoader=\"javafml\" #mandatory\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]] #mandatory\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n" + dependencies;
    }

    static String dependency(String id, String mandatory, String range) {
        return "[[dependencies.x]] #optional\nmodId=\"" + id + "\"\nmandatory=" + mandatory + "\nversionRange=\"" + range + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
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
