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
            said.put(member, row);
        }
        Files.writeString(Path.of(out), Json.write(said), StandardCharsets.UTF_8);
        System.out.printf("%s -> %s: what the game's own code did, for %d references%n", from, to, said.size());
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
     * One release of the game in Mojang names, with NeoForge's own classes when asked, or opened as Fabric API opens it
     * for every mod (its transitive access wideners) with --loader fabric.
     */
    static GameDiff.Snap snap(String version, String loader) throws Exception {
        if ("fabric".equals(loader)) {
            GameDiff.Snap s = GameDiff.load(version, null, null);
            fabricAccess(s, version);
            return s;
        }
        if (!"neoforge".equals(loader)) {
            return GameDiff.load(version, null, null);
        }
        String metadata = VersionMap.fetchText(GameDiff.NEOFORGE + "maven-metadata.xml", "neoforge-maven-metadata.xml");
        String lv = GameDiff.neoforgeFor(version, metadata);
        Path jar = lv == null ? null : VersionMap.fetch(GameDiff.NEOFORGE + lv + "/neoforge-" + lv + "-universal.jar", "neoforge-" + lv + "-universal.jar");
        GameDiff.Snap s = GameDiff.load(version, jar, lv);
        if (jar != null) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry at = zip.getEntry("META-INF/accesstransformer.cfg");
                if (at != null) {
                    loaderAccess(s, new String(zip.getInputStream(at).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return s;
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

    /**
     * Fabric API's modules open game members to every mod with "transitive-" access widener lines (BlockEntityRenderers.
     * register, MenuScreens.register), as NeoForge does with its access transformer. The newest Fabric API built for the
     * release ("+1.21.1", or "+1.14" for the first ones) is read from Fabric's own maven; a release with none is left as is.
     */
    static void fabricAccess(GameDiff.Snap s, String version) throws Exception {
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
        if (chosen == null) {
            return;
        }
        Path jar = VersionMap.fetch(FABRIC_API + chosen + "/fabric-api-" + chosen + ".jar", "fabric-api-" + chosen + ".jar");
        StringBuilder lines = new StringBuilder();
        boolean intermediary = false;
        for (String text : wideners(Files.readAllBytes(jar))) {
            for (String line : text.split("\r?\n")) {
                String body = line.replaceAll("#.*", "").trim();
                if (body.startsWith("accessWidener") || body.startsWith("classTweaker")) {
                    intermediary |= body.endsWith("intermediary");
                } else if (body.startsWith("transitive-")) {
                    lines.append(body.substring("transitive-".length())).append('\n');
                }
            }
        }
        String opened = intermediary ? new Intermediary(version).rename(lines.toString()) : lines.toString();
        loaderAccess(s, transformer(opened));
    }

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
        static final Pattern MEMBER = Pattern.compile("\\b(?:method|field|comp)_\\d+\\b");

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

        /** Intermediary class and member names inside a text, in Mojang names (classes keep the text's separator). */
        String rename(String s) {
            if (!s.contains("class_") && !s.contains("method_") && !s.contains("field_") && !s.contains("comp_")) {
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
