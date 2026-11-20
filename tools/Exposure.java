import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Exposure - what the game's own code did about each game part a ported mod still references and the new release lacks.
 *
 * JarPort's report ranks the references that do not link by the mods they hold back. For each, GameDiff reads both
 * releases and says what the game's own code shows: "replaced" (its uses line up with what the new release uses
 * instead), "template" or "split" (a member read became a chain), "linked" (the map follows it to a new signature),
 * "redesigned" (every use was rewritten beyond lining up) or "unused" (the game never used it). The first kinds are
 * the ones a porter can learn from the game; that is what makes the list a to-do list.
 *
 *   java Exposure --from 1.21.1 --to 1.21.2 [--loader neoforge] --report jarport.json --out evidence.json
 *
 * Also the shared lookups of the port: one release's snapshot with its loader, where a member is declared, and the
 * route map read for lookups.
 */
public final class Exposure {

    public static void main(String[] args) throws Exception {
        String from = null, to = null, out = null, loader = null, report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--report" -> report = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (from == null || to == null || out == null || report == null) {
            System.err.println("usage: Exposure --from V --to V [--loader neoforge] --report jarport.json --out evidence.json");
            System.exit(2);
        }
        GameDiff.Snap a = snap(from, loader), b = snap(to, loader);
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        Map<String, GameDiff.Hop.Evidence> evidence = hop.evidence();
        Set<String> portable = hop.portable();
        Map<String, Object> said = new LinkedHashMap<>();
        for (Object o : Json.array(Json.read(Path.of(report)).get("unlinkedByMods"))) {
            String member = Json.text(Json.object(o), "member", "");
            String key = key(a, member);
            if (key == null) {
                continue;
            }
            String[] game = gameSays(evidence.get(key));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("game", game[0]);
            row.put("gameDetail", game[1]);
            row.put("portable", portable.contains(key));
            row.put("part", loaderPart(a, key) ? "loader" : "game");
            said.put(member, row);
        }
        Files.writeString(Path.of(out), Json.write(said), StandardCharsets.UTF_8);
        System.out.printf("%s -> %s: what the game's own code did, for %d references%n", from, to, said.size());
    }

    /** Whether a key (a class, or owner.member) names the loader's API rather than the game's. */
    static boolean loaderPart(GameDiff.Snap a, String key) {
        String k = key.contains("(") ? key.substring(0, key.indexOf('(')) : key;
        GameDiff.Cls c = a.classes.get(k.replace('.', '/'));
        if (c == null && k.lastIndexOf('.') > 0) {
            c = a.classes.get(k.substring(0, k.lastIndexOf('.')).replace('.', '/'));
        }
        return c != null && c.loader;
    }

    static final Pattern REFERENCE = Pattern.compile("^[^:]+: ([\\w.$]+?)(?:\\.([\\w$<>]+)(\\(.*)?)?$");

    /**
     * The GameDiff key of what a JarPort line names ("call: a.b.C.m(desc)", "field: a.b.C.f", "class gone: a.b.C"), at
     * the class of the old release that declares it; null for what the old release does not have (a new abstract
     * method, a member of another mod).
     */
    static String key(GameDiff.Snap a, String line) {
        Matcher m = REFERENCE.matcher(line.replaceAll(" \\(in [^)]*\\)$", ""));
        if (!m.matches()) {
            return null;
        }
        String owner = m.group(1).replace('.', '/'), name = m.group(2), desc = m.group(3);
        if (name == null) {
            return a.classes.containsKey(owner) ? dotted(owner) : null;
        }
        if (!a.classes.containsKey(owner)) {
            // a class splits at its last dot too ("class gone: a.b.C")
            String whole = owner + "/" + name;
            return desc == null && a.classes.containsKey(whole) ? dotted(whole) : null;
        }
        GameDiff.Cls decl = declaring(a, owner, desc == null ? 'f' : 'm', name, desc);
        if (decl == null) {
            return null;
        }
        return dotted(decl.name) + "." + name + (desc == null ? "" : desc);
    }

    /**
     * One release of the game in Mojang names with the loader a mod runs on, as the mod sees it at run time. NeoForge:
     * its own classes and every library of net.neoforged its build depends on (the mod loader FML, the event bus), opened
     * by its access transformer. Fabric: Fabric Loader and Fabric API, with the interfaces Fabric API adds to game classes
     * and the game opened by its transitive access wideners. A mod's references to the loader are linked like its
     * references to the game, so a loader API change is porting work, not a surprise at load time.
     */
    static GameDiff.Snap snap(String version, String loader) throws Exception {
        Platform p = "neoforge".equals(loader) ? neoforge(version) : "fabric".equals(loader) ? fabric(version) : new Platform(null, List.of(), "", Map.of());
        String vj = GameDiff.versionJson(version);
        Path client = GameDiff.verified(GameDiff.download(vj, "client"), "client-" + version + ".jar");
        String[] m = GameDiff.download(vj, "client_mappings");
        VersionMap.Proguard pg = m == null ? null : VersionMap.Proguard.parse(Files.readString(GameDiff.verified(m, "client-" + version + ".txt"), StandardCharsets.UTF_8));
        List<byte[]> game = GameDiff.classes(client, null);
        if (!p.injected().isEmpty()) {
            game = injected(game, pg, p.injected());
        }
        GameDiff.Snap s = GameDiff.read(version, game, pg, p.classes());
        s.loaderVersion = p.version();
        loaderAccess(s, p.access());
        // The game's own libraries (JOML, DataFixerUpper, Brigadier, fastutil...) are linked against too: a mod's use of one
        // that changed or moved breaks at run time like a game member does (1.19.3 moved the game's math to JOML).
        GameDiff.addLibraries(s.classes, GameDiff.libraries(vj));
        System.out.printf("  read %s with %s: %,d classes (%,d of the loader)%n", version, loader == null ? "no loader" : loader + " " + p.version(),
            s.classes.size(), s.classes.values().stream().filter(c -> c.loader).count());
        return s;
    }

    /**
     * What a loader adds to one release: its version (NeoForge's; null for Fabric, whose loader is not tied to a release),
     * its classes in Mojang names, its access transformer, and interfaces it adds to game classes (Mojang names).
     */
    record Platform(String version, List<byte[]> classes, String access, Map<String, List<String>> injected) {}

    static final String NEOFORGED = "https://maven.neoforged.net/releases/";

    /**
     * NeoForge's build for a release (the newest, as GameDiff picks it) and the libraries it publishes as dependencies in
     * net.neoforged's groups (FML, the event bus, ...), read from its own pom. Before 1.20.5 NeoForge ran on SRG member
     * names, so its classes are read in Mojang names through its NeoForm mappings, as its mods are.
     */
    static Platform neoforge(String version) throws Exception {
        String lv = neoforgeVersion(version);
        if (lv == null) {
            return new Platform(null, List.of(), "", Map.of());
        }
        Path universal = VersionMap.fetch(GameDiff.NEOFORGE + lv + "/neoforge-" + lv + "-universal.jar", "neoforge-" + lv + "-universal.jar");
        byte[] bytes = Files.readAllBytes(universal);
        if (srgNames(version)) {
            bytes = new ModNames(Intermediary.neoform(version)).jar(bytes, new ModNames.Counts());
        }
        List<byte[]> classes = new ArrayList<>(classes(bytes, "net/neoforged/"));
        for (String[] d : dependencies(neoforgePom(lv))) {
            if (!d[0].startsWith("net.neoforged") || d[1].equals("neoform") || d[1].equals("neoforge")) {
                continue;
            }
            String path = d[0].replace('.', '/') + "/" + d[1] + "/" + d[2] + "/" + d[1] + "-" + d[2] + ".jar";
            try {
                classes.addAll(classes(Files.readAllBytes(VersionMap.fetch(NEOFORGED + path, d[0] + "-" + d[1] + "-" + d[2] + ".jar")), "net/neoforged/"));
            } catch (java.io.IOException notPublished) {
                System.out.println("  " + path + ": " + notPublished.getMessage()); // a build-time library with no plain jar
            }
        }
        String at = "";
        try (ZipFile zip = new ZipFile(universal.toFile())) {
            ZipEntry e = zip.getEntry("META-INF/accesstransformer.cfg");
            if (e != null) {
                at = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new Platform(lv, classes, srgNames(version) ? Intermediary.neoform(version).rename(at) : at, Map.of());
    }

    static String neoforgeVersion(String version) throws Exception {
        return GameDiff.neoforgeFor(version, VersionMap.fetchText(GameDiff.NEOFORGE + "maven-metadata.xml", "neoforge-maven-metadata.xml"));
    }

    /** NeoForge ran on SRG member names (m_N_, f_N_) before 1.20.5, its mods too. */
    static boolean srgNames(String version) {
        return compare(version, "1.20.5") < 0;
    }

    /**
     * The NeoForm build a NeoForge build was made from, as its own jar's manifest names it (the section
     * net/neoforged/neoforge/versions/neoform/); every build from 1.20.2 on writes it, while its pom does only sometimes.
     */
    static String neoformVersion(String lv) throws Exception {
        Path universal = VersionMap.fetch(GameDiff.NEOFORGE + lv + "/neoforge-" + lv + "-universal.jar", "neoforge-" + lv + "-universal.jar");
        return manifestVersion(VersionMap.zipEntry(universal, "META-INF/MANIFEST.MF"), "net/neoforged/neoforge/versions/neoform/");
    }

    /** Implementation-Version of one named section of a jar manifest, or null. */
    static String manifestVersion(String manifest, String section) {
        boolean inside = false;
        for (String line : manifest.split("\r?\n")) {
            if (line.startsWith("Name:")) {
                inside = line.substring(5).trim().equals(section);
            } else if (inside && line.startsWith("Implementation-Version:")) {
                return line.substring("Implementation-Version:".length()).trim();
            }
        }
        return null;
    }

    static String neoforgePom(String lv) throws Exception {
        return VersionMap.fetchText(GameDiff.NEOFORGE + lv + "/neoforge-" + lv + ".pom", "neoforge-" + lv + ".pom");
    }

    /** {group, artifact, version} of each plain jar dependency a pom lists (none with a type or classifier). */
    static List<String[]> dependencies(String pom) {
        List<String[]> out = new ArrayList<>();
        Matcher d = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (d.find()) {
            String body = d.group(1); // its own coordinates come before any exclusion it lists
            String g = tag(body, "groupId"), a = tag(body, "artifactId"), v = tag(body, "version");
            if (g != null && a != null && v != null && tag(body, "type") == null && tag(body, "classifier") == null) {
                out.add(new String[] {g, a, v});
            }
        }
        return out;
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    static final String FABRIC_MAVEN = "https://maven.fabricmc.net/net/fabricmc/";

    /**
     * Fabric Loader (its newest stable release: players run the newest loader on every release) and the newest Fabric API
     * built for the release, both from Fabric's own maven, in Mojang names. Fabric API's modules name game classes they
     * add an interface to ("loom:injected_interfaces"); a mod built with Loom calls those methods on the game class.
     */
    static Platform fabric(String version) throws Exception {
        List<byte[]> classes = new ArrayList<>();
        String loaderMeta = VersionMap.fetchText(FABRIC_MAVEN + "fabric-loader/maven-metadata.xml", "fabric-loader-maven-metadata.xml");
        String loaderVersion = tag(loaderMeta, "release");
        if (loaderVersion != null) {
            Path jar = VersionMap.fetch(FABRIC_MAVEN + "fabric-loader/" + loaderVersion + "/fabric-loader-" + loaderVersion + ".jar", "fabric-loader-" + loaderVersion + ".jar");
            classes.addAll(classes(Files.readAllBytes(jar), "net/fabricmc/"));
        }
        Path api = fabricApi(version);
        StringBuilder access = new StringBuilder();
        Map<String, List<String>> injected = new HashMap<>();
        if (api != null) {
            byte[] bytes = Files.readAllBytes(api);
            Intermediary names = compare(version, "26.1") < 0 ? new Intermediary(version) : null;
            // Fabric API's modules open game members to every mod with "transitive-" widener lines (BlockEntityRenderers.
            // register, MenuScreens.register), as NeoForge does with its access transformer.
            for (String text : wideners(bytes)) {
                StringBuilder lines = new StringBuilder();
                boolean intermediary = false;
                for (String line : text.split("\r?\n")) {
                    String body = line.replaceAll("#.*", "").trim();
                    if (body.startsWith("accessWidener") || body.startsWith("classTweaker")) {
                        intermediary = body.endsWith("intermediary");
                    } else if (body.startsWith("transitive-")) {
                        lines.append(body.substring("transitive-".length())).append('\n');
                    }
                }
                access.append(transformer(intermediary && names != null ? names.rename(lines.toString()) : lines.toString()));
            }
            for (String fmj : modJsons(bytes)) {
                Matcher block = Pattern.compile("\"loom:injected_interfaces\"\\s*:\\s*\\{([^}]*)\\}").matcher(fmj);
                while (block.find()) {
                    Matcher e = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(block.group(1));
                    while (e.find()) {
                        String target = names == null ? e.group(1) : names.rename(e.group(1));
                        Matcher i = Pattern.compile("\"([^\"]+)\"").matcher(e.group(2));
                        while (i.find()) {
                            injected.computeIfAbsent(target, k -> new ArrayList<>()).add(i.group(1).replaceAll("<.*", ""));
                        }
                    }
                }
            }
            if (names != null) {
                bytes = new ModNames(names).jar(bytes, new ModNames.Counts());
            }
            classes.addAll(classes(bytes, "net/fabricmc/"));
        }
        classes.removeIf(Exposure::isMixin);
        return new Platform(null, classes, access.toString(), injected);
    }

    /** Releases in order: 1.20.4 before 1.20.5 before 26.1. */
    static int compare(String a, String b) {
        String[] x = a.split("\\D+"), y = b.split("\\D+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    /** The newest Fabric API built for a release ("+1.21.1", or "+1.14" for the first ones), or null. */
    static Path fabricApi(String version) throws Exception {
        String metadata = VersionMap.fetchText(FABRIC_API + "maven-metadata.xml", "fabric-api-maven-metadata.xml");
        String minor = version.replaceFirst("^(\\d+\\.\\d+)\\.\\d+$", "$1");
        String chosen = null;
        for (String wanted : List.of(version, minor)) {
            Matcher m = Pattern.compile("<version>([^<]+\\+" + Pattern.quote(wanted) + ")</version>").matcher(metadata);
            while (m.find()) {
                chosen = m.group(1);
            }
            if (chosen != null) {
                break;
            }
        }
        return chosen == null ? null : VersionMap.fetch(FABRIC_API + chosen + "/fabric-api-" + chosen + ".jar", "fabric-api-" + chosen + ".jar");
    }

    /** A Fabric API class in a mixin package: Mixin applies it to the game, no mod links against it. */
    static boolean isMixin(byte[] c) {
        String name = new org.objectweb.asm.ClassReader(c).getClassName();
        return name.contains("/mixin/");
    }

    /** The classes of a jar and the jars nested in it (by fabric.mod.json or NeoForge's jarjar list), under a package. */
    static List<byte[]> classes(byte[] jar, String prefix) throws java.io.IOException {
        List<byte[]> out = new ArrayList<>();
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        for (var e : entries.entrySet()) {
            String n = e.getKey();
            if (n.endsWith(".class") && n.startsWith(prefix) && !n.endsWith("package-info.class")) {
                out.add(e.getValue());
            } else if (n.endsWith(".jar") && (n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/"))) {
                out.addAll(classes(e.getValue(), prefix));
            }
        }
        return out;
    }

    /** Every fabric.mod.json in a jar and the jars nested in it. */
    static List<String> modJsons(byte[] jar) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    out.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                } else if (e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")) {
                    out.addAll(modJsons(zip.readAllBytes()));
                }
            }
        }
        return out;
    }

    /**
     * The game's class files with the interfaces a loader adds to them, as Fabric API's mixins do at run time. Classes are
     * named in Mojang names; the game jar names them obfuscated before 26.1, so each is found through Mojang's mappings.
     */
    static List<byte[]> injected(List<byte[]> game, VersionMap.Proguard pg, Map<String, List<String>> add) {
        Map<String, List<String>> byJarName = new HashMap<>();
        if (pg == null) {
            byJarName.putAll(add);
        } else {
            pg.classes.forEach((obf, named) -> {
                if (add.containsKey(named)) {
                    byJarName.put(obf, add.get(named));
                }
            });
        }
        List<byte[]> out = new ArrayList<>(game.size());
        for (byte[] c : game) {
            org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(c);
            List<String> more = byJarName.get(r.getClassName());
            if (more == null) {
                out.add(c);
                continue;
            }
            org.objectweb.asm.ClassWriter w = new org.objectweb.asm.ClassWriter(0);
            r.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, w) {
                @Override
                public void visit(int v, int access, String name, String signature, String sup, String[] ifaces) {
                    // The interfaces are Fabric's own classes: Mojang's mappings do not name them, so they stay as written.
                    Set<String> all = new LinkedHashSet<>(Arrays.asList(ifaces == null ? new String[0] : ifaces));
                    all.addAll(more);
                    String generic = signature;
                    if (generic != null) {
                        for (String i : more) {
                            generic += "L" + i + ";";
                        }
                    }
                    super.visit(v, access, name, generic, sup, all.toArray(new String[0]));
                }
            }, 0);
            out.add(w.toByteArray());
        }
        return out;
    }

    /**
     * The loader opens game classes and members with its own access transformer (NeoForge makes BlockEntityType's
     * constructor public), and every mod sees the game that way at run time, so the snapshot is widened to match.
     */
    static void loaderAccess(GameDiff.Snap s, String text) {
        for (String line : text.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            int open = p[0].startsWith("public") ? 0x0001 : p[0].startsWith("protected") ? 0x0004 : 0;
            GameDiff.Cls c = p.length < 2 || open == 0 ? null : s.classes.get(p[1].replace('.', '/'));
            if (c == null) {
                continue;
            }
            if (p.length == 2) {
                c.flags = opened(c.flags, open);
            } else if (p[2].contains("(")) {
                GameDiff.Meth m = c.methods.get(p[2]);
                if (m != null) {
                    m.flags = opened(m.flags, open);
                }
            } else {
                GameDiff.Fld f = c.fields.get(p[2]);
                if (f != null) {
                    f.flags = opened(f.flags, open);
                }
            }
        }
    }

    static final String FABRIC_API = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/";


    /** Every access widener in a Fabric jar and the jars nested in it. */
    static List<String> wideners(byte[] jar) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".accesswidener") || e.getKey().endsWith(".classtweaker")) {
                out.add(new String(e.getValue(), StandardCharsets.UTF_8));
            } else if (e.getKey().startsWith("META-INF/jars/") && e.getKey().endsWith(".jar")) {
                out.addAll(wideners(e.getValue()));
            }
        }
        return out;
    }

    /**
     * Access widener lines as access transformer lines, which is what the port reads for what is opened and checks for
     * members that are gone: accessible -> public, extendable -> public-f (a class) or protected-f (a method),
     * mutable -> protected-f (only final is dropped; the port counts no grant for it).
     */
    static String transformer(String widener) {
        StringBuilder out = new StringBuilder("# from an access widener\n");
        for (String line : widener.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            if (p.length < 3 || p[0].equals("accessWidener") || p[0].equals("classTweaker")) {
                continue;
            }
            String modifier = switch (p[0].replaceFirst("^transitive-", "")) {
                case "accessible" -> "public";
                case "extendable" -> p[1].equals("class") ? "public-f" : "protected-f";
                case "mutable" -> "protected-f";
                default -> null;
            };
            if (modifier == null) {
                continue;
            }
            String owner = p[2].replace('/', '.');
            if (p[1].equals("class")) {
                out.append(modifier).append(' ').append(owner).append('\n');
            } else if (p[1].equals("method") && p.length >= 5) {
                out.append(modifier).append(' ').append(owner).append(' ').append(p[3]).append(p[4]).append('\n');
            } else if (p[1].equals("field") && p.length >= 4) {
                out.append(modifier).append(' ').append(owner).append(' ').append(p[3]).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Fabric's intermediary names of one release in Mojang's. Intermediary and Mojang's own mappings both name the
     * obfuscated game, so joined through it (VersionMap.Table) each intermediary class and member has its Mojang name;
     * method_N, field_N and comp_N (a record component's field and accessor) each name one member in the whole game.
     */
    static final class Intermediary {
        static final Pattern CLASS = Pattern.compile("net([/.])minecraft\\1class_\\d+(?:\\$class_\\d+)*");
        // Fabric's intermediary members, and NeoForm's SRG ids (m_N_, f_N_) NeoForge ran on before 1.20.5.
        static final Pattern MEMBER = Pattern.compile("\\b(?:(?:method|field|comp)_\\d+|[mf]_\\d+_)\\b");

        final Map<String, String> classes = new HashMap<>(); // slash form
        final Map<String, String> members = new HashMap<>();

        Intermediary(Map<String, String> classes, Map<String, String> members) {
            this.classes.putAll(classes);
            this.members.putAll(members);
        }

        Intermediary(String version) throws Exception {
            VersionMap.Table t = VersionMap.Table.load(version, "mojmap");
            classes.putAll(t.classes);
            for (Map<String, VersionMap.Member> kind : List.of(t.methods, t.fields)) {
                for (var e : kind.entrySet()) {
                    // Keyed owner.method_N, owner.field_N, or owner + name + intermediary descriptor for every other name.
                    String rest = e.getKey().substring(e.getKey().indexOf('.') + 1);
                    if (rest.startsWith("method_") || rest.startsWith("field_")) {
                        members.putIfAbsent(rest, e.getValue().name());
                    } else if (rest.startsWith("comp_") && rest.endsWith(e.getValue().interDesc())) {
                        members.putIfAbsent(rest.substring(0, rest.length() - e.getValue().interDesc().length()), e.getValue().name());
                    }
                }
            }
        }

        /**
         * NeoForge's SRG names of one release in Mojang's. NeoForge's pom names the NeoForm build it was made from; NeoForm's
         * joined mappings name each obfuscated class (in Mojang's name already) and member with an SRG id, unique in the
         * whole game, and Mojang's own mappings name the same obfuscated members, so the two join through them.
         */
        static Intermediary neoform(String version) throws Exception {
            Intermediary cached = NEOFORM.get(version);
            if (cached != null) {
                return cached;
            }
            String lv = neoforgeVersion(version);
            String nf = lv == null ? null : neoformVersion(lv);
            if (nf == null) {
                throw new java.io.IOException("no NeoForge build with NeoForm mappings for " + version);
            }
            Path zip = VersionMap.fetch(NEOFORGED + "net/neoforged/neoform/" + nf + "/neoform-" + nf + ".zip", "neoform-" + nf + ".zip");
            Matcher at = Pattern.compile("\"mappings\"\\s*:\\s*\"([^\"]+)\"").matcher(VersionMap.zipEntry(zip, "config.json"));
            String tsrg = VersionMap.zipEntry(zip, at.find() ? at.group(1) : "config/joined.tsrg");
            String vj = GameDiff.versionJson(version);
            VersionMap.Proguard pg = VersionMap.Proguard.parse(Files.readString(GameDiff.verified(GameDiff.download(vj, "client_mappings"), "client-" + version + ".txt"), StandardCharsets.UTF_8));
            String[] server = GameDiff.download(vj, "server_mappings");
            if (server != null) {
                pg.merge(VersionMap.Proguard.parse(Files.readString(GameDiff.verified(server, "server-" + version + ".txt"), StandardCharsets.UTF_8)));
            }
            Intermediary out = new Intermediary(Map.of(), srg(tsrg, pg));
            NEOFORM.put(version, out);
            return out;
        }

        static final Map<String, Intermediary> NEOFORM = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * SRG id -> Mojang name from NeoForm's tsrg2 ("tsrg2 obf srg id"; a class line "obf name", a method line
         * "\tobf (desc) m_N_ N", a field line "\tobf f_N_ N"; deeper lines are parameters) and Mojang's mappings.
         */
        static Map<String, String> srg(String tsrg, VersionMap.Proguard pg) {
            Map<String, String> members = new HashMap<>();
            String owner = null;
            for (String line : tsrg.split("\r?\n")) {
                if (line.startsWith("tsrg") || line.isBlank() || line.startsWith("\t\t")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                if (!line.startsWith("\t")) {
                    owner = p[0];
                    continue;
                }
                if (owner == null || p.length < 2) {
                    continue;
                }
                boolean method = p.length >= 3 && p[1].startsWith("(");
                String id = method ? p[2] : p[1];
                String named = method ? pg.methods.get(owner + "." + p[0] + p[1]) : pg.fields.get(owner + "." + p[0]);
                if (named != null && id.matches("[mf]_\\d+_")) {
                    members.putIfAbsent(id, named);
                }
            }
            return members;
        }

        /** Intermediary class and member names inside a text, in Mojang names (classes keep the text's separator). */
        String rename(String s) {
            if (!s.contains("class_") && !s.contains("method_") && !s.contains("field_") && !s.contains("comp_") && !s.contains("m_") && !s.contains("f_")) {
                return s;
            }
            Matcher c = CLASS.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (c.find()) {
                String slash = c.group().replace('.', '/');
                String named = classes.getOrDefault(slash, slash);
                c.appendReplacement(sb, Matcher.quoteReplacement(c.group(1).equals(".") ? named.replace('/', '.') : named));
            }
            String classed = c.appendTail(sb).toString();
            Matcher m = MEMBER.matcher(classed);
            sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(members.getOrDefault(m.group(), m.group())));
            }
            return m.appendTail(sb).toString();
        }
    }

    /** Access flags after an access transformer's public (1) or protected (4); it never narrows. */
    static int opened(int flags, int open) {
        if ((flags & 0x0001) != 0) {
            return flags;
        }
        return open == 0x0001 ? (flags & ~0x0006) | 0x0001 : (flags & ~0x0002) | 0x0004;
    }

    /** The member a reference names, where the release declares it (the owner or one of its supertypes). */
    static GameDiff.Cls declaring(GameDiff.Snap s, String owner, char kind, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            GameDiff.Cls c = s.classes.get(queue.poll());
            if (c == null || !seen.add(c.name)) {
                continue;
            }
            if (kind == 'f' ? c.fields.containsKey(name) : c.methods.containsKey(name + desc)) {
                return c;
            }
            if (c.sup != null) {
                queue.add(c.sup);
            }
            queue.addAll(c.ifaces);
        }
        return null;
    }

    /** from can be passed where to is asked, through the new release's hierarchy and then the JDK's. */
    static boolean assignable(GameDiff.Snap b, String from, String to) {
        if (from.equals(to) || to.equals("java.lang.Object") && !from.matches("[ZBCSIJFD]")) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String c = todo.pop();
            if (!seen.add(c)) {
                continue;
            }
            if (c.equals(to)) {
                return true;
            }
            GameDiff.Cls k = b.classes.get(c.replace('.', '/'));
            if (k != null) {
                if (k.sup != null) {
                    todo.push(k.sup.replace('/', '.'));
                }
                k.ifaces.forEach(i -> todo.push(i.replace('/', '.')));
            } else if (c.startsWith("java.")) {
                try {
                    return Class.forName(to, false, null).isAssignableFrom(Class.forName(c, false, null));
                } catch (ClassNotFoundException | LinkageError notJdk) {
                    // to is the game's: a JDK class never extends it
                }
            }
        }
        return false;
    }

    /**
     * What the game's own code says about a member a port cannot carry yet: "linked" (the map follows it to a new
     * signature), "unused" (the game never used it), "redesigned" (every place that used it was rewritten beyond lining
     * up), or "replaced" (places line up with what b uses instead: the miner's threshold or kind kept it out).
     */
    static String[] gameSays(GameDiff.Hop.Evidence e) {
        if (e == null) {
            return new String[] {"linked", "the map follows it; the new signature differs"};
        }
        if (e.sites() == 0) {
            return new String[] {"unused", "the game never uses it"};
        }
        if (e.templateVotes() >= 2 && e.templateVotes() * 10 >= e.templateSites() * 6) {
            return new String[] {"template", e.template() + " at " + e.templateVotes() + " of " + e.templateSites() + " rewritten uses (" + e.sites() + " uses)"};
        }
        if (e.templateVotes() > 0) {
            return new String[] {"split", e.template() + " at " + e.templateVotes() + " of " + e.templateSites() + " rewritten uses (" + e.sites() + " uses)"};
        }
        if (e.aligned() == 0) {
            return new String[] {"redesigned", e.sites() + " uses, " + e.kept() + " in methods b kept, none line up"};
        }
        return new String[] {"replaced", e.best() + " at " + e.votes() + " of " + e.aligned() + " aligned uses (" + e.sites() + " uses)"};
    }

    static String dotted(String internal) {
        return internal.replace('/', '.');
    }

    /** A GameDiff route map read for lookups: classes, members (by old owner, name, descriptor) and rewrites. */
    static final class Route {

        final Map<String, String> classes = new HashMap<>(); // internal -> internal
        final Map<String, String[]> methods = new HashMap<>(); // owner.name+desc -> {owner, name, desc}
        final Map<String, String[]> fields = new HashMap<>(); // owner.name -> {owner, name}
        final Set<String> rewrites = new HashSet<>(); // owner.name+desc
        final Map<String, String> classRewrites = new HashMap<>();

        static Route read(Path file) throws java.io.IOException {
            Route r = new Route();
            Map<String, Object> map = Json.read(file);
            for (Object o : Json.array(map.getOrDefault("classes", List.of()))) {
                var c = Json.object(o);
                r.classes.put(internal(Json.text(c, "from", "")), internal(Json.text(c, "to", "")));
            }
            for (String list : List.of("methods", "methodsMoved", "signatures")) {
                for (Object o : Json.array(map.getOrDefault(list, List.of()))) {
                    var m = Json.object(o);
                    String owner = internal(Json.text(m, "owner", ""));
                    String newOwner = m.get("newOwner") == null ? null : internal(Json.text(m, "newOwner", ""));
                    r.methods.put(
                        owner + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""),
                        new String[] { newOwner, Json.text(m, "to", ""), Json.text(m, "newDesc", "") }
                    );
                }
            }
            for (Object o : Json.array(map.getOrDefault("fields", List.of()))) {
                var f = Json.object(o);
                String owner = internal(Json.text(f, "owner", ""));
                String newOwner = f.get("newOwner") == null ? null : internal(Json.text(f, "newOwner", ""));
                r.fields.put(owner + "." + Json.text(f, "from", ""), new String[] { newOwner, Json.text(f, "to", "") });
            }
            for (Object o : Json.array(map.getOrDefault("rewrites", List.of()))) {
                var w = Json.object(o);
                r.rewrites.add(internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", ""));
            }
            for (Object o : Json.array(map.getOrDefault("chains", List.of()))) {
                var w = Json.object(o);
                r.rewrites.add(internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", ""));
            }
            for (Object o : Json.array(map.getOrDefault("classRewrites", List.of()))) {
                var w = Json.object(o);
                r.classRewrites.put(internal(Json.text(w, "from", "")), internal(Json.text(w, "to", "")));
            }
            return r;
        }

        static String internal(String dotted) {
            // Map names are dotted with $ for nested classes: a.b.C$D -> a/b/C$D.
            return dotted.replace('.', '/');
        }

        String cls(String internal) {
            return classes.getOrDefault(internal, internal);
        }

        String desc(String d) {
            Matcher m = Pattern.compile("L([^;]+);").matcher(d);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("L" + cls(m.group(1)) + ";"));
            }
            return m.appendTail(sb).toString();
        }

        /** {owner, name, desc} of a method in the new release, by the map or else unchanged (classes renamed). */
        String[] method(String owner, String name, String desc) {
            String[] e = methods.get(owner + "." + name + desc);
            if (e == null) {
                return new String[] { cls(owner), name, desc(desc) };
            }
            return new String[] { e[0] != null ? e[0] : cls(owner), e[1].isEmpty() ? name : e[1], e[2].isEmpty() ? desc(desc) : e[2] };
        }

        String[] field(String owner, String name) {
            String[] e = fields.get(owner + "." + name);
            if (e == null) {
                return new String[] { cls(owner), name };
            }
            return new String[] { e[0] != null ? e[0] : cls(owner), e[1].isEmpty() ? name : e[1] };
        }
    }
}
