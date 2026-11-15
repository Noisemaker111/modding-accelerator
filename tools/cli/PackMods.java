import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * What each jar in a pack declares, read the way its loader reads it: Forge's and NeoForge's mods.toml and
 * neoforge.mods.toml, Fabric's fabric.mod.json and Quilt's quilt.mod.json, with the mods bundled inside (Forge's
 * jarjar, Fabric's and Quilt's jars). Only the metadata the pack's loader reads counts; a jar that has only another
 * loader's metadata is recorded as foreign. Also an index from class package and mixin config to jar, so a crash can be
 * traced back to the mod whose code it ran.
 */
final class PackMods {

    private PackMods() {}

    record Provider(String jar, String version, boolean bundled, String format) {}

    /** kind: "required" (the loader refuses to start without it), "breaks" (refuses to start with it). */
    record Dependency(String owner, String id, List<String> ranges, String format, String kind) {}

    static final class Scan {

        /** Mod id to the jars that provide it (bundled copies included). */
        final Map<String, List<Provider>> providers = new TreeMap<>();
        final List<Dependency> dependencies = new ArrayList<>();
        /** Jar to the metadata files of other loaders it carries, when it has none of the pack's loader. */
        final Map<String, List<String>> foreign = new TreeMap<>();
        /** Jars with no loader metadata at all. */
        final List<String> notMods = new ArrayList<>();
        /** Jars that plug into the loader itself (language providers, mod locators such as Connector), with no mod metadata of their own. */
        final List<String> plugins = new ArrayList<>();
        final List<String> unreadable = new ArrayList<>();
        /** Jar to side, when its own metadata says it is client- or server-only (Fabric's "environment"). */
        final Map<String, String> sides = new TreeMap<>();
        /** Class package to the jar its classes are in. */
        final Map<String, String> packages = new HashMap<>();
        /** Mixin config file name to its jar. */
        final Map<String, String> mixins = new TreeMap<>();
        /** Look and drop files (blockstates, models, textures, loot tables) to the jars that ship them. */
        final Map<String, List<String>> blockFiles = new TreeMap<>();
        /** English block name to the block ids that carry it. */
        final Map<String, Set<String>> blockNames = new TreeMap<>();

        /** The top-level jar's main mod ids. */
        List<String> idsOf(String jar) {
            List<String> ids = new ArrayList<>();
            for (var entry : providers.entrySet()) for (Provider p : entry.getValue()) if (
                p.jar().equals(jar) && !p.bundled()
            ) ids.add(entry.getKey());
            return ids;
        }

        /** The version the loader will use for an id: the highest one found anywhere, bundled or not. */
        Provider used(String id) {
            List<Provider> found = providers.get(id);
            if (found == null || found.isEmpty()) return null;
            return found.stream().max((a, b) -> Versions.compare(a.version(), b.version())).get();
        }
    }

    static final List<String> FORMATS = List.of(
        "META-INF/neoforge.mods.toml",
        "META-INF/mods.toml",
        "quilt.mod.json",
        "fabric.mod.json"
    );
    static final Pattern LOOK = Pattern.compile(
        "assets/[^/]+/(blockstates/.+\\.json|models/(block|item)/.+\\.json|textures/(block|item)/.+\\.png)|data/[^/]+/loot_tables?/blocks/.+\\.json"
    );
    static final Pattern BLOCK_KEY = Pattern.compile("block\\.([a-z0-9_\\-]+)\\.([a-z0-9_/]+)");
    static final Pattern KEY = Pattern.compile("^\\s*([A-Za-z]+)\\s*=\\s*[\"']?([^\"'#]*)[\"']?");

    /** Every jar in the pack, read with the metadata files the loader reads (most preferred first). */
    static Scan scan(List<PackCheck.Mod> mods, List<String> metadata) {
        Scan scan = new Scan();
        for (PackCheck.Mod mod : mods) {
            if (!mod.file().endsWith(".jar")) continue;
            try (var zip = new ZipFile(mod.jar().toFile())) {
                read(zip, mod.file(), false, metadata, scan);
                index(zip, mod.file(), scan);
            } catch (IOException unreadable) {
                scan.unreadable.add(mod.file() + ": " + unreadable.getMessage());
            }
        }
        return scan;
    }

    static void index(ZipFile zip, String jar, Scan scan) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && !name.startsWith("META-INF/") && name.indexOf('/') > 0) {
                scan.packages.putIfAbsent(name.substring(0, name.lastIndexOf('/')).replace('/', '.'), jar);
                continue;
            }
            if (LOOK.matcher(name).matches()) scan.blockFiles.computeIfAbsent(name, k -> new ArrayList<>()).add(jar);
            if (name.matches("assets/[^/]+/lang/en_us\\.json")) try (var in = zip.getInputStream(entry)) {
                if (Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) instanceof Map<?, ?> lang) {
                    for (var line : lang.entrySet()) {
                        Matcher block = BLOCK_KEY.matcher(String.valueOf(line.getKey()));
                        if (!block.matches() || !(line.getValue() instanceof String shown) || shown.isBlank()) continue;
                        scan.blockNames
                            .computeIfAbsent(shown.strip().toLowerCase(Locale.ROOT), k -> new TreeSet<>())
                            .add(block.group(1) + ":" + block.group(2));
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                /* a lang file that is not JSON: the game skips it too */
            }
        }
    }

    /** One jar (or a jar bundled in one): its mods, dependencies, mixin configs and the jars inside it. */
    static void read(ZipFile zip, String owner, boolean bundled, List<String> metadata, Scan scan) throws IOException {
        String format = null;
        for (String file : metadata) if (zip.getEntry(file) != null) {
            format = file;
            break;
        }
        String manifestVersion = "", modType = "null", embedded = "null";
        ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
        if (manifest != null) try (var in = zip.getInputStream(manifest)) {
            var attributes = new java.util.jar.Manifest(in).getMainAttributes();
            manifestVersion = Objects.requireNonNullElse(attributes.getValue("Implementation-Version"), "");
            modType = String.valueOf(attributes.getValue("FMLModType"));
            embedded = String.valueOf(attributes.getValue("Embedded-Dependencies-Mod"));
            String configs = attributes.getValue("MixinConfigs");
            if (configs != null && !bundled) for (String c : configs.split(",")) if (!c.isBlank()) scan.mixins.put(c.strip(), owner);
        }
        if (format == null) {
            if (bundled) return;
            List<String> others = new ArrayList<>();
            for (String file : FORMATS) if (zip.getEntry(file) != null) others.add(file);
            boolean plugin = !modType.equals("null") || zip.stream().anyMatch(PackMods::loaderService);
            if (plugin) {
                // A loader plugin: its own mod, if it carries one, is a jar inside it (Connector's Embedded-Dependencies-Mod).
                scan.plugins.add(owner);
                if (!embedded.equals("null")) nestedRead(zip, owner, List.of(embedded), metadata, scan);
            } else if (others.isEmpty()) scan.notMods.add(owner);
            else scan.foreign.put(owner, others);
            return;
        }
        String text = new String(zip.getInputStream(zip.getEntry(format)).readAllBytes(), StandardCharsets.UTF_8);
        List<String> nested = new ArrayList<>();
        if (format.endsWith(".toml")) toml(text, owner, bundled, format, manifestVersion, scan);
        else if (format.equals("fabric.mod.json")) fabric(text, owner, bundled, scan, nested);
        else quilt(text, owner, bundled, scan, nested);
        ZipEntry jarjar = zip.getEntry("META-INF/jarjar/metadata.json");
        if (jarjar != null) for (Object j : Json.array(
            Json.object(Json.parse(new String(zip.getInputStream(jarjar).readAllBytes(), StandardCharsets.UTF_8))).get("jars")
        )) nested.add(Json.text(Json.object(j), "path", ""));
        nestedRead(zip, owner, nested, metadata, scan);
    }

    static void nestedRead(ZipFile zip, String owner, List<String> nested, List<String> metadata, Scan scan) throws IOException {
        for (String path : nested) {
            ZipEntry inner = zip.getEntry(path);
            if (inner == null) continue;
            Path temporary = Files.createTempFile("modaccel-nested-", ".jar");
            try {
                try (var in = zip.getInputStream(inner)) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                try (var innerZip = new ZipFile(temporary.toFile())) {
                    read(innerZip, owner + "!" + path.substring(path.lastIndexOf('/') + 1), true, metadata, scan);
                }
            } catch (ZipException notJar) {
                /* a bundled resource that is not a jar */
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** A service file that plugs into Forge's or NeoForge's loader (a language provider, mod locator or transformer). */
    static boolean loaderService(ZipEntry entry) {
        String name = entry.getName();
        return name.startsWith("META-INF/services/cpw.mods") ||
            name.startsWith("META-INF/services/net.minecraftforge") ||
            name.startsWith("META-INF/services/net.neoforged");
    }

    /** mods.toml and neoforge.mods.toml, line by line: [[mods]], [[dependencies.X]] and [[mixins]]. */
    static void toml(String text, String owner, boolean bundled, String format, String jarVersion, Scan scan) {
        String section = "", modId = null, dependency = null, range = "", type = "", side = "";
        boolean mandatory = false;
        List<String> lines = new ArrayList<>(text.lines().toList());
        lines.add("[[end]]");
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                if (dependency != null) {
                    boolean server = !side.equalsIgnoreCase("CLIENT");
                    if (server && (mandatory || type.equalsIgnoreCase("required"))) scan.dependencies.add(
                        new Dependency(owner, dependency, List.of(range), format, "required")
                    );
                    if (server && type.equalsIgnoreCase("incompatible")) scan.dependencies.add(
                        new Dependency(owner, dependency, List.of(range.isEmpty() ? "*" : range), format, "breaks")
                    );
                }
                if (modId != null) {
                    // A [[mods]] block without a version line: the jar's own version.
                    scan.providers.computeIfAbsent(modId, k -> new ArrayList<>()).add(new Provider(owner, jarVersion, bundled, format));
                    modId = null;
                }
                // "[[mods]] #mandatory" is how Forge's template writes it: the comment is not part of the name.
                section = line.replaceFirst("\\s*#.*$", "");
                dependency = null;
                mandatory = false;
                range = "";
                type = "";
                side = "";
                continue;
            }
            Matcher match = KEY.matcher(line);
            if (!match.find()) continue;
            String key = match.group(1), value = match.group(2).strip();
            if (section.equals("[[mods]]") && key.equals("modId")) modId = value;
            if (section.equals("[[mods]]") && key.equals("version") && modId != null) {
                String version = value.equals("${file.jarVersion}") ? jarVersion : value;
                scan.providers.computeIfAbsent(modId, k -> new ArrayList<>()).add(new Provider(owner, version, bundled, format));
                modId = null;
            }
            if (section.equals("[[mixins]]") && key.equals("config") && !bundled) scan.mixins.put(value, owner);
            if (section.startsWith("[[dependencies")) {
                if (key.equals("modId")) dependency = value;
                if (key.equals("mandatory")) mandatory = value.equals("true");
                if (key.equals("type")) type = value;
                if (key.equals("versionRange")) range = value;
                if (key.equals("side")) side = value;
            }
        }
    }

    static void fabric(String text, String owner, boolean bundled, Scan scan, List<String> nested) {
        var meta = Json.object(lenient(text));
        String id = Json.text(meta, "id", ""), version = Json.text(meta, "version", "");
        if (id.isEmpty()) return;
        scan.providers.computeIfAbsent(id, k -> new ArrayList<>()).add(new Provider(owner, version, bundled, "fabric.mod.json"));
        for (Object alias : Json.array(meta.getOrDefault("provides", List.of()))) scan.providers
            .computeIfAbsent(String.valueOf(alias), k -> new ArrayList<>())
            .add(new Provider(owner, version, bundled, "fabric.mod.json"));
        if (!bundled) {
            String environment = Json.text(meta, "environment", "*");
            if (environment.equals("client") || environment.equals("server")) scan.sides.put(owner, environment);
            for (Object m : Json.array(meta.getOrDefault("mixins", List.of()))) {
                if (m instanceof String config) scan.mixins.put(config, owner);
                else if (m instanceof Map<?, ?> config) scan.mixins.put(String.valueOf(config.get("config")), owner);
            }
        }
        for (String kind : List.of("depends", "breaks")) for (var entry : Json.object(meta.getOrDefault(kind, Map.of())).entrySet()) {
            List<String> ranges = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) for (Object r : list) ranges.add(String.valueOf(r));
            else ranges.add(String.valueOf(entry.getValue()));
            scan.dependencies.add(
                new Dependency(owner, entry.getKey(), ranges, "fabric.mod.json", kind.equals("depends") ? "required" : "breaks")
            );
        }
        for (Object j : Json.array(meta.getOrDefault("jars", List.of()))) nested.add(Json.text(Json.object(j), "file", ""));
    }

    static void quilt(String text, String owner, boolean bundled, Scan scan, List<String> nested) {
        var meta = Json.object(Json.object(lenient(text)).get("quilt_loader"));
        String id = Json.text(meta, "id", ""), version = Json.text(meta, "version", "");
        if (id.isEmpty()) return;
        scan.providers.computeIfAbsent(id, k -> new ArrayList<>()).add(new Provider(owner, version, bundled, "quilt.mod.json"));
        for (Object p : Json.array(meta.getOrDefault("provides", List.of()))) {
            String alias = p instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(p);
            scan.providers.computeIfAbsent(alias, k -> new ArrayList<>()).add(new Provider(owner, version, bundled, "quilt.mod.json"));
        }
        Object mixin = Json.object(lenient(text)).get("mixin");
        if (!bundled) for (Object m : mixin instanceof List<?> list ? list : mixin == null ? List.of() : List.of(mixin)) scan.mixins.put(
            String.valueOf(m),
            owner
        );
        for (String kind : List.of("depends", "breaks")) for (Object d : Json.array(meta.getOrDefault(kind, List.of()))) {
            String dependency;
            List<String> ranges = new ArrayList<>();
            boolean optional = false;
            if (d instanceof Map<?, ?> m) {
                dependency = String.valueOf(m.get("id"));
                optional = Boolean.TRUE.equals(m.get("optional"));
                Object versions = m.get("versions");
                if (versions instanceof List<?> list) for (Object r : list) ranges.add(String.valueOf(r));
                else if (versions != null) ranges.add(String.valueOf(versions));
            } else dependency = String.valueOf(d);
            if (ranges.isEmpty()) ranges.add("*");
            if (dependency.contains(":")) dependency = dependency.substring(dependency.indexOf(':') + 1);
            if (!optional) scan.dependencies.add(
                new Dependency(owner, dependency, ranges, "quilt.mod.json", kind.equals("depends") ? "required" : "breaks")
            );
        }
        for (Object j : Json.array(meta.getOrDefault("jars", List.of()))) nested.add(String.valueOf(j));
    }

    /** Mod metadata is often written by hand: a JSON reader that fails would hide the mod, so a broken file reads as empty. */
    static Object lenient(String text) {
        try {
            return Json.parse(text.replaceFirst("^\uFEFF", ""));
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** Fabric and Quilt compare versions as SemVer, where build metadata (after "+") never counts: 0.5.11+mc1.21 is 0.5.11. */
    static String semantic(String version) {
        int plus = version.indexOf('+');
        return plus > 0 ? version.substring(0, plus) : version;
    }

    /** Does a version satisfy a dependency's ranges? Maven ranges ([1,2)) for .toml, Fabric/Quilt predicates otherwise; any range may match. */
    static boolean accepts(Dependency dependency, String version) {
        for (String range : dependency.ranges()) {
            String r = range.strip();
            if (r.isEmpty() || r.equals("*")) return true;
            if (dependency.format().endsWith(".toml")) {
                try {
                    if (MavenRange.own(r, version)) return true;
                } catch (RuntimeException unreadable) {
                    return true;
                }
            } else if (Versions.matches(r.replaceAll("\\+[^\\s]*", ""), semantic(version))) return true;
        }
        return false;
    }
}
