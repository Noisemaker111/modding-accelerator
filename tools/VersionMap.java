import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.*;

/**
 * VersionMap - what Minecraft renamed, moved or changed between two versions, generated from
 * published mappings instead of written by hand.
 *
 * Fabric's intermediary names are stable across versions: the same intermediary name is the same
 * class, method or field in every release. Joining each version's readable names (Mojang's
 * official mappings, or yarn) through intermediary gives every rename and every signature change.
 *
 *   java tools/VersionMap.java <from> <to> [--names mojmap|yarn|mcp[:channel]] [--out map.json] [--cache dir]
 *   java tools/VersionMap.java --chain <v1,v2,...> [--names mojmap|yarn|mcp[:channel]] [--to-names ...] --out map.json
 *     (one map across every update, with history; a chain crossing 1.14.4 reads MCP names up to the
 *     seam through SRG ids, translates keys through the obfuscated names both lanes share, and reads
 *     the target namespace past it -- Mojang's by default for an MCP source, or --to-names)
 *
 * Releases before 1.14.4 have no intermediary and no Mojang mappings; Forge's SRG ids are their
 * stable identity instead, with MCP's published names as the readable side (see Srg).
 *   java tools/VersionMap.java --map map.json --explain <build log>
 *   java tools/VersionMap.java --map map.json --fix <build log> --repo <dir> [--rules learned.json] [--dry-run]
 *   java tools/VersionMap.java --map map.json --learn <a.diff> [--learn <b.diff>...] --out learned.json
 *
 * Mappings download once into the cache (default ~/.mmt/mappings). Versions from 26.1 ship
 * unobfuscated and have no intermediary yet; they are reported, not guessed.
 * Exit: 0 ok, 2 usage or I/O error.
 */
public final class VersionMap {

    static Path cache = Path.of(System.getProperty("user.home"), ".mmt", "mappings");
    static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The published name sets a table can be built from; mcp may pin a channel (mcp:stable_39). */
    static boolean knownNames(String names) {
        return names != null && (names.equals("mojmap") || names.equals("yarn") || names.equals("mcp") || names.startsWith("mcp:"));
    }

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        String names = "mojmap", out = null, map = null, explain = null, fix = null, repo = ".", rules =
            null, fromNames = null, toNames = null, chain = null;
        List<String> learn = new ArrayList<>();
        boolean dryRun = false;
        boolean preflight = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--names" -> names = args[++i];
                case "--from-names" -> fromNames = args[++i];
                case "--to-names" -> toNames = args[++i];
                case "--out" -> out = args[++i];
                case "--cache" -> cache = Path.of(args[++i]);
                case "--map" -> map = args[++i];
                case "--explain" -> explain = args[++i];
                case "--fix" -> fix = args[++i];
                case "--repo" -> repo = args[++i];
                case "--dry-run" -> dryRun = true;
                case "--preflight" -> preflight = true;
                case "--rules" -> rules = args[++i];
                case "--learn" -> learn.add(args[++i]);
                case "--chain" -> chain = args[++i];
                case "--supers" -> {
                    // "Type Super1 Super2 ..." per line, simple names: a member renamed on a supertype is the receiver's too.
                    for (String line : Files.readAllLines(Path.of(args[++i]), StandardCharsets.UTF_8)) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 1) SUPERS.put(parts[0], new HashSet<>(Arrays.asList(parts).subList(1, parts.length)));
                    }
                }
                default -> pos.add(args[i]);
            }
        }
        if (chain != null) {
            if (!knownNames(names) || out == null) {
                System.err.println(
                    "usage: java tools/VersionMap.java --chain <v1,v2,...> [--names mojmap|yarn|mcp[:channel]] --out map.json"
                );
                System.exit(2);
            }
            long t0 = System.nanoTime();
            List<String> versions = List.of(chain.split(","));
            String n = names, t = toNames;
            boolean srgSeam = versions.stream().anyMatch(v -> releaseCompare(v, SEAM) < 0);
            Chain c = Chain.of(versions, v -> laneTable(v, n, t, srgSeam));
            Path target = Path.of(out);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, c.json(names), StandardCharsets.UTF_8);
            Diff d = c.diff;
            System.out.printf(
                "chain %s (%s, %d updates): classes %d renamed or moved, %d removed; methods %d renamed, %d moved, " +
                "%d signature changes, %d removed; fields %d renamed, %d removed; %d history events (%.1fs)%n",
                String.join(" -> ", versions),
                names,
                versions.size() - 1,
                d.classRenamed.size(),
                d.classRemoved.size(),
                d.methodRenamed.size(),
                d.methodMoved.size(),
                d.methodSignature.size(),
                d.methodRemoved.size(),
                d.fieldRenamed.size(),
                d.fieldRemoved.size(),
                c.history.size(),
                (System.nanoTime() - t0) / 1e9
            );
            System.out.println("        " + target);
            return;
        }
        if (preflight && map != null && fix == null) {
            Preflight.run(Path.of(map), Path.of(repo).toAbsolutePath().normalize(), dryRun);
            return;
        }
        if (map != null && fix != null) {
            Fix.run(
                Path.of(map),
                Path.of(fix),
                Path.of(repo).toAbsolutePath().normalize(),
                dryRun,
                rules == null ? null : Path.of(rules)
            );
            return;
        }
        if (map != null && !learn.isEmpty()) {
            Learn.run(Path.of(map), learn, Path.of(out != null ? out : "learned.json"));
            return;
        }
        if (map != null && explain != null) {
            Explain.run(Path.of(map), Path.of(explain));
            return;
        }
        if (
            pos.size() != 2 ||
            !knownNames(names) ||
            (fromNames != null && !knownNames(fromNames)) ||
            (toNames != null && !knownNames(toNames))
        ) {
            System.err.println(
                "usage: java tools/VersionMap.java <from> <to> [--names mojmap|yarn|mcp[:channel]] [--from-names] [--to-names] [--out map.json]"
            );
            System.exit(2);
        }
        long t0 = System.nanoTime();
        // Same version with two namespaces converts a source tree (yarn to official, for example);
        // different versions with one namespace is the usual rename/move map.
        String aNames = fromNames != null ? fromNames : names, bNames = toNames != null ? toNames : names;
        Table a = Table.load(pos.get(0), aNames);
        Table b = Table.load(pos.get(1), bNames);
        Diff d = Diff.of(a, b);
        Path target = out != null
            ? Path.of(out)
            : cache.resolve("map-" + names + "-" + pos.get(0) + "-to-" + pos.get(1) + ".json");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, d.json(pos.get(0), pos.get(1), names), StandardCharsets.UTF_8);
        System.out.printf(
            "map %s -> %s (%s): classes %d renamed or moved, %d removed; methods %d renamed, %d moved, " +
            "%d signature changes, %d removed; fields %d renamed, %d removed (%.1fs)%n",
            pos.get(0),
            pos.get(1),
            names,
            d.classRenamed.size(),
            d.classRemoved.size(),
            d.methodRenamed.size(),
            d.methodMoved.size(),
            d.methodSignature.size(),
            d.methodRemoved.size(),
            d.fieldRenamed.size(),
            d.fieldRemoved.size(),
            (System.nanoTime() - t0) / 1e9
        );
        System.out.println("        " + target);
    }

    // ------------------------------------------------------------------ downloads

    static Path fetch(String url, String name) throws IOException, InterruptedException {
        Path p = cache.resolve(name);
        if (Files.isRegularFile(p) && Files.size(p) > 0) {
            return p;
        }
        Files.createDirectories(cache);
        HttpResponse<byte[]> r = HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " for " + url);
        }
        Path tmp = Files.createTempFile(cache, "dl", ".part");
        Files.write(tmp, r.body());
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        return p;
    }

    static String fetchText(String url, String name) throws IOException, InterruptedException {
        return Files.readString(fetch(url, name), StandardCharsets.UTF_8);
    }

    static String zipEntry(Path jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) {
                throw new IOException(entry + " not in " + jar);
            }
            return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Tiny JSON value lookup, enough for Mojang's and Fabric's metadata. */
    static String jsonString(String json, String key, int from) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find(from) ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ one version's names

    record Member(String owner, String name, String desc, String interDesc) {}

    static final class Table {

        final String version;
        String lane = "inter"; // the key space: srg (Forge SRG ids) or inter (Fabric intermediary ids)
        String mappings; // the published artifacts these names came from (the mcp lane's provenance)
        final Map<String, String> classes = new HashMap<>(); // intermediary -> named
        final Map<String, Member> methods = new HashMap<>(); // key -> named
        final Map<String, Member> fields = new HashMap<>();
        final Map<String, List<String>> methodsByInterName = new HashMap<>();

        Table(String version) {
            this.version = version;
        }

        /** Ids assigned once and never reused: intermediary's method_/field_ and SRG's func_/field_. */
        static boolean stableId(String interName) {
            return interName.startsWith("method_") || interName.startsWith("field_") || interName.startsWith("func_");
        }

        static String key(String interOwner, String interName, String interDesc) {
            return stableId(interName) ? interOwner + "." + interName : interOwner + "." + interName + interDesc;
        }

        /** Stable method ids by bare name, so a member moved to another owner is found when its key misses. */
        void indexMoved() {
            for (String k : methods.keySet()) {
                String name = k.substring(k.indexOf('.') + 1);
                if (name.startsWith("method_") || name.startsWith("func_")) {
                    methodsByInterName.computeIfAbsent(name, x -> new ArrayList<>()).add(k);
                }
            }
        }

        static Table load(String version, String names) throws Exception {
            if (names.equals("mcp") || names.startsWith("mcp:")) {
                return loadMcp(version, names.length() > 4 ? names.substring(4) : null);
            }
            String manifest = fetchText(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "version_manifest_v2.json"
            );
            int at = manifest.indexOf("\"id\": \"" + version + "\"");
            if (at < 0) {
                at = manifest.indexOf("\"id\":\"" + version + "\"");
            }
            if (at < 0) {
                throw new IOException("Minecraft " + version + " is not in Mojang's version list");
            }
            String versionJson = fetchText(jsonString(manifest, "url", at), "version-" + version + ".json");
            Path inter;
            try {
                inter = fetch(
                    "https://maven.fabricmc.net/net/fabricmc/intermediary/" +
                    version +
                    "/intermediary-" +
                    version +
                    "-v2.jar",
                    "intermediary-" + version + "-v2.jar"
                );
            } catch (IOException e) {
                throw new IOException(
                    "Minecraft " +
                    version +
                    " has no published intermediary (versions from 26.1 ship " +
                    "unobfuscated); this map covers obfuscated versions only for now"
                );
            }
            Tiny it = Tiny.parse(zipEntry(inter, "mappings/mappings.tiny")); // official, intermediary
            Table t = new Table(version);
            Map<String, String> obfToInter = it.classes;
            if (names.equals("yarn")) {
                String meta = fetchText(
                    "https://meta.fabricmc.net/v2/versions/yarn/" + version,
                    "yarn-meta-" + version + ".json"
                );
                String yv = jsonString(meta, "version", 0);
                if (yv == null) {
                    throw new IOException("no yarn for " + version);
                }
                Path y = fetch(
                    "https://maven.fabricmc.net/net/fabricmc/yarn/" + yv + "/yarn-" + yv + "-v2.jar",
                    "yarn-" + yv + "-v2.jar"
                );
                Tiny yt = Tiny.parse(zipEntry(y, "mappings/mappings.tiny")); // intermediary, named
                Map<String, String> interToNamed = yt.classes;
                for (String obf : obfToInter.keySet()) {
                    String in = obfToInter.get(obf);
                    t.classes.put(in, interToNamed.getOrDefault(in, in));
                }
                fill(
                    t,
                    it,
                    obfToInter,
                    (owner, name, interDesc, isMethod) -> {
                        String named =
                            (isMethod ? yt.methods : yt.fields).get(owner + "." + name + (isMethod ? interDesc : ""));
                        if (named == null && !isMethod) {
                            named = yt.fieldsNoDesc.get(owner + "." + name);
                        }
                        return named != null ? named : name;
                    },
                    interToNamed
                );
            } else {
                int dl = versionJson.indexOf("\"client_mappings\"");
                if (dl < 0) {
                    throw new IOException("Minecraft " + version + " publishes no official mappings");
                }
                String proguard = fetchText(jsonString(versionJson, "url", dl), "client-" + version + ".txt");
                int sdl = versionJson.indexOf("\"server_mappings\"");
                Proguard pg = Proguard.parse(proguard);
                if (sdl >= 0) {
                    pg.merge(
                        Proguard.parse(fetchText(jsonString(versionJson, "url", sdl), "server-" + version + ".txt"))
                    );
                }
                Map<String, String> interToNamed = new HashMap<>();
                for (var e : obfToInter.entrySet()) {
                    interToNamed.put(e.getValue(), pg.classes.getOrDefault(e.getKey(), e.getKey()));
                }
                t.classes.putAll(interToNamed);
                // Official names keyed by obf owner + obf name + obf descriptor, joined to intermediary.
                Map<String, String> interToObf = new HashMap<>();
                obfToInter.forEach((o, i) -> interToObf.put(i, o));
                fill(t, it, obfToInter, (owner, name, interDesc, isMethod) -> null, interToNamed);
                // Second pass: the readable name for each member comes from the obf side.
                for (var entry : it.memberRows) {
                    String named = entry.method
                        ? pg.methods.get(entry.obfOwner + "." + entry.obfName + entry.obfDesc)
                        : pg.fields.get(entry.obfOwner + "." + entry.obfName);
                    if (named == null) {
                        continue;
                    }
                    String interOwner = obfToInter.getOrDefault(entry.obfOwner, entry.obfOwner);
                    String interDesc = Tiny.remapDesc(entry.obfDesc, obfToInter);
                    Map<String, Member> table = entry.method ? t.methods : t.fields;
                    String k = key(interOwner, entry.interName, interDesc);
                    Member m = table.get(k);
                    if (m != null) {
                        table.put(k, new Member(m.owner(), named, m.desc(), m.interDesc()));
                    }
                }
            }
            t.indexMoved();
            return t;
        }

        interface Namer {
            String name(String interOwner, String interName, String interDesc, boolean method);
        }

        static void fill(
            Table t,
            Tiny it,
            Map<String, String> obfToInter,
            Namer namer,
            Map<String, String> interToNamed
        ) {
            for (var row : it.memberRows) {
                String interOwner = obfToInter.getOrDefault(row.obfOwner, row.obfOwner);
                String interDesc = Tiny.remapDesc(row.obfDesc, obfToInter);
                String named = namer.name(interOwner, row.interName, interDesc, row.method);
                if (named == null) {
                    named = stableId(row.interName) ? row.obfName : row.interName;
                }
                String namedDesc = Tiny.remapDesc(interDesc, interToNamed);
                Member m = new Member(interToNamed.getOrDefault(interOwner, interOwner), named, namedDesc, interDesc);
                (row.method ? t.methods : t.fields).put(key(interOwner, row.interName, interDesc), m);
            }
        }

        /**
         * One version's names the pre-1.14.4 way: Forge's SRG table for identity (joined.srg in
         * de.oceanlabs.mcp:mcp's srg zips, published to 1.12.2; joined.tsrg in mcp_config from 1.12.2)
         * and MCP's CSVs for the readable side. The channel names the published name set the source
         * was written against (stable_39, snapshot_20171003); the default is the last stable set
         * published for the release.
         */
        static Table loadMcp(String version, String channel) throws Exception {
            McpNames mn = mcpNames(version, channel);
            Table t = fromSrg(version, mn.keys(), mn.csv());
            t.mappings = mn.source();
            return t;
        }

        /**
         * One release's SRG data in the shapes its readers use: keys (obfuscated to SRG id), names
         * (obfuscated to MCP name, in Proguard's layout), the name CSVs, and the artifacts it all
         * came from.
         */
        record McpNames(Tiny keys, Proguard names, Map<String, String> csv, String source) {}

        static McpNames mcpNames(String version, String channel) throws Exception {
            String srgMeta = fetchText(FORGE_MAVEN + "de/oceanlabs/mcp/mcp/maven-metadata.xml", "mcp-meta.xml");
            Tiny rows;
            String srgFrom;
            if (srgMeta.contains("<version>" + version + "</version>")) {
                Path z = fetch(
                    FORGE_MAVEN + "de/oceanlabs/mcp/mcp/" + version + "/mcp-" + version + "-srg.zip",
                    "mcp-" + version + "-srg.zip"
                );
                rows = Srg.srg(zipEntry(z, "joined.srg"));
                srgFrom = "mcp " + version + " joined.srg";
            } else {
                Path z = fetch(
                    FORGE_MAVEN + "de/oceanlabs/mcp/mcp_config/" + version + "/mcp_config-" + version + ".zip",
                    "mcp_config-" + version + ".zip"
                );
                rows = Srg.tsrg(zipEntry(z, "config/joined.tsrg"));
                srgFrom = "mcp_config " + version + " joined.tsrg";
            }
            String group = channel != null && channel.startsWith("snapshot_") ? "mcp_snapshot" : "mcp_stable";
            String namesMeta = fetchText(FORGE_MAVEN + "de/oceanlabs/mcp/" + group + "/maven-metadata.xml", group + "-meta.xml");
            String artifact = mcpArtifact(namesMeta, version, channel);
            if (artifact == null) {
                throw new IOException(
                    "no MCP " +
                    group +
                    " names published for " +
                    version +
                    " (a legacy source can only be read in the names it was written against)"
                );
            }
            Path nz = fetch(
                FORGE_MAVEN + "de/oceanlabs/mcp/" + group + "/" + artifact + "/" + group + "-" + artifact + ".zip",
                group + "-" + artifact + ".zip"
            );
            Map<String, String> csv = Srg.csv(zipEntry(nz, "fields.csv"), zipEntry(nz, "methods.csv"));
            return new McpNames(rows, Srg.proguardOf(rows, csv), csv, srgFrom + ", " + group + " " + artifact);
        }

        /** The published MCP name artifact for a release: an explicit channel wins, else the last stable set. */
        static String mcpArtifact(String metadata, String version, String channel) {
            List<String> all = new ArrayList<>();
            Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
            while (m.find()) {
                all.add(m.group(1));
            }
            if (channel != null) {
                String prefix = channel.substring(channel.indexOf('_') + 1) + "-";
                for (String a : all) {
                    if (a.startsWith(prefix)) {
                        return a;
                    }
                }
                return null;
            }
            int secondDot = version.indexOf('.', version.indexOf('.') + 1);
            String minor = secondDot < 0 ? version : version.substring(0, secondDot); // 39-1.12 serves 1.12.2
            String best = null, bestForMinor = null;
            long bestN = -1, bestMinorN = -1;
            for (String a : all) {
                int dash = a.indexOf('-');
                if (dash < 0) {
                    continue;
                }
                long n;
                try {
                    n = Long.parseLong(a.substring(0, dash));
                } catch (NumberFormatException e) {
                    continue;
                }
                String suffix = a.substring(dash + 1);
                if (suffix.equals(version) && n > bestN) {
                    best = a;
                    bestN = n;
                } else if (suffix.equals(minor) && n > bestMinorN) {
                    bestForMinor = a;
                    bestMinorN = n;
                }
            }
            return best != null ? best : bestForMinor;
        }

        /**
         * A table from SRG rows and MCP names. SRG class names are already deobfuscated and MCP never
         * renames a class, so a class's named side is its SRG name; a member MCP never named keeps its
         * SRG id, which is what legacy sources call it by.
         */
        static Table fromSrg(String version, Tiny rows, Map<String, String> names) {
            Table t = new Table(version);
            t.lane = "srg";
            Map<String, String> identity = new HashMap<>();
            for (String srg : rows.classes.values()) {
                identity.put(srg, srg);
            }
            t.classes.putAll(identity);
            fill(t, rows, rows.classes, (owner, name, desc, method) -> names.getOrDefault(name, name), identity);
            t.indexMoved();
            return t;
        }
    }

    // ------------------------------------------------------------------ tiny v2

    static final class Tiny {

        record Row(boolean method, String obfOwner, String obfName, String obfDesc, String interName) {}

        final Map<String, String> classes = new HashMap<>(); // ns0 class -> ns1 class
        final Map<String, String> methods = new HashMap<>(); // ns0 owner.name+desc -> ns1 name
        final Map<String, String> fields = new HashMap<>(); // ns0 owner.name -> ns1 name
        final Map<String, String> fieldsNoDesc = fields;
        final List<Row> memberRows = new ArrayList<>();

        /** One member in both shapes its readers use: the lookup maps and the row list. */
        void add(boolean method, String obfOwner, String obfName, String obfDesc, String interName) {
            if (method) {
                methods.put(obfOwner + "." + obfName + obfDesc, interName);
            } else {
                fields.put(obfOwner + "." + obfName, interName);
            }
            memberRows.add(new Row(method, obfOwner, obfName, obfDesc, interName));
        }

        static Tiny parse(String text) {
            Tiny t = new Tiny();
            String owner0 = null;
            for (String line : text.split("\r?\n")) {
                if (line.startsWith("c\t")) {
                    String[] p = line.split("\t", -1);
                    owner0 = p[1];
                    t.classes.put(p[1], p[2].isEmpty() ? p[1] : p[2]);
                } else if (owner0 != null && (line.startsWith("\tm\t") || line.startsWith("\tf\t"))) {
                    String[] p = line.split("\t", -1);
                    boolean method = p[1].equals("m");
                    String desc = p[2], n0 = p[3], n1 = p.length > 4 && !p[4].isEmpty() ? p[4] : n0;
                    t.add(method, owner0, n0, desc, n1);
                }
            }
            return t;
        }

        static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;]+);");

        static String remapDesc(String desc, Map<String, String> classes) {
            Matcher m = CLASS_IN_DESC.matcher(desc);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement("L" + classes.getOrDefault(m.group(1), m.group(1)) + ";")
                );
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ Mojang ProGuard

    static final class Proguard {

        final Map<String, String> classes = new HashMap<>(); // obf -> named (slashes)
        final Map<String, String> methods = new HashMap<>(); // obfOwner.obfName+obfDesc -> named
        final Map<String, String> fields = new HashMap<>(); // obfOwner.obfName -> named

        void merge(Proguard o) {
            o.classes.forEach(classes::putIfAbsent);
            o.methods.forEach(methods::putIfAbsent);
            o.fields.forEach(fields::putIfAbsent);
        }

        static Proguard parse(String text) {
            // Pass 1: classes, so member descriptors can be written in obf names.
            Map<String, String> namedToObf = new HashMap<>();
            Proguard pg = new Proguard();
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) {
                    continue;
                }
                int arrow = line.indexOf(" -> ");
                String named = line.substring(0, arrow).replace('.', '/');
                String obf = line.substring(arrow + 4, line.length() - 1).replace('.', '/');
                pg.classes.put(obf, named);
                namedToObf.put(named, obf);
            }
            String owner = null;
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    owner = line.substring(line.indexOf(" -> ") + 4, line.length() - 1).replace('.', '/');
                    continue;
                }
                String s = line.trim();
                if (s.startsWith("#")) {
                    continue;
                }
                int arrow = s.indexOf(" -> ");
                String left = s.substring(0, arrow), obfName = s.substring(arrow + 4);
                int paren = left.indexOf('(');
                if (paren < 0) {
                    String[] tp = left.split(" ");
                    pg.fields.put(owner + "." + obfName, tp[tp.length - 1]);
                    continue;
                }
                String head = left.replaceFirst("^\\d+:\\d+:", "");
                paren = head.indexOf('(');
                int close = head.indexOf(')');
                String[] rn = head.substring(0, paren).split(" ");
                String ret = rn[0], name = rn[1];
                String params = head.substring(paren + 1, close);
                StringBuilder desc = new StringBuilder("(");
                if (!params.isEmpty()) {
                    for (String p : params.split(",")) {
                        desc.append(type(p, namedToObf));
                    }
                }
                desc.append(')').append(type(ret, namedToObf));
                pg.methods.put(owner + "." + obfName + desc, name);
            }
            return pg;
        }

        static String type(String t, Map<String, String> namedToObf) {
            int dims = 0;
            while (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2);
            }
            String base =
                switch (t) {
                    case "void" -> "V";
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "char" -> "C";
                    case "short" -> "S";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    case "double" -> "D";
                    default -> {
                        String slash = t.replace('.', '/');
                        yield "L" + namedToObf.getOrDefault(slash, slash) + ";";
                    }
                };
            return "[".repeat(dims) + base;
        }
    }

    // ------------------------------------------------------------------ SRG (MCP)

    /**
     * Forge's SRG mappings: the stable identity of the releases intermediary never covered. Both
     * spellings (the joined.srg text MCP shipped and mcp_config's joined.tsrg) parse into the row
     * shape the other lanes use, with the SRG id standing where the intermediary id stands. SRG field
     * rows carry no type, so a field's descriptor stays empty in this lane.
     */
    static final class Srg {

        static Tiny srg(String text) {
            Tiny t = new Tiny();
            for (String line : text.split("\r?\n")) {
                String[] p = line.split(" ");
                switch (p[0]) {
                    case "CL:" -> {
                        if (p.length == 3) {
                            t.classes.put(p[1], p[2]);
                        }
                    }
                    case "FD:" -> {
                        if (p.length == 3) {
                            int s = p[1].lastIndexOf('/');
                            t.add(
                                false,
                                p[1].substring(0, s),
                                p[1].substring(s + 1),
                                "",
                                p[2].substring(p[2].lastIndexOf('/') + 1)
                            );
                        }
                    }
                    case "MD:" -> {
                        if (p.length == 5) {
                            int s = p[1].lastIndexOf('/');
                            t.add(true, p[1].substring(0, s), p[1].substring(s + 1), p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
                        }
                    }
                    default -> {} // PK: package rows name nothing a class row does not
                }
            }
            return t;
        }

        static Tiny tsrg(String text) {
            Tiny t = new Tiny();
            String owner = null;
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("tsrg2")) {
                    continue;
                }
                if (line.startsWith("\t")) {
                    if (owner == null) {
                        continue;
                    }
                    String[] p = line.trim().split(" ");
                    if (p.length == 3 && p[1].startsWith("(")) {
                        t.add(true, owner, p[0], p[1], p[2]);
                    } else if (p.length >= 2) {
                        t.add(false, owner, p[0], p.length == 3 ? p[1] : "", p[p.length - 1]);
                    }
                } else {
                    String[] p = line.trim().split(" ");
                    if (p.length >= 2) {
                        owner = p[0];
                        t.classes.put(p[0], p[1]);
                    }
                }
            }
            return t;
        }

        /** The same data in Proguard's layout (obfuscated to readable), for readers that walk a jar. */
        static Proguard proguardOf(Tiny rows, Map<String, String> csv) {
            Proguard pg = new Proguard();
            pg.classes.putAll(rows.classes);
            for (Tiny.Row r : rows.memberRows) {
                String named = csv.getOrDefault(r.interName(), r.interName());
                if (r.method()) {
                    pg.methods.put(r.obfOwner() + "." + r.obfName() + r.obfDesc(), named);
                } else {
                    pg.fields.put(r.obfOwner() + "." + r.obfName(), named);
                }
            }
            return pg;
        }

        /** Searge id to MCP name from the first two columns only; the quoted javadoc column is never read. */
        static Map<String, String> csv(String... texts) {
            Map<String, String> names = new HashMap<>();
            for (String text : texts) {
                for (String line : text.split("\r?\n")) {
                    int a = line.indexOf(','), b = a < 0 ? -1 : line.indexOf(',', a + 1);
                    if (b > a) {
                        String id = line.substring(0, a);
                        if (id.startsWith("func_") || id.startsWith("field_")) {
                            names.put(id, line.substring(a + 1, b));
                        }
                    }
                }
            }
            return names;
        }
    }

    // ------------------------------------------------------------------ the seam

    /**
     * The release both name lanes share: the last Forge SRG release and the first with Mojang
     * mappings. Kept equal to Route.FIRST_MAPPED. Chains load this release in SRG names and
     * translate keys past it; the index measures the translation as its own step.
     */
    static final String SEAM = "1.14.4";

    /** The pseudo-step a seam-crossing chain records the MCP to Mojang key translation under. */
    static final String BRIDGE_STEP = SEAM + " -> " + SEAM;

    /** Numeric dotted release order; chain versions are plain releases, so no pre-release handling. */
    static int releaseCompare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int l = i < x.length ? leadingNumber(x[i]) : 0, r = i < y.length ? leadingNumber(y[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    static int leadingNumber(String part) {
        int i = 0;
        while (i < part.length() && Character.isDigit(part.charAt(i))) {
            i++;
        }
        return i == 0 ? 0 : Integer.parseInt(part.substring(0, i));
    }

    /**
     * Mojang's mappings for a version, client and server separately: a member can live on either
     * side, and the two obfuscations do not always agree. A release without published mappings
     * yields empty tables.
     */
    static Proguard[] proguardsFor(String version) throws Exception {
        String manifest = fetchText(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "version_manifest_v2.json"
        );
        int atManifest = manifest.indexOf("\"id\": \"" + version + "\"");
        if (atManifest < 0) {
            atManifest = manifest.indexOf("\"id\":\"" + version + "\"");
        }
        String versionJson = fetchText(jsonString(manifest, "url", atManifest), "version-" + version + ".json");
        int cdl = versionJson.indexOf("\"client_mappings\"");
        int sdl = versionJson.indexOf("\"server_mappings\"");
        Proguard client = cdl < 0
            ? new Proguard()
            : Proguard.parse(fetchText(jsonString(versionJson, "url", cdl), "client-" + version + ".txt"));
        Proguard server = sdl < 0
            ? new Proguard()
            : Proguard.parse(fetchText(jsonString(versionJson, "url", sdl), "server-" + version + ".txt"));
        return new Proguard[] {client, server};
    }

    /** Official member triples (owner, name, descriptor) a version's own mappings know. */
    static Set<String> officialTriples(Proguard[] pgs) {
        Set<String> triples = new HashSet<>();
        for (Proguard pg : pgs) {
            for (var e : pg.methods.entrySet()) {
                String key = e.getKey();
                int dot = key.indexOf('.'), paren = key.indexOf('(');
                String obfOwner = key.substring(0, dot);
                String owner = pg.classes.getOrDefault(obfOwner, obfOwner);
                triples.add(owner + "\0" + e.getValue() + "\0" + Tiny.remapDesc(key.substring(paren), pg.classes));
            }
            for (var e : pg.fields.entrySet()) {
                String key = e.getKey();
                String obfOwner = key.substring(0, key.indexOf('.'));
                String owner = pg.classes.getOrDefault(obfOwner, obfOwner);
                triples.add(owner + "\0" + e.getValue() + "\0");
            }
        }
        return triples;
    }

    /** Official class names a version's own mappings know. */
    static Set<String> officialClasses(Proguard[] pgs) {
        Set<String> names = new HashSet<>();
        for (Proguard pg : pgs) {
            names.addAll(pg.classes.values());
        }
        return names;
    }

    /**
     * The seam translation: SRG keys to intermediary keys at 1.14.4, joined through the obfuscated
     * names both lanes publish (mcp_config's joined.tsrg and intermediary's tiny). Members join on
     * owner, name and descriptor; fields carry no descriptor in SRG, so they join on owner and name.
     * Classes join on the obfuscated class. Forward reads members and classes; a backport reads the
     * Back maps. Built from cached downloads; two small file parses, no network when cached.
     */
    static final class Bridge {
        final Map<String, String> classes = new LinkedHashMap<>(), classesBack = new LinkedHashMap<>();
        final Map<String, String> members = new LinkedHashMap<>(), membersBack = new LinkedHashMap<>();
        /**
         * Fallback identities for members and classes the exact join misses: the retroactive
         * intermediary drops some overloads, so a notch name can be absent there while present in
         * both jars. SRG key to official owner, name and descriptor at the seam, guarded: a notch
         * name resolving differently on the client and server sides yields no fallback. The step
         * file pairs by these identities; chains use them to rescue what the exact join misses.
         * The reverse maps serve backports.
         */
        final Map<String, String> membersOfficial = new LinkedHashMap<>(), membersBackOfficial = new LinkedHashMap<>();
        final Map<String, String> classesOfficial = new LinkedHashMap<>(), classesBackOfficial = new LinkedHashMap<>();

        static Bridge at() throws Exception {
            Tiny srg = Table.mcpNames(SEAM, null).keys();
            Path inter = fetch(
                "https://maven.fabricmc.net/net/fabricmc/intermediary/" + SEAM + "/intermediary-" + SEAM + "-v2.jar",
                "intermediary-" + SEAM + "-v2.jar"
            );
            Tiny it = Tiny.parse(zipEntry(inter, "mappings/mappings.tiny"));
            Proguard[] pgs = proguardsFor(SEAM);
            return join(srg, it, pgs[0], pgs[1]);
        }

        /**
         * The pure join both lanes share: unit-testable without downloads. Callers pass the
         * 1.14.4 artifacts (mcp_config joined.tsrg rows, intermediary tiny, Mojang client and
         * server mappings); every map out is keyed through the shared obfuscated names.
         */
        static Bridge join(Tiny srg, Tiny it, Proguard client, Proguard server) {
            Map<String, String[]> byObf = new HashMap<>();
            for (Tiny.Row r : it.memberRows) {
                byObf.put(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc(), new String[] {
                    it.classes.getOrDefault(r.obfOwner(), r.obfOwner()), r.interName()
                });
            }
            Map<String, String[]> fieldsByObf = new HashMap<>();
            for (Tiny.Row r : it.memberRows) {
                if (!r.method()) {
                    fieldsByObf.putIfAbsent(r.obfOwner() + "\0" + r.obfName(), new String[] {
                        it.classes.getOrDefault(r.obfOwner(), r.obfOwner()), r.interName()
                    });
                }
            }
            Bridge b = new Bridge();
            for (var e : srg.classes.entrySet()) {
                String named = it.classes.get(e.getKey());
                if (named == null) {
                    continue;
                }
                b.classes.put(e.getValue(), named);
                b.classesBack.put(named, e.getValue());
            }
            for (Tiny.Row r : srg.memberRows) {
                String seargeOwner = srg.classes.getOrDefault(r.obfOwner(), r.obfOwner());
                String srgKey = Table.key(seargeOwner, r.interName(), Tiny.remapDesc(r.obfDesc(), srg.classes));
                String[] found = r.method()
                    ? byObf.get(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc())
                    : fieldsByObf.get(r.obfOwner() + "\0" + r.obfName());
                if (found == null) {
                    continue;
                }
                String interKey = Table.key(found[0], found[1], Tiny.remapDesc(r.obfDesc(), it.classes));
                b.members.put(srgKey, interKey);
                b.membersBack.put(interKey, srgKey);
            }
            // Fallback identities for everything the exact join missed, resolved through Mojang's
            // own mappings for both sides (the notch names coincide here; where they do not, the
            // guards below yield no fallback and the member reads as removed, honestly).
            Map<String, String> obfToOfficial = new HashMap<>();
            client.classes.forEach((k, v) -> obfToOfficial.putIfAbsent(k, v));
            server.classes.forEach((k, v) -> obfToOfficial.putIfAbsent(k, v));
            for (Tiny.Row r : srg.memberRows) {
                String seargeOwner = srg.classes.getOrDefault(r.obfOwner(), r.obfOwner());
                String srgKey = Table.key(seargeOwner, r.interName(), Tiny.remapDesc(r.obfDesc(), srg.classes));
                String owner = officialOwner(r.obfOwner(), client, server);
                String name = officialName(r, client, server);
                if (owner != null && name != null) {
                    String okey = owner + "\0" + name + "\0" + Tiny.remapDesc(r.obfDesc(), obfToOfficial);
                    b.membersOfficial.put(srgKey, okey);
                    b.membersBackOfficial.putIfAbsent(okey, srgKey);
                }
                if (b.members.containsKey(srgKey)) {
                    continue;
                }
                String[] found = r.method()
                    ? byObf.get(r.obfOwner() + "\0" + r.obfName() + "\0" + r.obfDesc())
                    : fieldsByObf.get(r.obfOwner() + "\0" + r.obfName());
                if (found == null) {
                    continue;
                }
                String interKey = Table.key(found[0], found[1], Tiny.remapDesc(r.obfDesc(), it.classes));
                b.members.put(srgKey, interKey);
                b.membersBack.put(interKey, srgKey);
            }
            for (var e : srg.classes.entrySet()) {
                String official = officialOwner(e.getKey(), client, server);
                if (official != null) {
                    b.classesOfficial.put(e.getValue(), official);
                    b.classesBackOfficial.putIfAbsent(official, e.getValue());
                }
            }
            return b;
        }

        /** A notch owner's official name, or null when the client and server disagree. */
        static String officialOwner(String obf, Proguard client, Proguard server) {
            String c = client.classes.get(obf), s = server.classes.get(obf);
            if (c != null && s != null && !c.equals(s)) {
                return null;
            }
            return c != null ? c : s;
        }

        /** A notch member's official name, or null when the client and server disagree. */
        static String officialName(Tiny.Row r, Proguard client, Proguard server) {
            String key = r.method() ? r.obfOwner() + "." + r.obfName() + r.obfDesc() : r.obfOwner() + "." + r.obfName();
            String c = r.method() ? client.methods.get(key) : client.fields.get(key);
            String s = r.method() ? server.methods.get(key) : server.fields.get(key);
            if (c != null && s != null && !c.equals(s)) {
                return null;
            }
            return c != null ? c : s;
        }

        /**
         * Official identity for a constructor: neither SRG spelling rows them, so synthesize from
         * the class link. Null unless the key names a constructor whose owner links. A partially
         * remapped descriptor simply misses its target later, honestly.
         */
        String initForward(String srgKey, String seargeDesc) {
            int dot = srgKey.indexOf('.');
            if (dot < 0 || !srgKey.substring(dot + 1).startsWith("<init>")) {
                return null;
            }
            String official = classesOfficial.get(srgKey.substring(0, dot));
            if (official == null) {
                return null;
            }
            return official + "\0<init>\0" + Tiny.remapDesc(seargeDesc, classesOfficial);
        }

        /**
         * The SRG key for a constructor reached from the intermediary side: the intermediary
         * spelling rows them, so translate the owner through the class link and the descriptor
         * back into SRG names. Null unless the key names a constructor whose owner links back.
         */
        String initBack(String interKey, String interDesc) {
            int dot = interKey.indexOf('.');
            if (dot < 0 || !interKey.substring(dot + 1).startsWith("<init>")) {
                return null;
            }
            String searge = classesBack.get(interKey.substring(0, dot));
            if (searge == null) {
                return null;
            }
            return searge + ".<init>" + Tiny.remapDesc(interDesc, classesBack);
        }
    }

    /**
     * Lane-aware table loading for a chain: releases before the seam have no official names and
     * always read MCP names through SRG ids; 1.14.4 reads the requested lane (MCP for an MCP
     * source, official otherwise), except a chain reaching back past the seam always reads it in
     * SRG names so the bridge, not the retroactive intermediary, carries the crossing; later
     * releases read the target namespace (Mojang's when the source is MCP, since the bridge lands
     * in Mojang's names). The bridge between lanes is joined by key, not guessed.
     */
    static Table laneTable(String version, String names, String toNames, boolean srgSeam) throws Exception {
        boolean srg = releaseCompare(version, SEAM) < 0
            || (version.equals(SEAM) && (names.startsWith("mcp") || srgSeam));
        if (srg) {
            return Table.loadMcp(version, names.startsWith("mcp:") ? names.substring(4) : null);
        }
        String modern = names.startsWith("mcp") ? (toNames != null ? toNames : "mojmap") : names;
        return Table.load(version, modern);
    }

    // ------------------------------------------------------------------ the diff

    record Change(String owner, String from, String to, String desc, String newDesc, String newOwner) {}

    static final class Diff {

        final List<String[]> classRenamed = new ArrayList<>();
        final List<String> classRemoved = new ArrayList<>();
        final List<Change> methodRenamed = new ArrayList<>(), methodMoved = new ArrayList<>(), methodSignature =
            new ArrayList<>(), methodRemoved = new ArrayList<>(), fieldRenamed = new ArrayList<>(), fieldRemoved =
            new ArrayList<>();

        static boolean noise(String name) {
            return name.startsWith("lambda$") || name.contains("$") || name.equals("<clinit>");
        }

        static Diff of(Table a, Table b) {
            Diff d = new Diff();
            for (var e : a.classes.entrySet()) {
                String nb = b.classes.get(e.getKey());
                if (nb == null) {
                    d.classRemoved.add(e.getValue());
                } else if (!nb.equals(e.getValue())) {
                    d.classRenamed.add(new String[] { e.getValue(), nb });
                }
            }
            for (var e : a.methods.entrySet()) {
                Member ma = e.getValue();
                if (noise(ma.name())) {
                    continue;
                }
                Member mb = b.methods.get(e.getKey());
                if (mb == null) {
                    String interName = e.getKey().substring(e.getKey().indexOf('.') + 1);
                    List<String> elsewhere = b.methodsByInterName.getOrDefault(interName, List.of());
                    if (!elsewhere.isEmpty()) {
                        Member mv = b.methods.get(elsewhere.get(0));
                        d.methodMoved.add(
                            new Change(ma.owner(), ma.name(), mv.name(), ma.desc(), mv.desc(), mv.owner())
                        );
                    } else {
                        d.methodRemoved.add(new Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                    }
                    continue;
                }
                boolean renamed = !mb.name().equals(ma.name());
                boolean sig = !mb.interDesc().equals(ma.interDesc());
                if (renamed) {
                    d.methodRenamed.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
                }
                if (sig) {
                    d.methodSignature.add(
                        new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())
                    );
                }
            }
            for (var e : a.fields.entrySet()) {
                Member fa = e.getValue();
                if (noise(fa.name())) {
                    continue;
                }
                Member fb = b.fields.get(e.getKey());
                if (fb == null) {
                    d.fieldRemoved.add(new Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
                } else if (!fb.name().equals(fa.name())) {
                    d.fieldRenamed.add(new Change(fa.owner(), fa.name(), fb.name(), fa.desc(), fb.desc(), fb.owner()));
                }
            }
            Comparator<Change> byOwner = Comparator.comparing(Change::owner).thenComparing(Change::from);
            for (var l : List.of(
                d.methodRenamed,
                d.methodMoved,
                d.methodSignature,
                d.methodRemoved,
                d.fieldRenamed,
                d.fieldRemoved
            )) {
                l.sort(byOwner);
            }
            d.classRenamed.sort(Comparator.comparing(x -> x[0]));
            Collections.sort(d.classRemoved);
            return d;
        }

        static String q(String s) {
            if (s == null) {
                return "null";
            }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        static String dots(String s) {
            return s == null ? null : s.replace('/', '.');
        }

        String json(String from, String to, String names) {
            StringBuilder sb = new StringBuilder("{\n");
            sb
                .append("  \"from\": ")
                .append(q(from))
                .append(", \"to\": ")
                .append(q(to))
                .append(", \"names\": ")
                .append(q(names))
                .append(",\n");
            sb.append("  \"classes\": [");
            for (int i = 0; i < classRenamed.size(); i++) {
                sb
                    .append(i == 0 ? "\n" : ",\n")
                    .append("    { \"from\": ")
                    .append(q(dots(classRenamed.get(i)[0])))
                    .append(", \"to\": ")
                    .append(q(dots(classRenamed.get(i)[1])))
                    .append(" }");
            }
            sb.append("\n  ],\n  \"classesRemoved\": [");
            for (int i = 0; i < classRemoved.size(); i++) {
                sb.append(i == 0 ? "\n    " : ",\n    ").append(q(dots(classRemoved.get(i))));
            }
            sb.append("\n  ],\n");
            list(sb, "methods", methodRenamed, true);
            list(sb, "methodsMoved", methodMoved, true);
            list(sb, "signatures", methodSignature, true);
            list(sb, "methodsRemoved", methodRemoved, false);
            list(sb, "fields", fieldRenamed, true);
            list(sb, "fieldsRemoved", fieldRemoved, false);
            sb.setLength(sb.length() - 2);
            return sb.append("\n}\n").toString();
        }

        static void list(StringBuilder sb, String name, List<Change> l, boolean full) {
            sb.append("  \"").append(name).append("\": [");
            for (int i = 0; i < l.size(); i++) {
                Change c = l.get(i);
                sb
                    .append(i == 0 ? "\n" : ",\n")
                    .append("    { \"owner\": ")
                    .append(q(dots(c.owner())))
                    .append(", \"from\": ")
                    .append(q(c.from()))
                    .append(", \"desc\": ")
                    .append(q(c.desc()));
                if (full) {
                    sb.append(", \"to\": ").append(q(c.to())).append(", \"newDesc\": ").append(q(c.newDesc()));
                    if (!c.newOwner().equals(c.owner())) {
                        sb.append(", \"newOwner\": ").append(q(dots(c.newOwner())));
                    }
                }
                sb.append(" }");
            }
            sb.append("\n  ],\n");
        }
    }

    // ------------------------------------------------------------------ a chain of updates

    /**
     * One map across several updates. Every class, method and field of the first release is followed
     * through each neighbouring release by its intermediary id, so A -> B -> C resolves straight to the
     * final name, a move follows the member to its new owner, and anything removed on the way is kept
     * with the update that removed it instead of disappearing. The history is in the first release's
     * names (what the source still says) with the name it had when it changed.
     */
    static final class Chain {

        record Event(
            String kind,
            String owner,
            String name,
            String desc,
            String what,
            String was,
            String to,
            String step
        ) {}

        interface Loader {
            Table load(String version) throws Exception;
        }

        final Diff diff = new Diff();
        final List<Event> history = new ArrayList<>();
        final List<String> versions;

        Chain(List<String> versions) {
            this.versions = versions;
        }

        static Chain of(List<String> versions, Loader loader) throws Exception {
            if (versions.size() < 2) {
                throw new IOException("A chain needs at least two releases");
            }
            Chain c = new Chain(versions);
            Table first = loader.load(versions.get(0)), prev = first, last = first;
            Map<String, String> classes = new LinkedHashMap<>(), methods = new LinkedHashMap<>(), fields =
                new LinkedHashMap<>(); // original key -> current key
            Set<String> classGone = new HashSet<>(), methodGone = new HashSet<>(), fieldGone = new HashSet<>();
            boolean seamCrossed = false;
            Map<String, String> seamRemap = new HashMap<>(); // first-lane class names to last-lane ones
            // Forward bridge misses with a known official identity resolve at the tally against the
            // last version's own mappings (the intermediary drops overloads the jars still carry).
            Map<String, String> pendingMethods = new LinkedHashMap<>(), pendingFields = new LinkedHashMap<>(),
                pendingClasses = new LinkedHashMap<>(); // original key -> official identity at the seam
            for (String k : first.classes.keySet()) {
                classes.put(k, k);
            }
            for (var e : first.methods.entrySet()) {
                if (!Diff.noise(e.getValue().name())) {
                    methods.put(e.getKey(), e.getKey());
                }
            }
            for (var e : first.fields.entrySet()) {
                if (!Diff.noise(e.getValue().name())) {
                    fields.put(e.getKey(), e.getKey());
                }
            }
            for (int i = 1; i < versions.size(); i++) {
                Table cur = loader.load(versions.get(i));
                String step = prev.version + " -> " + cur.version;
                // Crossing the seam changes the key space, so tracked keys are translated through the
                // bridge first (recorded under the bridge step); the update's own changes then read as
                // usual. On the SRG lane a renamed class is linked back up by shared member ids, the
                // index's convention. A translation both renames and moves an element still reads as one
                // bridge event naming its endpoints; the update's own renames read under its own step.
                Bridge bridge = null;
                Map<String, String> links = Map.of(), curMembersByOfficial = Map.of(), curClassesByOfficial = Map.of();
                if (!cur.lane.equals(prev.lane)) {
                    bridge = Bridge.at();
                    seamCrossed = true;
                    seamRemap.putAll(prev.lane.equals("srg") ? bridge.classes : bridge.classesBack);
                    if (prev.lane.equals("srg")) {
                        curMembersByOfficial = indexMembersByOfficial(cur);
                        curClassesByOfficial = indexClassesByOfficial(cur);
                    }
                } else if (cur.lane.equals("srg")) {
                    links = linkSrgClasses(prev, cur);
                }
                boolean srgToInter = bridge != null && prev.lane.equals("srg");
                for (var e : classes.entrySet()) {
                    if (classGone.contains(e.getKey())) {
                        continue;
                    }
                    String was = prev.classes.get(e.getValue()), next = e.getValue(), hop = step;
                    if (bridge != null) {
                        hop = BRIDGE_STEP;
                        if (srgToInter) {
                            next = bridge.classes.get(next);
                            String official = next == null ? bridge.classesOfficial.get(e.getValue()) : null;
                            if (next == null) {
                                next = official == null ? null : curClassesByOfficial.get(official);
                            }
                            if (next == null && official != null) {
                                classGone.add(e.getKey());
                                pendingClasses.put(e.getKey(), official);
                                continue;
                            }
                        } else {
                            next = bridge.classesBack.get(next);
                            if (next == null) {
                                String official = prev.classes.get(e.getValue());
                                next = official == null ? null : bridge.classesBackOfficial.get(official);
                            }
                        }
                    } else {
                        next = links.getOrDefault(next, next);
                    }
                    String now = next == null ? null : cur.classes.get(next);
                    String orig = Diff.dots(first.classes.get(e.getKey()));
                    if (now == null) {
                        classGone.add(e.getKey());
                        c.history.add(new Event("class", null, orig, null, "removed", Diff.dots(was), null, hop));
                        continue;
                    }
                    if (!next.equals(e.getValue())) {
                        e.setValue(next);
                    }
                    if (!now.equals(was)) {
                        c.history.add(
                            new Event("class", null, orig, null, "renamed", Diff.dots(was), Diff.dots(now), hop)
                        );
                    }
                }
                for (var e : methods.entrySet()) {
                    if (methodGone.contains(e.getKey())) {
                        continue;
                    }
                    Member o = first.methods.get(e.getKey()), mp = prev.methods.get(e.getValue());
                    String was = Diff.dots(mp.owner()) + "." + mp.name(), hop = step;
                    if (bridge != null) {
                        String next;
                        if (srgToInter) {
                            next = bridge.members.get(e.getValue());
                            String okey = next == null ? bridge.membersOfficial.get(e.getValue()) : null;
                            if (next == null && okey == null) okey = bridge.initForward(e.getValue(), mp.interDesc());
                            if (next == null) {
                                next = okey == null ? null : curMembersByOfficial.get(okey);
                            }
                            if (next == null && okey != null) {
                                methodGone.add(e.getKey());
                                pendingMethods.put(e.getKey(), okey);
                                continue;
                            }
                        } else {
                            next = bridge.membersBack.get(e.getValue());
                            if (next == null) {
                                Member pm = prev.methods.get(e.getValue());
                                String okey = pm.owner() + "\0" + pm.name() + "\0" + pm.desc();
                                next = bridge.membersBackOfficial.get(okey);
                                if (next == null) next = bridge.initBack(e.getValue(), pm.interDesc());
                            }
                        }
                        if (next == null) {
                            methodGone.add(e.getKey());
                            c.history.add(
                                new Event(
                                    "method",
                                    Diff.dots(o.owner()),
                                    o.name(),
                                    o.desc(),
                                    "removed",
                                    was,
                                    null,
                                    BRIDGE_STEP
                                )
                            );
                            continue;
                        }
                        e.setValue(next);
                        hop = BRIDGE_STEP;
                    }
                    if (!links.isEmpty()) {
                        String translated = followLink(links, cur.methods::containsKey, e.getValue());
                        if (!translated.equals(e.getValue())) {
                            e.setValue(translated);
                        }
                    }
                    Member mc = cur.methods.get(e.getValue());
                    if (mc == null) {
                        String interName = e.getValue().substring(e.getValue().indexOf('.') + 1);
                        List<String> elsewhere = cur.methodsByInterName.getOrDefault(interName, List.of());
                        if (!elsewhere.isEmpty()) {
                            e.setValue(pickMoveTarget(cur, mp, elsewhere, links, ownerOf(e.getValue())));
                            mc = cur.methods.get(e.getValue());
                            c.history.add(
                                new Event(
                                    "method",
                                    Diff.dots(o.owner()),
                                    o.name(),
                                    o.desc(),
                                    "moved",
                                    was,
                                    Diff.dots(mc.owner()) + "." + mc.name(),
                                    step
                                )
                            );
                        } else {
                            methodGone.add(e.getKey());
                            c.history.add(
                                new Event(
                                    "method",
                                    Diff.dots(o.owner()),
                                    o.name(),
                                    o.desc(),
                                    "removed",
                                    was,
                                    null,
                                    step
                                )
                            );
                            continue;
                        }
                    } else if (!mc.name().equals(mp.name())) {
                        c.history.add(
                            new Event(
                                "method",
                                Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "renamed",
                                was,
                                Diff.dots(mc.owner()) + "." + mc.name(),
                                hop
                            )
                        );
                    }
                    // Descriptors compare across the seam in one lane: the previous side remapped through
                    // the bridge's class translation, so only a true signature change reads as one.
                    String mpDesc = bridge != null
                        ? Tiny.remapDesc(mp.interDesc(), srgToInter ? bridge.classes : bridge.classesBack)
                        : mp.interDesc();
                    if (!mc.interDesc().equals(mpDesc)) {
                        c.history.add(
                            new Event(
                                "method",
                                Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "signature",
                                was + mp.desc(),
                                Diff.dots(mc.owner()) + "." + mc.name() + mc.desc(),
                                hop
                            )
                        );
                    }
                }
                for (var e : fields.entrySet()) {
                    if (fieldGone.contains(e.getKey())) {
                        continue;
                    }
                    Member o = first.fields.get(e.getKey()), fp = prev.fields.get(e.getValue());
                    String was = Diff.dots(fp.owner()) + "." + fp.name(), hop = step;
                    if (bridge != null) {
                        String next;
                        if (srgToInter) {
                            next = bridge.members.get(e.getValue());
                            String okey = next == null ? bridge.membersOfficial.get(e.getValue()) : null;
                            if (next == null && okey == null) okey = bridge.initForward(e.getValue(), fp.interDesc());
                            if (next == null) {
                                next = okey == null ? null : curMembersByOfficial.get(okey);
                            }
                            if (next == null && okey != null) {
                                fieldGone.add(e.getKey());
                                pendingFields.put(e.getKey(), okey);
                                continue;
                            }
                        } else {
                            next = bridge.membersBack.get(e.getValue());
                            if (next == null) {
                                Member pm = prev.fields.get(e.getValue());
                                String okey = pm.owner() + "\0" + pm.name() + "\0";
                                next = bridge.membersBackOfficial.get(okey);
                                if (next == null) next = bridge.initBack(e.getValue(), pm.interDesc());
                            }
                        }
                        if (next == null) {
                            fieldGone.add(e.getKey());
                            c.history.add(
                                new Event("field", Diff.dots(o.owner()), o.name(), o.desc(), "removed", was, null, BRIDGE_STEP)
                            );
                            continue;
                        }
                        e.setValue(next);
                        hop = BRIDGE_STEP;
                    }
                    if (!links.isEmpty()) {
                        String translated = followLink(links, cur.fields::containsKey, e.getValue());
                        if (!translated.equals(e.getValue())) {
                            e.setValue(translated);
                        }
                    }
                    Member fc = cur.fields.get(e.getValue());
                    if (fc == null) {
                        fieldGone.add(e.getKey());
                        c.history.add(
                            new Event("field", Diff.dots(o.owner()), o.name(), o.desc(), "removed", was, null, step)
                        );
                    } else if (!fc.name().equals(fp.name())) {
                        c.history.add(
                            new Event(
                                "field",
                                Diff.dots(o.owner()),
                                o.name(),
                                o.desc(),
                                "renamed",
                                was,
                                Diff.dots(fc.owner()) + "." + fc.name(),
                                hop
                            )
                        );
                    }
                }
                prev = cur;
                last = cur;
            }
            // The final map compares the first release with the last along the tracked keys. Something removed
            // on the way and back by the end (same intermediary id) compiles, so it is not reported as removed.
            Diff d = c.diff;
            for (var e : classes.entrySet()) {
                if (pendingClasses.containsKey(e.getKey())) {
                    continue;
                }
                String a = first.classes.get(e.getKey()), b = last.classes.get(e.getValue());
                if (b == null) {
                    d.classRemoved.add(a);
                } else if (!b.equals(a)) {
                    d.classRenamed.add(new String[] { a, b });
                }
            }
            for (var e : methods.entrySet()) {
                if (pendingMethods.containsKey(e.getKey())) {
                    continue;
                }
                Member ma = first.methods.get(e.getKey());
                boolean gone = methodGone.contains(e.getKey());
                Member mb;
                String key;
                if (gone) {
                    mb = last.methods.get(e.getKey());
                    key = e.getKey();
                    if (mb == null) {
                        mb = last.methods.get(e.getValue());
                        key = e.getValue();
                    }
                } else {
                    mb = last.methods.get(e.getValue());
                    key = e.getValue();
                }
                if (mb == null) {
                    d.methodRemoved.add(new Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                    continue;
                }
                if (!key.equals(e.getKey())) {
                    d.methodMoved.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
                } else if (!mb.name().equals(ma.name())) {
                    d.methodRenamed.add(new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner()));
                }
                // Across the seam the first descriptor is remapped through the bridge's class
                // translation before comparing, so only a true signature change reads as one.
                String maSpace = seamCrossed ? Tiny.remapDesc(ma.interDesc(), seamRemap) : ma.interDesc();
                if (!mb.interDesc().equals(maSpace)) {
                    d.methodSignature.add(
                        new Change(ma.owner(), ma.name(), mb.name(), ma.desc(), mb.desc(), mb.owner())
                    );
                }
            }
            for (var e : fields.entrySet()) {
                if (pendingFields.containsKey(e.getKey())) {
                    continue;
                }
                Member fa = first.fields.get(e.getKey()), fb = last.fields.get(e.getValue());
                if (fb == null) {
                    d.fieldRemoved.add(new Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
                } else if (!fb.name().equals(fa.name())) {
                    d.fieldRenamed.add(new Change(fa.owner(), fa.name(), fb.name(), fa.desc(), fb.desc(), fb.owner()));
                }
            }
            resolvePending(c, d, first, last, pendingMethods, pendingFields, pendingClasses);
            Comparator<Change> byOwner = Comparator.comparing(Change::owner).thenComparing(Change::from);
            for (var l : List.of(
                d.methodRenamed,
                d.methodMoved,
                d.methodSignature,
                d.methodRemoved,
                d.fieldRenamed,
                d.fieldRemoved
            )) {
                l.sort(byOwner);
            }
            d.classRenamed.sort(Comparator.comparing(x -> x[0]));
            Collections.sort(d.classRemoved);
            return c;
        }

        /**
         * Pair renamed classes on the SRG lane by shared member ids. The index measures the same
         * links; port follows them so a renamed class keeps its history.
         */
        static Map<String, String> linkSrgClasses(Table prev, Table cur) {
            Map<String, Set<String>> pIds = new HashMap<>(), cIds = new HashMap<>();
            for (var e : prev.methods.entrySet()) {
                if (!e.getValue().name().equals("<init>")) {
                    pIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("m" + idOf(e.getKey()));
                }
            }
            for (var e : prev.fields.entrySet()) {
                pIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("f" + idOf(e.getKey()));
            }
            for (var e : cur.methods.entrySet()) {
                if (!e.getValue().name().equals("<init>")) {
                    cIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("m" + idOf(e.getKey()));
                }
            }
            for (var e : cur.fields.entrySet()) {
                cIds.computeIfAbsent(ownerOf(e.getKey()), x -> new HashSet<>()).add("f" + idOf(e.getKey()));
            }
            Set<String> gone = new HashSet<>(prev.classes.keySet()), fresh = new HashSet<>(cur.classes.keySet());
            gone.removeAll(cur.classes.keySet());
            fresh.removeAll(prev.classes.keySet());
            return linkBySharedIds(gone, fresh, pIds, cIds);
        }

        /**
         * Mutual-best Jaccard matches need at least half shared. A class that grew or shrank around
         * the Flattening can fall below that despite one obvious successor, so an unclaimed pair
         * also links when it shares at least three stable member ids, two of them distinctive,
         * with twice every rival's distinctive count (or a strict win with five or more, since
         * abundant evidence needs less margin). Both directions must choose the same pair; a tie on
         * either side links nothing. Shared ids used by more than MAX_ID_USES classes carry no
         * weight: base-class and packet API ids repeat across whole hierarchies, while a rename
         * keeps its own members' ids. Jaccard ties break by distinctive-id count, then by shared
         * name tokens, and an unbreakable tie links nothing, so no link depends on iteration order.
         */
        static final int MAX_ID_USES = 12;

        static Map<String, String> linkBySharedIds(
            Collection<String> gone,
            Collection<String> fresh,
            Map<String, Set<String>> goneIds,
            Map<String, Set<String>> freshIds
        ) {
            Map<String, Integer> idUses = new HashMap<>();
            for (Map<String, Set<String>> ids : List.of(goneIds, freshIds)) {
                for (Set<String> s : ids.values()) {
                    for (String k : s) {
                        if (stableSrgMember(k)) {
                            idUses.merge(k, 1, Integer::sum);
                        }
                    }
                }
            }
            Map<String, String> best = new HashMap<>(), bestBack = new HashMap<>();
            Map<String, Double> score = new HashMap<>(), scoreBack = new HashMap<>();
            Map<String, Integer> bestRare = new HashMap<>(), bestBackRare = new HashMap<>();
            Map<String, Integer> bestName = new HashMap<>(), bestBackName = new HashMap<>();
            Set<String> deadJ = new HashSet<>(), deadJBack = new HashSet<>();
            Map<String, String> overlapBest = new HashMap<>(), overlapBack = new HashMap<>();
            Map<String, Integer> overlapCount = new HashMap<>(), overlapSecond = new HashMap<>();
            Map<String, Integer> overlapTotal = new HashMap<>();
            Map<String, Integer> overlapBackCount = new HashMap<>(), overlapBackSecond = new HashMap<>();
            Map<String, Integer> stableGone = stableSrgCounts(goneIds), stableFresh = stableSrgCounts(freshIds);
            for (String g : gone) {
                Set<String> gn = goneIds.getOrDefault(g, Set.of());
                if (gn.size() < 4) {
                    continue;
                }
                for (String f : fresh) {
                    Set<String> fn = freshIds.getOrDefault(f, Set.of());
                    if (fn.size() < 4) {
                        continue;
                    }
                    int common = 0, sharedIds = 0, rareIds = 0;
                    for (String n : gn) {
                        if (fn.contains(n)) {
                            common++;
                            if (stableSrgMember(n)) {
                                sharedIds++;
                                if (idUses.getOrDefault(n, 0) <= MAX_ID_USES) {
                                    rareIds++;
                                }
                            }
                        }
                    }
                    double j = (double) common / (gn.size() + fn.size() - common);
                    double sg = score.getOrDefault(g, 0.0);
                    int sr = bestRare.getOrDefault(g, -1);
                    if (j > sg) {
                        score.put(g, j);
                        best.put(g, f);
                        bestRare.put(g, rareIds);
                        bestName.put(g, nameSim(g, f));
                        deadJ.remove(g);
                    } else if (j > 0.0 && j == sg) {
                        int ns = nameSim(g, f);
                        int bns = bestName.getOrDefault(g, -1);
                        if (rareIds > sr || (rareIds == sr && ns > bns)) {
                            score.put(g, j);
                            best.put(g, f);
                            bestRare.put(g, rareIds);
                            bestName.put(g, ns);
                            deadJ.remove(g);
                        } else if (rareIds == sr && ns == bns) {
                            deadJ.add(g);
                        }
                    }
                    double sf = scoreBack.getOrDefault(f, 0.0);
                    int sbr = bestBackRare.getOrDefault(f, -1);
                    if (j > sf) {
                        scoreBack.put(f, j);
                        bestBack.put(f, g);
                        bestBackRare.put(f, rareIds);
                        bestBackName.put(f, nameSim(g, f));
                        deadJBack.remove(f);
                    } else if (j > 0.0 && j == sf) {
                        int sbn = nameSim(g, f);
                        int bbn = bestBackName.getOrDefault(f, -1);
                        if (rareIds > sbr || (rareIds == sbr && sbn > bbn)) {
                            scoreBack.put(f, j);
                            bestBack.put(f, g);
                            bestBackRare.put(f, rareIds);
                            bestBackName.put(f, sbn);
                            deadJBack.remove(f);
                        } else if (rareIds == sbr && sbn == bbn) {
                            deadJBack.add(f);
                        }
                    }
                    if (stableGone.getOrDefault(g, 0) >= 4 && stableFresh.getOrDefault(f, 0) >= 4) {
                        if (rareIds > overlapCount.getOrDefault(g, 0)) {
                            overlapSecond.put(g, overlapCount.getOrDefault(g, 0));
                            overlapCount.put(g, rareIds);
                            overlapTotal.put(g, sharedIds);
                            overlapBest.put(g, f);
                        } else if (rareIds > overlapSecond.getOrDefault(g, 0)) {
                            overlapSecond.put(g, rareIds);
                        }
                        if (rareIds > overlapBackCount.getOrDefault(f, 0)) {
                            overlapBackSecond.put(f, overlapBackCount.getOrDefault(f, 0));
                            overlapBackCount.put(f, rareIds);
                            overlapBack.put(f, g);
                        } else if (rareIds > overlapBackSecond.getOrDefault(f, 0)) {
                            overlapBackSecond.put(f, rareIds);
                        }
                    }
                }
            }
            Map<String, String> links = new HashMap<>();
            Set<String> claimed = new HashSet<>();
            for (var e : best.entrySet()) {
                if (score.get(e.getKey()) >= 0.5 && e.getKey().equals(bestBack.get(e.getValue()))
                    && !deadJ.contains(e.getKey()) && !deadJBack.contains(e.getValue())) {
                    links.put(e.getKey(), e.getValue());
                    claimed.add(e.getValue());
                }
            }
            for (var e : overlapBest.entrySet()) {
                String g = e.getKey(), f = e.getValue();
                int n = overlapCount.getOrDefault(e.getKey(), 0), second = overlapSecond.getOrDefault(e.getKey(), 0);
                int t = overlapTotal.getOrDefault(e.getKey(), 0);
                boolean clearWinner = t >= 3 && n >= 2
                    && (second == 0 || n >= 2 * second || (n >= 5 && n > second));
                int backSecond = overlapBackSecond.getOrDefault(f, 0);
                boolean reverseClear = overlapBackCount.getOrDefault(f, 0) > backSecond;
                if (!links.containsKey(g) && !claimed.contains(f) && clearWinner && reverseClear && g.equals(overlapBack.get(f))) {
                    links.put(g, f);
                    claimed.add(f);
                }
            }
            return links;
        }

        static boolean stableSrgMember(String key) {
            return key.startsWith("mfunc_") || key.startsWith("ffield_");
        }

        /** Shared CamelCase tokens of two classes' simple names, plural-insensitive. */
        static int nameSim(String g, String f) {
            Set<String> gt = tokens(g), ft = tokens(f);
            int common = 0;
            for (String t : gt) {
                if (ft.contains(t)) {
                    common++;
                }
            }
            return common;
        }

        static Set<String> tokens(String internal) {
            String simple = internal.substring(internal.lastIndexOf('/') + 1).replace('$', ' ');
            Set<String> out = new HashSet<>();
            for (String part : simple.split("[^A-Za-z0-9]+")) {
                for (String w : part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[0-9])(?=[A-Za-z])|(?<=[A-Za-z])(?=[0-9])")) {
                    String t = w.toLowerCase(Locale.ROOT);
                    if (t.endsWith("s") && t.length() > 3 && !t.endsWith("ss")) {
                        t = t.substring(0, t.length() - 1);
                    }
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
            }
            return out;
        }

        static Map<String, Integer> stableSrgCounts(Map<String, Set<String>> ids) {
            Map<String, Integer> counts = new HashMap<>();
            for (var e : ids.entrySet()) {
                int count = 0;
                for (String key : e.getValue()) {
                    if (stableSrgMember(key)) {
                        count++;
                    }
                }
                counts.put(e.getKey(), count);
            }
            return counts;
        }

        static String ownerOf(String key) {
            return key.substring(0, key.indexOf('.'));
        }

        static String idOf(String key) {
            return key.substring(key.indexOf('.') + 1);
        }

        /**
         * Follow a class rename before looking a member up: the member kept its id, so the linked
         * owner names its key in the next table (descriptors remapped through the step's links).
         * Falls back to the tracked key.
         */
        static String followLink(Map<String, String> links, java.util.function.Predicate<String> present, String key) {
            String owner = ownerOf(key);
            String linked = links.get(owner);
            if (linked == null) {
                return key;
            }
            String id = idOf(key);
            String translated = linked + "." + id;
            if (present.test(translated)) {
                return translated;
            }
            int paren = id.indexOf('(');
            if (paren < 0) {
                return key;
            }
            String remapped = linked + "." + id.substring(0, paren) + Tiny.remapDesc(id.substring(paren), links);
            return present.test(remapped) ? remapped : key;
        }

        /**
         * Pick a move target among same-id members. SRG ids are shared across an override
         * hierarchy, so an id alone can name a dozen methods: prefer the candidate that keeps the
         * member's name, then its descriptor, then one owned by the linked class. Intermediary ids
         * are unique, so modern steps never reach past the first candidate.
         */
        static String pickMoveTarget(Table cur, Member mp, List<String> elsewhere, Map<String, String> links, String oldOwner) {
            if (elsewhere.size() == 1) {
                return elsewhere.get(0);
            }
            for (String k : elsewhere) {
                if (cur.methods.get(k).name().equals(mp.name())) {
                    return k;
                }
            }
            for (String k : elsewhere) {
                if (cur.methods.get(k).interDesc().equals(mp.interDesc())) {
                    return k;
                }
            }
            String linked = links.get(oldOwner);
            if (linked != null) {
                for (String k : elsewhere) {
                    if (ownerOf(k).equals(linked)) {
                        return k;
                    }
                }
            }
            return elsewhere.get(0);
        }

        /**
         * The adjacent table's members by official identity, for the bridge's name fallback. Fields
         * carry no descriptor on the SRG lane, so both sides key fields by owner and name alone.
         */
        static Map<String, String> indexMembersByOfficial(Table cur) {
            Map<String, String> index = new HashMap<>();
            for (var e : cur.methods.entrySet()) {
                index.putIfAbsent(e.getValue().owner() + "\0" + e.getValue().name() + "\0" + e.getValue().desc(), e.getKey());
            }
            for (var e : cur.fields.entrySet()) {
                index.putIfAbsent(e.getValue().owner() + "\0" + e.getValue().name() + "\0", e.getKey());
            }
            return index;
        }

        static Map<String, String> indexClassesByOfficial(Table cur) {
            Map<String, String> index = new HashMap<>();
            for (var e : cur.classes.entrySet()) {
                index.putIfAbsent(e.getValue(), e.getKey());
            }
            return index;
        }

        /**
         * Bridge-pending members and classes resolve against the last version's own mappings: an
         * official identity unchanged since the seam maps straight to the final name. Anything still
         * unmapped reads as removed, with the bridge step. Only forward routes pend; a backport
         * resolves eagerly at the bridge, when the SRG table is in hand.
         */
        static void resolvePending(
            Chain c,
            Diff d,
            Table first,
            Table last,
            Map<String, String> pendingMethods,
            Map<String, String> pendingFields,
            Map<String, String> pendingClasses
        ) throws Exception {
            if (pendingMethods.isEmpty() && pendingFields.isEmpty() && pendingClasses.isEmpty()) {
                return;
            }
            Set<String> lastMembers = Set.of(), lastClasses = Set.of();
            if (!last.lane.equals("srg")) {
                Proguard[] pgs = proguardsFor(last.version);
                lastMembers = officialTriples(pgs);
                lastClasses = officialClasses(pgs);
            }
            for (var e : pendingMethods.entrySet()) {
                Member ma = first.methods.get(e.getKey());
                String[] id = e.getValue().split("\0", -1);
                String was = Diff.dots(ma.owner()) + "." + ma.name();
                if (lastMembers.contains(e.getValue())) {
                    String to = Diff.dots(id[0]) + "." + id[1];
                    String what = id[0].equals(ma.owner()) ? "renamed" : "moved";
                    if (what.equals("moved")) {
                        d.methodMoved.add(new Change(ma.owner(), ma.name(), id[1], ma.desc(), id[2], id[0]));
                    } else {
                        d.methodRenamed.add(new Change(ma.owner(), ma.name(), id[1], ma.desc(), id[2], id[0]));
                    }
                    c.history.add(
                        new Event("method", Diff.dots(ma.owner()), ma.name(), ma.desc(), what, was, to, BRIDGE_STEP)
                    );
                } else {
                    d.methodRemoved.add(new Change(ma.owner(), ma.name(), null, ma.desc(), null, null));
                    c.history.add(
                        new Event("method", Diff.dots(ma.owner()), ma.name(), ma.desc(), "removed", was, null, BRIDGE_STEP)
                    );
                }
            }
            for (var e : pendingFields.entrySet()) {
                Member fa = first.fields.get(e.getKey());
                String[] id = e.getValue().split("\0", -1);
                String was = Diff.dots(fa.owner()) + "." + fa.name();
                if (lastMembers.contains(e.getValue())) {
                    d.fieldRenamed.add(new Change(fa.owner(), fa.name(), id[1], fa.desc(), id[2], id[0]));
                    c.history.add(
                        new Event(
                            "field",
                            Diff.dots(fa.owner()),
                            fa.name(),
                            fa.desc(),
                            "renamed",
                            was,
                            Diff.dots(id[0]) + "." + id[1],
                            BRIDGE_STEP
                        )
                    );
                } else {
                    d.fieldRemoved.add(new Change(fa.owner(), fa.name(), null, fa.desc(), null, null));
                    c.history.add(
                        new Event("field", Diff.dots(fa.owner()), fa.name(), fa.desc(), "removed", was, null, BRIDGE_STEP)
                    );
                }
            }
            for (var e : pendingClasses.entrySet()) {
                String a = first.classes.get(e.getKey());
                if (lastClasses.contains(e.getValue())) {
                    d.classRenamed.add(new String[] {a, e.getValue()});
                    c.history.add(
                        new Event("class", null, Diff.dots(a), null, "renamed", Diff.dots(a), Diff.dots(e.getValue()), BRIDGE_STEP)
                    );
                } else {
                    d.classRemoved.add(a);
                    c.history.add(
                        new Event("class", null, Diff.dots(a), null, "removed", Diff.dots(a), null, BRIDGE_STEP)
                    );
                }
            }
        }

        /** The usual map sections, so explain/fix/preflight read it unchanged, plus the chain and its history. */
        String json(String names) {
            String body = diff.json(versions.get(0), versions.get(versions.size() - 1), names);
            StringBuilder sb = new StringBuilder(body.substring(0, body.lastIndexOf("\n}")));
            sb.append(",\n  \"chain\": [");
            for (int i = 0; i < versions.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(Diff.q(versions.get(i)));
            }
            sb.append("],\n  \"history\": [");
            for (int i = 0; i < history.size(); i++) {
                Event e = history.get(i);
                sb
                    .append(i == 0 ? "\n" : ",\n")
                    .append("    { \"kind\": ")
                    .append(Diff.q(e.kind()))
                    .append(", \"owner\": ")
                    .append(Diff.q(e.owner()))
                    .append(", \"name\": ")
                    .append(Diff.q(e.name()))
                    .append(", \"desc\": ")
                    .append(Diff.q(e.desc()))
                    .append(", \"what\": ")
                    .append(Diff.q(e.what()))
                    .append(", \"was\": ")
                    .append(Diff.q(e.was()))
                    .append(", \"to\": ")
                    .append(Diff.q(e.to()))
                    .append(", \"step\": ")
                    .append(Diff.q(e.step()))
                    .append(" }");
            }
            return sb.append("\n  ]\n}\n").toString();
        }
    }

    // ------------------------------------------------------------------ explain a build log

    /** One javac error with the lines javac prints under it. */
    record Err(String file, int line, String message, String source, int caret, String symbol, String location) {}

    static List<Err> readErrors(Path log) throws IOException {
        // javac on Windows writes in the console's code page: bytes that are not UTF-8 never stop the repair (owo-lib).
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        // Gradle indents javac's lines in multi-project builds.
        // A path starts like one (Port.FILE): Gradle's summary repeats errors with the previous line glued on, and those are skipped.
        Pattern head = Pattern.compile(
            "^\\s*((?:[A-Za-z]:[\\\\/]|/)[^:\\r\\n]*?\\.java|[\\w.-]+(?:[\\\\/][\\w .-]+)*\\.java):(\\d+): error: (.*)$"
        );
        Map<String, Err> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = head.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String source = i + 1 < lines.size() ? lines.get(i + 1) : "";
            int caret = i + 2 < lines.size() ? lines.get(i + 2).indexOf('^') : -1;
            int indent = m.start(1);
            if (indent > 0 && source.startsWith(" ".repeat(indent))) {
                source = source.substring(indent);
            }
            if (caret >= indent) {
                caret -= indent;
            }
            String symbol = null, location = null;
            for (int j = i + 1; j < Math.min(lines.size(), i + 8); j++) {
                String s = lines.get(j).trim();
                if (head.matcher(lines.get(j)).find()) {
                    break;
                }
                if (s.startsWith("symbol:")) {
                    symbol = s.substring(7).trim();
                }
                if (s.startsWith("location:")) {
                    location = s.substring(9).trim();
                }
            }
            Err e = new Err(m.group(1), Integer.parseInt(m.group(2)), m.group(3), source, caret, symbol, location);
            seen.putIfAbsent(e.file() + ":" + e.line() + ":" + e.caret() + ":" + e.message() + ":" + symbol, e);
        }
        return new ArrayList<>(seen.values());
    }

    /** Supertypes of the receiver types a compiler log names (simple names), from --supers. */
    static final Map<String, Set<String>> SUPERS = new HashMap<>();

    static String simple(String dotted) {
        String s = dotted.substring(dotted.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\": \"([^\"]*)\"").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    /** A generated map, indexed the way compiler errors ask about it. */
    static final class Known {

        String fromVersion, toVersion, names;
        final Map<String, String> classFqn = new LinkedHashMap<>(); // old fqn ($ nested) -> new fqn
        final Map<String, Set<String>> classBySimple = new HashMap<>(), removedClassBySimple = new HashMap<>();
        final Map<String, Set<String>> packages = new HashMap<>();
        final Map<String, Set<String[]>> methodTo = new HashMap<>(), fieldTo = new HashMap<>(); // old name -> {owner simple, new name}
        final Map<String, Set<String>> sigTo = new HashMap<>();
        final Map<String, Set<String>> sigTransitions = new HashMap<>();
        final Set<String> methodGone = new HashSet<>(), fieldGone = new HashSet<>(), sigNames = new HashSet<>();
        final List<Map<String, String>> history = new ArrayList<>(); // kind, name, what, was, to, step, in map order

        static Known load(Path mapFile) throws IOException {
            Known k = new Known();
            String json = Files.readString(mapFile, StandardCharsets.UTF_8);
            k.fromVersion = field(json, "from");
            k.toVersion = field(json, "to");
            k.names = field(json, "names");
            String section = null;
            Pattern sec = Pattern.compile("^  \"(\\w+)\": \\[");
            for (String line : Files.readString(mapFile, StandardCharsets.UTF_8).split("\n")) {
                Matcher sm = sec.matcher(line);
                if (sm.find()) {
                    section = sm.group(1);
                    continue;
                }
                if (section == null) {
                    continue;
                }
                String t = line.trim();
                if (t.contains("\"step\":")) {
                    Map<String, String> event = new HashMap<>();
                    event.put("kind", field(t, "kind"));
                    event.put("name", field(t, "name"));
                    event.put("what", field(t, "what"));
                    event.put("was", field(t, "was"));
                    event.put("to", field(t, "to"));
                    event.put("step", field(t, "step"));
                    k.history.add(event);
                    continue;
                }
                if (section.equals("classesRemoved")) {
                    if (t.startsWith("\"")) {
                        String c = t.replaceAll("[\",]", "");
                        k.removedClassBySimple.computeIfAbsent(simple(c), x -> new HashSet<>()).add(c);
                    }
                    continue;
                }
                if (!t.startsWith("{")) {
                    continue;
                }
                String from = field(t, "from"), to = field(t, "to"), owner = field(t, "owner"), newOwner = field(
                    t,
                    "newOwner"
                );
                String ownerSimple = owner == null ? "" : simple(owner);
                switch (section) {
                    case "classes" -> {
                        k.classFqn.put(from, to);
                        k.classBySimple.computeIfAbsent(simple(from), x -> new HashSet<>()).add(to);
                        String pkg = from.contains(".") ? from.substring(0, from.lastIndexOf('.')) : "";
                        k.packages.computeIfAbsent(pkg, x -> new HashSet<>()).add(to);
                    }
                    case "methods", "methodsMoved" -> {
                        if (!from.equals(to)) {
                            k.methodTo
                                .computeIfAbsent(from, x -> new HashSet<>())
                                .add(
                                    new String[] { ownerSimple, to, newOwner == null ? ownerSimple : simple(newOwner) }
                                );
                        }
                    }
                    case "signatures" -> {
                        k.sigTo
                            .computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
                            .add(to + field(t, "newDesc"));
                        k.sigTransitions
                            .computeIfAbsent(ownerSimple + "." + from, x -> new HashSet<>())
                            .add(field(t, "desc") + " -> " + field(t, "newDesc"));
                        k.sigNames.add(from);
                    }
                    case "methodsRemoved" -> k.methodGone.add(from);
                    case "fields" -> k.fieldTo
                        .computeIfAbsent(from, x -> new HashSet<>())
                        .add(new String[] { ownerSimple, to, ownerSimple });
                    case "fieldsRemoved" -> k.fieldGone.add(from);
                    default -> {}
                }
            }
            return k;
        }

        /** The one new name for an old member name, narrowed by the type javac says it looked in. */
        static String pick(Set<String[]> candidates, String location) {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            String type = locationType(location);
            if (type == null) {
                return null;
            }
            Set<String> names = new HashSet<>();
            for (String[] c : candidates) {
                if (c[0].equals(type) || c[2].equals(type)) {
                    names.add(c[1]);
                }
            }
            if (names.isEmpty() && SUPERS.containsKey(type)) {
                // The receiver's static type inherits the member: LocalPlayer.getHungerManager is Player's (PlayerEntity in yarn).
                Set<String> supers = SUPERS.get(type);
                for (String[] c : candidates) {
                    if (supers.contains(c[0]) || supers.contains(c[2])) {
                        names.add(c[1]);
                    }
                }
            }
            return names.size() == 1 ? names.iterator().next() : null;
        }

        /** The new name when every owner that renamed this member chose the same one, else null. */
        static String unanimous(Set<String[]> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Set<String> names = new HashSet<>();
            for (String[] c : candidates) {
                names.add(c[1]);
            }
            return names.size() == 1 ? names.iterator().next() : null;
        }

        static String locationType(String location) {
            if (location == null) {
                return null;
            }
            Matcher m = Pattern.compile("of type ([\\w.$]+)").matcher(location);
            String t = m.find() ? m.group(1) : location.replaceFirst("^(class|interface|enum|record) ", "");
            t = t.replaceAll("<.*", "").trim();
            return simple(t);
        }
    }

    static final Pattern CALL = Pattern.compile("\\.(\\w+)\\(");

    /** First method the source line calls whose signature the map says changed. */
    static String lineCalls(String source, Set<String> names) {
        Matcher m = CALL.matcher(source);
        while (m.find()) {
            if (names.contains(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ explain a build log

    static final class Explain {

        static String verdict(Err e, Known k, StringBuilder detail) {
            String sym = e.symbol() == null ? "" : e.symbol();
            String msg = e.message();
            if (msg.startsWith("package ") && msg.endsWith(" does not exist")) {
                return k.packages.containsKey(msg.substring(8, msg.length() - 15))
                    ? "mechanical: package moved"
                    : "removed: package";
            }
            if (msg.equals("cannot find symbol") && sym.startsWith("class ")) {
                String c = sym.substring(6).trim();
                detail.append(c);
                if (k.classBySimple.containsKey(c)) {
                    detail.append(" -> ").append(k.classBySimple.get(c));
                    return "mechanical: class renamed/moved";
                }
                return k.removedClassBySimple.containsKey(c) ? "removed: class" : "unknown symbol: class";
            }
            if (msg.equals("cannot find symbol") && sym.startsWith("method ")) {
                String n = sym.substring(7, sym.indexOf('('));
                detail.append(n);
                String to = Known.pick(k.methodTo.get(n), e.location());
                if (to != null) {
                    detail.append(" -> ").append(to);
                    return "mechanical: method renamed";
                }
                if (k.methodTo.containsKey(n)) {
                    return "renamed, target ambiguous";
                }
                return k.methodGone.contains(n) ? "removed: method" : "unknown symbol: method";
            }
            if (msg.equals("cannot find symbol") && sym.startsWith("variable ")) {
                String n = sym.substring(9).trim();
                detail.append(n);
                String to = Known.pick(k.fieldTo.get(n), e.location());
                if (to != null) {
                    detail.append(" -> ").append(to);
                    return "mechanical: field renamed";
                }
                return k.fieldGone.contains(n) ? "removed: field" : "unknown symbol: variable";
            }
            Matcher am = Pattern.compile("method (\\w+) in (?:class|interface) (\\w+)").matcher(msg);
            if (am.find() && k.sigTo.containsKey(am.group(2) + "." + am.group(1))) {
                detail
                    .append(am.group(2))
                    .append('.')
                    .append(am.group(1))
                    .append(" -> ")
                    .append(k.sigTo.get(am.group(2) + "." + am.group(1)));
                return "signature changed (map knows the new one)";
            }
            if (!msg.startsWith("method does not override") && lineCalls(e.source(), k.sigNames) != null) {
                detail.append("line calls ").append(lineCalls(e.source(), k.sigNames));
                return "signature changed (map knows the new one)";
            }
            if (msg.startsWith("method does not override")) {
                return "override broke";
            }
            String m = msg.replaceAll("[A-Z][\\w.$<>,? ]*", "T").replaceAll("\\s+", " ");
            return "other: " + (m.length() > 50 ? m.substring(0, 50) : m);
        }

        static void run(Path mapFile, Path log) throws IOException {
            Known k = Known.load(mapFile);
            Map<String, Integer> counts = new TreeMap<>();
            List<String> examples = new ArrayList<>();
            for (Err e : readErrors(log)) {
                StringBuilder detail = new StringBuilder();
                String v = verdict(e, k, detail);
                counts.merge(v, 1, Integer::sum);
                if (detail.length() > 0 && examples.size() < 25) {
                    examples.add(v + "  " + detail);
                }
            }
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            int mech = counts
                .entrySet()
                .stream()
                .filter(x -> x.getKey().startsWith("mechanical"))
                .mapToInt(Map.Entry::getValue)
                .sum();
            int sig = counts.getOrDefault("signature changed (map knows the new one)", 0);
            System.out.printf(
                "%d errors: %d mechanical (rename/move), %d signature known, %d other%n",
                total,
                mech,
                sig,
                total - mech - sig
            );
            counts
                .entrySet()
                .stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(x -> System.out.printf("  %4d  %s%n", x.getValue(), x.getKey()));
            for (String ex : examples) {
                System.out.println("        " + ex);
            }
        }
    }

    // ------------------------------------------------------------------ apply the mechanical part

    /**
     * Rewrites what the map makes certain: moved or renamed classes everywhere they are imported
     * (javac stops at 100 errors, so waiting for each one costs rounds), and renamed methods and
     * fields exactly where javac reported them. Prints "map-fix: N replacement(s)".
     */
    static final class Fix {

        static final Set<String> EXCLUDED = Set.of(
            "build",
            ".gradle",
            ".git",
            "run",
            ".mmt",
            ".tools",
            ".runs",
            ".lab",
            "node_modules",
            "out"
        );

        static Path sourcePath(Err error, Path repo) {
            Path path = Path.of(error.file());
            if (!path.isAbsolute()) {
                path = repo.resolve(path);
            }
            path = path.toAbsolutePath().normalize();
            if (!path.startsWith(repo.toAbsolutePath().normalize())) {
                return null;
            }
            for (Path part : repo.toAbsolutePath().normalize().relativize(path)) if (
                EXCLUDED.contains(part.toString())
            ) {
                return null;
            }
            try {
                return path.toRealPath().startsWith(repo.toRealPath()) ? path : null;
            } catch (IOException missing) {
                return null;
            }
        }

        static List<Path> sourceFiles(Path repo) throws IOException {
            List<Path> result = new ArrayList<>();
            Files.walkFileTree(
                repo,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                        Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs
                    ) {
                        // A folder with its own settings.gradle is another Gradle build, not part of this port.
                        boolean separate =
                            !dir.getFileName().toString().equals("buildSrc") &&
                            !dir.getFileName().toString().equals("build-logic") &&
                            (Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts")));
                        return (
                                !dir.equals(repo) &&
                                (EXCLUDED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git")) || separate)
                            )
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && file.toString().endsWith(".java")) {
                            result.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
            return result;
        }

        /** The source that declares TYPE: this file when it does, else the first project file that does (or this file). */
        static String declaring(String text, String type, List<Path> files) throws IOException {
            if (type == null) {
                return text;
            }
            Pattern declared = Pattern.compile("\\bclass\\s+" + Pattern.quote(type) + "\\b");
            if (declared.matcher(text).find()) {
                return text;
            }
            for (Path f : files) {
                if (!f.getFileName().toString().equals(type + ".java")) {
                    continue;
                }
                String other = Files.readString(f, StandardCharsets.UTF_8);
                if (declared.matcher(other).find()) {
                    return other;
                }
            }
            return text;
        }

        /** Whether the file declares TYPE as extending a class it imports from the game (net.minecraft or com.mojang). */
        static boolean extendsGameClass(String text, String type) {
            if (type == null) {
                return false;
            }
            Matcher declared = Pattern.compile(
                "\\bclass\\s+" + Pattern.quote(type) + "\\b[^{;]*?\\bextends\\s+([\\w.]+)"
            ).matcher(text);
            if (!declared.find()) {
                return false;
            }
            String parent = declared.group(1);
            if (parent.startsWith("net.minecraft.") || parent.startsWith("com.mojang.")) {
                return true;
            }
            return Pattern.compile(
                "(?m)^import\\s+(?:net\\.minecraft|com\\.mojang)\\.[\\w.]*\\." + Pattern.quote(parent) + "\\s*;"
            ).matcher(text).find();
        }

        static void run(Path mapFile, Path log, Path repo, boolean dryRun, Path rulesFile) throws IOException {
            Known k = Known.load(mapFile);
            Map<Path, String> texts = new LinkedHashMap<>();
            int replacements = 0;
            Map<String, Integer> byKind = new TreeMap<>();

            // 1. Imports of moved/renamed classes, every Java file in the repo.
            List<Path> files = sourceFiles(repo);
            Pattern imp = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
            Map<String, String> dotted = new HashMap<>();
            for (var e : k.classFqn.entrySet()) {
                dotted.put(e.getKey().replace('$', '.'), e.getValue().replace('$', '.'));
            }
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = imp.matcher(text);
                StringBuilder sb = new StringBuilder();
                List<String[]> simpleRenames = new ArrayList<>();
                int hits = 0;
                while (m.find()) {
                    String fqn = m.group(2);
                    String to = dotted.get(fqn);
                    if (to == null && m.group(1) != null && fqn.contains(".")) {
                        String owner = fqn.substring(0, fqn.lastIndexOf('.'));
                        if (dotted.containsKey(owner)) {
                            to = dotted.get(owner) + fqn.substring(owner.length());
                        }
                    }
                    if (to == null) {
                        continue;
                    }
                    m.appendReplacement(
                        sb,
                        Matcher.quoteReplacement("import " + (m.group(1) == null ? "" : "static ") + to + ";")
                    );
                    hits++;
                    if (m.group(1) == null && !simple(fqn).equals(simple(to))) {
                        simpleRenames.add(new String[] { simple(fqn), simple(to) });
                    }
                }
                if (hits == 0) {
                    continue;
                }
                m.appendTail(sb);
                String updated = sb.toString();
                for (String[] r : simpleRenames) {
                    int at = updated.indexOf("\n", updated.lastIndexOf("\nimport "));
                    String head = updated.substring(0, at), body = updated.substring(at);
                    Matcher wm = Pattern.compile("\\b" + Pattern.quote(r[0]) + "\\b").matcher(body);
                    int n = 0;
                    StringBuilder bb = new StringBuilder();
                    while (wm.find()) {
                        wm.appendReplacement(bb, Matcher.quoteReplacement(r[1]));
                        n++;
                    }
                    wm.appendTail(bb);
                    updated = head + bb;
                    hits += n;
                }
                texts.put(f, updated);
                replacements += hits;
                byKind.merge("class", hits, Integer::sum);
            }

            // 2. Renamed methods and fields at the reported line and column.
            for (Err e : readErrors(log)) {
                String sym = e.symbol() == null ? "" : e.symbol();
                if (!e.message().equals("cannot find symbol")) {
                    continue;
                }
                String from, to;
                boolean method = sym.startsWith("method ");
                if (method) {
                    from = sym.substring(7, sym.indexOf('('));
                    to = Known.pick(k.methodTo.get(from), e.location());
                } else if (sym.startsWith("variable ")) {
                    from = sym.substring(9).trim();
                    to = Known.pick(k.fieldTo.get(from), e.location());
                } else {
                    continue;
                }
                Path f = sourcePath(e, repo);
                if (f == null || !Files.isRegularFile(f)) {
                    continue;
                }
                String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                if (to == null && method && extendsGameClass(declaring(text, Known.locationType(e.location()), files), Known.locationType(e.location()))) {
                    // A mod's own subclass of a game class: the member is inherited, so a rename the game made the
                    // same way on every owner is the one it means (getMaxScroll -> maxScrollAmount on a list widget).
                    to = Known.unanimous(k.methodTo.get(from));
                }
                if (to == null) {
                    continue;
                }
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                String[] lines = text.split("\r?\n", -1);
                if (e.line() < 1 || e.line() > lines.length) {
                    continue;
                }
                String line = lines[e.line() - 1];
                int col = e.caret() >= 0 && e.caret() < line.length() && line.startsWith(from, e.caret())
                    ? e.caret()
                    : -1;
                if (col < 0) {
                    Matcher wm = Pattern.compile(
                        "\\b" + Pattern.quote(from) + "\\b" + (method ? "\\s*\\(" : "")
                    ).matcher(line);
                    if (!wm.find()) {
                        continue;
                    }
                    col = wm.start();
                }
                lines[e.line() - 1] = line.substring(0, col) + to + line.substring(col + from.length());
                texts.put(f, String.join(nl, lines));
                replacements++;
                byKind.merge(method ? "method" : "field", 1, Integer::sum);
            }
            // 2b. An @Override that no longer overrides: the game renamed the method it overrode, the same way on every owner.
            for (Err e : readErrors(log)) {
                if (!e.message().startsWith("method does not override or implement a method from a supertype")) {
                    continue;
                }
                Path f = sourcePath(e, repo);
                if (f == null || !Files.isRegularFile(f)) {
                    continue;
                }
                String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                String[] lines = text.split("\r?\n", -1);
                // Only in a class that extends a game class: the method it meant to override is the game's.
                String enclosing = null;
                for (int back = Math.min(lines.length, e.line()) - 1; back >= 0 && enclosing == null; back--) {
                    Matcher type = Pattern.compile("\\bclass\\s+(\\w+)").matcher(lines[back]);
                    if (type.find()) {
                        enclosing = type.group(1);
                    }
                }
                if (!extendsGameClass(text, enclosing)) {
                    continue;
                }
                for (int at = e.line() - 1; at >= 0 && at < Math.min(lines.length, e.line() + 3); at++) {
                    Matcher declared = Pattern.compile(
                        "^(\\s*(?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w.<>\\[\\], ?]+\\s+)(\\w+)(\\s*\\()"
                    ).matcher(lines[at]);
                    if (!declared.find() || lines[at].trim().startsWith("@")) {
                        continue;
                    }
                    String to = Known.unanimous(k.methodTo.get(declared.group(2)));
                    if (to != null) {
                        lines[at] = declared.group(1) + to + lines[at].substring(declared.end(2));
                        texts.put(f, String.join(nl, lines));
                        replacements++;
                        byKind.merge("override", 1, Integer::sum);
                    }
                    break;
                }
            }
            // 3. Learned rewrites (from other mods' real ports of this jump), on the reported lines only.
            if (rulesFile != null && Files.isRegularFile(rulesFile)) {
                Map<Path, Set<String>> pendingImports = new LinkedHashMap<>();
                List<String[]> learned = new ArrayList<>(); // from, to, imports
                Pattern importsField = Pattern.compile("\"imports\": \\[([^\\]]*)\\]");
                for (String line : Files.readAllLines(rulesFile, StandardCharsets.UTF_8)) {
                    Matcher fm = Pattern.compile(
                        "\"from\": \"((?:[^\"\\\\]|\\\\.)*)\", \"to\": \"((?:[^\"\\\\]|\\\\.)*)\""
                    ).matcher(line);
                    if (!fm.find()) {
                        continue;
                    }
                    Matcher im = importsField.matcher(line);
                    learned.add(
                        new String[] {
                            unq(fm.group(1)),
                            unq(fm.group(2)),
                            im.find() ? im.group(1).replace("\"", "") : "",
                        }
                    );
                }
                Set<String> done = new HashSet<>();
                for (Err e : readErrors(log)) {
                    Path f = sourcePath(e, repo);
                    if (f == null || !Files.isRegularFile(f) || !done.add(f + ":" + e.line())) {
                        continue;
                    }
                    String text = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                    String nl = text.contains("\r\n") ? "\r\n" : "\n";
                    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
                    if (e.line() < 1 || e.line() > lines.size()) {
                        continue;
                    }
                    String line = lines.get(e.line() - 1), before = line;
                    Set<String> imports = new LinkedHashSet<>();
                    // Whole names only, one rewrite per spot, and for "cannot find symbol" only rules about that symbol.
                    String sym = e.symbol() == null
                        ? ""
                        : e.symbol().replaceFirst("^(method|variable|class) ", "").replaceAll("\\(.*", "").trim();
                    List<String> usedFrom = new ArrayList<>();
                    for (String[] r : learned) {
                        if (r[0].isEmpty() || usedFrom.stream().anyMatch(u -> u.contains(r[0]) || r[0].contains(u))) {
                            continue;
                        }
                        if (
                            !sym.isEmpty() &&
                            e.message().equals("cannot find symbol") &&
                            !Learn.tokens(r[0]).contains(sym)
                        ) {
                            continue;
                        }
                        String pat =
                            (Character.isLetterOrDigit(r[0].charAt(0)) ? "\\b" : "") +
                            Pattern.quote(r[0]) +
                            (Character.isLetterOrDigit(r[0].charAt(r[0].length() - 1)) ? "\\b" : "");
                        Matcher rm = Pattern.compile(pat).matcher(line);
                        if (!rm.find()) {
                            continue;
                        }
                        line = rm.replaceAll(Matcher.quoteReplacement(r[1]));
                        usedFrom.add(r[0]);
                        for (String im : r[2].split(",")) {
                            if (!im.isBlank()) {
                                imports.add(im.trim());
                            }
                        }
                    }
                    if (line.equals(before)) {
                        continue;
                    }
                    lines.set(e.line() - 1, line);
                    pendingImports.computeIfAbsent(f, x -> new LinkedHashSet<>()).addAll(imports);
                    texts.put(f, String.join(nl, lines));
                    replacements++;
                    byKind.merge("learned", 1, Integer::sum);
                }
                // Preserve javac's source line numbers until every reported line has been repaired.
                for (var entry : pendingImports.entrySet()) {
                    String text = texts.get(entry.getKey());
                    String nl = text.contains("\r\n") ? "\r\n" : "\n";
                    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
                    int after = -1;
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).startsWith("import ") || lines.get(i).startsWith("package ")) {
                            after = i;
                        }
                    }
                    for (String im : entry.getValue()) {
                        if (!lines.contains("import " + im + ";")) {
                            lines.add(++after, "import " + im + ";");
                        }
                    }
                    texts.put(entry.getKey(), String.join(nl, lines));
                }
            }
            // API changes need the enclosing Java expression and local declarations, not a file-wide rename.
            Map<Path, List<Err>> apiErrors = new LinkedHashMap<>();
            for (Err e : readErrors(log)) {
                Path f = sourcePath(e, repo);
                if (f != null && Files.isRegularFile(f)) {
                    apiErrors.computeIfAbsent(f, x -> new ArrayList<>()).add(e);
                }
            }
            for (var entry : apiErrors.entrySet()) {
                Path f = entry.getKey();
                String before = texts.containsKey(f) ? texts.get(f) : Files.readString(f, StandardCharsets.UTF_8);
                String after = ApiFix.apply(k, f, before, entry.getValue());
                if (!after.equals(before)) {
                    texts.put(f, after);
                    replacements++;
                    byKind.merge("api", 1, Integer::sum);
                }
            }
            if (!dryRun) {
                for (var e : texts.entrySet()) {
                    Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
                }
            }
            System.out.println(
                "map-fix: " +
                replacements +
                " replacement(s) in " +
                texts.size() +
                " file(s) " +
                byKind +
                (dryRun ? " (dry run)" : "")
            );
        }
    }

    /** Only reviewed AST recipes with a matching target mapping, before the first compile. */
    static final class Preflight {

        /** A removed name used more than this many times across the repo prints once as a common word
            instead of once per use: past the 90th percentile of repetition the uses are lookalikes,
            not the removed member (measured p90 = 19 on Apotheosis 8.9.0). */
        static final int COMMON_WORD_USES = 20;

        /** One printed line per finding, except common words, which print once per repo (see run). */
        static List<String> renderGrouped(String rel, List<ApiFix.LintHit> hits, Map<String, Integer> uses) {
            List<String> lines = new ArrayList<>();
            for (ApiFix.LintHit hit : hits) {
                if (!hit.auto() && uses.getOrDefault(hit.detail(), 0) > COMMON_WORD_USES) continue;
                lines.add(rel + ":" + hit.line() + ": " + hit.detail());
            }
            return lines;
        }

        static int run(Path mapFile, Path repo, boolean dryRun) throws IOException {
            Known known = Known.load(mapFile);
            if ("yarn".equals(known.names)) {
                int files = 0;
                for (Path file : Fix.sourceFiles(repo)) {
                    String before = Files.readString(file, StandardCharsets.UTF_8);
                    String after = ApiFix.preflight(known, file, before);
                    if (after.equals(before)) {
                        continue;
                    }
                    files++;
                    if (!dryRun) {
                        Files.writeString(file, after, StandardCharsets.UTF_8);
                    }
                }
                System.out.println("map-preflight: " + files + " file(s)" + (dryRun ? " (dry run)" : ""));
                return files;
            }
            // Map-driven lint for MCP and Mojang names: every method call and dotted field read
            // whose name the map renamed, moved or removed is reported with the update that changed
            // it. Unanimous renames on provably game-owned receivers are rewritten; everything else
            // (ambiguous renames, removals, unknown receivers, mod-owned or self-declared names) is
            // reported for a human. Parse only: no build, no game.
            record FileLint(Path file, String before, ApiFix.Lint lint) {}
            List<FileLint> lints = new ArrayList<>();
            for (Path file : Fix.sourceFiles(repo)) {
                String before = Files.readString(file, StandardCharsets.UTF_8);
                ApiFix.Lint lint;
                try {
                    lint = ApiFix.lintKnown(known, file, before);
                } catch (RuntimeException failure) {
                    System.out.println(repo.relativize(file) + ": skipped, could not parse (" + failure.getMessage() + ")");
                    continue;
                }
                lints.add(new FileLint(file, before, lint));
            }
            Map<String, Integer> uses = new HashMap<>();
            for (FileLint fileLint : lints) for (ApiFix.LintHit hit : fileLint.lint.hits) if (!hit.auto()) {
                uses.merge(hit.detail(), 1, Integer::sum);
            }
            Map<String, Set<String>> commonWords = new LinkedHashMap<>();
            for (FileLint fileLint : lints) for (ApiFix.LintHit hit : fileLint.lint.hits) if (!hit.auto()) {
                if (uses.getOrDefault(hit.detail(), 0) > COMMON_WORD_USES) commonWords.computeIfAbsent(hit.detail(), key -> new LinkedHashSet<>()).add(repo.relativize(fileLint.file).toString());
            }
            int files = 0, auto = 0, review = 0;
            for (FileLint fileLint : lints) {
                int fileAuto = 0;
                for (ApiFix.LintHit hit : fileLint.lint.hits) if (hit.auto()) fileAuto++;
                List<String> lines = renderGrouped(repo.relativize(fileLint.file).toString(), fileLint.lint.hits, uses);
                for (String line : lines) System.out.println(line);
                auto += fileAuto;
                review += lines.size() - fileAuto;
                if (!fileLint.lint.fixed.equals(fileLint.before)) {
                    files++;
                    if (!dryRun) {
                        Files.writeString(fileLint.file, fileLint.lint.fixed, StandardCharsets.UTF_8);
                    }
                }
            }
            for (var word : commonWords.entrySet()) {
                System.out.println("common word: " + uses.get(word.getKey()) + " uses across " + word.getValue().size() + " files: " + word.getKey() + " [common-word]");
                review++;
            }
            System.out.println(
                "map-preflight: " + files + " file(s), " + auto + " auto-fix(es), " + review + " to review" + (dryRun ? " (dry run)" : "")
            );
            return files;
        }
    }

    /** Reviewed API migrations. Parse only: no target classes are loaded and no mod code is executed. */
    static final class ApiFix {

        record Edit(int start, int end, String text) {}

        final String source;
        final CompilationUnitTree unit;
        final SourcePositions positions;
        final List<TreePath> paths = new ArrayList<>();
        final List<Edit> edits = new ArrayList<>();

        ApiFix(Path file, String source) throws IOException {
            this.source = source;
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            JavaFileObject input = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignored) {
                    return source;
                }
            };
            JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostic -> {},
                List.of("-proc:none"),
                null,
                List.of(input)
            );
            unit = task.parse().iterator().next();
            positions = Trees.instance(task).getSourcePositions();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree != null && getCurrentPath() != null) {
                        paths.add(new TreePath(getCurrentPath(), tree));
                    }
                    return super.scan(tree, unused);
                }
            }.scan(unit, null);
        }

        int start(Tree t) {
            return (int) positions.getStartPosition(unit, t);
        }

        int end(Tree t) {
            return (int) positions.getEndPosition(unit, t);
        }

        String text(Tree t) {
            return source.substring(start(t), end(t));
        }

        void edit(Tree t, String replacement) {
            edits.add(new Edit(start(t), end(t), replacement));
        }

        boolean imported(String type) {
            return unit
                .getImports()
                .stream()
                .anyMatch(i -> !i.isStatic() && i.getQualifiedIdentifier().toString().equals(type));
        }

        boolean shadowed(String simpleName) {
            return paths
                .stream()
                .map(TreePath::getLeaf)
                .anyMatch(
                    t ->
                        (t instanceof ClassTree c && c.getSimpleName().contentEquals(simpleName)) ||
                        (t instanceof VariableTree v && v.getName().contentEquals(simpleName))
                );
        }

        TreePath enclosing(TreePath path, Class<?> kind) {
            for (; path != null; path = path.getParentPath()) {
                if (kind.isInstance(path.getLeaf())) {
                    return path;
                }
            }
            return null;
        }

        List<TreePath> inside(Tree tree) {
            return paths
                .stream()
                .filter(p -> start(p.getLeaf()) >= start(tree) && end(p.getLeaf()) <= end(tree))
                .toList();
        }

        List<VariableTree> variables(Tree method, String name) {
            return inside(method)
                .stream()
                .map(TreePath::getLeaf)
                .filter(VariableTree.class::isInstance)
                .map(VariableTree.class::cast)
                .filter(v -> v.getName().contentEquals(name))
                .toList();
        }

        boolean drawContext(MethodTree method) {
            return (
                imported("net.minecraft.client.gui.DrawContext") &&
                !shadowed("DrawContext") &&
                method.getParameters().stream().anyMatch(p -> p.getType().toString().equals("DrawContext"))
            );
        }

        boolean entityType(String type, ExpressionTree receiver, MethodTree method) {
            Map<String, String> owners = Map.of(
                "Entity",
                "net.minecraft.entity.Entity",
                "LivingEntity",
                "net.minecraft.entity.LivingEntity",
                "PlayerEntity",
                "net.minecraft.entity.player.PlayerEntity",
                "ClientPlayerEntity",
                "net.minecraft.client.network.ClientPlayerEntity",
                "ServerPlayerEntity",
                "net.minecraft.server.network.ServerPlayerEntity"
            );
            if (type == null || !owners.containsKey(type)) {
                return false;
            }
            List<String> importedTypes = unit
                .getImports()
                .stream()
                .filter(i -> !i.isStatic())
                .map(i -> i.getQualifiedIdentifier().toString())
                .filter(i -> i.endsWith("." + type))
                .toList();
            if (!importedTypes.isEmpty()) {
                return importedTypes.size() == 1 && importedTypes.getFirst().equals(owners.get(type));
            }
            // javac can report the type of MinecraftClient.player without importing ClientPlayerEntity.
            if (
                type.equals("ClientPlayerEntity") &&
                receiver instanceof MemberSelectTree player &&
                player.getIdentifier().contentEquals("player") &&
                imported("net.minecraft.client.MinecraftClient")
            ) {
                List<VariableTree> clients = variables(method, player.getExpression().toString());
                return clients.size() == 1 && clients.getFirst().getType().toString().equals("MinecraftClient");
            }
            return false;
        }

        static String preflight(Known k, Path file, String source) throws IOException {
            ApiFix a = new ApiFix(file, source);
            for (TreePath p : a.paths) {
                if (
                    !(p.getLeaf() instanceof MethodInvocationTree call) ||
                    !(call.getMethodSelect() instanceof MemberSelectTree member)
                ) {
                    continue;
                }
                TreePath enclosing = a.enclosing(p, MethodTree.class);
                if (enclosing == null) {
                    continue;
                }
                MethodTree method = (MethodTree) enclosing.getLeaf();
                String name = member.getIdentifier().toString();
                boolean context =
                    a.drawContext(method) &&
                    method
                        .getParameters()
                        .stream()
                        .anyMatch(
                            v ->
                                v.getType().toString().equals("DrawContext") &&
                                v.getName().contentEquals(member.getExpression().toString())
                        );
                if (
                    "yarn".equals(k.names) &&
                    "1.21.5".equals(k.fromVersion) &&
                    "1.21.6".equals(k.toVersion) &&
                    context &&
                    ((name.equals("drawText") && call.getArguments().size() == 6) ||
                        (name.equals("drawTextWithShadow") && call.getArguments().size() == 5)) &&
                    k.sigTransitions
                        .getOrDefault("DrawContext." + name, Set.of())
                        .stream()
                        .anyMatch(transition -> {
                            String[] pair = transition.split(" -> ", 2);
                            return (
                                pair.length == 2 &&
                                pair[0].startsWith("(Lnet/minecraft/client/font/TextRenderer;") &&
                                pair[0].endsWith(")I") &&
                                pair[1].equals(pair[0].substring(0, pair[0].length() - 1) + "V")
                            );
                        })
                ) {
                    String opaque = a.opaqueTextColor(call.getArguments().get(4));
                    if (opaque != null) a.edit(call.getArguments().get(4), opaque);
                }
                if (
                    "1.21.5".equals(k.toVersion) &&
                    context &&
                    a.imported("net.minecraft.client.gl.RenderPipelines") &&
                    !a.shadowed("RenderPipelines") &&
                    (name.equals("drawGuiTexture") || name.equals("drawTexture")) &&
                    !call.getArguments().isEmpty() &&
                    call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED") &&
                    k.sigTo
                        .getOrDefault("DrawContext." + name, Set.of())
                        .stream()
                        .anyMatch(s -> s.contains("Ljava/util/function/Function;"))
                ) {
                    a.edit(call.getArguments().getFirst(), "net.minecraft.client.render.RenderLayer::getGuiTextured");
                }
                if (
                    "1.21.5".equals(k.toVersion) &&
                    context &&
                    name.equals("getMatrices") &&
                    call.getArguments().isEmpty() &&
                    k.sigTo
                        .getOrDefault("DrawContext.getMatrices", Set.of())
                        .contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")
                ) {
                    a.backportGuiMatrices(p, method, member);
                }
                if (
                    member.toString().equals("InputUtil.isKeyPressed") &&
                    a.imported("net.minecraft.client.util.InputUtil") &&
                    !a.shadowed("InputUtil") &&
                    k.sigTo
                        .getOrDefault("InputUtil.isKeyPressed", Set.of())
                        .contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z") &&
                    call.getArguments().size() == 2
                ) {
                    String window = a.windowFor(call.getArguments().getFirst(), method);
                    if (window != null) {
                        a.edit(call.getArguments().getFirst(), window);
                    }
                }
            }
            return a.withEdits();
        }

        /** One lint finding: a call site the map knows, with its rewrite when it is unambiguous. */
        record LintHit(int line, String oldName, String detail, boolean auto) {}

        /** The lint result: findings for the report, and the source with unanimous renames applied. */
        static final class Lint {
            final List<LintHit> hits = new ArrayList<>();
            String fixed;
        }

        static int lineOf(String source, int offset) {
            int line = 1;
            for (int i = 0; i < offset && i < source.length(); i++) {
                if (source.charAt(i) == 10) {
                    line++;
                }
            }
            return line;
        }

        /** Call sites and field reads by their receiver: game code we can rewrite, mod code we leave alone. */
        static final class Scope {
            final Set<String> methods = new HashSet<>(), vars = new HashSet<>(), types = new HashSet<>();
            final Map<String, String> varTypes = new HashMap<>(), imports = new HashMap<>(), staticImports = new HashMap<>();
            final Set<String> ambiguousStatics = new HashSet<>();
        }

        static String simpleTypeName(String type) {
            String t = type.replaceAll("<.*", "").replace("[]", "").trim();
            int dot = t.lastIndexOf('.');
            return dot >= 0 ? t.substring(dot + 1) : t;
        }

        static Scope scopeOf(CompilationUnitTree unit, List<TreePath> paths) {
            Scope s = new Scope();
            for (TreePath p : paths) {
                if (p.getLeaf() instanceof MethodTree m) {
                    s.methods.add(m.getName().toString());
                } else if (p.getLeaf() instanceof VariableTree v) {
                    String name = v.getName().toString();
                    s.vars.add(name);
                    if (v.getType() != null) {
                        s.varTypes.put(name, simpleTypeName(v.getType().toString()));
                    }
                } else if (p.getLeaf() instanceof ClassTree c) {
                    s.types.add(c.getSimpleName().toString());
                }
            }
            for (ImportTree i : unit.getImports()) {
                String fqn = i.getQualifiedIdentifier().toString();
                int dot = fqn.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                if (i.isStatic()) {
                    String name = fqn.substring(dot + 1);
                    if (name.equals("*") || s.staticImports.containsKey(name)) {
                        s.ambiguousStatics.add(name);
                    } else {
                        s.staticImports.put(name, fqn);
                    }
                } else {
                    s.imports.put(fqn.substring(dot + 1), fqn);
                }
            }
            return s;
            }

        static boolean gameImport(Scope scope, String simple) {
            String fqn = scope.imports.get(simple);
            return fqn != null && !scope.types.contains(simple) &&
                (fqn.startsWith("net.minecraft.") || fqn.startsWith("com.mojang."));
        }

        /** Whether a statically imported name unambiguously names game code. */
        static boolean gameStaticImport(Scope scope, String name) {
            String fqn = scope.staticImports.get(name);
            return fqn != null && !scope.ambiguousStatics.contains(name) && !scope.types.contains(name) &&
                (fqn.startsWith("net.minecraft.") || fqn.startsWith("com.mojang."));
        }

        /** The simple name of a type, or null when the tree is not a plain or qualified name. */
        static String typeName(Tree t) {
            if (t instanceof IdentifierTree id) {
                return id.getName().toString();
            }
            if (t instanceof MemberSelectTree m) {
                return m.getIdentifier().toString();
            }
            if (t instanceof ParameterizedTypeTree p) {
                return typeName(p.getType());
            }
            return null;
        }

        /** Whether the nearest enclosing class or interface directly extends a game type. */
        static boolean extendsGame(Scope scope, TreePath p) {
            for (TreePath q = p; q != null; q = q.getParentPath()) {
                if (!(q.getLeaf() instanceof ClassTree c)) {
                    continue;
                }
                if (c.getExtendsClause() != null) {
                    String sup = typeName(c.getExtendsClause());
                    if (sup != null && gameImport(scope, sup)) {
                        return true;
                    }
                }
                for (Tree io : c.getImplementsClause()) {
                    String sup = typeName(io);
                    if (sup != null && gameImport(scope, sup)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        /** A bare identifier in a type, annotation or declaration position: a name, never a field read. */
        static boolean nonValuePosition(TreePath p) {
            TreePath parent = p.getParentPath();
            if (parent == null) {
                return true;
            }
            Tree t = parent.getLeaf();
            if (t instanceof VariableTree v) {
                return v.getType() == p.getLeaf();
            }
            if (t instanceof MethodTree m) {
                return m.getReturnType() == p.getLeaf();
            }
            if (t instanceof NewClassTree n) {
                return n.getIdentifier() == p.getLeaf();
            }
            if (t instanceof InstanceOfTree io) {
                return io.getType() == p.getLeaf();
            }
            if (
                t instanceof ClassTree ||
                t instanceof TypeParameterTree ||
                t instanceof ParameterizedTypeTree ||
                t instanceof ArrayTypeTree ||
                t instanceof WildcardTree ||
                t instanceof UnionTypeTree ||
                t instanceof IntersectionTypeTree ||
                t instanceof AnnotatedTypeTree ||
                t instanceof AnnotationTree
            ) {
                return true;
            }
            for (TreePath q = p; q != null; q = q.getParentPath()) {
                if (q.getLeaf() instanceof AnnotationTree) {
                    return true;
                }
            }
            return false;
            }

        /** The leftmost name of a receiver expression, or null when it is not a plain name. */
        static String receiverRoot(ExpressionTree receiver) {
            Tree r = receiver;
            while (r instanceof MemberSelectTree m) {
                r = m.getExpression();
            }
            return r instanceof IdentifierTree id ? id.getName().toString() : null;
        }

        /** The simple type behind a call receiver, or null when the source cannot prove it. */
        static String receiverType(Scope scope, ExpressionTree receiver) {
            if (receiver instanceof NewClassTree n && n.getIdentifier() instanceof IdentifierTree id) {
                String name = id.getName().toString();
                return scope.imports.containsKey(name) ? simpleTypeName(scope.imports.get(name)) : name;
            }
            String root = receiverRoot(receiver);
            if (root == null || root.equals("this") || root.equals("super")) {
                return null;
            }
            if (scope.varTypes.containsKey(root)) {
                return scope.varTypes.get(root);
            }
            if (scope.imports.containsKey(root)) {
                return simpleTypeName(scope.imports.get(root));
            }
            return null;
        }

        /** A member select that names a type or a package, not a member access. */
        static boolean qualifiedContext(TreePath p) {
            TreePath parent = p.getParentPath();
            if (parent == null) {
                return true;
            }
            if (parent.getLeaf() instanceof MemberSelectTree) {
                return true;
            }
            if (parent.getLeaf() instanceof ImportTree) {
                return true;
            }
            if (parent.getLeaf() instanceof VariableTree v && v.getType() == p.getLeaf()) {
                return true;
            }
            if (parent.getLeaf() instanceof MethodTree m && m.getReturnType() == p.getLeaf()) {
                return true;
            }
            if (parent.getLeaf() instanceof NewClassTree n && n.getIdentifier() == p.getLeaf()) {
                return true;
            }
            if (parent.getLeaf() instanceof ClassTree) {
                return true;
            }
            if (parent.getLeaf() instanceof InstanceOfTree io && io.getType() == p.getLeaf()) {
                return true;
            }
            for (TreePath q = p; q != null; q = q.getParentPath()) {
                if (q.getLeaf() instanceof ImportTree) {
                    return true;
                }
            }
            return false;
        }

        static int callArity(String desc) {
            int depth = desc.indexOf('('), end = desc.indexOf(')');
            if (depth < 0 || end < 0) {
                return -1;
            }
            int count = 0, i = depth + 1;
            while (i < end) {
                char c = desc.charAt(i);
                if (c == '[') {
                    i++;
                } else if (c == 'L') {
                    int semi = desc.indexOf(';', i);
                    if (semi < 0 || semi > end) {
                        return -1;
                    }
                    i = semi + 1;
                    count++;
                } else {
                    i++;
                    count++;
                }
            }
            return count;
        }

        static String stepsFor(Known k, String kind, String name, Set<String> whats) {
            String key = k.names + "\0" + k.fromVersion + "\0" + k.toVersion + "\0" + kind + "\0" + name + "\0" + String.join(",", new TreeSet<>(whats));
            return stepsForCache.computeIfAbsent(key, x -> {
                List<String> steps = new ArrayList<>();
                for (Map<String, String> e : k.history) {
                    if (kind.equals(e.get("kind")) && name.equals(e.get("name")) && whats.contains(e.get("what"))) {
                        steps.add(e.get("step"));
                    }
                }
                return String.join(", ", new LinkedHashSet<>(steps));
            });
        }

        static final Map<String, String> stepsForCache = new HashMap<>();

        static String candidatesDetail(Set<String[]> candidates) {
            Set<String> names = new TreeSet<>();
            for (String[] c : candidates) {
                names.add(c[1]);
            }
            return String.join("|", names);
        }

        /**
         * Map-driven lint for any namespace: parse only, no build. Every method call and dotted
         * field read whose name the map renamed, moved or removed is reported with the update that
         * A rename is rewritten only when it is unanimous (one new name everywhere),
         * the file does not declare the name itself, and the receiver provably names a game type
         * (or is a game static import); unqualified calls in a game subclass and everything else
         * are reported for a human.
         */
        static Lint lintKnown(Known k, Path file, String source) throws IOException {
            ApiFix a = new ApiFix(file, source);
            Lint out = new Lint();
            out.fixed = source;
            List<String> lines = Arrays.asList(source.split("\\R", -1));
            Scope scope = scopeOf(a.unit, a.paths);
            for (TreePath p : a.paths) {
                if (p.getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree member) {
                    String name = member.getIdentifier().toString();
                    int line = lineOf(source, a.start(member));
                    if (scope.methods.contains(name)) {
                        if (k.methodTo.containsKey(name) || k.methodGone.contains(name) || k.sigNames.contains(name)) {
                            out.hits.add(new LintHit(line, name, name + " declared here; skipped [self-declared]", false));
                        }
                        continue;
                    }
                    Set<String[]> candidates = k.methodTo.get(name);
                    if (candidates == null || candidates.isEmpty()) {
                        if (k.methodGone.contains(name)) {
                            out.hits.add(
                                new LintHit(line, name, name + " removed (" + stepsFor(k, "method", name, Set.of("removed")) + ") [hand-fix]", false)
                            );
                        } else if (k.sigNames.contains(name)) {
                            boolean any = false;
                            for (Map.Entry<String, Set<String>> e : k.sigTo.entrySet()) {
                                if (!e.getKey().endsWith("." + name)) {
                                    continue;
                                }
                                for (String desc : e.getValue()) {
                                    if (callArity(desc) == call.getArguments().size()) {
                                        any = true;
                                    }
                                }
                            }
                            if (!any) {
                                out.hits.add(
                                    new LintHit(line, name, name + " signature changed (" + stepsFor(k, "method", name, Set.of("signature")) + ") [review args]", false)
                                );
                            }
                        }
                        continue;
                    }
                    String steps = stepsFor(k, "method", name, Set.of("renamed", "moved"));
                    String type = receiverType(scope, member.getExpression());
                    boolean game = type != null && gameImport(scope, type);
                    Set<String[]> relevant = candidates;
                    if (game) {
                        List<String[]> owned = new ArrayList<>();
                        for (String[] c : candidates) {
                            if (c[0].equals(type)) {
                                owned.add(c);
                            }
                        }
                        if (!owned.isEmpty()) {
                            relevant = new HashSet<>(owned);
                        }
                    }
                    String to = game ? Known.unanimous(relevant) : null;
                    boolean applied = false;
                    if (to != null) {
                        String next = replaceCall(out.fixed, line, name, to);
                        applied = !next.equals(out.fixed);
                        out.fixed = next;
                    }
                    String where = member.getExpression() + "." + name;
                    if (applied) {
                        out.hits.add(new LintHit(line, name, where + " -> " + to + " (" + steps + ") [auto]", true));
                    } else {
                        out.hits.add(
                            new LintHit(line, name, where + " -> " + candidatesDetail(candidates) + " (" + steps + ") [review]", false)
                        );
                    }
                } else if (p.getLeaf() instanceof MemberSelectTree member && !qualifiedContext(p)) {
                    String name = member.getIdentifier().toString();
                    int line = lineOf(source, a.start(member));
                    if (scope.vars.contains(name)) {
                        if (k.fieldTo.containsKey(name) || k.fieldGone.contains(name)) {
                            out.hits.add(new LintHit(line, name, name + " declared here; skipped [self-declared]", false));
                        }
                        continue;
                    }
                    String lineText = line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
                    Set<String[]> candidates = k.fieldTo.get(name);
                    if (candidates == null || candidates.isEmpty()) {
                        if (k.fieldGone.contains(name)) {
                            out.hits.add(
                                new LintHit(line, name, name + " removed (" + stepsFor(k, "field", name, Set.of("removed")) + ") [hand-fix]", false)
                            );
                        }
                        continue;
                    }
                    if (lineText.matches(".*\\b" + Pattern.quote(name) + "\\s*\\(.*")) {
                        out.hits.add(new LintHit(line, name, name + " -> " + candidatesDetail(candidates) + " [review: call-shaped]", false));
                        continue;
                    }
                    String steps = stepsFor(k, "field", name, Set.of("renamed", "moved"));
                    String type = receiverType(scope, member.getExpression());
                    boolean game = type != null && gameImport(scope, type);
                    Set<String[]> relevant = candidates;
                    if (game) {
                        List<String[]> owned = new ArrayList<>();
                        for (String[] c : candidates) {
                            if (c[0].equals(type)) {
                                owned.add(c);
                            }
                        }
                        if (!owned.isEmpty()) {
                            relevant = new HashSet<>(owned);
                        }
                    }
                    String to = game ? Known.unanimous(relevant) : null;
                    boolean applied = false;
                    if (to != null) {
                        String next = replaceField(out.fixed, line, name, to);
                        applied = !next.equals(out.fixed);
                        out.fixed = next;
                    }
                    String where = member.getExpression() + "." + name;
                    if (applied) {
                        out.hits.add(new LintHit(line, name, where + " -> " + to + " (" + steps + ") [auto]", true));
                    } else {
                        out.hits.add(new LintHit(line, name, where + " -> " + candidatesDetail(candidates) + " (" + steps + ") [review]", false));
                    }
                } else if (
                    p.getLeaf() instanceof MethodInvocationTree call &&
                    call.getMethodSelect() instanceof IdentifierTree id &&
                    !(call.getMethodSelect() instanceof MemberSelectTree)
                ) {
                    // An unqualified call: provable only through a static import or an inherited
                    // game member. Anything else is the mod's own code or broken code; stay silent.
                    String name = id.getName().toString();
                    if (scope.methods.contains(name) || !k.methodTo.containsKey(name)) {
                        continue;
                    }
                    int line = lineOf(source, a.start(call));
                    String steps = stepsFor(k, "method", name, Set.of("renamed", "moved"));
                    if (gameStaticImport(scope, name)) {
                        String to = Known.unanimous(k.methodTo.get(name));
                        if (to != null) {
                            String next = replaceCall(out.fixed, line, name, to);
                            if (!next.equals(out.fixed)) {
                                out.fixed = next;
                                out.hits.add(new LintHit(line, name, name + " -> " + to + " (" + steps + ") [auto: static import]", true));
                                continue;
                            }
                        }
                    }
                    if (!extendsGame(scope, p)) {
                        continue;
                    }
                    out.hits.add(
                        new LintHit(
                            line,
                            name,
                            name + " -> " + candidatesDetail(k.methodTo.get(name)) + " (" + steps + ") [review: unqualified call in game subclass]",
                            false
                        )
                    );
                } else if (p.getLeaf() instanceof IdentifierTree id && !(p.getParentPath() instanceof MemberSelectTree) && !nonValuePosition(p)) {
                    // A bare name: a local (skipped silently), an inherited game field (reported),
                    // a static import (auto when unanimous), or nothing we can prove (skipped).
                    String name = id.getName().toString();
                    if (scope.vars.contains(name) || scope.methods.contains(name) || !k.fieldTo.containsKey(name)) {
                        continue;
                    }
                    int line = lineOf(source, a.start(id));
                    String steps = stepsFor(k, "field", name, Set.of("renamed", "moved"));
                    if (gameStaticImport(scope, name)) {
                        String to = Known.unanimous(k.fieldTo.get(name));
                        if (to != null) {
                            String next = replaceField(out.fixed, line, name, to);
                            if (!next.equals(out.fixed)) {
                                out.fixed = next;
                                out.hits.add(new LintHit(line, name, name + " -> " + to + " (" + steps + ") [auto: static import]", true));
                                continue;
                            }
                        }
                    }
                    if (!extendsGame(scope, p)) {
                        continue;
                    }
                    out.hits.add(
                        new LintHit(
                            line,
                            name,
                            name + " -> " + candidatesDetail(k.fieldTo.get(name)) + " (" + steps + ") [review: unqualified field in game subclass]",
                            false
                        )
                    );
                }
            }
            return out;
        }

        static String replaceCall(String source, int line, String from, String to) {
            return replaceOnLine(source, line, "\\b" + Pattern.quote(from) + "(?=\\s*\\()", Matcher.quoteReplacement(to));
        }

        static String replaceField(String source, int line, String from, String to) {
            return replaceOnLine(source, line, "\\b" + Pattern.quote(from) + "\\b(?![\\w$])", Matcher.quoteReplacement(to));
        }

        static String replaceOnLine(String source, int line, String pattern, String replacement) {
            String nl = source.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = source.split("\r\n|\n", -1);
            if (line < 1 || line > lines.length) {
                return source;
            }
            lines[line - 1] = Pattern.compile(pattern).matcher(lines[line - 1]).replaceAll(replacement);
            return String.join(nl, lines);
        }

        String opaqueTextColor(ExpressionTree color) {
            if (color instanceof LiteralTree literal && literal.getValue() instanceof Integer value) {
                return (value & 0xFC000000) == 0 ? String.format("0x%08X", value | 0xFF000000) : null;
            }
            boolean stable =
                color instanceof IdentifierTree ||
                (color instanceof MemberSelectTree member && member.getExpression() instanceof IdentifierTree);
            if (!stable) return null;
            String value = text(color);
            return "((" + value + " & 0xFC000000) == 0 ? (" + value + " | 0xFF000000) : " + value + ")";
        }

        static String apply(Known k, Path file, String source, List<Err> errors) throws IOException {
            if (!"yarn".equals(k.names)) {
                return source;
            }
            ApiFix a = new ApiFix(file, source);
            String[] lines = source.split("\r?\n", -1);
            for (Err e : errors) {
                // A preceding rule/import can invalidate a supplied log. Rebuild before using those positions.
                if (
                    e.caret() < 0 ||
                    e.line() < 1 ||
                    e.line() > lines.length ||
                    !lines[e.line() - 1].strip().equals(e.source().strip())
                ) {
                    continue;
                }
                long at = a.unit.getLineMap().getPosition(e.line(), 1) + e.caret();
                for (TreePath p : a.paths) {
                    Tree tree = p.getLeaf();
                    if (a.start(tree) > at || a.end(tree) <= at) {
                        continue;
                    }
                    TreePath mp = a.enclosing(p, MethodTree.class);
                    if (mp == null) {
                        continue;
                    }
                    MethodTree method = (MethodTree) mp.getLeaf();
                    if (
                        tree instanceof MemberSelectTree field &&
                        field.toString().equals("MinecraftClient.IS_SYSTEM_MAC") &&
                        a.imported("net.minecraft.client.MinecraftClient") &&
                        k.fieldGone.contains("IS_SYSTEM_MAC") &&
                        "1.21.9".equals(k.toVersion)
                    ) {
                        a.edit(field, "net.minecraft.client.input.SystemKeycodes.IS_MAC_OS");
                    }
                    if (
                        tree instanceof MethodInvocationTree call &&
                        call.getMethodSelect() instanceof MemberSelectTree member
                    ) {
                        String name = member.getIdentifier().toString();
                        String type = Known.locationType(e.location());
                        if (
                            "1.21.5".equals(k.toVersion) &&
                            e
                                .message()
                                .contains("RenderPipeline cannot be converted to Function<Identifier,RenderLayer>") &&
                            a.drawContext(method) &&
                            a.imported("net.minecraft.client.gl.RenderPipelines") &&
                            !a.shadowed("RenderPipelines") &&
                            (name.equals("drawGuiTexture") || name.equals("drawTexture")) &&
                            k.sigTo
                                .getOrDefault("DrawContext." + name, Set.of())
                                .stream()
                                .anyMatch(s -> s.contains("Ljava/util/function/Function;")) &&
                            !call.getArguments().isEmpty() &&
                            call.getArguments().getFirst().toString().equals("RenderPipelines.GUI_TEXTURED") &&
                            method
                                .getParameters()
                                .stream()
                                .anyMatch(
                                    v ->
                                        v.getType().toString().equals("DrawContext") &&
                                        v.getName().contentEquals(member.getExpression().toString())
                                )
                        ) {
                            a.edit(
                                call.getArguments().getFirst(),
                                "net.minecraft.client.render.RenderLayer::getGuiTextured"
                            );
                        }
                        if (
                            "1.21.5".equals(k.toVersion) &&
                            e.message().contains("MatrixStack cannot be converted to Matrix3x2fStack") &&
                            name.equals("getMatrices") &&
                            a.drawContext(method) &&
                            k.sigTo
                                .getOrDefault("DrawContext.getMatrices", Set.of())
                                .contains("getMatrices()Lnet/minecraft/client/util/math/MatrixStack;")
                        ) {
                            a.backportGuiMatrices(p, method, member);
                        }
                        if (
                            e.message().equals("cannot find symbol") &&
                            e.symbol() != null &&
                            e.symbol().startsWith("method " + name + "(") &&
                            call.getArguments().isEmpty() &&
                            a.entityType(type, member.getExpression(), method)
                        ) {
                            String to = name.equals("getEntityWorld") && "1.21.6".equals(k.toVersion)
                                ? "getWorld"
                                : name.equals("getWorld") && "1.21.9".equals(k.toVersion) ? "getEntityWorld" : null;
                            if (to != null && k.methodGone.contains(name)) {
                                a.edits.add(new Edit(a.end(member) - name.length(), a.end(member), to));
                            }
                        }
                        if (
                            member.toString().equals("InputUtil.isKeyPressed") &&
                            a.imported("net.minecraft.client.util.InputUtil") &&
                            !a.shadowed("InputUtil") &&
                            k.sigTo
                                .getOrDefault("InputUtil.isKeyPressed", Set.of())
                                .contains("isKeyPressed(Lnet/minecraft/client/util/Window;I)Z") &&
                            e.message().contains("long cannot be converted to Window") &&
                            call.getArguments().size() == 2
                        ) {
                            ExpressionTree arg = call.getArguments().getFirst();
                            String window = a.windowFor(arg, method);
                            if (window != null) {
                                a.edit(arg, window);
                            }
                        }
                        if (
                            member.toString().equals("RenderSystem.setShaderColor") &&
                            e.message().equals("cannot find symbol") &&
                            e.symbol() != null &&
                            e.symbol().startsWith("method setShaderColor(") &&
                            a.imported("com.mojang.blaze3d.systems.RenderSystem") &&
                            a.drawContext(method) &&
                            k.sigTo
                                .getOrDefault("DrawContext.getMatrices", Set.of())
                                .contains("getMatrices()Lorg/joml/Matrix3x2fStack;") &&
                            call.getArguments().size() == 4 &&
                            call
                                .getArguments()
                                .stream()
                                .allMatch(
                                    x ->
                                        x instanceof LiteralTree l &&
                                        l.getValue() instanceof Number n &&
                                        n.doubleValue() == 1.0
                                ) &&
                            p.getParentPath().getLeaf() instanceof ExpressionStatementTree statement
                        ) {
                            a.edit(statement, "");
                        }
                        if (
                            name.equals("getMatrices") &&
                            e.message().contains("Matrix3x2fStack cannot be converted to MatrixStack") &&
                            a.drawContext(method)
                        ) {
                            a.guiMatrices(p, method, member);
                        }
                    }
                }
            }
            return a.withEdits();
        }

        String withEdits() {
            // Deduplicate diagnostics/overlapping selections, then edit from right to left.
            List<Edit> unique = new ArrayList<>(new LinkedHashSet<>(edits));
            unique.sort(Comparator.comparingInt(Edit::start).reversed());
            int boundary = source.length();
            String result = source;
            for (Edit e : unique) {
                if (e.start() < 0 || e.end() > boundary) {
                    continue;
                }
                result = result.substring(0, e.start()) + e.text() + result.substring(e.end());
                boundary = e.start();
            }
            return result;
        }

        void backportGuiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
            Tree parent = callPath.getParentPath().getLeaf();
            String name = parent instanceof AssignmentTree assign ? assign.getVariable().toString() : "";
            List<VariableTree> vars = variables(method, name);
            if (
                vars.size() != 1 ||
                !vars.getFirst().getType().toString().equals("Matrix3x2fStack") ||
                !imported("org.joml.Matrix3x2fStack")
            ) {
                return;
            }
            String context = getMatrices.getExpression().toString();
            if (
                method
                    .getParameters()
                    .stream()
                    .noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))
            ) {
                return;
            }
            List<Edit> planned = new ArrayList<>();
            planned.add(
                new Edit(
                    start(vars.getFirst().getType()),
                    end(vars.getFirst().getType()),
                    "net.minecraft.client.util.math.MatrixStack"
                )
            );
            for (TreePath p : inside(method)) {
                if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) {
                    continue;
                }
                Tree up = p.getParentPath().getLeaf();
                if (
                    up instanceof AssignmentTree assign &&
                    assign.getVariable() == id &&
                    assign.getExpression() == callPath.getLeaf()
                ) {
                    continue;
                }
                if (up instanceof VariableTree variable && variable == vars.getFirst()) {
                    continue;
                }
                if (
                    !(up instanceof MemberSelectTree member) ||
                    member.getExpression() != id ||
                    !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)
                ) {
                    return;
                }
                String operation = member.getIdentifier().toString();
                List<? extends ExpressionTree> args = call.getArguments();
                String replacement;
                if ((operation.equals("pushMatrix") || operation.equals("popMatrix")) && args.isEmpty()) {
                    replacement = name + "." + (operation.equals("pushMatrix") ? "push" : "pop") + "()";
                } else if ((operation.equals("translate") || operation.equals("scale")) && args.size() == 2) {
                    replacement =
                        name +
                        "." +
                        operation +
                        "(" +
                        text(args.get(0)) +
                        ", " +
                        text(args.get(1)) +
                        (operation.equals("translate") ? ", 0" : ", 1") +
                        ")";
                } else {
                    return;
                }
                planned.add(new Edit(start(call), end(call), replacement));
            }
            edits.addAll(planned);
        }

        String windowFor(ExpressionTree argument, MethodTree method) {
            ExpressionTree value = argument;
            if (argument instanceof IdentifierTree id) {
                List<VariableTree> vars = variables(method, id.getName().toString());
                if (vars.size() != 1 || vars.getFirst().getInitializer() == null) {
                    return null;
                }
                value = vars.getFirst().getInitializer();
                for (TreePath p : inside(method)) {
                    Tree t = p.getLeaf();
                    if (
                        (t instanceof AssignmentTree assign && assign.getVariable().toString().equals(id.toString())) ||
                        (t instanceof CompoundAssignmentTree compound &&
                            compound.getVariable().toString().equals(id.toString())) ||
                        (t instanceof UnaryTree unary &&
                            unary.getExpression().toString().equals(id.toString()) &&
                            Set.of(
                                Tree.Kind.PREFIX_INCREMENT,
                                Tree.Kind.PREFIX_DECREMENT,
                                Tree.Kind.POSTFIX_INCREMENT,
                                Tree.Kind.POSTFIX_DECREMENT
                            ).contains(t.getKind()))
                    ) {
                        return null;
                    }
                }
            }
            if (
                value instanceof MethodInvocationTree call &&
                call.getArguments().isEmpty() &&
                call.getMethodSelect().toString().equals("MinecraftClient.getInstance().getWindow().getHandle") &&
                imported("net.minecraft.client.MinecraftClient") &&
                !shadowed("MinecraftClient")
            ) {
                return "MinecraftClient.getInstance().getWindow()";
            }
            return null;
        }

        void guiMatrices(TreePath callPath, MethodTree method, MemberSelectTree getMatrices) {
            // Only a local GUI stack with known uses. Passing it elsewhere or mixing in 3D operations needs a guided port.
            Tree parent = callPath.getParentPath().getLeaf();
            String name = parent instanceof AssignmentTree assign
                ? assign.getVariable().toString()
                : parent instanceof VariableTree v ? v.getName().toString() : "";
            List<VariableTree> vars = variables(method, name);
            if (
                vars.size() != 1 ||
                !vars.getFirst().getType().toString().equals("MatrixStack") ||
                !imported("net.minecraft.client.util.math.MatrixStack")
            ) {
                return;
            }
            String context = getMatrices.getExpression().toString();
            if (
                method
                    .getParameters()
                    .stream()
                    .noneMatch(v -> v.getName().contentEquals(context) && v.getType().toString().equals("DrawContext"))
            ) {
                return;
            }
            List<Edit> planned = new ArrayList<>();
            VariableTree variable = vars.getFirst();
            if (method.getParameters().contains(variable)) {
                return;
            }
            if (variable.getInitializer() != null && variable.getInitializer() != callPath.getLeaf()) {
                return;
            }
            planned.add(new Edit(start(variable.getType()), end(variable.getType()), "org.joml.Matrix3x2fStack"));
            for (TreePath p : inside(method)) {
                if (!(p.getLeaf() instanceof IdentifierTree id) || !id.getName().contentEquals(name)) {
                    continue;
                }
                Tree up = p.getParentPath().getLeaf();
                if (
                    up instanceof AssignmentTree assign &&
                    assign.getVariable() == id &&
                    assign.getExpression() == callPath.getLeaf()
                ) {
                    continue;
                }
                if (
                    !(up instanceof MemberSelectTree member) ||
                    member.getExpression() != id ||
                    !(p.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree call)
                ) {
                    return;
                }
                String operation = member.getIdentifier().toString();
                List<? extends ExpressionTree> args = call.getArguments();
                String replacement;
                if ((operation.equals("push") || operation.equals("pop")) && args.isEmpty()) {
                    replacement = name + "." + operation + "Matrix()";
                } else if (
                    (operation.equals("translate") || operation.equals("scale")) &&
                    args.size() == 3 &&
                    (args.get(2) instanceof IdentifierTree || args.get(2) instanceof LiteralTree)
                ) {
                    // GUI draw order replaces Z depth. Keep X/Y unchanged and never discard a call with side effects.
                    replacement = name + "." + operation + "(" + text(args.get(0)) + ", " + text(args.get(1)) + ")";
                } else {
                    return;
                }
                planned.add(new Edit(start(call), end(call), replacement));
            }
            edits.addAll(planned);
        }
    }

    static String unq(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ------------------------------------------------------------------ learn from real ports

    /**
     * Reads unified diffs of real ports of the same jump (other mods' "old version branch ->
     * new version branch") and keeps the line rewrites that touch something the map says changed:
     * the smallest differing span of each changed line pair, e.g.
     * "RenderLayer::getGuiTextured" -> "RenderPipelines.GUI_TEXTURED", with the imports that file
     * added for it. Writes one rule per line, most-seen first.
     */
    static final class Learn {

        static final Pattern TOKEN = Pattern.compile("\\w+|::|->|\\S");
        static final String SEP = "\u0001";

        static void run(Path mapFile, List<String> diffs, Path out) throws IOException {
            Known k = Known.load(mapFile);
            Set<String> changed = new HashSet<>();
            changed.addAll(k.sigNames);
            changed.addAll(k.methodGone);
            changed.addAll(k.fieldGone);
            changed.addAll(k.methodTo.keySet());
            changed.addAll(k.fieldTo.keySet());
            changed.addAll(k.classBySimple.keySet());
            changed.addAll(k.removedClassBySimple.keySet());
            Map<String, int[]> seen = new LinkedHashMap<>();
            Map<String, Set<String>> sources = new HashMap<>(), imports = new HashMap<>();
            for (String d : diffs) {
                Path p = Path.of(d);
                String source = p.getFileName().toString().replaceAll("\\.diff$", "");
                List<String> removed = new ArrayList<>(), added = new ArrayList<>(), fileImports = new ArrayList<>();
                List<String[]> pairsOfFile = new ArrayList<>();
                boolean java = false;
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.startsWith("diff --git")) {
                        flush(removed, added, pairsOfFile);
                        record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
                        pairsOfFile.clear();
                        fileImports.clear();
                        java = line.endsWith(".java");
                        continue;
                    }
                    if (!java || line.startsWith("+++") || line.startsWith("---")) {
                        continue;
                    }
                    if (line.startsWith("-")) {
                        if (!added.isEmpty()) {
                            flush(removed, added, pairsOfFile);
                        }
                        removed.add(line.substring(1));
                    } else if (line.startsWith("+")) {
                        String a = line.substring(1);
                        if (a.startsWith("import ")) {
                            fileImports.add(a.replaceAll("^import\\s+|;\\s*$", ""));
                        }
                        added.add(a);
                    } else {
                        flush(removed, added, pairsOfFile);
                    }
                }
                flush(removed, added, pairsOfFile);
                record(pairsOfFile, fileImports, changed, seen, sources, imports, source);
            }
            List<String> keys = new ArrayList<>(seen.keySet());
            keys.sort((a, b) -> seen.get(b)[0] - seen.get(a)[0]);
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                String[] ft = key.split(SEP, 2);
                sb
                    .append("{ \"from\": ")
                    .append(Diff.q(ft[0]))
                    .append(", \"to\": ")
                    .append(Diff.q(ft[1]))
                    .append(", \"seen\": ")
                    .append(seen.get(key)[0])
                    .append(", \"sources\": [")
                    .append(String.join(", ", sources.get(key).stream().map(Diff::q).toList()))
                    .append("]")
                    .append(", \"imports\": [")
                    .append(String.join(", ", imports.getOrDefault(key, Set.of()).stream().map(Diff::q).toList()))
                    .append("] }\n");
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("learned " + keys.size() + " rewrite(s) from " + diffs.size() + " port(s) -> " + out);
            keys
                .stream()
                .limit(15)
                .forEach(x -> System.out.println("  " + seen.get(x)[0] + "x  " + x.replace(SEP, "  ->  ")));
        }

        /** Pairs each removed line with its most similar added line. */
        static void flush(List<String> removed, List<String> added, List<String[]> pairs) {
            Set<Integer> used = new HashSet<>();
            for (String r : removed) {
                int best = -1;
                double score = 0.5;
                for (int i = 0; i < added.size(); i++) {
                    if (used.contains(i)) {
                        continue;
                    }
                    double s = similarity(r, added.get(i));
                    if (s > score) {
                        score = s;
                        best = i;
                    }
                }
                if (best >= 0) {
                    used.add(best);
                    pairs.add(new String[] { r, added.get(best) });
                }
            }
            removed.clear();
            added.clear();
        }

        static double similarity(String a, String b) {
            Set<String> ta = new HashSet<>(tokens(a)), tb = new HashSet<>(tokens(b));
            if (ta.isEmpty() || tb.isEmpty()) {
                return 0;
            }
            Set<String> both = new HashSet<>(ta);
            both.retainAll(tb);
            Set<String> any = new HashSet<>(ta);
            any.addAll(tb);
            return (double) both.size() / any.size();
        }

        static List<String> tokens(String s) {
            List<String> out = new ArrayList<>();
            Matcher m = TOKEN.matcher(s);
            while (m.find()) {
                out.add(m.group());
            }
            return out;
        }

        static void record(
            List<String[]> pairs,
            List<String> fileImports,
            Set<String> changed,
            Map<String, int[]> seen,
            Map<String, Set<String>> sources,
            Map<String, Set<String>> imports,
            String source
        ) {
            for (String[] pr : pairs) {
                String[] span = span(pr[0], pr[1]);
                if (span == null) {
                    continue;
                }
                boolean anchored = false;
                for (String t : tokens(span[0])) {
                    if (changed.contains(t)) {
                        anchored = true;
                        break;
                    }
                }
                if (!anchored || span[0].contains("\"") || span[0].length() > 80 || span[1].length() > 100) {
                    continue;
                }
                // Bare names lose their declaring type; published mappings already cover certain renames.
                // Keep self-contained qualified expressions, never arbitrary fragments of a mod's code.
                if (
                    !span[0].matches("[A-Z]\\w*(?:\\.|::).*") ||
                    !span[1].matches("[A-Z]\\w*(?:\\.|::).*") ||
                    !balanced(span[0]) ||
                    !balanced(span[1])
                ) {
                    continue;
                }
                if (
                    fileImports.stream().anyMatch(im -> im.contains(".impl.") && tokens(span[1]).contains(simple(im)))
                ) {
                    continue;
                }
                if (introducesLocalName(span, fileImports)) {
                    continue;
                }
                String key = span[0] + SEP + span[1];
                seen.computeIfAbsent(key, x -> new int[1])[0]++;
                sources.computeIfAbsent(key, x -> new TreeSet<>()).add(source);
                for (String im : fileImports) {
                    String simpleName = im.substring(im.lastIndexOf('.') + 1);
                    if (Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(span[1]).find()) {
                        imports.computeIfAbsent(key, x -> new TreeSet<>()).add(im);
                    }
                }
            }
        }

        static final Set<String> KEYWORDS = Set.of(
            "new",
            "this",
            "super",
            "null",
            "true",
            "false",
            "return",
            "instanceof",
            "int",
            "long",
            "float",
            "double",
            "boolean",
            "byte",
            "char",
            "short",
            "void",
            "var",
            "final",
            "class"
        );

        /**
         * True when the new side names something the target file cannot see: not in the old side,
         * not imported by that port, not reached through "." or "::". Those come from that mod's own
         * code (a local constant, a helper), so the rewrite would not compile anywhere else.
         */
        static boolean introducesLocalName(String[] span, List<String> fileImports) {
            Set<String> old = new HashSet<>(tokens(span[0]));
            Set<String> imported = new HashSet<>();
            for (String im : fileImports) {
                imported.add(im.substring(im.lastIndexOf('.') + 1));
            }
            List<String> t = tokens(span[1]);
            for (int i = 0; i < t.size(); i++) {
                String x = t.get(i);
                if (x.equals("this") || x.equals("super")) {
                    return true;
                }
                if (!x.matches("[A-Za-z_]\\w*") || old.contains(x) || imported.contains(x) || KEYWORDS.contains(x)) {
                    continue;
                }
                if (i > 0 && (t.get(i - 1).equals(".") || t.get(i - 1).equals("::"))) {
                    continue;
                }
                return true;
            }
            return false;
        }

        static boolean balanced(String expression) {
            Deque<String> open = new ArrayDeque<>();
            for (String t : tokens(expression)) {
                if (t.equals("(") || t.equals("[")) {
                    open.push(t);
                }
                if (t.equals(")") || t.equals("]")) {
                    if (open.isEmpty() || !open.pop().equals(t.equals(")") ? "(" : "[")) {
                        return false;
                    }
                }
            }
            return open.isEmpty();
        }

        /** The smallest differing source span between two lines, cut on token boundaries. */
        static String[] span(String a, String b) {
            List<int[]> pa = positions(a), pb = positions(b);
            int pre = 0;
            while (pre < pa.size() && pre < pb.size() && tok(a, pa.get(pre)).equals(tok(b, pb.get(pre)))) {
                pre++;
            }
            int suf = 0;
            while (
                suf < pa.size() - pre &&
                suf < pb.size() - pre &&
                tok(a, pa.get(pa.size() - 1 - suf)).equals(tok(b, pb.get(pb.size() - 1 - suf)))
            ) {
                suf++;
            }
            if (pre == pa.size() && pre == pb.size()) {
                return null;
            }
            if (pre >= pa.size() - suf) {
                return null;
            }
            int as = pa.get(pre)[0], ae = pa.get(pa.size() - 1 - suf)[1];
            String na = a.substring(as, ae);
            String nb = pre < pb.size() - suf ? b.substring(pb.get(pre)[0], pb.get(pb.size() - 1 - suf)[1]) : "";
            return new String[] { na, nb };
        }

        static List<int[]> positions(String s) {
            List<int[]> out = new ArrayList<>();
            Matcher m = TOKEN.matcher(s);
            while (m.find()) {
                out.add(new int[] { m.start(), m.end() });
            }
            return out;
        }

        static String tok(String s, int[] p) {
            return s.substring(p[0], p[1]);
        }
    }
}
