import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * Exposure - what a mod uses of the game, and what of it a port would carry over, without building anything.
 *
 * Every class, method and field of the game (and of NeoForge, with --loader neoforge) that a mod's bytecode refers to,
 * and every game method it overrides, is found where the old release declares it, followed through the route map
 * (GameDiff --chain FROM,TO) to the new release, and looked up there:
 *   ok        the same name and descriptor are there (a supertype may declare it);
 *   renamed   the map moves it to a name that is there;
 *   rewritten the map's call-site or class rewrites (from the game's own code) give its replacement;
 *   signature the new release has the name with other parameters or another return type;
 *   removed   nothing of that name is left.
 * The last two are what a port cannot carry yet. Mods are jars or class folders in the old release's Mojang names
 * (NeoForge jars from 1.20.5 on, any jar from 26.1 on, or a Mojang-named build's classes).
 *
 *   java Exposure --from 1.21.1 --to 1.21.2 --map route.json [--loader neoforge] --out report.json MOD.jar|DIR ...
 *
 * report.json: per mod the counts and its broken members; across mods, every broken member with the number of mods
 * that use it, most first (the order to fix them in), and the share of mods with nothing broken.
 */
public final class Exposure {

    record Use(char kind, String owner, String name, String desc) {}

    record Verdict(String status, String key, String detail) {}

    public static void main(String[] args) throws Exception {
        String from = null, to = null, map = null, out = null, loader = null, rules = null;
        List<Path> mods = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                case "--map" -> map = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                case "--rules" -> rules = args[++i];
                default -> mods.add(Path.of(args[i]));
            }
        }
        if (from == null || to == null || map == null || out == null || mods.isEmpty()) {
            System.err.println("usage: Exposure --from V --to V --map route.json [--loader neoforge] --out report.json MOD.jar|DIR ...");
            System.exit(2);
        }
        GameDiff.Snap a = snap(from, loader), b = snap(to, loader);
        Route route = Route.read(Path.of(map));
        if (rules != null) {
            route.ruled.addAll(ruledMembers(Path.of(rules), from, to));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("from", from);
        report.put("to", to);
        report.put("loader", loader == null ? "" : loader);
        List<Object> perMod = new ArrayList<>();
        Map<String, Set<String>> brokenBy = new TreeMap<>();
        Map<String, String> brokenDetail = new HashMap<>();
        int clean = 0, read = 0;
        for (Path mod : mods) {
            Map<String, byte[]> classes;
            try {
                classes = classes(mod);
            } catch (IOException | RuntimeException unreadable) {
                perMod.add(Map.of("mod", mod.getFileName().toString(), "error", String.valueOf(unreadable.getMessage())));
                continue;
            }
            Set<Use> uses = uses(classes, a);
            uses.addAll(newAbstracts(classes, a, b, route));
            Map<String, Integer> counts = new TreeMap<>();
            List<Object> broken = new ArrayList<>();
            for (Use u : uses) {
                Verdict v = judge(u, a, b, route);
                if (v == null) {
                    continue; // not the game's (a library, the JDK, the mod itself)
                }
                if ((v.status().equals("signature") || v.status().equals("removed")) && route.ruled(v.key())) {
                    counts.merge("ruled", 1, Integer::sum);
                    continue;
                }
                counts.merge(v.status(), 1, Integer::sum);
                if (v.status().equals("signature") || v.status().equals("removed")) {
                    broken.add(Map.of("member", v.key(), "status", v.status(), "detail", v.detail()));
                    brokenBy.computeIfAbsent(v.key(), k -> new TreeSet<>()).add(mod.getFileName().toString());
                    brokenDetail.putIfAbsent(v.key(), v.status() + (v.detail().isEmpty() ? "" : ": " + v.detail()));
                }
            }
            read++;
            int game = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (broken.isEmpty() && game > 0) {
                clean++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", mod.getFileName().toString());
            row.put("gameUses", game);
            row.put("counts", counts);
            row.put("carried", game == 0 ? 1.0 : Math.round(1000.0 * (game - broken.size()) / game) / 1000.0);
            broken.sort(Comparator.comparing(o -> String.valueOf(((Map<?, ?>) o).get("member"))));
            row.put("broken", broken);
            perMod.add(row);
        }
        // Why the game's own code gave no replacement for each, so the miner is fixed for a kind of change, not a member.
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        Map<String, GameDiff.Hop.Evidence> evidence = hop.evidence();
        Set<String> portable = hop.portable();
        Map<String, int[]> bySource = new TreeMap<>();
        Map<String, Integer> byChange = new HashMap<>();
        List<Object> ranked = new ArrayList<>();
        brokenBy.entrySet()
            .stream()
            .sorted((x, y) -> y.getValue().size() != x.getValue().size() ? y.getValue().size() - x.getValue().size() : x.getKey().compareTo(y.getKey()))
            .forEach(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("member", e.getKey());
                row.put("mods", e.getValue().size());
                row.put("why", brokenDetail.get(e.getKey()));
                String[] game = gameSays(evidence.get(e.getKey()));
                row.put("game", game[0]);
                row.put("gameDetail", game[1]);
                row.put("portable", portable.contains(e.getKey()));
                String why = brokenDetail.get(e.getKey());
                if (why.startsWith("signature: ")) {
                    String kind = why.substring("signature: ".length()).replaceFirst("^overridden, ", "");
                    byChange.merge(kind, e.getValue().size(), Integer::sum);
                }
                int[] n = bySource.computeIfAbsent(game[0], k -> new int[2]);
                n[0]++;
                n[1] += e.getValue().size();
                row.put("examples", List.copyOf(e.getValue()).subList(0, Math.min(5, e.getValue().size())));
                ranked.add(row);
            });
        Map<String, Object> sources = new LinkedHashMap<>();
        bySource.forEach((k, n) -> sources.put(k, Map.of("members", n[0], "modUses", n[1])));
        report.put("gameEvidence", sources);
        Map<String, Object> changes = new LinkedHashMap<>();
        byChange.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> changes.put(e.getKey(), e.getValue()));
        report.put("signatureChanges", changes);
        report.put("modsRead", read);
        report.put("modsWithNothingBroken", clean);
        report.put("mods", perMod);
        report.put("brokenByMods", ranked);
        Files.createDirectories(Path.of(out).toAbsolutePath().getParent());
        Files.writeString(Path.of(out), Json.write(report), StandardCharsets.UTF_8);
        System.out.printf("%s -> %s: %d mods read, %d with nothing broken, %d distinct broken members%n", from, to, read, clean, ranked.size());
        for (int i = 0; i < Math.min(15, ranked.size()); i++) {
            Map<?, ?> r = (Map<?, ?>) ranked.get(i);
            System.out.printf("  %3d mods  %s  (%s; game: %s)%n", (Integer) r.get("mods"), r.get("member"), r.get("why"), r.get("game"));
        }
        System.out.println("what the game's own code says about them (members, mod uses): " + sources);
    }

    /** One release of the game in Mojang names, with NeoForge's own classes when asked. */
    static GameDiff.Snap snap(String version, String loader) throws Exception {
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

    /** Access flags after an access transformer's public (1) or protected (4); it never narrows. */
    static int opened(int flags, int open) {
        if ((flags & 0x0001) != 0) {
            return flags;
        }
        return open == 0x0001 ? (flags & ~0x0006) | 0x0001 : (flags & ~0x0002) | 0x0004;
    }

    /** A jar's (or a folder's) class files by internal name; nested jars (jar-in-jar libraries) are not the mod's. */
    static Map<String, byte[]> classes(Path mod) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (Files.isDirectory(mod)) {
            try (Stream<Path> walk = Files.walk(mod)) {
                for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    out.put(mod.relativize(p).toString().replace('\\', '/').replaceAll("\\.class$", ""), Files.readAllBytes(p));
                }
            }
            return out;
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(mod))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    out.put(n.replaceAll("\\.class$", ""), zip.readAllBytes());
                }
            }
        }
        return out;
    }

    static boolean gameName(String internal) {
        return internal != null && (internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/") || internal.startsWith("net/neoforged/"));
    }

    /** Everything of the game the mod's classes name: calls, field access, types, supertypes, and overridden methods. */
    static Set<Use> uses(Map<String, byte[]> classes, GameDiff.Snap a) {
        Set<Use> out = new LinkedHashSet<>();
        for (var e : classes.entrySet()) {
            GameDiff.RawCls rc;
            try {
                rc = GameDiff.parse(e.getValue());
            } catch (RuntimeException broken) {
                continue;
            }
            if (gameName(rc.sup)) {
                out.add(new Use('c', rc.sup, null, null));
            }
            for (String i : rc.ifaces) {
                if (gameName(i)) {
                    out.add(new Use('c', i, null, null));
                }
            }
            for (var f : rc.fields.values()) {
                for (String t : types(f[0])) {
                    out.add(new Use('c', t, null, null));
                }
            }
            List<String> supers = gameSupertypes(rc, a);
            // A mixin's constructor never runs; the porter gives its super call placeholders (Port.mixinConstructors).
            boolean mixin = new String(e.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Lorg/spongepowered/asm/mixin/Mixin;");
            for (GameDiff.RawMeth m : rc.methods.values()) {
                for (String t : types(m.desc)) {
                    out.add(new Use('c', t, null, null));
                }
                for (GameDiff.RawRef r : m.refs) {
                    if (r.kind() == 'c' && gameName(r.owner())) {
                        out.add(new Use('c', r.owner(), null, null));
                    } else if (mixin && m.name.equals("<init>") && r.kind() == 'm' && r.name().equals("<init>") && r.owner().equals(rc.sup)) {
                        continue;
                    } else if ((r.kind() == 'm' || r.kind() == 'f') && gameName(r.owner())) {
                        out.add(new Use(r.kind(), r.owner(), r.name(), r.desc()));
                    }
                }
                // An override of a game method: the mod relies on that method's name and descriptor staying.
                if ((m.flags & 0x000A) == 0 && !m.name.startsWith("<")) {
                    for (String s : supers) {
                        GameDiff.Meth over = a.method(s, m.name, m.desc);
                        if (over != null && (over.flags & 0x001A) == 0) {
                            out.add(new Use('o', s, m.name, m.desc));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Abstract methods the new release adds to a game class a concrete mod class extends or implements (1.21.2:
     * Recipe.placementInfo(), recipeBookCategory()): the mod must now write them, so each is a use that breaks
     * (kind 'a', judged as removed) unless the mod class or a game supertype already has a method of that name.
     */
    static Set<Use> newAbstracts(Map<String, byte[]> classes, GameDiff.Snap a, GameDiff.Snap b, Route route) {
        Set<Use> out = new LinkedHashSet<>();
        Map<String, GameDiff.RawCls> mod = new HashMap<>();
        for (var e : classes.entrySet()) {
            try {
                GameDiff.RawCls rc = GameDiff.parse(e.getValue());
                mod.put(rc.name, rc);
            } catch (RuntimeException unreadable) {
                // skipped like in uses()
            }
        }
        for (GameDiff.RawCls rc : mod.values()) {
            if ((rc.flags & 0x0600) != 0) {
                continue; // an interface or an abstract class need not implement anything
            }
            Set<String> names = new HashSet<>();
            for (GameDiff.RawCls c = rc; c != null; c = mod.get(c.sup)) {
                for (GameDiff.RawMeth m : c.methods.values()) {
                    names.add(m.name);
                }
            }
            // The porter renames an override with the method it overrides: the mod has the new name too.
            List<String> supers = gameSupertypes(rc, a);
            for (GameDiff.RawCls c = rc; c != null; c = mod.get(c.sup)) {
                for (GameDiff.RawMeth m : c.methods.values()) {
                    for (String s : supers) {
                        GameDiff.Cls decl = declaring(a, s, 'm', m.name, m.desc);
                        if (decl != null) {
                            names.add(route.method(decl.name, m.name, m.desc)[1]);
                        }
                    }
                }
            }
            for (String s : supers) {
                GameDiff.Cls target = b.classes.get(route.cls(s));
                GameDiff.Cls old = a.classes.get(s);
                if (target == null || old == null) {
                    continue;
                }
                for (GameDiff.Meth m : target.methods.values()) {
                    if ((m.flags & 0x0400) == 0 || names.contains(m.name) || implementedInGame(target, m.name, b)) {
                        continue;
                    }
                    boolean wasAbstract = false;
                    for (GameDiff.Meth o : old.methods.values()) {
                        wasAbstract |= o.name.equals(m.name) && (o.flags & 0x0400) != 0;
                    }
                    if (!wasAbstract) {
                        out.add(new Use('a', target.name, m.name, m.desc));
                    }
                }
            }
        }
        return out;
    }

    /** Whether a non-abstract method of that name exists on the class or one of its supertypes in the release. */
    static boolean implementedInGame(GameDiff.Cls start, String name, GameDiff.Snap s) {
        Deque<GameDiff.Cls> queue = new ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            GameDiff.Cls c = queue.poll();
            if (c == null || !seen.add(c.name)) {
                continue;
            }
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(name) && (m.flags & 0x0400) == 0 && c != start) {
                    return true;
                }
            }
            List<String> parents = new ArrayList<>(c.ifaces);
            if (c.sup != null) {
                parents.add(c.sup);
            }
            for (String p : parents) {
                GameDiff.Cls parent = s.classes.get(p);
                if (parent != null) {
                    queue.add(parent); // a JDK or library supertype is not the game's
                }
            }
        }
        return false;
    }

    /** The game classes a mod class extends or implements, nearest first, through the game's own hierarchy. */
    static List<String> gameSupertypes(GameDiff.RawCls rc, GameDiff.Snap a) {
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        if (gameName(rc.sup)) {
            queue.add(rc.sup);
        }
        for (String i : rc.ifaces) {
            if (gameName(i)) {
                queue.add(i);
            }
        }
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!seen.add(n)) {
                continue;
            }
            out.add(n);
            GameDiff.Cls c = a.classes.get(n);
            if (c == null) {
                continue;
            }
            if (c.sup != null) {
                queue.add(c.sup);
            }
            queue.addAll(c.ifaces);
        }
        return out;
    }

    static List<String> types(String desc) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("L([^;<]+)[;<]").matcher(desc == null ? "" : desc);
        while (m.find()) {
            if (gameName(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** The member a reference names, where the old release declares it (the owner or one of its supertypes). */
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

    static Verdict judge(Use u, GameDiff.Snap a, GameDiff.Snap b, Route route) {
        if (u.kind() == 'c') {
            if (!a.classes.containsKey(u.owner())) {
                return null;
            }
            String target = route.cls(u.owner());
            String key = dotted(u.owner());
            if (b.classes.containsKey(target)) {
                return new Verdict(target.equals(u.owner()) ? "ok" : "renamed", key, "");
            }
            if (route.classRewrites.containsKey(u.owner())) {
                return new Verdict("rewritten", key, "-> " + dotted(route.classRewrites.get(u.owner())));
            }
            return new Verdict("removed", key, "class");
        }
        if (u.kind() == 'a') {
            // A new abstract method the mod has to write: what the port must add, keyed by its new owner.
            return new Verdict("removed", dotted(u.owner()) + "." + u.name() + u.desc(), "new abstract method to implement");
        }
        GameDiff.Cls decl = declaring(a, u.owner(), u.kind() == 'f' ? 'f' : 'm', u.name(), u.desc());
        if (decl == null) {
            return null; // a library's or the JDK's member reached through a game class, or not the game's at all
        }
        String owner = decl.name;
        String key = dotted(owner) + "." + u.name() + (u.kind() == 'f' ? "" : u.desc());
        if (u.kind() == 'f') {
            String[] moved = route.field(owner, u.name());
            String newOwner = moved[0], newName = moved[1];
            if (declaring(b, newOwner, 'f', newName, null) != null) {
                return new Verdict(newOwner.equals(route.cls(owner)) && newName.equals(u.name()) ? "ok" : "renamed", key, "");
            }
            if (!owner.equals(u.owner()) && declaring(b, route.cls(u.owner()), 'f', u.name(), null) != null) {
                return new Verdict("ok", key, "");
            }
            if (route.rewrites.contains(owner + "." + u.name())) {
                return new Verdict("rewritten", key, "chain");
            }
            return new Verdict("removed", key, "field");
        }
        String[] moved = route.method(owner, u.name(), u.desc());
        String newOwner = moved[0], newName = moved[1], newDesc = moved[2];
        // The class the mod's code names may declare it itself now (1.21.2 took sendSystemMessage from Entity, and
        // Player keeps its own): a call written on that class still compiles.
        if (!owner.equals(u.owner()) && moved[1].equals(u.name()) && declaring(b, route.cls(u.owner()), 'm', u.name(), route.desc(u.desc())) != null) {
            return new Verdict("ok", key, "");
        }
        if (declaring(b, newOwner, 'm', newName, newDesc) != null) {
            boolean same = newName.equals(u.name()) && newOwner.equals(route.cls(owner));
            return new Verdict(same ? "ok" : "renamed", key, same ? "" : "-> " + dotted(newOwner) + "." + newName);
        }
        if (route.rewrites.contains(owner + "." + u.name() + u.desc())) {
            return new Verdict("rewritten", key, "");
        }
        GameDiff.Cls target = b.classes.get(newOwner);
        boolean sameName = false;
        for (GameDiff.Cls c = target; c != null && !sameName; c = c.sup == null ? null : b.classes.get(c.sup)) {
            for (String m : c.methods.keySet()) {
                sameName |= m.startsWith(newName + "(");
            }
        }
        if (u.kind() == 'o' && !sameName) {
            // The porter drops the @Override and keeps the method (VersionMap.Fix 2b''): it compiles, and the game no longer
            // calls it. Counted apart from what breaks, so a hook that went silent stays visible.
            return new Verdict("unhooked", key, "overridden");
        }
        if (sameName && u.kind() == 'm' && target != null && fewerArguments(target, newName, route.desc(u.desc()))) {
            // The porter drops what javac says the one candidate no longer takes (VersionMap.Fix 2c*).
            return new Verdict("rewritten", key, "arguments dropped");
        }
        if (u.kind() == 'm' && widened(b, target, newName, route.desc(u.desc()))) {
            // Each parameter is now a supertype of what was passed (1.21.2: NonNullList -> List): the call compiles as written.
            return new Verdict("ok", key, "");
        }
        String change = sameName ? change(target, newName, route.desc(u.desc())) : "";
        if (u.kind() == 'm' && (change.equals("swap ResourceLocation->ResourceKey") || change.equals("swap ResourceKey->ResourceLocation")
            || change.equals("added HolderGetter"))) {
            // The porter converts ids and keys, and passes a built-in registry, where javac says so (VersionMap.Fix 2c**, 2c***).
            return new Verdict("rewritten", key, change);
        }
        String detail = u.kind() == 'o' ? "overridden" : "";
        return new Verdict(sameName ? "signature" : "removed", key, change.isEmpty() ? detail : (detail.isEmpty() ? change : detail + ", " + change));
    }

    /**
     * How a method's parameters changed, in simple class names, against the same-name method of the new release closest
     * to the old one: "fewer", "added A,B", "swap A->B;C->D" or "return". The kinds, counted over every mod, are the
     * porter's to-do list by kind of change rather than by member.
     */
    static String change(GameDiff.Cls target, String name, String oldDesc) {
        List<String> before = simple(VersionMap.Fix.paramTypes(oldDesc));
        String best = "";
        int bestCost = Integer.MAX_VALUE;
        if (target != null) {
            for (String m : target.methods.keySet()) {
                if (!m.startsWith(name + "(")) {
                    continue;
                }
                List<String> after = simple(VersionMap.Fix.paramTypes(m));
                String kind;
                int cost;
                if (after.equals(before)) {
                    kind = "return";
                    cost = 0;
                } else if (after.size() < before.size()) {
                    kind = "fewer";
                    cost = before.size() - after.size();
                } else if (after.size() == before.size()) {
                    List<String> swaps = new ArrayList<>();
                    for (int i = 0; i < before.size(); i++) {
                        if (!before.get(i).equals(after.get(i))) {
                            swaps.add(before.get(i) + "->" + after.get(i));
                        }
                    }
                    kind = "swap " + String.join(";", swaps);
                    cost = swaps.size();
                } else {
                    List<String> added = new ArrayList<>(after);
                    for (String p : before) {
                        added.remove(p);
                    }
                    kind = "added " + String.join(",", added);
                    cost = added.size() + (after.size() - before.size() == added.size() ? 0 : 10);
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = kind;
                }
            }
        }
        return best;
    }

    /** The class has a same-name method whose every parameter accepts what the old one took (the same type or a supertype). */
    static boolean widened(GameDiff.Snap b, GameDiff.Cls target, String name, String oldDesc) {
        if (target == null) {
            return false;
        }
        List<String> before = VersionMap.Fix.paramTypes(oldDesc);
        for (String m : target.methods.keySet()) {
            if (!m.startsWith(name + "(")) {
                continue;
            }
            List<String> after = VersionMap.Fix.paramTypes(m);
            boolean all = after.size() == before.size();
            for (int i = 0; all && i < after.size(); i++) {
                all = assignable(b, before.get(i), after.get(i));
            }
            if (all) {
                return true;
            }
        }
        return false;
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

    static List<String> simple(List<String> types) {
        List<String> out = new ArrayList<>();
        for (String t : types) {
            out.add(t.substring(t.lastIndexOf('.') + 1).replace('$', '.'));
        }
        return out;
    }

    /** The class has one method of that name, and its parameters are the old ones with trailing ones, or one choice, left out. */
    static boolean fewerArguments(GameDiff.Cls c, String name, String oldDesc) {
        String only = null;
        for (String m : c.methods.keySet()) {
            if (m.startsWith(name + "(")) {
                if (only != null) {
                    return false;
                }
                only = m;
            }
        }
        if (only == null) {
            return false;
        }
        List<String> before = VersionMap.Fix.paramTypes(oldDesc), after = VersionMap.Fix.paramTypes(only);
        boolean trailing = before.size() > after.size() && before.subList(0, after.size()).equals(after);
        return trailing || VersionMap.Fix.uniqueEmbedding(before, after) != null;
    }

    /**
     * The game members the port rules of knowledge/fixes.json say they carry ("members"), for the rules whose update lies
     * on this route in its direction: a member a rule rewrites is carried over though the map alone cannot.
     */
    static Set<String> ruledMembers(Path rules, String from, String to) throws IOException {
        Set<String> out = new HashSet<>();
        boolean forward = compareVersions(from, to) < 0;
        for (Object o : Json.array(Json.read(rules).get("rules"))) {
            var rule = Json.object(o);
            if (!(rule.get("members") instanceof List<?> members)) {
                continue;
            }
            String[] jump = Json.text(rule, "jump", "").replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            if (jump.length != 2) {
                continue;
            }
            boolean ruleForward = compareVersions(jump[0], jump[1]) < 0;
            String lo = forward ? from : to, hi = forward ? to : from;
            String rlo = ruleForward ? jump[0] : jump[1], rhi = ruleForward ? jump[1] : jump[0];
            // The porter runs a rule on a route when the rule's range covers one of the route's updates (Route.stepsCovered):
            // the two ranges overlap.
            if (ruleForward != forward || compareVersions(rlo, hi) >= 0 || compareVersions(rhi, lo) <= 0) {
                continue;
            }
            for (Object m : members) {
                out.add(String.valueOf(m));
            }
        }
        return out;
    }

    /** Releases compared number by number (1.21.10 after 1.21.9, 26.1 after 1.21.11). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\D+"), y = b.split("\\D+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
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
        /** Members a port rule of this route covers (its "members"), as written: a class, a member, or one overload. */
        final Set<String> ruled = new HashSet<>();

        boolean ruled(String key) {
            for (String r : ruled) {
                if (key.equals(r) || key.startsWith(r + ".") || key.startsWith(r + "$") || key.startsWith(r + "(") || (key.startsWith(r) && key.length() > r.length() && key.charAt(r.length()) == '(')) {
                    return true;
                }
            }
            return false;
        }

        static Route read(Path file) throws IOException {
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
