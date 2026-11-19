import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * mmt port --jar: a compiled mod moved to another release with tools/JarPort.java, for everyone who has only the .jar.
 * No source and no build: the mod's classes are relinked against the new release (GameDiff's route map from the game's
 * own jars), its metadata asks for the new release, and what does not link is named rather than guessed.
 */
final class PortJar {

    private PortJar() {}

    static Map<String, Object> run(Path home, Mmt.Options options, Mmt.Report report) throws Exception {
        Path jar = Path.of(options.get("jar", "")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !jar.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("--jar needs a mod's .jar file; " + jar + " is not one.");
        }
        String to = options.get("to", "");
        if (to.isBlank()) {
            throw new IllegalArgumentException("mmt port --jar needs --to VERSION, the Minecraft release to move the mod to.");
        }
        String toml = metadata(jar);
        if (toml == null) {
            throw new IllegalArgumentException(
                jar.getFileName() + " has no META-INF/neoforge.mods.toml. mmt port --jar moves NeoForge mods from 1.20.5 on " +
                "(they ship in Mojang's names); for Fabric and older Forge mods, port their source with --root."
            );
        }
        String from = options.get("from", "");
        if (from.isBlank()) {
            from = builtFor(toml);
        }
        if (from == null) {
            throw new IllegalArgumentException(
                jar.getFileName() + " does not say which one Minecraft release it was built for. Add --from VERSION."
            );
        }
        if (Versions.compare(from, "1.20.5") < 0 || Versions.compare(to, "1.20.5") < 0) {
            throw new IllegalArgumentException("mmt port --jar moves NeoForge mods between 1.20.5 and newer releases (Mojang's names at run time).");
        }
        var blocked = Route.blocked(home, from, to);
        if (blocked != null) {
            throw new IllegalArgumentException(String.valueOf(blocked.getOrDefault("details", blocked.get("reason"))));
        }
        Path out = options.values.containsKey("out")
            ? Path.of(options.get("out", "")).toAbsolutePath().normalize()
            : jar.resolveSibling(outName(jar.getFileName().toString(), from, to));
        if (out.equals(jar)) {
            throw new IllegalArgumentException("--out is the jar being ported; give another file.");
        }

        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(home, 25);
        Path classes = compile(home, jdk, report);
        String cp = classes + File.pathSeparator + home.resolve("tools/perf/lib/asm-9.9.jar") + File.pathSeparator + home.resolve("tools/perf/lib/asm-commons-9.9.jar");
        Path cache = Path.of(System.getProperty("user.home"), ".mmt", "jar-port");
        List<String> chain = Route.plan(home, from, to).versions;
        String differ = Fingerprints.files(List.of(home.resolve("tools/GameDiff.java"), home.resolve("tools/VersionMap.java")));
        Path map = cache.resolve("maps").resolve("neoforge-" + String.join("_", chain) + "-" + differ + ".json");
        if (!Files.isRegularFile(map)) {
            Files.createDirectories(map.getParent());
            var diff = Run.command(
                List.of(jdk.java().toString(), "-Xmx6g", "-cp", cp, "GameDiff", "--chain", String.join(",", chain), "--loader", "neoforge", "--out", map.toString()),
                report.root, Map.of(), report.log("route-map"), 900
            );
            report.stage("route-map", diff, Map.of("chain", chain));
            if (diff.exit() != 0) {
                throw new IOException("The game's change map from " + from + " to " + to + " could not be made. " + Run.tail(diff.log()));
            }
        }
        Path receipt = report.directory.resolve("jar-port.json");
        Files.createDirectories(receipt.getParent());
        var ported = Run.command(
            List.of(jdk.java().toString(), "-Xmx6g", "-cp", cp, "JarPort", "--from", from, "--to", to, "--loader", "neoforge",
                "--map", map.toString(), "--fixes", home.resolve("knowledge/fixes.json").toString(), "--out", out.toString(), "--report", receipt.toString(), jar.toString()),
            report.root, Map.of(), report.log("jar-port"), 900
        );
        report.stage("jar-port", ported, Map.of());
        if (ported.exit() != 0 || !Files.isRegularFile(receipt)) {
            throw new IOException("The jar could not be ported. " + Run.tail(ported.log()));
        }
        var row = Json.object(Json.array(Json.read(receipt).get("mods")).get(0));
        if (row.containsKey("error")) {
            throw new IOException(jar.getFileName() + " could not be read: " + row.get("error"));
        }
        List<Object> unlinked = Json.array(row.get("unlinked"));
        List<Object> silent = Json.array(row.get("silent"));
        Map<String, Object> changed = Json.object(row.get("changed"));
        int renamed = changed.values().stream().mapToInt(v -> ((Number) v).intValue()).sum();
        StringBuilder details = new StringBuilder();
        details.append(jar.getFileName()).append(" (Minecraft ").append(from).append(") -> ").append(out).append(" (Minecraft ").append(to).append(")\n");
        details.append(renamed == 0 ? "Nothing in its code needed a new name" : renamed + " references moved to the new release's names " + changed).append(".\n");
        if (unlinked.isEmpty()) {
            details.append("Every reference to the game links against ").append(to).append(".");
        } else {
            details.append(unlinked.size()).append(" references do not link against ").append(to)
                .append(" and were left as they were; the game fails where it reaches them:\n");
            unlinked.stream().limit(15).forEach(u -> details.append("  ").append(u).append('\n'));
            if (unlinked.size() > 15) {
                details.append("  ... ").append(unlinked.size() - 15).append(" more in ").append(receipt).append('\n');
            }
        }
        if (!silent.isEmpty()) {
            details.append("\n").append(silent.size()).append(" methods override game methods ").append(to).append(" no longer has; they load and are never called.");
        }
        details.append("\nCheck it in the game: mmt pack-check --pack FOLDER_WITH_mods --minecraft ").append(to).append(" --loader neoforge");
        return Json.map(
            "ok", unlinked.isEmpty(),
            "jar", jar.toString(),
            "out", out.toString(),
            "from", from,
            "to", to,
            "changed", changed,
            "unlinked", unlinked,
            "silent", silent,
            "details", details.toString().strip()
        );
    }

    static String metadata(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("META-INF/neoforge.mods.toml");
            return e == null ? null : new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static final Pattern MINECRAFT = Pattern.compile("(?s)modId\\s*=\\s*\"minecraft\"[^\\[]*?versionRange\\s*=\\s*\"([^\"]*)\"|versionRange\\s*=\\s*\"([^\"]*)\"[^\\[]*?modId\\s*=\\s*\"minecraft\"");

    /** The one release the metadata's game range names ("[1.21.1]", "[1.21.1,1.21.2)"), or null when it allows several. */
    static String builtFor(String toml) {
        Matcher m = MINECRAFT.matcher(toml);
        if (!m.find()) {
            return null;
        }
        String range = (m.group(1) != null ? m.group(1) : m.group(2)).replace(" ", "");
        Matcher exact = Pattern.compile("\\[(\\d+(?:\\.\\d+)+)(?:\\]|,(\\d+(?:\\.\\d+)+)\\))").matcher(range);
        if (!exact.matches()) {
            return null;
        }
        String low = exact.group(1);
        String high = exact.group(2);
        if (high == null) {
            return low;
        }
        // [1.21.1,1.21.2) is one release; [1.21,1.21.2) is two.
        String[] l = low.split("\\.");
        String[] h = high.split("\\.");
        boolean next = l.length == 3 && h.length == 3 && l[0].equals(h[0]) && l[1].equals(h[1]) && Integer.parseInt(h[2]) == Integer.parseInt(l[2]) + 1;
        return next ? low : null;
    }

    static String outName(String name, String from, String to) {
        String base = name.replaceFirst("(?i)\\.jar$", "");
        return (base.contains(from) ? base.replace(from, to) : base + "-mc" + to) + ".jar";
    }

    /** JarPort and what it reads, compiled once per version of their sources. */
    static Path compile(Path home, Runtimes.Jdk jdk, Mmt.Report report) throws Exception {
        List<Path> sources = new ArrayList<>();
        for (String s : List.of("tools/JarPort.java", "tools/FixExpr.java", "tools/Exposure.java", "tools/GameDiff.java", "tools/VersionMap.java", "tools/cli/Json.java")) {
            sources.add(home.resolve(s));
        }
        Path classes = Path.of(System.getProperty("user.home"), ".mmt", "jar-port", "classes", Fingerprints.files(sources));
        if (Files.isRegularFile(classes.resolve("ready"))) {
            return classes;
        }
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(
            jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
            "--release", "25", "-encoding", "UTF-8",
            "-cp", home.resolve("tools/perf/lib/asm-9.9.jar") + File.pathSeparator + home.resolve("tools/perf/lib/asm-commons-9.9.jar"),
            "-d", classes.toString()
        ));
        sources.forEach(p -> command.add(p.toString()));
        var result = Run.command(command, report.root, Map.of(), report.log("tools-compile"), 180);
        report.stage("tools-compile", result, Map.of());
        if (result.exit() != 0) {
            throw new IOException("The jar porter could not compile. " + Run.tail(result.log()));
        }
        Files.writeString(classes.resolve("ready"), "ok");
        return classes;
    }
}
