import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * JarPort - move a compiled mod (.jar) to another game release without its source and without a build.
 *
 * Every reference the mod's classes make to the game (calls, field access, method references, overrides, and what its
 * mixins name: @Shadow members, injection targets, @At targets, accessors) is looked up where the old release declares
 * it, followed through GameDiff's route map, and linked against the new release exactly as the JVM and Mixin will link
 * it: same owner chain, name and descriptor. A reference that links is rewritten to the new name; one that does not is
 * reported, never guessed. Class renames are applied everywhere. The mod's metadata is moved to the new release, nested
 * mods (jar-in-jar) are ported the same way, and signatures that no longer match are removed.
 *
 *   java JarPort --from 1.21.1 --to 1.21.2 --map route.json [--loader neoforge] [--fixes knowledge/fixes.json] --out OUT.jar|DIR [--report R.json] IN.jar ...
 *
 * --fixes applies the port rules' "inClass" replacements (FixExpr) to members nothing in the game's own code replaces.
 *
 * The report lists per jar what was rewritten and every reference that does not link in the new release ("unlinked");
 * a jar with no unlinked reference is expected to load. Exit 0 when every jar was written, whatever it reports.
 */
public final class JarPort {

    final GameDiff.Snap a, b;
    String loader; // "neoforge" or "fabric": the loader ported for, which a rule may name
    final Exposure.Route route;
    final String to, loaderTo;
    final Map<String, String> classMap = new HashMap<>();
    final Map<String, String> inverse = new HashMap<>();
    final Map<String, List<Map<String, Object>>> removals = new HashMap<>(); // owner.name+desc -> rewrite to nothing

    /** One jar's classes (old names), what was changed in it, and what does not link. */
    final class Mod {
        final Map<String, GameDiff.RawCls> classes = new HashMap<>();
        final Map<String, Integer> maxLocals = new HashMap<>(); // class.method+desc
        final Map<String, String[]> renamedDecl = new HashMap<>(); // class.name+desc (or class.field) -> {name, desc}
        final Map<String, List<String>> mixinTargets = new HashMap<>(); // mixin class -> target classes (old names)
        final Map<String, String[]> accessors = new HashMap<>(); // class.method+desc -> {Accessor|Invoker, value or null}
        final Map<String, String> accessorTargets = new HashMap<>(); // class.method+desc -> the target's new name to write
        final Set<String> widened = new HashSet<>(); // "owner.name" or "owner.name+desc" the mod's access transformer opens
        final Set<String> unlinked = new TreeSet<>();
        final Set<String> silent = new TreeSet<>(); // overrides the new release no longer calls: the mod loads, a hook is quiet
        final Map<String, Integer> changed = new TreeMap<>();
        String self; // the class being rewritten, and what a method added to it must respect
        boolean selfInterface;
        int selfVersion;
        final Map<String, Added> added = new LinkedHashMap<>(); // rule replacement + operands -> method added to self
        final Map<String, Boolean> subscribers = new HashMap<>(); // @EventBusSubscriber class -> whether it names its bus
        final Map<String, List<String>> listeners = new HashMap<>(); // class -> event types of its @SubscribeEvent methods (old names)
        final Set<String> toModBus = new HashSet<>(); // subscriber classes whose annotation gets bus = MOD
        final Map<String, List<Object[]>> subscriberValues = new HashMap<>(); // its other annotation values: {name, value} or {name, [desc, enum]...}
        final Map<String, List<Listener>> listenerMethods = new HashMap<>();
        final Map<String, List<Listener>> split = new HashMap<>(); // mixed subscriber -> its mod-bus listeners, moved to a companion
        final Map<String, byte[]> bytes = new HashMap<>(); // class -> its bytes as read, for what a method body reads
        final Map<String, Injector> injectors = new HashMap<>(); // mixin class.handler+desc -> its @Inject, @Redirect...
        final Map<String, String> retargets = new HashMap<>(); // class.handler+desc + " " + annotation string -> what is written instead
        final Map<String, Adapter> adapters = new HashMap<>(); // class.handler+desc -> the handler re-declared for its target's new parameters
        final Map<String, List<Bridge>> bridges = new HashMap<>(); // class -> methods added with a game method's new signature
        final Set<String> grants = new TreeSet<>(); // lines the port adds to the mod's access transformer (new names)
        boolean canWiden; // the loader reads this jar's access transformer
        final Set<String> kept = new HashSet<>(); // name+desc of moved methods also kept as they were, for calls that cannot follow
        final Set<String> unmoved = new HashSet<>(); // class.name+desc of bodies a call in them cannot be given its arguments
        final Map<String, Object[]> twins = new LinkedHashMap<>(); // class.name+desc -> {parameter, new type, steps} (Sites.twin)
        final Map<String, Integer> requires = new HashMap<>(); // mixin class -> its config's injectors.defaultRequire

        void change(String kind) {
            changed.merge(kind, 1, Integer::sum);
        }
    }

    /** a and b are the old and the new release (with their loader); map is GameDiff's route map between them. */
    JarPort(GameDiff.Snap a, GameDiff.Snap b, Path map) throws IOException {
        this.a = a;
        this.b = b;
        loader = b.loaderVersion != null ? "neoforge" : "fabric";
        to = b.version;
        loaderTo = b.loaderVersion;
        route = Exposure.Route.read(map);
        // A library the new release no longer lists may still be there at run time (the loader brings ASM, for one): only
        // what changed inside a library both releases list is checked.
        for (GameDiff.Cls c : a.classes.values()) {
            if (c.library && !b.classes.containsKey(c.name)) {
                b.classes.put(c.name, c);
            }
        }
        classMap.putAll(route.classes);
        for (var e : route.classRewrites.entrySet()) {
            classMap.putIfAbsent(e.getKey(), e.getValue());
        }
        classMap.entrySet().removeIf(e -> e.getKey().equals(e.getValue()) || !b.classes.containsKey(e.getValue()));
        for (var e : classMap.entrySet()) {
            inverse.putIfAbsent(e.getValue(), e.getKey());
        }
        Map<String, Object> raw = Json.read(map);
        for (Object o : Json.array(raw.getOrDefault("rewrites", List.of()))) {
            var w = Json.object(o);
            String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
            if (Json.text(w, "to", "").isEmpty() && Json.text(w, "toOwner", "").isEmpty()) {
                removals.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            } else if (majority(w)) {
                rewrites.computeIfAbsent(key, k -> new ArrayList<>()).add(new Call(
                    Exposure.Route.internal(Json.text(w, "toOwner", "")), Json.text(w, "to", ""), Json.text(w, "newDesc", ""),
                    Boolean.TRUE.equals(w.get("newStatic"))
                ));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("argDefaults", List.of()))) {
            var w = Json.object(o);
            if (majority(w)) {
                String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
                defaults.put(key, new Default(
                    new Call(Exposure.Route.internal(Json.text(w, "toOwner", "")), Json.text(w, "to", ""), Json.text(w, "newDesc", ""), false),
                    ((Number) w.get("index")).intValue(), Json.text(w, "value", "")
                ));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("chains", List.of()))) {
            var w = Json.object(o);
            if (majority(w)) {
                String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
                chains.put(key, new Chain(Exposure.Route.internal(Json.text(w, "newOwner", "")), Json.text(w, "steps", "")));
            }
        }
        // What the game's own code computes in place of a member (GameDiff's Learner), applied as a rule's inClass is: on
        // any update of the port whose old member the release ported from has by that name (a mod names it so), and
        // compiled against the release ported to, so an expression a later update broke is refused, not written.
        for (Object o : Json.array(raw.getOrDefault("retyped", List.of()))) {
            var w = Json.object(o);
            String owner = Exposure.Route.internal(Json.text(w, "owner", ""));
            if (majority(w) && covers(Json.text(w, "step", ""), "")) {
                retypedCalls.computeIfAbsent(owner, k -> new ArrayList<>()).add(new RetypedCall(Json.text(w, "from", ""), Json.text(w, "desc", ""),
                    ((Number) w.get("at")).intValue(), Json.text(w, "as", ""), Json.text(w, "expr", "")));
            }
        }
        for (Object o : Json.array(raw.getOrDefault("learned", List.of()))) {
            var w = Json.object(o);
            String owner = Exposure.Route.internal(Json.text(w, "owner", "")), from = Json.text(w, "from", ""), desc = Json.text(w, "desc", "");
            String member = from.endsWith("=") ? from.substring(0, from.length() - 1) : from; // "name=": a write
            boolean named = desc.isEmpty() ? a.field(owner, member) != null : a.method(owner, from, desc) != null;
            if (w.get("then") != null) {
                if (majority(w) && named && covers(Json.text(w, "step", ""), "")) {
                    chain(w, owner, from, desc);
                }
                continue;
            }
            if (majority(w) && named && covers(Json.text(w, "step", ""), "") && Json.text(w, "expr", "").isEmpty()) {
                // A call the game removed with nothing in its place: dropped as a removal is, its operands still evaluated.
                removals.computeIfAbsent(owner + "." + from + desc, k -> new ArrayList<>()).add(w);
            } else if (majority(w) && named && covers(Json.text(w, "step", ""), "")) {
                fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game " + Json.text(w, "step", ""),
                    Pattern.compile(Pattern.quote(from)), desc.isEmpty() ? null : desc.substring(0, desc.indexOf(')') + 1), Json.text(w, "expr", "")));
            }
        }
    }

    final List<PortChains.Spec> pairs = new ArrayList<>();

    /** A member of a library class that both releases declare with the same type and access. */
    boolean unchangedLibrary(char kind, String owner, String name, String desc) {
        GameDiff.Cls old = a.classes.get(owner), now = b.classes.get(owner);
        if (old == null || now == null || !old.library) {
            return false;
        }
        if (kind == 'f' || kind == 'r') {
            GameDiff.Fld x = old.fields.get(name), y = now.fields.get(name);
            return x != null && y != null && x.desc.equals(y.desc) && x.flags == y.flags;
        }
        GameDiff.Meth x = old.methods.get(name + desc), y = now.methods.get(name + desc);
        return x != null && y != null && x.flags == y.flags;
    }

    /** A library's abstract method the old release's copy of that library had too: a mod that skips it skipped it before the port. */
    boolean alreadyAbstract(GameDiff.Cls k, GameDiff.Meth m) {
        GameDiff.Cls old = k.library ? a.classes.get(k.name) : null;
        GameDiff.Meth before = old == null ? null : old.methods.get(m.name + m.desc);
        return before != null && (before.flags & Opcodes.ACC_ABSTRACT) != 0;
    }

    /**
     * Two old members replaced together (GameDiff's chains): a stand-in static method of the first one's class, taking both
     * members' operands, is added to the old release with the learned expression as its replacement; PortChains rewrites
     * the mod's pair into a call of it before the class is read.
     */
    void chain(Map<String, Object> w, String owner, String from, String desc) {
        var then = Json.object(w.get("then"));
        String yOwner = Exposure.Route.internal(Json.text(then, "owner", "")), yName = Json.text(then, "from", ""), yDesc = Json.text(then, "desc", "");
        GameDiff.Cls xc = a.classes.get(owner);
        boolean field = desc.isEmpty();
        GameDiff.Fld f = field ? a.field(owner, from) : null;
        GameDiff.Meth m = field ? null : a.method(owner, from, desc);
        GameDiff.Meth y = a.method(yOwner, yName, yDesc);
        int at = then.get("at") instanceof Number n ? n.intValue() : 0;
        if (xc == null || y == null) {
            return;
        }
        boolean xStatic = field ? (f.flags & Opcodes.ACC_STATIC) != 0 : m.isStatic();
        boolean first = "first".equals(Json.text(w, "result", ""));
        StringBuilder params = new StringBuilder("(");
        if (at < 0) {
            // A sibling: Y on X's receiver, with X's value (kept in a local) passed after Y's receiver.
            if (field || y.isStatic() || xStatic) {
                return;
            }
            params.append('L').append(yOwner).append(';').append(desc.substring(desc.indexOf(')') + 1)).append(yDesc, 1, yDesc.indexOf(')')).append(')');
            String fake = from + "+" + yName + "+r", fakeDesc = params + yDesc.substring(yDesc.indexOf(')') + 1);
            xc.methods.put(fake + fakeDesc, new GameDiff.Meth(xc, fake, fakeDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game chain " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(fake)),
                params.toString(), Json.text(w, "expr", "")));
            pairs.add(new PortChains.Spec('m', false, owner, from, desc, yOwner, yName, yDesc, false, -1, false, fake, fakeDesc,
                (xc.flags & Opcodes.ACC_INTERFACE) != 0));
            return;
        }
        if (!xStatic) {
            params.append('L').append(owner).append(';');
        }
        if (!field) {
            params.append(desc, 1, desc.indexOf(')'));
        }
        List<Type> yOps = new ArrayList<>();
        if (!y.isStatic()) {
            yOps.add(Type.getObjectType(yOwner));
        }
        yOps.addAll(List.of(Type.getArgumentTypes(yDesc)));
        if (at >= yOps.size()) {
            return;
        }
        for (int i = 0; i < yOps.size(); i++) {
            if (i != at) {
                params.append(yOps.get(i).getDescriptor());
            }
        }
        params.append(')');
        String result = first ? (field ? f.desc : desc.substring(desc.indexOf(')') + 1)) : yDesc.substring(yDesc.indexOf(')') + 1);
        String fake = from + "+" + yName + (at == 0 ? "" : "+" + at), fakeDesc = params + result;
        xc.methods.put(fake + fakeDesc, new GameDiff.Meth(xc, fake, fakeDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        fixes.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Fix("game chain " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(fake)),
            params.toString(), Json.text(w, "expr", "")));
        pairs.add(new PortChains.Spec(field ? 'f' : 'm', xStatic, owner, from, desc, yOwner, yName, yDesc, y.isStatic(), at, first, fake, fakeDesc,
            (xc.flags & Opcodes.ACC_INTERFACE) != 0));
    }

    /**
     * A call whose operand at ($0 its receiver) was a parameter the game retyped, and what the game's own code wrote in its
     * place with that operand as the new type (GameDiff.Retyped): font.draw(pose, ...) -> $1.drawString($0, ...).
     */
    record RetypedCall(String name, String desc, int at, String as, String expr) {}

    final Map<String, List<RetypedCall>> retypedCalls = new HashMap<>(); // owner (old names) -> calls

    /** A replacement the game's own code shows in more than half of the places it changed. */
    static boolean majority(Map<String, Object> w) {
        Object votes = w.get("votes"), of = w.get("of");
        return !(votes instanceof Number v) || !(of instanceof Number n) || v.intValue() * 2 > n.intValue();
    }

    /** A call in the new release's names. */
    record Call(String owner, String name, String desc, boolean isStatic) {}

    /** A parameter the game added, and the one value its own code passes there ("a.b.C::m", "a.b.C.FIELD", "a.b.C.m()"). */
    record Default(Call to, int index, String value) {}

    /** What replaces a member read from the same receiver: "f:name" and "m:name" steps; the last takes the old call's arguments. */
    record Chain(String newOwner, String steps) {}

    final Map<String, List<Call>> rewrites = new HashMap<>(); // owner.name+desc (old names) -> calls the game's code switched to
    final Map<String, Default> defaults = new HashMap<>();
    final Map<String, Chain> chains = new HashMap<>(); // owner.name+desc, or owner.name for a field

    public static void main(String[] args) throws Exception {
        String from = null, to = null, map = null, out = null, loader = null, report = null, book = null;
        boolean runtime = false;
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime-names" -> runtime = true;
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                case "--map" -> map = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--report" -> report = args[++i];
                case "--fixes" -> book = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (from == null || to == null || map == null || out == null || jars.isEmpty()) {
            System.err.println("usage: JarPort --from V --to V --map route.json [--loader neoforge|fabric] [--runtime-names] [--fixes knowledge/fixes.json] --out OUT.jar|DIR [--report R.json] IN.jar ...");
            System.exit(2);
        }
        JarPort port = new JarPort(Exposure.snap(from, loader), Exposure.snap(to, loader), Path.of(map));
        if (loader != null) {
            port.loader = loader;
        }
        port.runtimeNames = runtime;
        if (book != null) {
            port.fixes(Path.of(book));
        }
        Path target = Path.of(out);
        boolean folder = jars.size() > 1 || Files.isDirectory(target);
        if (folder) {
            Files.createDirectories(target);
        }
        List<Object> rows = new ArrayList<>();
        int linked = 0;
        Map<String, Set<String>> byMods = new HashMap<>();
        for (Path jar : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", jar.getFileName().toString());
            try {
                List<Map<String, Object>> nested = new ArrayList<>();
                byte[] ported = port.port(Files.readAllBytes(jar), jar.getFileName().toString(), nested);
                Path dest = folder ? target.resolve(jar.getFileName().toString()) : target;
                Files.write(dest, ported);
                Set<String> unlinked = new TreeSet<>(), silent = new TreeSet<>();
                Map<String, Integer> changed = new TreeMap<>();
                for (Map<String, Object> n : nested) {
                    for (Object u : Json.array(n.get("unlinked"))) {
                        unlinked.add(n.get("jar") + ": " + u);
                        byMods.computeIfAbsent(u.toString().replaceAll(" \\(in [^)]*\\)$", ""), k -> new TreeSet<>()).add(jar.getFileName().toString());
                    }
                    for (Object u : Json.array(n.get("silent"))) {
                        silent.add(n.get("jar") + ": " + u);
                    }
                    for (var e : Json.object(n.get("changed")).entrySet()) {
                        changed.merge(e.getKey(), ((Number) e.getValue()).intValue(), Integer::sum);
                    }
                }
                row.put("out", dest.toString());
                row.put("changed", changed);
                row.put("unlinked", new ArrayList<>(unlinked));
                row.put("silent", new ArrayList<>(silent));
                linked += unlinked.isEmpty() ? 1 : 0;
            } catch (IOException | RuntimeException failed) {
                row.put("error", String.valueOf(failed));
            }
            rows.add(row);
            System.out.println(row.get("mod") + ": " + (row.containsKey("error") ? row.get("error")
                : Json.array(row.get("unlinked")).size() + " unlinked, changed " + row.get("changed")));
        }
        System.out.printf("%d of %d jars link against %s%n", linked, jars.size(), to);
        if (report != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", from);
            r.put("to", to);
            r.put("loader", loader == null ? "" : loader);
            r.put("linked", linked);
            r.put("mods", rows);
            // What to teach the porter next: each reference that does not link, by how many mods it holds back.
            List<Object> ranked = new ArrayList<>();
            byMods.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Set<String>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey))
                .forEach(e -> ranked.add(Map.of("member", e.getKey(), "mods", e.getValue().size())));
            r.put("unlinkedByMods", ranked);
            Files.writeString(Path.of(report), Json.write(r), StandardCharsets.UTF_8);
        }
    }

    /** A whole jar, nested mods included; each jar's result is added to results. */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results) throws IOException {
        boolean fabric = loader.equals("fabric");
        if (fabric && compareVersions(a.version, UNOBFUSCATED) < 0 && intermediary(jar)) {
            jar = fabricIn().jar(jar, new ModNames.Counts());
        }
        byte[] out = port(jar, name, results, null);
        if (fabric && runtimeNames && compareVersions(b.version, UNOBFUSCATED) < 0) {
            out = fabricOut().jar(out);
        }
        return out;
    }

    /** The first release Fabric runs in Mojang's names; before it, in that release's intermediary. */
    static final String UNOBFUSCATED = "26.1";

    boolean runtimeNames; // hand a Fabric jar back in the names Fabric runs, not Mojang's
    ModNames in;
    ModNames.Back out;

    ModNames fabricIn() throws IOException {
        try {
            return in != null ? in : (in = new ModNames(new Exposure.Intermediary(a.version)));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + a.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    ModNames.Back fabricOut() throws IOException {
        try {
            return out != null ? out : (out = new ModNames.Back(b.version, b));
        } catch (Exception unreadable) {
            throw new IOException("Fabric's intermediary names for " + b.version + " could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /** Whether a jar's classes, or its nested jars', name the game in Fabric's intermediary (net/minecraft/class_N). */
    static boolean intermediary(byte[] jar) throws IOException {
        byte[] mark = "net/minecraft/class_".getBytes(StandardCharsets.ISO_8859_1);
        Map<String, byte[]> entries = entries(jar, "", new HashMap<>());
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && indexOf(e.getValue(), mark) >= 0) {
                return true;
            }
        }
        for (String n : nested(entries)) {
            if (intermediary(entries.get(n))) {
                return true;
            }
        }
        return false;
    }

    static int indexOf(byte[] data, byte[] mark) {
        outer:
        for (int i = 0; i + mark.length <= data.length; i++) {
            for (int j = 0; j < mark.length; j++) {
                if (data[i + j] != mark[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * grants: what every access transformer in the outermost jar and its nested jars opens (null: read it here). The loader
     * applies an access transformer to the game for every mod, so a nested helper may rely on its parent's.
     */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Set<String> grants) throws IOException {
        Map<String, Long> times = new HashMap<>();
        Map<String, byte[]> entries = entries(jar, name, times);
        // NeoForge's loader reads META-INF/mods.toml up to 1.20.4 (FML 2) and only META-INF/neoforge.mods.toml from 1.20.5
        // (FML 3); a jar keeping the old name is skipped without a word.
        boolean renamed = false;
        if (loader.equals("neoforge") && compareVersions(to, "1.20.5") >= 0 && entries.containsKey("META-INF/mods.toml")
            && !entries.containsKey("META-INF/neoforge.mods.toml")) {
            Map<String, byte[]> moved = new LinkedHashMap<>();
            entries.forEach((k, v) -> moved.put(k.equals("META-INF/mods.toml") ? "META-INF/neoforge.mods.toml" : k, v));
            entries = moved;
            times.put("META-INF/neoforge.mods.toml", times.getOrDefault("META-INF/mods.toml", 0L));
            renamed = true;
        }
        Set<String> nested = nested(entries);
        if (grants == null) {
            Map<String, GameDiff.RawCls> all = new HashMap<>();
            carried(entries, all);
            tree = all;
            grants = new HashSet<>();
            for (String text : accessTransformers(entries, name)) {
                widen(grants, text);
            }
        }
        Mod mod = new Mod();
        if (renamed) {
            mod.change("metadata renamed to neoforge.mods.toml");
        }
        mod.widened.addAll(grants);
        // NeoForge's loader (FML 4 to 11) reads the access transformers neoforge.mods.toml lists, or else
        // META-INF/accesstransformer.cfg, for every mod file, nested ones included.
        // Fabric's reads the access widener fabric.mod.json names, for every mod file too.
        byte[] fmj = entries.get("fabric.mod.json");
        mod.canWiden = entries.containsKey("META-INF/neoforge.mods.toml") || fmj != null;
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                e.setValue(PortChains.apply(e.getValue(), pairs, (o, k, n, d) -> {
                    GameDiff.Cls c = Exposure.declaring(a, o, k, n, d);
                    return c == null ? null : c.name;
                }));
                read(mod, e.getValue());
            }
        }
        requires(mod, entries);
        plan(mod);
        buses(mod);
        for (var e : mod.split.entrySet()) {
            String file = e.getKey() + "$ModBusListeners.class";
            entries.put(file, companion(mod, e.getKey(), e.getValue()));
            mod.change("mod-bus listeners moved to a companion class");
        }
        // Classes first: what they need opened goes into the access transformer written after them.
        Map<String, byte[]> rewritten = new HashMap<>();
        // A mod method moved to a game method's new parameter types that some call cannot follow (no new arguments at
        // hand there) is kept as well, as it was: that call still links, as it did before the port.
        Set<String> wasUnlinked = new TreeSet<>(mod.unlinked), wasSilent = new TreeSet<>(mod.silent), wasGranted = new TreeSet<>(mod.grants);
        Map<String, Integer> wasChanged = new TreeMap<>(mod.changed);
        Set<String> twinned = new HashSet<>();
        for (int pass = 0; pass < 8; pass++) {
            int kept = mod.kept.size(), unmoved = mod.unmoved.size();
            rewritten.clear();
            for (var e : entries.entrySet()) {
                if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                    rewritten.put(e.getKey(), rewriteOrReport(mod, e.getValue()));
                }
            }
            // Helpers a moved body passes a worked-out parameter to move too, one level more each pass.
            int added = 0;
            for (var e : mod.twins.entrySet()) {
                if (twinned.add(e.getKey())) {
                    added += twin(mod, e.getKey(), e.getValue()) ? 1 : 0;
                }
            }
            if (mod.kept.size() == kept && mod.unmoved.size() == unmoved && added == 0) {
                break;
            }
            // A body that cannot be moved whole stays as it was, an override the game no longer calls, as before the port.
            for (String key : mod.unmoved) {
                String owner = key.substring(0, key.indexOf('.')), member = key.substring(key.indexOf('.') + 1);
                if (mod.bridges.getOrDefault(owner, new ArrayList<>()).removeIf(br -> br.via() != null && (br.target() + br.targetDesc()).equals(member))) {
                    wasSilent.add("override no longer called: " + Exposure.dotted(owner) + "." + member + " (a call in it needs what the game passes now)");
                }
            }
            mod.unlinked.clear();
            mod.unlinked.addAll(wasUnlinked);
            mod.silent.clear();
            mod.silent.addAll(wasSilent);
            mod.grants.clear();
            mod.grants.addAll(wasGranted);
            mod.changed.clear();
            mod.changed.putAll(wasChanged);
        }
        List<String> atFiles = atFiles(entries);
        String widener = fmj == null ? null : widenerFile(fmj);
        if (fmj != null && widener == null && !mod.grants.isEmpty()) {
            widener = "mmt-port.accesswidener";
            entries.put(widener, "accessWidener v2 named\n".getBytes(StandardCharsets.UTF_8));
        }
        if (fmj == null && !mod.grants.isEmpty()) {
            entries.putIfAbsent(atFiles.get(0), new byte[0]);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (var e : entries.entrySet()) {
                String n = e.getKey();
                byte[] data = e.getValue();
                if (n.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) {
                    continue;
                }
                if (n.equals("META-INF/MANIFEST.MF")) {
                    data = unsigned(data);
                } else if (n.equals("META-INF/neoforge.mods.toml") || n.equals("META-INF/mods.toml")) {
                    data = metadata(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                } else if (n.equals("fabric.mod.json")) {
                    data = fabricMetadata(data, widener);
                } else if (n.equals(widener)) {
                    String text = accessWidener(mod, new String(data, StandardCharsets.UTF_8));
                    if (!mod.grants.isEmpty()) {
                        text += (text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + widenerLines(mod.grants);
                        mod.change("members opened through the mod's access widener");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                } else if (nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    data = port(data, name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants);
                } else if (rewritten.containsKey(n)) {
                    data = rewritten.get(n);
                } else if (atFiles.contains(n)) {
                    String text = accessTransformer(mod, new String(data, StandardCharsets.UTF_8));
                    if (fmj == null && n.equals(atFiles.get(0)) && !mod.grants.isEmpty()) {
                        text += (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "# Opened by mmt port: closed in " + to + ", used by this mod\n" + String.join("\n", mod.grants) + "\n";
                        mod.change("members opened through the mod's access transformer");
                    }
                    data = text.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry entry = new ZipEntry(n);
                entry.setTime(times.getOrDefault(n, 0L));
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jar", name);
        result.put("changed", mod.changed);
        result.put("unlinked", new ArrayList<>(mod.unlinked));
        result.put("silent", new ArrayList<>(mod.silent));
        results.add(result);
        return buffer.toByteArray();
    }

    /** A jar's files by name (directories left out); times gets each file's time. An entry pointing outside the jar refuses it. */
    static Map<String, byte[]> entries(byte[] jar, String name, Map<String, Long> times) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (unsafe(e.getName())) {
                    throw new IOException(name + " has an entry that points outside the jar: " + e.getName());
                }
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                    times.put(e.getName(), e.getTime());
                }
            }
        }
        return entries;
    }

    /**
     * Jar-in-jar: nested jars are where META-INF/jarjar/metadata.json says ("path", often META-INF/jars/), in
     * META-INF/jarjar/, or where fabric.mod.json lists them ("jars": [{"file": ...}]).
     */
    static Set<String> nested(Map<String, byte[]> entries) {
        Set<String> nested = new HashSet<>();
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        if (list != null) {
            for (Object j : Json.array(Json.object(Json.parse(new String(list, StandardCharsets.UTF_8))).getOrDefault("jars", List.of()))) {
                nested.add(Json.text(Json.object(j), "path", ""));
            }
        }
        byte[] fmj = entries.get("fabric.mod.json");
        if (fmj != null) {
            try {
                for (Object j : Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("jars") instanceof List<?> l ? l : List.of()) {
                    if (j instanceof Map<?, ?> m && m.get("file") instanceof String file) {
                        nested.add(file);
                    }
                }
            } catch (RuntimeException unreadable) {
                // the loader rejects it too
            }
        }
        for (String n : entries.keySet()) {
            if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                nested.add(n);
            }
        }
        nested.retainAll(entries.keySet());
        return nested;
    }

    /** Every access transformer in a jar and its nested jars. */
    static List<String> accessTransformers(Map<String, byte[]> entries, String name) throws IOException {
        List<String> out = new ArrayList<>();
        for (String file : atFiles(entries)) {
            byte[] at = entries.get(file);
            if (at != null) {
                out.add(new String(at, StandardCharsets.UTF_8));
            }
        }
        for (String n : nested(entries)) {
            out.addAll(accessTransformers(entries(entries.get(n), name + "!" + n, new HashMap<>()), name + "!" + n));
        }
        return out;
    }

    static final Pattern DECLARED_AT = Pattern.compile("\\[\\[accessTransformers\\]\\][^\\[]*?file\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')");

    /** The access transformers the loader reads from a jar: those its neoforge.mods.toml lists, else the default one. */
    static List<String> atFiles(Map<String, byte[]> entries) {
        List<String> out = new ArrayList<>();
        byte[] toml = entries.get("META-INF/neoforge.mods.toml");
        if (toml != null) {
            Matcher m = DECLARED_AT.matcher(new String(toml, StandardCharsets.UTF_8));
            while (m.find()) {
                out.add((m.group(1) != null ? m.group(1) : m.group(2)).replaceFirst("^/", ""));
            }
        }
        if (out.isEmpty()) {
            out.add("META-INF/accesstransformer.cfg");
        }
        return out;
    }

    /** An entry name that an unzip would write outside its folder: absolute, a drive, a backslash or a ".." part. */
    static boolean unsafe(String entry) {
        return entry.startsWith("/") || entry.contains("\\") || entry.matches("^[A-Za-z]:.*") || Arrays.asList(entry.split("/")).contains("..");
    }

    void read(Mod mod, byte[] bytes) {
        GameDiff.RawCls rc;
        try {
            rc = GameDiff.parse(bytes);
        } catch (RuntimeException unreadable) {
            return;
        }
        mod.classes.put(rc.name, rc);
        mod.bytes.put(rc.name, bytes);
        ClassModel cm = ClassFile.of().parse(bytes);
        for (MethodModel m : cm.methods()) {
            m.findAttribute(java.lang.classfile.Attributes.code()).ifPresent(code ->
                mod.maxLocals.put(rc.name + "." + m.methodName().stringValue() + m.methodType().stringValue(), ((CodeAttribute) code).maxLocals())
            );
        }
        // A mixin's targets, from @Mixin(value = {Class...}, targets = {"name"...}) in its class annotations.
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (desc.equals(SUBSCRIBER)) {
                    mod.subscribers.put(rc.name, false);
                    List<Object[]> values = mod.subscriberValues.computeIfAbsent(rc.name, k -> new ArrayList<>());
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            values.add(new Object[] { name, value });
                        }

                        @Override
                        public void visitEnum(String name, String d, String value) {
                            if ("bus".equals(name)) {
                                mod.subscribers.put(rc.name, true);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            List<String[]> items = new ArrayList<>();
                            values.add(new Object[] { name, items });
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitEnum(String n, String d, String value) {
                                    items.add(new String[] { d, value });
                                }
                            };
                        }
                    };
                }
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    return null;
                }
                List<String> targets = mod.mixinTargets.computeIfAbsent(rc.name, k -> new ArrayList<>());
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof Type t) {
                                    targets.add(t.getInternalName());
                                } else if (value instanceof String s) {
                                    targets.add(s.replace('.', '/'));
                                }
                            }
                        };
                    }

                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof Type t) {
                            targets.add(t.getInternalName());
                        }
                    }
                };
            }

            // @Accessor("field") / @Invoker("method"), or with no value the name Mixin infers from the method's own name.
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        Type[] params = Type.getArgumentTypes(desc);
                        if (annotation.equals(LISTENER) && params.length == 1 && params[0].getSort() == Type.OBJECT) {
                            mod.listeners.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(params[0].getInternalName());
                            Listener l = new Listener(name, desc, access, params[0].getInternalName(), new ArrayList<>());
                            mod.listenerMethods.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(l);
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String n, Object value) {
                                    l.values().add(new Object[] { n, value });
                                }

                                @Override
                                public void visitEnum(String n, String d, String value) {
                                    l.values().add(new Object[] { n, d, value });
                                }
                            };
                        }
                        if (INJECTORS.contains(annotation)) {
                            Injector inj = new Injector(annotation, new ArrayList<>(), new ArrayList<>(), new int[] { -1 });
                            mod.injectors.put(rc.name + "." + name + desc, inj);
                            return injection(inj, null);
                        }
                        String kind = ACCESSORS.get(annotation);
                        if (kind == null) {
                            return null;
                        }
                        String key = rc.name + "." + name + desc;
                        mod.accessors.put(key, new String[] { kind, null });
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if ("value".equals(n) && value instanceof String s && !s.isEmpty()) {
                                    mod.accessors.put(key, new String[] { kind, s });
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
    }

    static final String SUBSCRIBER = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    static final String LISTENER = "Lnet/neoforged/bus/api/SubscribeEvent;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    static final Set<String> CALLBACKS = Set.of(
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
        "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"
    );

    /** Mixin's and MixinExtras' annotations that inject at an @At into a target method. */
    static final Set<String> INJECTORS = Set.of(INJECT,
        "Lorg/spongepowered/asm/mixin/injection/Redirect;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    /**
     * A handler's injector (its annotation): the target method strings, each @At's {value, target, ordinal} (in @Slice
     * too), and its require (-1: the mixin config's defaultRequire).
     */
    record Injector(String kind, List<String> methods, List<String[]> ats, int[] require) {}

    /**
     * A handler re-declared with its target's new parameters (desc, new names) and the original body moved to name$mmt:
     * each of the first captured parameters of the original comes from the new parameter from[i], or is a default
     * where the body never reads it.
     */
    record Adapter(String desc, int[] from, int captured, String[][] via) {}

    /**
     * A method added to a mod class with a game method's new name and signature (new names), calling the mod's own
     * (target). Each old argument i is the new parameter from[i], or a default when from[i] is -1. When via is not null
     * the game method's parameters changed type, and the mod's method is replaced: its body moves to the new signature,
     * with each old parameter worked out first from new parameter from[i] through the steps via[i] (null: as it is), so
     * calls in the body that need what the game passes now get it (see Retype).
     */
    record Bridge(String name, String desc, String target, String targetDesc, int[] from, int access, String[][] via) {}

    /** Collects an @Inject's method strings and @At values; at is the @At being read, or null. */
    static AnnotationVisitor injection(Injector inj, String[] at) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String n, Object v) {
                if (at == null && "require".equals(n) && v instanceof Integer i) {
                    inj.require()[0] = i;
                } else if (at != null && v instanceof String s && ("value".equals(n) || "target".equals(n))) {
                    at["value".equals(n) ? 0 : 1] = s;
                } else if (at != null && "ordinal".equals(n) && v instanceof Integer i && i >= 0) {
                    at[2] = String.valueOf(i);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String n) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String x, Object v) {
                        if (at == null && "method".equals(n) && v instanceof String s) {
                            inj.methods().add(s);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String x, String d) {
                        return nested(d);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String n, String d) {
                return nested(d);
            }

            AnnotationVisitor nested(String d) {
                if (!d.equals(AT)) {
                    return injection(inj, null);
                }
                String[] found = new String[3];
                inj.ats().add(found);
                return injection(inj, found);
            }
        };
    }

    /** A @SubscribeEvent method and its annotation's values ({name, value} or {name, enum desc, value}). */
    record Listener(String name, String desc, int access, String event, List<Object[]> values) {}

    /**
     * Newer loaders send each listener of an @EventBusSubscriber class that names no bus to the bus its event belongs to;
     * older ones (1.21.2's NeoForge, FML 4.0.29) put the whole class on the game bus, and refuse a mod-bus event there.
     * A class whose listeners are all mod-bus events gets bus = MOD written, which every loader honours. A class with
     * both kinds keeps its game-bus listeners; its mod-bus ones are called from a companion class on the mod bus.
     */
    void buses(Mod mod) {
        for (var e : mod.subscribers.entrySet()) {
            List<String> events = mod.listeners.getOrDefault(e.getKey(), List.of());
            long onMod = events.stream().filter(t -> modBus(mod, cls(t))).count();
            if (e.getValue() || onMod == 0) {
                continue;
            }
            if (onMod == events.size()) {
                mod.toModBus.add(e.getKey());
                continue;
            }
            List<Listener> moved = mod.listenerMethods.getOrDefault(e.getKey(), List.of()).stream().filter(l -> modBus(mod, cls(l.event()))).toList();
            if (moved.stream().allMatch(l -> (l.access() & Opcodes.ACC_STATIC) != 0)) {
                mod.split.put(e.getKey(), moved);
            } else {
                mod.unlinked.add("listeners for both event buses in one @EventBusSubscriber class: " + Exposure.dotted(e.getKey()));
            }
        }
    }

    /** The companion of a subscriber with listeners on both buses: on the mod bus, each listener calls the original. */
    byte[] companion(Mod mod, String owner, List<Listener> listeners) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, owner + "$ModBusListeners", null, "java/lang/Object", null);
        AnnotationVisitor av = w.visitAnnotation(SUBSCRIBER, true);
        for (Object[] v : mod.subscriberValues.getOrDefault(owner, List.of())) {
            if (v[1] instanceof List<?> items) {
                AnnotationVisitor array = av.visitArray((String) v[0]);
                for (Object item : items) {
                    String[] e = (String[]) item;
                    array.visitEnum(null, e[0], e[1]);
                }
                array.visitEnd();
            } else {
                av.visit((String) v[0], v[1]);
            }
        }
        av.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "MOD");
        av.visitEnd();
        for (Listener l : listeners) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, l.name(), l.desc(), null, null);
            AnnotationVisitor sub = m.visitAnnotation(LISTENER, true);
            for (Object[] v : l.values()) {
                if (v.length == 3) {
                    sub.visitEnum((String) v[0], (String) v[1], (String) v[2]);
                } else {
                    sub.visit((String) v[0], v[1]);
                }
            }
            sub.visitEnd();
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, l.name(), l.desc(), false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** Whether an event (new names) is dispatched on a mod's own bus: the loader's lifecycle events and every IModBusEvent. */
    boolean modBus(Mod mod, String event) {
        return event.startsWith("net/neoforged/fml/event/") || isA(mod, event, "net/neoforged/fml/event/IModBusEvent");
    }

    // ---- Linking: where the old release declares a member, and whether the new one has it as the JVM sees it.

    String cls(String internal) {
        return classMap.getOrDefault(internal, internal);
    }

    String desc(String d) {
        return mapDesc(d, classMap);
    }

    String oldDesc(String d) {
        return mapDesc(d, inverse);
    }

    static String mapDesc(String d, Map<String, String> names) {
        if (d == null) {
            return null;
        }
        Matcher m = Pattern.compile("L([^;<]+)([;<])").matcher(d);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("L" + names.getOrDefault(m.group(1), m.group(1)) + m.group(2)));
        }
        return m.appendTail(sb).toString();
    }

    record Decl(String owner, boolean game) {}

    Map<String, GameDiff.RawCls> tree = Map.of(); // every class the jar being ported and its nested jars carry

    /**
     * A class the jar carries in one of its own nested jars, which the new release lacks and whose package the new release
     * no longer has (a module the platform stopped shipping, so the bundled copy is what runs): it is the mod's, not
     * gone. A class whose package the new release still has is left to the game: the platform's newer copy runs.
     */
    GameDiff.RawCls provided(String c) {
        GameDiff.RawCls own = tree.get(c);
        if (own == null || b.classes.containsKey(cls(c))) {
            return null;
        }
        return packagesB().contains(pkg(c)) ? null : own;
    }

    Set<String> packagesB;

    Set<String> packagesB() {
        if (packagesB == null) {
            packagesB = new HashSet<>();
            b.classes.keySet().forEach(k -> packagesB.add(pkg(k)));
        }
        return packagesB;
    }

    /** Every class in a jar and its nested jars, by name. */
    static void carried(Map<String, byte[]> entries, Map<String, GameDiff.RawCls> out) throws IOException {
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                try {
                    GameDiff.RawCls rc = GameDiff.parse(e.getValue());
                    out.putIfAbsent(rc.name, rc);
                } catch (RuntimeException unreadable) {
                    // read() skips such a class too
                }
            }
        }
        for (String n : nested(entries)) {
            carried(entries(entries.get(n), n, new HashMap<>()), out);
        }
    }

    /** The class that declares a member reached through owner in the old release: the mod's own classes first, then the game. */
    Decl declaringOld(Mod mod, String owner, char kind, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null && provided(c) != null) {
                // A class another of the mod's jars carries: its own members are the mod's; what it inherits is left as
                // written, as for any library class this jar does not hold.
                GameDiff.RawCls carried = provided(c);
                return kind == 'f' ? carried.fields.containsKey(name) ? new Decl(c, false) : null
                    : carried.methods.containsKey(name + desc) ? new Decl(c, false) : null;
            }
            if (own != null) {
                if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + desc)) {
                    return new Decl(c, false);
                }
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(a, c, kind, name, desc);
            if (game != null) {
                return new Decl(game.name, true);
            }
        }
        return null;
    }

    /**
     * Whether the new release (with the mod's own classes, renamed) resolves the member from owner (new names), and one of
     * callers (the class the code runs in; a mixin's code runs in each of its targets) may use it, as the JVM checks.
     */
    boolean linksNew(Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!classOpen(mod, callers, owner)) {
            return false;
        }
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                String[] renamed = mod.renamedDecl.get(c + "." + name + (kind == 'f' ? "" : desc));
                if (renamed == null) {
                    if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + oldDesc(desc))) {
                        return true;
                    }
                }
                for (var r : mod.renamedDecl.entrySet()) {
                    if (r.getKey().startsWith(c + ".") && r.getValue()[0].equals(name) && (kind == 'f' || desc.equals(r.getValue()[1]))) {
                        return true;
                    }
                }
                if (own.sup != null) {
                    queue.add(cls(own.sup));
                }
                for (String i : own.ifaces) {
                    queue.add(cls(i));
                }
                continue;
            }
            GameDiff.Cls holder = Exposure.declaring(b, c, kind, name, kind == 'f' ? null : desc);
            if (holder != null) {
                int flags = kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(name + desc).flags;
                return memberOpen(mod, callers, holder.name, kind == 'f' ? name : name + desc, flags);
            }
            if (c.startsWith("java/") && kind == 'm') {
                return true; // Object's methods reached through an interface
            }
        }
        return false;
    }

    /** A game class of the new release that callers may name: public, or in their package. */
    boolean classOpen(Mod mod, List<String> callers, String owner) {
        GameDiff.Cls c = b.classes.get(owner);
        if (c == null || (c.flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(owner)) {
            return true;
        }
        return callers.stream().anyMatch(caller -> pkg(caller).equals(pkg(owner)));
    }

    /** Whether one of callers may use a member with these flags declared in holder (new names), after the mod's access transformer. */
    boolean memberOpen(Mod mod, List<String> callers, String holder, String member, int flags) {
        boolean method = member.contains("(");
        String every = holder + (method ? ".*()" : ".*");
        if ((flags & Opcodes.ACC_PUBLIC) != 0 || mod.widened.contains(holder + "." + member) || mod.widened.contains(every) && !member.startsWith("<init>")) {
            return true;
        }
        if (mod.widened.contains("~" + holder + "." + member) || mod.widened.contains("~" + every) && !member.startsWith("<init>")) {
            flags = flags & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED;
        }
        for (String caller : callers) {
            if (caller.equals(holder)) {
                return true;
            }
            if ((flags & Opcodes.ACC_PRIVATE) != 0) {
                continue;
            }
            if (pkg(caller).equals(pkg(holder)) || (flags & Opcodes.ACC_PROTECTED) != 0 && subclass(mod, caller, holder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A game member reached from owner (new names) that the callers may not use: when the loader reads the mod's access
     * transformer, the member, and its class when that is closed too, is opened there and counts as open from here on.
     * The loader's own classes are left as they are. Returns whether it was opened.
     */
    boolean opened(Mod mod, List<String> callers, String owner, char kind, String name, String desc) {
        if (!mod.canWiden) {
            return false;
        }
        String c = owner;
        while (c != null && mod.classes.containsKey(c)) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (kind == 'f' ? own.fields.containsKey(name) : own.methods.containsKey(name + oldDesc(desc))) {
                return false;
            }
            c = own.sup == null ? null : cls(own.sup);
        }
        GameDiff.Cls holder = c == null ? null : Exposure.declaring(b, c, kind, name, desc);
        if (holder == null || holder.loader) {
            return false;
        }
        String member = kind == 'f' ? name : name + desc;
        boolean classOk = classOpen(mod, callers, owner);
        boolean memberOk = memberOpen(mod, callers, holder.name, member, kind == 'f' ? holder.fields.get(name).flags : holder.methods.get(member).flags);
        GameDiff.Cls site = b.classes.get(owner);
        if (classOk && memberOk || !classOk && (site == null || site.loader)) {
            return false;
        }
        if (!classOk) {
            mod.grants.add("public " + Exposure.dotted(owner));
            mod.widened.add(owner);
        }
        if (!memberOk) {
            mod.grants.add("public " + Exposure.dotted(holder.name) + " " + member);
            mod.widened.add(holder.name + "." + member);
        }
        return true;
    }

    static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    /** Whether c (new names) extends holder, through the mod's own classes and the new release. */
    boolean subclass(Mod mod, String c, String holder) {
        for (String k = c; k != null; ) {
            if (k.equals(holder)) {
                return true;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                k = own.sup == null ? null : cls(own.sup);
                continue;
            }
            GameDiff.Cls game = b.classes.get(k);
            k = game == null ? null : game.sup;
        }
        return false;
    }

    /** Whether c (new names) is target or extends or implements it, through the mod's own classes and the new release. */
    boolean isA(Mod mod, String c, String target) {
        Deque<String> queue = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (k.equals(target)) {
                return true;
            }
            if (!seen.add(k)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(k);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(cls(own.sup));
                }
                own.ifaces.forEach(i -> queue.add(cls(i)));
                continue;
            }
            GameDiff.Cls game = b.classes.get(k);
            if (game != null) {
                if (game.sup != null) {
                    queue.add(game.sup);
                }
                queue.addAll(game.ifaces);
                continue;
            }
            Class<?> jdk = jdk(k);
            if (jdk != null) {
                if (jdk.getSuperclass() != null) {
                    queue.add(Type.getInternalName(jdk.getSuperclass()));
                }
                for (Class<?> i : jdk.getInterfaces()) {
                    queue.add(Type.getInternalName(i));
                }
            }
        }
        return false;
    }

    /** {name, descriptor} of the one abstract method of a functional interface (the game's or the JDK's), or null. */
    String[] functional(String internal) {
        List<String[]> found = new ArrayList<>();
        GameDiff.Cls game = b.classes.get(internal);
        if (game != null) {
            Deque<GameDiff.Cls> queue = new ArrayDeque<>(List.of(game));
            Set<String> defaults = new HashSet<>();
            while (!queue.isEmpty()) {
                GameDiff.Cls k = queue.poll();
                for (GameDiff.Meth m : k.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !OBJECT.contains(m.name + m.desc) && !defaults.contains(m.name + m.desc)) {
                        found.add(new String[] { m.name, m.desc });
                    } else if ((m.flags & Opcodes.ACC_STATIC) == 0) {
                        defaults.add(m.name + m.desc);
                    }
                }
                k.ifaces.stream().map(b.classes::get).filter(Objects::nonNull).forEach(queue::add);
            }
        } else if (internal.startsWith("java/")) {
            try {
                Class<?> type = Class.forName(internal.replace('/', '.'), false, null);
                for (java.lang.reflect.Method m : type.getMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !OBJECT.contains(m.getName() + Type.getMethodDescriptor(m))) {
                        found.add(new String[] { m.getName(), Type.getMethodDescriptor(m) });
                    }
                }
            } catch (ClassNotFoundException | LinkageError notJdk) {
                return null;
            }
        }
        return found.stream().map(f -> f[0] + f[1]).distinct().count() == 1 ? found.get(0) : null;
    }

    record FieldHit(String desc, boolean open) {}

    /** The field the new release resolves from owner (new names), through the mod's own classes, or null. */
    FieldHit fieldNew(Mod mod, List<String> callers, String owner, String name) {
        for (String c = owner; c != null; ) {
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                GameDiff.Cls holder = Exposure.declaring(b, c, 'f', name, null);
                if (holder == null) {
                    return null;
                }
                GameDiff.Fld f = holder.fields.get(name);
                return new FieldHit(f.desc, classOpen(mod, callers, owner) && memberOpen(mod, callers, holder.name, name, f.flags));
            }
            if (own.fields.containsKey(name)) {
                return new FieldHit(desc(own.fields.get(name)[0]), true);
            }
            c = own.sup == null ? null : cls(own.sup);
        }
        return null;
    }

    /**
     * {owner, name, desc} in new names that a reference to a game member becomes, or null when nothing in the new release
     * links. A new descriptor is taken only when the arguments already on the stack still fit it: the same types after class
     * renames, or parameters that became supertypes, with the same return or one that serves where the old one was used
     * (a subtype; a field read may also now hold a subtype). Anything else (an added, removed or narrowed
     * parameter, another return type) does not link here; dropped arguments are handled at the call site.
     */
    String[] target(Mod mod, List<String> callers, String siteOwner, char kind, String name, String desc, Decl decl) {
        String newSite = cls(siteOwner);
        if (unchangedLibrary(kind, decl.owner(), name, desc)) {
            // A library member both releases have as it was: whatever lets the mod use it (its own newer copy of the
            // library, say) still does; only what changed inside a library is the port's.
            return new String[] { newSite, name, desc(desc) };
        }
        if (kind == 'f' || kind == 'r') {
            String[] moved = route.field(decl.owner(), name);
            String fieldDesc = desc(desc);
            for (String[] t : List.of(new String[] { newSite, name }, new String[] { newSite, moved[1] }, new String[] { moved[0], moved[1] })) {
                FieldHit found = fieldNew(mod, callers, t[0], t[1]);
                // A field read ('r') may now hold a subtype (1.21.2: InteractionResult.SUCCESS is an InteractionResult$Success):
                // the value read still serves where the old type was used. A write must match exactly.
                boolean fits = found != null && (found.desc().equals(fieldDesc) || kind == 'r' && usable(Type.getType(found.desc()), Type.getType(fieldDesc)));
                if (fits && (found.open() || t[1].equals(moved[1]) && opened(mod, callers, t[0], 'f', t[1], null))) {
                    return new String[] { t[0], t[1], found.desc() };
                }
                if (found != null) {
                    return null; // there with another type, or closed to the mod: the access fails to link
                }
            }
            return null;
        }
        String[] moved = route.method(decl.owner(), name, desc);
        String mapped = desc(desc);
        if (linksNew(mod, callers, newSite, 'm', name, mapped)) {
            return new String[] { newSite, name, mapped };
        }
        // The same method, closed to the mod now: opened through the mod's access transformer.
        if (moved[1].equals(name) && moved[2].equals(mapped) && opened(mod, callers, newSite, 'm', name, mapped)) {
            return new String[] { newSite, name, mapped };
        }
        if (moved[2].equals(mapped) || widens(mapped, moved[2])) {
            if (linksNew(mod, callers, newSite, 'm', moved[1], moved[2])) {
                return new String[] { newSite, moved[1], moved[2] };
            }
            if (linksNew(mod, callers, moved[0], 'm', moved[1], moved[2])) {
                return moved;
            }
        }
        // Parameters that became supertypes (1.21.2: NonNullList -> List) with the same return: the old arguments still fit.
        GameDiff.Cls holder = b.classes.get(newSite);
        for (GameDiff.Cls c = holder; c != null; c = c.sup == null ? null : b.classes.get(c.sup)) {
            for (GameDiff.Meth m : c.methods.values()) {
                if (m.name.equals(moved[1]) && widens(mapped, m.desc) && linksNew(mod, callers, newSite, 'm', m.name, m.desc)) {
                    return new String[] { newSite, m.name, m.desc };
                }
            }
        }
        return null;
    }

    /**
     * Whether a call written for from (new names) still works against to: the same number of arguments, each old one
     * acceptable where the new one is asked (a supertype now), and a result the caller can use as the old one (a subtype now).
     */
    boolean widens(String from, String to) {
        Type[] before = Type.getArgumentTypes(from), after = Type.getArgumentTypes(to);
        if (before.length != after.length || !usable(Type.getReturnType(to), Type.getReturnType(from))) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (!usable(before[i], after[i])) {
                return false;
            }
        }
        return true;
    }

    /** A value of type value can be used where type wanted is asked, without a cast. */
    boolean usable(Type value, Type wanted) {
        if (value.equals(wanted)) {
            return true;
        }
        if (value.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT) {
            return false;
        }
        return Exposure.assignable(b, value.getClassName(), wanted.getClassName());
    }

    /** Positions of the new parameters among the old ones when the new list is the old one with some taken out (one way only), else null. */
    static int[] kept(String from, String to) {
        if (!Type.getReturnType(from).equals(Type.getReturnType(to))) {
            return null;
        }
        return VersionMap.Fix.uniqueEmbedding(VersionMap.Fix.paramTypes(from), VersionMap.Fix.paramTypes(to));
    }

    // ---- What changes in the mod's own declarations: overrides of renamed game methods, mixin shadows and accessors.

    void plan(Mod mod) {
        for (GameDiff.RawCls rc : mod.classes.values()) {
            List<String> targets = mod.mixinTargets.getOrDefault(rc.name, List.of());
            if (!targets.isEmpty()) {
                for (String t : targets) {
                    if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t))) {
                        mod.unlinked.add("mixin target class gone: " + Exposure.dotted(t) + " (in " + Exposure.dotted(rc.name) + ")");
                    }
                }
                for (var f : rc.fields.entrySet()) {
                    shadowField(mod, rc.name, targets, f.getKey(), f.getValue()[0]);
                }
                for (GameDiff.RawMeth m : rc.methods.values()) {
                    String key = rc.name + "." + m.name + m.desc;
                    if (mod.accessors.containsKey(key)) {
                        accessor(mod, rc.name, targets, m, mod.accessors.get(key));
                    } else if (!m.name.startsWith("<")) {
                        shadowMethod(mod, rc.name, targets, m.name, m.desc, m.flags);
                    }
                    Injector inj = mod.injectors.get(key);
                    if (inj != null && inj.kind().equals(INJECT)) {
                        retarget(mod, key, targets, m, inj);
                    }
                    if (inj != null) {
                        pointsGone(mod, rc.name, targets, inj, m.desc);
                    }
                }
                continue;
            }
            for (GameDiff.RawMeth m : rc.methods.values()) {
                if (!m.name.startsWith("<") && (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    override(mod, rc, m);
                }
            }
        }
        for (GameDiff.RawCls rc : mod.classes.values()) {
            if (mod.mixinTargets.getOrDefault(rc.name, List.of()).isEmpty()) {
                abstracts(mod, rc);
            }
        }
    }

    /**
     * The game method a mod method overrides, found through the mod's own parents up to the game (ModChild.foo over
     * ModBase.foo over GameBase.foo is GameBase.foo), so every member of an override family follows a rename.
     */
    Decl gameRoot(Mod mod, List<String> parents, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(parents);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                if (own.sup != null) {
                    queue.add(own.sup);
                }
                queue.addAll(own.ifaces);
                continue;
            }
            GameDiff.Cls game = Exposure.declaring(a, c, 'm', name, desc);
            if (game != null) {
                return new Decl(game.name, true);
            }
        }
        return null;
    }

    /** The game classes (new names) a mod class extends or implements, directly or through the mod's own classes. */
    List<String> gameParents(Mod mod, GameDiff.RawCls rc) {
        List<String> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        if (rc.sup != null) {
            queue.add(rc.sup);
        }
        queue.addAll(rc.ifaces);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own == null) {
                out.add(cls(c));
                continue;
            }
            if (own.sup != null) {
                queue.add(own.sup);
            }
            queue.addAll(own.ifaces);
        }
        return out;
    }

    void override(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m) {
        List<String> parents = new ArrayList<>(rc.ifaces);
        if (rc.sup != null) {
            parents.add(0, rc.sup);
        }
        Decl decl = gameRoot(mod, parents, m.name, m.desc);
        if (decl == null) {
            return;
        }
        GameDiff.Meth over = a.method(decl.owner(), m.name, m.desc);
        if (over == null || (over.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return;
        }
        String[] moved = route.method(decl.owner(), m.name, m.desc);
        String mapped = desc(m.desc);
        // Looked up from the game classes the mod actually extends: a game subclass may keep the old name while its
        // ancestor renames it, and then the mod's method still overrides as written.
        List<String> supers = gameParents(mod, rc);
        if (supers.stream().anyMatch(s -> Exposure.declaring(b, s, 'm', m.name, mapped) != null)) {
            return; // still overrides as written
        }
        if (moved[2].equals(mapped) && supers.stream().anyMatch(s -> Exposure.declaring(b, s, 'm', moved[1], moved[2]) != null)) {
            mod.renamedDecl.put(rc.name + "." + m.name + m.desc, new String[] { moved[1], moved[2] });
            mod.change("override renamed");
            return;
        }
        if (bridge(mod, rc, m, decl.owner(), moved, mapped, supers)) {
            boolean body = mod.bridges.get(rc.name).get(mod.bridges.get(rc.name).size() - 1).via() != null;
            mod.change(body ? "override moved to the game method's new parameter types" : "override bridged to the game method's new signature");
            return;
        }
        // The game no longer calls it: the class still loads, the hook is silent.
        mod.silent.add("override no longer called: " + Exposure.dotted(decl.owner()) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
    }

    /**
     * The game method a mod method overrides took other parameters (moved: its new owner, name and descriptor). The mod
     * class gets a method with the new signature that calls the mod's own with the arguments it had: each found among the
     * new ones by type (same types in another order, some added, one now narrower), or a default where the mod's body
     * never reads it. The result must serve as the new one. Anything else stays an override the game no longer calls.
     */
    boolean bridge(Mod mod, GameDiff.RawCls rc, GameDiff.RawMeth m, String gameOwner, String[] moved, String mapped, List<String> supers) {
        GameDiff.Meth now = null;
        for (String s : supers) {
            GameDiff.Cls holder = Exposure.declaring(b, s, 'm', moved[1], moved[2]);
            if (holder != null) {
                now = holder.methods.get(moved[1] + moved[2]);
                break;
            }
        }
        if (now == null || moved[2].equals(mapped) || (now.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0) {
            return false;
        }
        String signature = moved[1] + moved[2];
        boolean taken = rc.methods.containsKey(moved[1] + oldDesc(moved[2]))
            || mod.bridges.getOrDefault(rc.name, List.of()).stream().anyMatch(br -> (br.name() + br.desc()).equals(signature))
            || mod.renamedDecl.entrySet().stream().anyMatch(e -> e.getKey().startsWith(rc.name + ".") && (e.getValue()[0] + e.getValue()[1]).equals(signature));
        if (taken) {
            return false; // the class already has a method with the new signature
        }
        Type result = Type.getReturnType(mapped), wanted = Type.getReturnType(moved[2]);
        if (!result.equals(wanted) && (wanted.getSort() == Type.VOID || !usable(result, wanted))) {
            return false;
        }
        Type[] was = Type.getArgumentTypes(mapped), params = Type.getArgumentTypes(moved[2]);
        boolean[] unread = unread(mod, rc.name, m.name, m.desc, (m.flags & Opcodes.ACC_STATIC) != 0, was.length);
        int[] from = convert(params, was, unread);
        int access = (rc.flags & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : m.flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        if (from != null) {
            mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, access | Opcodes.ACC_SYNTHETIC, null));
            return true;
        }
        // Parameters that changed type: each old one from a new one, through what the game's own overrides use for it.
        if ((rc.flags & Opcodes.ACC_INTERFACE) != 0 || (m.flags & Opcodes.ACC_ABSTRACT) != 0 || mod.bytes.get(rc.name) == null
            || (new ClassReader(mod.bytes.get(rc.name)).readShort(6) & 0xFFFF) < Opcodes.V1_6) {
            return false; // a moved body needs stack map frames to carry over
        }
        int[] paired = pair(params, was);
        String[][] via = retype.conversions(gameOwner, m.name, m.desc, moved, paired);
        from = new int[was.length];
        String[][] steps = new String[was.length][];
        for (int i = 0; i < was.length; i++) {
            if (paired[i] >= 0) {
                from[i] = paired[i];
            } else if (via != null && via[i] != null) {
                from[i] = Integer.parseInt(via[i][0]);
                steps[i] = Arrays.copyOfRange(via[i], 1, via[i].length);
            } else if (unread != null && unread[i] || onlyPassed(mod, rc.name, m, slots(was)[i])) {
                from[i] = -1;
            } else {
                return false;
            }
        }
        mod.bridges.computeIfAbsent(rc.name, k -> new ArrayList<>()).add(new Bridge(moved[1], moved[2], m.name, m.desc, from, m.flags & ~Opcodes.ACC_ABSTRACT, steps));
        return true;
    }

    /**
     * Whether a mod method only ever passes the parameter in slot to game methods whose parameters changed: then it needs
     * no value of its own in a moved body, because each such call is given what the game passes now instead (Sites.given;
     * keyPressed(key, scan, mods) only passing scan and mods on to super.keyPressed). Read as GameDiff reads bodies.
     */
    boolean onlyPassed(Mod mod, String owner, GameDiff.RawMeth m, int slot) {
        byte[] bytes = mod.bytes.get(owner);
        GameDiff.Origin o = new GameDiff.Origin(bytes, Map.of(), null, new GameDiff.Names(null), new HashMap<>());
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            if (mm.methodName().stringValue().equals(m.name) && mm.methodType().stringValue().equals(m.desc)) {
                GameDiff.Body body = new GameDiff.Decoder(o, owner).run(mm);
                if (body == null) {
                    return false;
                }
                int[] seen = new int[1];
                for (GameDiff.Node s : body.statements) {
                    if (!passedOnly(mod, s, slot, seen)) {
                        return false;
                    }
                }
                return seen[0] > 0;
            }
        }
        return false;
    }

    boolean passedOnly(Mod mod, GameDiff.Node n, int slot, int[] seen) {
        for (GameDiff.Node k : n.kids) {
            if (k.op == 'p' && Integer.valueOf(slot).equals(k.value)) {
                seen[0]++;
                if (!(n.op == 'v' || n.op == 's') || n.ref == null || n.ref.kind() != 'm') {
                    return false;
                }
                Decl decl = n.ref.owner().startsWith("[") ? null : declaringOld(mod, n.ref.owner(), 'm', n.ref.name(), n.ref.desc());
                if (decl == null || !decl.game() || route.method(decl.owner(), n.ref.name(), n.ref.desc())[2].equals(desc(n.ref.desc()))) {
                    return false;
                }
            } else if (!passedOnly(mod, k, slot, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * For each parameter of to, the parameter of from with the same type: a type found once on each side wherever it
     * stands, or in order when both sides have it equally often; -2 where there is none.
     */
    static int[] pair(Type[] from, Type[] to) {
        int[] out = new int[to.length];
        for (int j = 0; j < to.length; j++) {
            out[j] = -2;
            List<Integer> same = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (from[i].equals(to[j])) {
                    same.add(i);
                }
            }
            long wanted = Arrays.stream(to).filter(to[j]::equals).count();
            if (!same.isEmpty() && same.size() == wanted) {
                int nth = (int) Arrays.stream(to, 0, j).filter(to[j]::equals).count();
                out[j] = same.get(nth);
            }
        }
        return out;
    }

    /**
     * For each parameter of to (new names), the parameter of from that supplies it, or -1 where unread says to's method
     * never reads it; null when one cannot be supplied. A type found once on each side is taken wherever it stands; a
     * type that appears several times pairs up in order only when both sides have it as often. A parameter with no
     * match takes the one parameter of from whose type now serves as it (a subtype).
     */
    int[] convert(Type[] from, Type[] to, boolean[] unread) {
        int[] out = pair(from, to);
        boolean[] taken = new boolean[from.length];
        for (int j = 0; j < to.length; j++) {
            if (out[j] >= 0) {
                taken[out[j]] = true;
            }
        }
        for (int j = 0; j < to.length; j++) {
            if (out[j] != -2) {
                continue;
            }
            List<Integer> fits = new ArrayList<>();
            for (int i = 0; i < from.length; i++) {
                if (!taken[i] && usable(from[i], to[j])) {
                    fits.add(i);
                }
            }
            if (fits.size() == 1) {
                out[j] = fits.get(0);
                taken[fits.get(0)] = true;
            } else if (unread != null && unread[j]) {
                out[j] = -1;
            } else {
                return null;
            }
        }
        return out;
    }

    /** Which of a mod method's first n parameters its body never reads; null when it has no body here. */
    boolean[] unread(Mod mod, String owner, String name, String desc, boolean isStatic, int n) {
        byte[] bytes = mod.bytes.get(owner);
        if (bytes == null) {
            return null;
        }
        Type[] params = Type.getArgumentTypes(desc);
        Map<Integer, Integer> slots = new HashMap<>(); // slot -> parameter
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < n && i < params.length; i++) {
            slots.put(slot, i);
            slot += params[i].getSize();
        }
        boolean[] out = new boolean[n];
        boolean[] seen = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
                if (!mname.equals(name) || !mdesc.equals(desc) || (access & Opcodes.ACC_ABSTRACT) != 0) {
                    return null;
                }
                seen[0] = true;
                Arrays.fill(out, true);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode == Opcodes.RET) {
                            read(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        read(var);
                    }

                    void read(int var) {
                        Integer p = slots.get(var);
                        if (p != null) {
                            out[p] = false;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return seen[0] ? out : null;
    }

    /**
     * A concrete mod class must implement every abstract method of its game supertypes in the new release (1.21.2 added
     * Recipe.placementInfo()); one it does not is reported, never filled in.
     */
    void abstracts(Mod mod, GameDiff.RawCls rc) {
        if ((rc.flags & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) {
            return;
        }
        Set<String> written = new HashSet<>();
        Deque<String> game = new ArrayDeque<>();
        for (GameDiff.RawCls c = rc; c != null; c = c.sup == null ? null : mod.classes.get(c.sup)) {
            for (GameDiff.RawMeth m : c.methods.values()) {
                String[] renamed = mod.renamedDecl.get(c.name + "." + m.name + m.desc);
                written.add(renamed == null ? m.name + desc(m.desc) : renamed[0] + renamed[1]);
            }
            mod.bridges.getOrDefault(c.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
            if (c.sup != null && !mod.classes.containsKey(c.sup)) {
                game.add(cls(c.sup));
            }
            for (String i : c.ifaces) {
                game.add(mod.classes.containsKey(i) ? i : cls(i));
            }
        }
        // A mod interface's own default methods count as written; its game parents are searched too.
        Set<String> seen = new HashSet<>();
        List<GameDiff.Cls> supers = new ArrayList<>();
        while (!game.isEmpty()) {
            String c = game.poll();
            if (!seen.add(c)) {
                continue;
            }
            GameDiff.RawCls own = mod.classes.get(c);
            if (own != null) {
                for (GameDiff.RawMeth m : own.methods.values()) {
                    if ((m.flags & Opcodes.ACC_ABSTRACT) == 0) {
                        String[] renamed = mod.renamedDecl.get(own.name + "." + m.name + m.desc);
                        written.add(renamed == null ? m.name + desc(m.desc) : renamed[0] + renamed[1]);
                    }
                }
                mod.bridges.getOrDefault(own.name, List.of()).forEach(br -> written.add(br.name() + br.desc()));
                own.ifaces.forEach(i -> game.add(mod.classes.containsKey(i) ? i : cls(i)));
                continue;
            }
            GameDiff.Cls k = b.classes.get(c);
            if (k == null) {
                continue;
            }
            supers.add(k);
            if (k.sup != null) {
                game.add(k.sup);
            }
            game.addAll(k.ifaces);
        }
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.startsWith("<")) {
                    continue;
                }
                written.add((m.flags & Opcodes.ACC_ABSTRACT) == 0 ? m.name + m.desc : "");
            }
        }
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !written.contains(m.name + m.desc) && !OBJECT.contains(m.name + m.desc) && !generic(supers, m)
                    && !alreadyAbstract(k, m)) {
                    mod.unlinked.add("abstract method not written: " + Exposure.dotted(k.name) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
                }
            }
        }
    }

    static final Set<String> OBJECT = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;");

    /**
     * Whether a game class among supers implements an abstract method through a generic parameter: a concrete method of
     * that name whose parameters are narrower (ShapedRecipe.matches(CraftingInput, Level) for Recipe.matches(RecipeInput,
     * Level)). javac wrote the bridge with the erased signature into that class, and the snapshot leaves bridges out.
     */
    boolean generic(List<GameDiff.Cls> supers, GameDiff.Meth abs) {
        Type[] want = Type.getArgumentTypes(abs.desc);
        for (GameDiff.Cls k : supers) {
            for (GameDiff.Meth m : k.methods.values()) {
                if (m.name.equals(abs.name) && (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0 && narrows(want, m.desc, abs.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parameters each the same as or narrower than want, and a result that serves as abs's. */
    boolean narrows(Type[] want, String desc, String abs) {
        Type[] got = Type.getArgumentTypes(desc);
        if (got.length != want.length || !usable(Type.getReturnType(desc), Type.getReturnType(abs))) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (!usable(got[i], want[i])) {
                return false;
            }
        }
        return true;
    }

    /** An @Accessor or @Invoker names a target member (its value, or the name Mixin infers); a renamed one is written into the value. */
    void accessor(Mod mod, String mixin, List<String> targets, GameDiff.RawMeth m, String[] spec) {
        boolean invoker = spec[0].equals("Invoker");
        String inferred = m.name.replaceFirst(invoker ? "^(call|invoke|new|create)" : "^(get|is|set)", "");
        if (!inferred.isEmpty()) {
            inferred = Character.toLowerCase(inferred.charAt(0)) + inferred.substring(1);
        }
        String name = spec[1] != null ? spec[1] : inferred;
        if (name.equals("<init>") || invoker && spec[1] == null && m.name.matches("^(new|create).*")) {
            return; // a constructor invoker: its descriptor follows the class, which renames already cover
        }
        String key = mixin + "." + m.name + m.desc;
        Type[] args = Type.getArgumentTypes(m.desc);
        String fieldDesc = Type.getReturnType(m.desc) == Type.VOID_TYPE ? (args.length > 0 ? args[args.length - 1].getDescriptor() : null) : Type.getReturnType(m.desc).getDescriptor();
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            if (!invoker) {
                List<String> names = spec[1] != null ? List.of(name) : List.of(name, name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
                for (String n : names) {
                    GameDiff.Cls decl = Exposure.declaring(a, t, 'f', n, null);
                    if (decl == null) {
                        continue;
                    }
                    String[] moved = route.field(decl.name, n);
                    GameDiff.Cls now = Exposure.declaring(b, cls(t), 'f', moved[1], null);
                    if (now == null || fieldDesc != null && !now.fields.get(moved[1]).desc.equals(desc(fieldDesc))) {
                        mod.unlinked.add("mixin accessor target gone: " + Exposure.dotted(t) + "." + n + " (in " + Exposure.dotted(mixin) + ")");
                    } else if (!moved[1].equals(n)) {
                        mod.accessorTargets.put(key, moved[1]);
                        mod.change("mixin accessor renamed");
                    }
                    return;
                }
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'm', name, m.desc);
            if (decl == null) {
                continue;
            }
            String[] moved = route.method(decl.name, name, m.desc);
            String mapped = desc(m.desc);
            if (Exposure.declaring(b, cls(t), 'm', name, mapped) != null) {
                return;
            }
            if (moved[2].equals(mapped) && Exposure.declaring(b, cls(t), 'm', moved[1], moved[2]) != null) {
                mod.accessorTargets.put(key, moved[1]);
                mod.change("mixin invoker renamed");
            } else {
                mod.unlinked.add("mixin invoker target gone: " + Exposure.dotted(t) + "." + name + m.desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return;
        }
    }

    // ---- Rewriting one class.

    /**
     * A helper of the mod (class.name+desc, old names) moved whole to take its parameter at want[0] as the new type
     * want[1] (new names), the old value worked out first through the steps want[2], as the moved body that calls it
     * worked it out: a Bridge with via, like an override's. False when it cannot move (no stack map frames).
     */
    boolean twin(Mod mod, String key, Object[] want) {
        String owner = key.substring(0, key.indexOf('.')), member = key.substring(key.indexOf('.') + 1);
        GameDiff.RawCls rc = mod.classes.get(owner);
        GameDiff.RawMeth m = rc == null ? null : rc.methods.get(member);
        byte[] bytes = mod.bytes.get(owner);
        if (m == null || bytes == null || (new ClassReader(bytes).readShort(6) & 0xFFFF) < Opcodes.V1_6) {
            return false;
        }
        int k = (Integer) want[0];
        Type[] was = Type.getArgumentTypes(m.desc), now = new Type[was.length];
        int[] from = new int[was.length];
        String[][] via = new String[was.length][];
        for (int i = 0; i < was.length; i++) {
            now[i] = i == k ? Type.getType((String) want[1]) : Type.getType(desc(was[i].getDescriptor()));
            from[i] = i;
        }
        via[k] = (String[]) want[2];
        String newDesc = Type.getMethodDescriptor(Type.getType(desc(Type.getReturnType(m.desc).getDescriptor())), now);
        if (rc.methods.containsKey(m.name + oldDesc(newDesc))) {
            return false;
        }
        mod.bridges.computeIfAbsent(owner, x -> new ArrayList<>()).add(new Bridge(m.name, newDesc, m.name, m.desc, from, m.flags, via));
        mod.change("helper moved to what the game passes now");
        return true;
    }

    /**
     * rewrite, and when a body moved to a game method's new parameter types cannot be written (code the stack tracking
     * cannot follow), the class again without moving it: that override is reported, so the mod is not said to link.
     */
    byte[] rewriteOrReport(Mod mod, byte[] bytes) {
        try {
            return rewrite(mod, bytes);
        } catch (RuntimeException failed) {
            List<Bridge> own = mod.bridges.get(new ClassReader(bytes).getClassName());
            if (own == null || own.stream().noneMatch(br -> br.via() != null)) {
                throw failed;
            }
            for (Bridge br : own) {
                if (br.via() != null) {
                    mod.unlinked.add("override not moved to the game's new parameter types: " + Exposure.dotted(new ClassReader(bytes).getClassName()) + "." + br.target() + br.targetDesc() + " (" + failed.getClass().getSimpleName() + ")");
                }
            }
            own.removeIf(br -> br.via() != null);
            return rewrite(mod, bytes);
        }
    }

    byte[] rewrite(Mod mod, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        ClassVisitor remap = new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, classMap));
        String[] self = new String[1];
        ClassVisitor sites = new ClassVisitor(Opcodes.ASM9, remap) {
            List<String> targets = List.of();
            List<String> callers = List.of();

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                self[0] = name;
                mod.self = name;
                mod.selfInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                mod.selfVersion = version & 0xFFFF;
                mod.added.clear();
                targets = mod.mixinTargets.getOrDefault(name, List.of());
                // A mixin's code runs inside each target class, so access is checked from there.
                callers = targets.isEmpty() ? List.of(name) : targets.stream().map(JarPort.this::cls).toList();
                // A target in another mod is unknown here. Mixin makes a mixin extend its target's superclass, so the
                // mixin's own parents stand in for the target's (protected members of the game class they extend).
                if (targets.stream().anyMatch(t -> !mod.classes.containsKey(t) && !a.classes.containsKey(t))) {
                    callers = new ArrayList<>(callers);
                    callers.add(name);
                }
                String where = Exposure.dotted(name);
                if (superName != null) {
                    types(mod, "L" + superName + ";", where);
                }
                for (String i : interfaces == null ? new String[0] : interfaces) {
                    types(mod, "L" + i + ";", where);
                }
                types(mod, signature, where);
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                AnnotationVisitor av = super.visitAnnotation(desc, visible);
                if (desc.equals(SUBSCRIBER) && av != null && mod.toModBus.contains(self[0])) {
                    mod.change("event subscriber put on the mod bus");
                    return new AnnotationVisitor(Opcodes.ASM9, av) {
                        @Override
                        public void visitEnd() {
                            super.visitEnum("bus", "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;", "MOD");
                            super.visitEnd();
                        }
                    };
                }
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || av == null) {
                    return av;
                }
                // @Mixin(targets = "a.b.C") names classes as text, which the class renamer does not touch.
                return new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        AnnotationVisitor inner = super.visitArray(name);
                        return !"targets".equals(name) || inner == null ? inner : new AnnotationVisitor(Opcodes.ASM9, inner) {
                            @Override
                            public void visit(String n, Object value) {
                                super.visit(n, value instanceof String s ? className(s) : value);
                            }
                        };
                    }

                    @Override
                    public void visit(String name, Object value) {
                        super.visit(name, "targets".equals(name) && value instanceof String s ? className(s) : value);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                types(mod, desc, Exposure.dotted(self[0]) + "." + name);
                String[] shadow = mod.renamedDecl.get(self[0] + "." + name);
                String newName = shadow == null ? name : shadow[0];
                FieldVisitor fv = super.visitField(access, newName, desc, signature, value);
                return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return mixinStrings(mod, targets, super.visitAnnotation(d, visible), null);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                String where = Exposure.dotted(self[0]) + "." + name;
                types(mod, desc, where);
                String[] renamed = mod.renamedDecl.get(self[0] + "." + name + desc);
                String newName = name, newDesc = desc;
                if (renamed != null) {
                    newName = renamed[0];
                    newDesc = oldDesc(renamed[1]);
                }
                if (!mod.kept.contains(name + desc)
                    && mod.bridges.getOrDefault(self[0], List.of()).stream().anyMatch(br -> br.via() != null && br.target().equals(name) && br.targetDesc().equals(desc))) {
                    return null; // its body moves to the game method's new signature (visitEnd)
                }
                String key = self[0] + "." + name + desc;
                Adapter adapter = mod.adapters.get(key);
                String originalSignature = signature;
                if (adapter != null) {
                    // The handler keeps its name and annotations with the new parameters; its body moves to name$mmt.
                    newDesc = oldDesc(adapter.desc());
                    signature = null;
                }
                boolean moved = mod.split.getOrDefault(self[0], List.of()).stream().anyMatch(l -> l.name().equals(name) && l.desc().equals(desc));
                MethodVisitor mv = super.visitMethod(moved ? access & ~Opcodes.ACC_PRIVATE : access, newName, newDesc, signature, exceptions);
                if (moved && mv != null) {
                    mv = new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                            return d.equals(LISTENER) ? null : super.visitAnnotation(d, visible);
                        }
                    };
                }
                int locals = mod.maxLocals.getOrDefault(key, 0);
                if (adapter != null && mv != null) {
                    int body = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                    mv = new Split(mv, super.visitMethod(body, name + "$mmt", desc, originalSignature, exceptions), self[0], name, desc, access, adapter, mod.selfInterface);
                    mod.change("mixin handler re-declared for its target's new parameters");
                }
                return mv == null ? null : new Sites(mod, mv, locals, targets, callers, key, where);
            }

            @Override
            public void visitEnd() {
                for (Bridge br : mod.bridges.getOrDefault(self[0], List.of())) {
                    if (br.via() != null) {
                        moved(mod, bytes, self[0], br, remap);
                    } else {
                        bridgeCode(super.visitMethod(br.access(), br.name(), oldDesc(br.desc()), null, null), self[0], mod.selfInterface, br);
                    }
                }
                for (Added m : mod.added.values()) {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, m.name(), m.desc(), null, null);
                    mv.visitCode();
                    m.code().steps.forEach(step -> step.accept(mv));
                    mv.visitMaxs(m.code().max, m.locals());
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        };
        reader.accept(sites, 0);
        return writer.toByteArray();
    }

    /** A class named as text ("a.b.C" or "a/b/C") in the new release's names, in the same spelling. */
    String className(String text) {
        boolean dotted = text.indexOf('/') < 0;
        String renamed = cls(text.replace('.', '/'));
        return dotted ? renamed.replace('/', '.') : renamed;
    }

    /** A mixin's @Shadow field named as its target's field is now named; other fields keep their names. */
    String shadowField(Mod mod, String mixin, List<String> targets, String name, String desc) {
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'f', name, null);
            if (decl == null) {
                continue;
            }
            String[] moved = route.field(decl.name, name);
            String newDesc = desc(desc);
            GameDiff.Cls now = Exposure.declaring(b, cls(t), 'f', moved[1], null);
            if (now != null && now.fields.get(moved[1]).desc.equals(newDesc)) {
                if (!moved[1].equals(name)) {
                    mod.renamedDecl.put(mixin + "." + name, new String[] { moved[1], newDesc });
                    mod.change("mixin shadow renamed");
                }
                return moved[1];
            }
            mod.unlinked.add("mixin field gone: " + Exposure.dotted(t) + "." + name + " (in " + Exposure.dotted(mixin) + ")");
            return name;
        }
        return name;
    }

    /** A mixin method that shadows a target method (@Shadow, or an abstract one) takes the target's new name. */
    String[] shadowMethod(Mod mod, String mixin, List<String> targets, String name, String desc, int access) {
        String bare = name.startsWith("shadow$") ? name.substring(7) : name;
        for (String t : targets) {
            if (!a.classes.containsKey(t)) {
                continue;
            }
            GameDiff.Cls decl = Exposure.declaring(a, t, 'm', bare, desc);
            if (decl == null) {
                continue;
            }
            String[] moved = route.method(decl.name, bare, desc);
            String mapped = desc(desc);
            if (Exposure.declaring(b, cls(t), 'm', bare, mapped) != null) {
                return null;
            }
            if (Exposure.declaring(b, cls(t), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                String prefix = name.substring(0, name.length() - bare.length());
                mod.renamedDecl.put(mixin + "." + name + desc, new String[] { prefix + moved[1], moved[2] });
                mod.change("mixin method renamed");
                return new String[] { prefix + moved[1] };
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0 || (access & Opcodes.ACC_PRIVATE) == 0) {
                mod.unlinked.add("mixin method gone: " + Exposure.dotted(t) + "." + bare + desc + " (in " + Exposure.dotted(mixin) + ")");
            }
            return null;
        }
        return null;
    }

    static final Set<String> MEMBER_KEYS = Set.of("method", "target", "value");
    static final Map<String, String> ACCESSORS = Map.of(
        "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Accessor",
        "Lorg/spongepowered/asm/mixin/gen/Invoker;", "Invoker"
    );

    /** Mixin annotation strings that name target members ("method", @At "target"): moved to the new names when they link, else reported. */
    AnnotationVisitor mixinStrings(Mod mod, List<String> targets, AnnotationVisitor av, String handler) {
        if (av == null || targets.isEmpty()) {
            return av;
        }
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override
            public void visit(String name, Object value) {
                super.visit(name, value instanceof String s && name != null && MEMBER_KEYS.contains(name) ? member(mod, targets, name, s, handler) : value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor inner = super.visitArray(name);
                if (name == null || !MEMBER_KEYS.contains(name)) {
                    return mixinStrings(mod, targets, inner, handler);
                }
                return new AnnotationVisitor(Opcodes.ASM9, inner) {
                    @Override
                    public void visit(String n, Object value) {
                        super.visit(n, value instanceof String s ? member(mod, targets, name, s, handler) : value);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        return mixinStrings(mod, targets, super.visitAnnotation(n, d), handler);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return mixinStrings(mod, targets, super.visitAnnotation(name, desc), handler);
            }
        };
    }

    static final Pattern REFERENCE = Pattern.compile("([\\w.$]+)::(\\w+)");
    static final Pattern STATIC_CALL = Pattern.compile("([\\w.$]+)\\.(\\w+)\\(\\)");
    static final Pattern STATIC_FIELD = Pattern.compile("([\\w.$]+)\\.(\\w+)");
    static final Pattern MEMBER = Pattern.compile("(?:L([^;]+);)?([\\w$<>]+)(?:(\\(.*)|:(.+))?");

    /**
     * One member string of a mixin annotation in the new release's names, or as written when it cannot be read or does
     * not link; handler is the class.method+desc whose annotation it is in, where a retarget may apply.
     */
    String member(Mod mod, List<String> targets, String key, String text, String handler) {
        String moved = handler == null ? null : mod.retargets.get(handler + " " + text);
        if (moved != null) {
            mod.change("mixin injection moved to the target's new signature");
            return moved;
        }
        Found f = find(targets, key, text);
        if (f.report() != null) {
            mod.unlinked.add(f.report());
        }
        if (f.change() != null) {
            mod.change(f.change());
        }
        return f.text();
    }

    /**
     * What a mixin annotation string names in the new release. kind: 'f' or 'm' when it links (owner, name and desc in
     * new names; a field's desc is null when the string gives none), 's' when the method only took other parameters
     * (desc its new descriptor, null when that is not known; changed the string naming it), 0 otherwise. text is what
     * is written when nothing is moved, report what is reported then.
     */
    record Found(String text, String report, String change, char kind, String owner, String name, String desc, String changed) {}

    static Found keep(String text) {
        return new Found(text, null, null, (char) 0, null, null, null, null);
    }

    Found find(List<String> targets, String key, String text) {
        if (text.contains("*") || text.contains(" ") || text.isEmpty() || key.equals("value") && !text.contains("(") && !text.contains(";")) {
            return keep(text); // wildcards, @At("HEAD") and friends
        }
        Matcher m = MEMBER.matcher(text);
        if (!m.matches()) {
            return keep(text);
        }
        String owner = m.group(1), name = m.group(2), mdesc = m.group(3), fdesc = m.group(4);
        if (name.equals("<init>") || name.equals("<clinit>")) {
            return mdesc == null ? keep(text) : new Found((owner == null ? "" : "L" + cls(owner) + ";") + name + desc(mdesc), null, null, 'm', owner == null ? null : cls(owner), name, desc(mdesc), null);
        }
        if (owner != null && !a.classes.containsKey(owner) && !b.classes.containsKey(owner) && mdesc != null) {
            return new Found(text, null, null, 'm', owner, name, mdesc, null); // the JDK's or a library's: the same in both
        }
        List<String> owners = owner != null ? List.of(owner) : targets;
        for (String o : owners) {
            if (!a.classes.containsKey(o)) {
                continue;
            }
            if (fdesc != null || (mdesc == null && Exposure.declaring(a, o, 'f', name, null) != null && owner != null)) {
                GameDiff.Cls decl = Exposure.declaring(a, o, 'f', name, null);
                if (decl == null) {
                    continue;
                }
                String[] moved = route.field(decl.name, name);
                if (Exposure.declaring(b, cls(o), 'f', moved[1], null) == null) {
                    return new Found(text, "mixin target gone: " + Exposure.dotted(o) + "." + name, null, (char) 0, null, null, null, null);
                }
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (fdesc == null ? "" : ":" + desc(fdesc)), null,
                    moved[1].equals(name) ? null : "mixin target renamed", 'f', cls(o), moved[1], fdesc == null ? null : desc(fdesc), null);
            }
            List<String> descs = new ArrayList<>();
            GameDiff.Cls holder = a.classes.get(o);
            for (GameDiff.Cls c = holder; c != null && descs.isEmpty(); c = c.sup == null ? null : a.classes.get(c.sup)) {
                for (GameDiff.Meth gm : c.methods.values()) {
                    if (gm.name.equals(name) && (mdesc == null || gm.desc.equals(mdesc))) {
                        descs.add(gm.desc);
                    }
                }
            }
            if (descs.isEmpty()) {
                continue;
            }
            String d = descs.get(0);
            GameDiff.Cls decl = Exposure.declaring(a, o, 'm', name, d);
            String[] moved = route.method(decl == null ? o : decl.name, name, d);
            String mapped = desc(d);
            if (Exposure.declaring(b, cls(o), 'm', name, mapped) != null) {
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + name + (mdesc == null ? "" : mapped), null, null, 'm', cls(o), name, mapped, null);
            }
            if (Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                return new Found((owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (mdesc == null ? "" : moved[2]), null, "mixin target renamed", 'm', cls(o), moved[1], moved[2], null);
            }
            List<GameDiff.Meth> sameName = named(b, cls(o), moved[1]);
            String report = "mixin target " + (sameName.isEmpty() ? "gone" : "changed signature") + ": " + Exposure.dotted(o) + "." + name + d;
            if (sameName.isEmpty()) {
                return new Found(text, report, null, (char) 0, null, null, null, null);
            }
            // Only its parameters changed: the route map's new signature, or the one method of that name on both sides.
            GameDiff.Meth now = !moved[2].equals(mapped) && Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]) != null
                ? Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]).methods.get(moved[1] + moved[2])
                : sameName.size() == 1 && named(a, o, name).size() == 1 ? sameName.get(0) : null;
            String prefix = owner == null ? "" : "L" + cls(o) + ";";
            return now == null
                ? new Found(text, report, null, 's', cls(o), moved[1], null, null)
                : new Found(text, report, null, 's', cls(o), now.name, now.desc, prefix + now.name + (mdesc == null ? "" : now.desc));
        }
        return keep(text);
    }

    /** Every method of that name a call on owner reaches through its superclasses in one release. */
    static List<GameDiff.Meth> named(GameDiff.Snap s, String owner, String name) {
        List<GameDiff.Meth> out = new ArrayList<>();
        for (GameDiff.Cls c = s.classes.get(owner); c != null; c = c.sup == null ? null : s.classes.get(c.sup)) {
            for (GameDiff.Meth gm : c.methods.values()) {
                if (gm.name.equals(name) && out.stream().noneMatch(x -> x.desc.equals(gm.desc))) {
                    out.add(gm);
                }
            }
        }
        return out;
    }

    /**
     * A mixin handler's @Inject whose target method only took other parameters is moved to the new signature when Mixin
     * will accept the handler there and its injection points are still in the new method: every @At is HEAD, RETURN or
     * TAIL, or names a call or field the new method's code still uses. A handler that takes no target arguments (only
     * its CallbackInfo) keeps its body; one that takes them is re-declared with the new parameters and calls the original
     * with the arguments it had (see convert). One that captures locals, or a target whose result type changed, stays
     * reported. An @At target of an @Inject that only took other parameters is moved when every method injected into
     * still calls the new one: an @Inject's handler takes the target method's arguments, not the call's. Other injectors
     * (@Redirect, @ModifyArg, @WrapOperation...) take the call's arguments and stay reported.
     */
    void retarget(Mod mod, String key, List<String> targets, GameDiff.RawMeth handler, Injector inj) {
        List<Found> into = inj.methods().stream().map(s -> find(targets, "method", s)).toList();
        List<Found> ats = inj.ats().stream().map(at -> at[1] == null ? null : find(targets, "target", at[1])).toList();
        boolean moving = into.stream().anyMatch(f -> f.kind() == 's');
        if (moving) {
            Type[] params = Type.getArgumentTypes(handler.desc);
            int captured = params.length - 1;
            if (captured < 0 || params[captured].getSort() != Type.OBJECT || !CALLBACKS.contains(params[captured].getInternalName())) {
                return; // locals captured after the CallbackInfo, or no CallbackInfo at all
            }
            if (captured > 0 && into.size() != 1) {
                return;
            }
            for (int i = 0; i < into.size(); i++) {
                Found f = into.get(i);
                if (f.kind() == 's') {
                    String old = inj.methods().get(i);
                    GameDiff.Meth before = oldMethod(targets, old);
                    GameDiff.Meth after = f.desc() == null ? null : b.method(f.owner(), f.name(), f.desc());
                    if (before == null || after == null || !Type.getReturnType(desc(before.desc)).equals(Type.getReturnType(after.desc))
                        || ((before.flags ^ after.flags) & Opcodes.ACC_STATIC) != 0) {
                        return;
                    }
                }
            }
            if (!pointsKept(targets, inj, into, ats)) {
                return;
            }
            Found f = into.get(0);
            if (captured > 0) {
                GameDiff.Meth before = oldMethod(targets, inj.methods().get(0));
                Type[] now = Type.getArgumentTypes(f.desc());
                Type[] was = Arrays.copyOf(Type.getArgumentTypes(desc(handler.desc)), captured);
                if (!Arrays.equals(was, Arrays.copyOf(Type.getArgumentTypes(desc(before.desc)), captured))) {
                    return; // not the target's own arguments
                }
                boolean[] unread = unread(mod, key.substring(0, key.indexOf('.')), handler.name, handler.desc, (handler.flags & Opcodes.ACC_STATIC) != 0, captured);
                int[] from = convert(now, was, unread);
                String[][] via = new String[captured][];
                if (from == null) {
                    // Arguments that changed type: worked out from the new ones as the game's own overrides do (Retype).
                    int[] paired = pair(now, was);
                    String[][] learned = retype.conversions(before.owner.name, before.name, before.desc, new String[] { f.owner(), f.name(), f.desc() }, paired);
                    from = new int[captured];
                    for (int i = 0; i < captured; i++) {
                        if (paired[i] >= 0) {
                            from[i] = paired[i];
                        } else if (learned != null && learned[i] != null) {
                            from[i] = Integer.parseInt(learned[i][0]);
                            via[i] = Arrays.copyOfRange(learned[i], 1, learned[i].length);
                        } else if (unread != null && unread[i]) {
                            from[i] = -1;
                        } else {
                            return;
                        }
                    }
                }
                Type[] desc = Arrays.copyOf(now, now.length + 1);
                desc[now.length] = Type.getType(desc(params[captured].getDescriptor()));
                if (Arrays.stream(via).anyMatch(Objects::nonNull) && (mod.bytes.get(key.substring(0, key.indexOf('.'))) != null)
                    && (new ClassReader(mod.bytes.get(key.substring(0, key.indexOf('.')))).readShort(6) & 0xFFFF) >= Opcodes.V1_6) {
                    // An argument that changed type: the handler's body moves whole to the new parameters, as an override's
                    // does, so the calls it makes on that argument get what the game passes now (Sites.given, onOrigin).
                    int[] all = Arrays.copyOf(from, captured + 1);
                    all[captured] = now.length;
                    mod.bridges.computeIfAbsent(key.substring(0, key.indexOf('.')), k -> new ArrayList<>())
                        .add(new Bridge(handler.name, Type.getMethodDescriptor(Type.VOID_TYPE, desc), handler.name, handler.desc, all, handler.flags, Arrays.copyOf(via, captured + 1)));
                } else {
                    mod.adapters.put(key, new Adapter(Type.getMethodDescriptor(Type.VOID_TYPE, desc), from, captured, via));
                }
            }
            for (int i = 0; i < into.size(); i++) {
                if (into.get(i).kind() == 's') {
                    mod.retargets.put(key + " " + inj.methods().get(i), into.get(i).changed());
                }
            }
        }
        // @At targets that only took other parameters, in methods that are themselves known in the new release.
        List<GameDiff.Meth[]> methods = enclosing(targets, inj, into);
        for (int i = 0; i < ats.size(); i++) {
            Found at = ats.get(i);
            int k = i;
            if (at != null && at.kind() == 's' && at.desc() != null && methods != null && methods.stream().allMatch(m -> pointKept(inj.ats().get(k), at, m))) {
                mod.retargets.put(key + " " + inj.ats().get(i)[1], at.changed());
            }
        }
    }

    /**
     * Each mixin class's config's injectors.defaultRequire (0 when the config gives none, as Mixin takes it), read from
     * every mixin config in the jar: a JSON file naming a package and its mixin classes.
     */
    static void requires(Mod mod, Map<String, byte[]> entries) {
        for (var e : entries.entrySet()) {
            if (!e.getKey().endsWith(".json") || e.getKey().contains("/lang/")) {
                continue;
            }
            Map<String, Object> config;
            try {
                config = Json.object(Json.parse(new String(e.getValue(), StandardCharsets.UTF_8)));
            } catch (RuntimeException notJson) {
                continue;
            }
            Object pkg = config.get("package");
            if (!(pkg instanceof String p)) {
                continue;
            }
            Object injectors = config.get("injectors");
            int require = injectors instanceof Map<?, ?> m && m.get("defaultRequire") instanceof Number n ? n.intValue() : 0;
            for (String list : List.of("mixins", "client", "server")) {
                for (Object c : config.get(list) instanceof List<?> l ? l : List.of()) {
                    if (c instanceof String name) {
                        mod.requires.put((p + "." + name).replace('.', '/'), require);
                    }
                }
            }
        }
    }

    /**
     * An injector into a method that still links, whose @At names a call or field that method made in the old release and
     * no longer makes in the new one (or not as often as its ordinal needs), finds no place there: Mixin scans 0 targets
     * and, with the require most mods set, stops the game (1.21.2's LoadingOverlay.render stopped calling
     * RenderSystem.blendFunc). Reported for every injector, not only the ones retarget moves.
     */
    static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

    /** How many loads (or stores) of a local of type t a method's own code makes (lambdas apart); -1 when it cannot be read. */
    int locals(GameCode code, GameDiff.Meth m, boolean store, Type t) {
        int[] counts = code == null ? null : code.locals(m.owner.name, m.name, m.desc);
        if (counts == null) {
            return -1;
        }
        int op = t.getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD);
        return counts[op];
    }

    void pointsGone(Mod mod, String mixin, List<String> targets, Injector inj, String handlerDesc) {
        for (String text : inj.methods()) {
            List<List<GameDiff.Meth>> into = selected(targets, text);
            if (into == null) {
                continue;
            }
            List<GameDiff.Meth> before = into.get(0), now = into.get(1);
            for (String[] point : inj.ats()) {
                String gone = null;
                int needed = point[2] == null ? 1 : Integer.parseInt(point[2]) + 1;
                if (point[0] != null && Set.of("LOAD", "STORE").contains(point[0]) && inj.kind().equals(MODIFY_VARIABLE) && point[2] != null) {
                    // @ModifyVariable at the nth load or store of a local of its type: the new method must still have
                    // that many of them at all (an upper bound, so a place it reports gone is gone).
                    Type local = Type.getReturnType(handlerDesc);
                    boolean store = point[0].equals("STORE");
                    int had = most(before, m -> locals(code(a), m, store, local)), has = most(now, m -> locals(code(b), m, store, local));
                    if (had >= needed && has >= 0 && has < needed) {
                        gone = point[0] + " #" + point[2] + " of a " + local.getClassName() + " local";
                    }
                } else if (point[0] != null && point[1] != null && Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(point[0])) {
                    Found at = find(targets, "target", point[1]);
                    Matcher m = MEMBER.matcher(point[1]);
                    if ((at.kind() != 'm' && at.kind() != 'f') || !m.matches()) {
                        continue;
                    }
                    int had = most(before, x -> uses(code(a), a, x, at.kind(), m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(4)));
                    int has = most(now, x -> uses(code(b), b, x, at.kind(), at.owner(), at.name(), at.desc()));
                    if (had >= needed && has >= 0 && has < needed) {
                        gone = Exposure.dotted(at.owner()) + "." + at.name() + (at.desc() == null ? "" : at.desc());
                    }
                }
                if (gone != null) {
                    // Mixin stops the game only where the injector requires a place (its require, or its config's
                    // defaultRequire); otherwise the hook is quiet, as an override the game no longer calls is.
                    int require = inj.require()[0] >= 0 ? inj.require()[0] : mod.requires.getOrDefault(mixin, 0);
                    String where = now.size() == 1 ? Exposure.dotted(now.get(0).owner.name) + "." + now.get(0).name + now.get(0).desc : text;
                    gone += " in " + where + " (in " + Exposure.dotted(mixin) + ")";
                    (require > 0 ? mod.unlinked : mod.silent).add((require > 0 ? "mixin injection point gone: " : "mixin injection finds no place: ") + gone);
                }
            }
        }
    }

    /** The most any of the methods gives (an injector finds its place if one of the methods it selects has it); -1 when one cannot be read. */
    static int most(List<GameDiff.Meth> methods, java.util.function.ToIntFunction<GameDiff.Meth> count) {
        int best = 0;
        for (GameDiff.Meth m : methods) {
            int n = count.applyAsInt(m);
            if (n < 0) {
                return -1;
            }
            best = Math.max(best, n);
        }
        return best;
    }

    /**
     * The methods a mixin "method" string selects in the targets, {old release's, new release's}, or null when it cannot
     * be read or selects nothing on a side. A plain string names one method; a regular expression ("/^(connect|m_1)$/", or
     * "name=/.../ desc=/.../") selects every method of the target classes whose name (and descriptor) it matches, as
     * Mixin selects them, so a name either release has is found.
     */
    List<List<GameDiff.Meth>> selected(List<String> targets, String text) {
        Matcher regex = Pattern.compile("^(?:name=)?/(.+?)/(?:\\s+desc=/(.+)/)?$").matcher(text.trim());
        if (!regex.matches()) {
            Found into = find(targets, "method", text);
            GameDiff.Meth before = oldMethod(targets, text);
            GameDiff.Meth now = into.kind() == 'm' && into.desc() != null ? b.method(into.owner(), into.name(), into.desc()) : null;
            return now == null || before == null ? null : List.of(List.of(before), List.of(now));
        }
        Pattern name, desc;
        try {
            name = Pattern.compile(regex.group(1));
            desc = regex.group(2) == null ? null : Pattern.compile(regex.group(2));
        } catch (PatternSyntaxException unreadable) {
            return null;
        }
        List<GameDiff.Meth> before = new ArrayList<>(), now = new ArrayList<>();
        for (String t : targets) {
            for (var side : List.of(new Object[] { a, t, before }, new Object[] { b, cls(t), now })) {
                GameDiff.Cls c = ((GameDiff.Snap) side[0]).classes.get((String) side[1]);
                for (GameDiff.Meth m : c == null ? List.<GameDiff.Meth>of() : c.methods.values()) {
                    if (name.matcher(m.name).find() && (desc == null || desc.matcher(m.desc).find())) {
                        @SuppressWarnings("unchecked") List<GameDiff.Meth> into = (List<GameDiff.Meth>) side[2];
                        into.add(m);
                    }
                }
            }
        }
        return before.isEmpty() || now.isEmpty() ? null : List.of(before, now);
    }

    /** Whether every @At of an injector still finds its place in the methods it injects into, in the new release. */
    boolean pointsKept(List<String> targets, Injector inj, List<Found> into, List<Found> ats) {
        List<GameDiff.Meth[]> methods = enclosing(targets, inj, into);
        if (methods == null) {
            return false;
        }
        for (int i = 0; i < ats.size(); i++) {
            String value = inj.ats().get(i)[0];
            if (Set.of("HEAD", "RETURN", "TAIL").contains(value)) {
                continue;
            }
            Found at = ats.get(i);
            if (!Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD").contains(value) || at == null || at.kind() == 0 || at.desc() == null) {
                return false;
            }
            int k = i;
            if (!methods.stream().allMatch(m -> pointKept(inj.ats().get(k), at, m))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an @At ({value, target, ordinal}; at its target in new names) finds its place in a method injected into
     * ({new, old}): the new method's own instructions use the member, and as often as the old one's did when the @At picks
     * one occurrence by its ordinal. A use inside a lambda does not count: Mixin injects into the method's own code.
     */
    boolean pointKept(String[] point, Found at, GameDiff.Meth[] method) {
        char kind = at.kind() == 's' ? 'm' : at.kind();
        int now = uses(code(b), b, method[0], kind, at.owner(), at.name(), at.desc());
        if (now < 1) {
            return false;
        }
        if (point[2] == null) {
            return true;
        }
        Matcher m = MEMBER.matcher(point[1]);
        if (!m.matches()) {
            return false;
        }
        String owner = m.group(1) != null ? m.group(1) : null;
        int before = uses(code(a), a, method[1], kind, owner, m.group(2), m.group(3) != null ? m.group(3) : m.group(4));
        return before == now && now > Integer.parseInt(point[2]);
    }

    /** The methods an injector's targets name, {new release, old release} each, when all are known on both sides. */
    List<GameDiff.Meth[]> enclosing(List<String> targets, Injector inj, List<Found> into) {
        List<GameDiff.Meth[]> out = new ArrayList<>();
        for (int i = 0; i < into.size(); i++) {
            Found f = into.get(i);
            GameDiff.Meth now = (f.kind() == 'm' || f.kind() == 's') && f.desc() != null ? b.method(f.owner(), f.name(), f.desc()) : null;
            GameDiff.Meth before = oldMethod(targets, inj.methods().get(i));
            if (now == null || before == null) {
                return null;
            }
            out.add(new GameDiff.Meth[] { now, before });
        }
        return out;
    }

    /**
     * How often a method's own instructions (lambdas apart) use a member (owner null: any owner), read from the release's
     * jar; -1 when they cannot be read.
     */
    int uses(GameCode code, GameDiff.Snap s, GameDiff.Meth m, char kind, String owner, String name, String desc) {
        List<String[]> refs = code == null ? null : code.refs(m.owner.name, m.name, m.desc);
        if (refs == null) {
            return -1;
        }
        GameDiff.Cls decl = owner == null ? null : Exposure.declaring(s, owner, kind, name, kind == 'f' ? null : desc);
        int n = 0;
        for (String[] r : refs) {
            if (r[0].charAt(0) != kind || !r[2].equals(name) || desc != null && !desc.equals(r[3])) {
                continue;
            }
            GameDiff.Cls at = Exposure.declaring(s, r[1], kind, r[2], kind == 'f' ? null : r[3]);
            if (owner == null || r[1].equals(owner) || decl != null && at != null && decl.name.equals(at.name)) {
                n++;
            }
        }
        return n;
    }

    GameCode newCode, oldCode; // read when an injection point is first checked; tests set them
    boolean codeTried;

    GameCode code(GameDiff.Snap s) {
        if (!codeTried && newCode == null && oldCode == null) {
            codeTried = true;
            try {
                newCode = GameCode.of(b.version);
                oldCode = GameCode.of(a.version);
            } catch (Exception unreadable) {
                newCode = null;
                oldCode = null;
            }
        }
        return s == b ? newCode : oldCode;
    }

    /**
     * One release's own code: a method's instructions in order, in Mojang names, without its lambdas. bytes gives a
     * class file by its name in the jar; pg maps the jar's names to Mojang's (null when they are Mojang's).
     */
    static final class GameCode {
        final java.util.function.Function<String, byte[]> bytes;
        final GameDiff.Names names;
        final Map<String, String> toJar = new HashMap<>();
        final Map<String, Optional<GameDiff.RawCls>> parsed = new HashMap<>();

        GameCode(java.util.function.Function<String, byte[]> bytes, VersionMap.Proguard pg) {
            this.bytes = bytes;
            names = new GameDiff.Names(pg);
            if (pg != null) {
                pg.classes.forEach((jar, named) -> toJar.put(named, jar));
            }
        }

        static GameCode of(String version) throws Exception {
            String json = GameDiff.versionJson(version);
            Path jar = GameDiff.verified(GameDiff.download(json, "client"), "client-" + version + ".jar");
            String[] mappings = GameDiff.download(json, "client_mappings");
            VersionMap.Proguard pg = mappings == null ? null
                : VersionMap.Proguard.parse(Files.readString(GameDiff.verified(mappings, "client-" + version + ".txt"), StandardCharsets.UTF_8));
            Map<String, byte[]> all = new HashMap<>();
            for (byte[] c : GameDiff.classes(jar, null)) {
                all.put(new ClassReader(c).getClassName(), c);
            }
            return new GameCode(all::get, pg);
        }

        GameDiff.RawCls raw(String jarName) {
            return parsed.computeIfAbsent(jarName, n -> Optional.ofNullable(bytes.apply(n)).map(GameDiff::parse)).orElse(null);
        }

        /** The class (jar names) that declares a member a reference names, or the owner when none of its classes does. */
        String declarer(String owner, String name, String desc, boolean field) {
            Deque<String> queue = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String c = queue.poll();
                GameDiff.RawCls rc = seen.add(c) ? raw(c) : null;
                if (rc == null) {
                    continue;
                }
                if (field ? rc.fields.containsKey(name) : rc.methods.containsKey(name + desc)) {
                    return c;
                }
                if (rc.sup != null) {
                    queue.add(rc.sup);
                }
                queue.addAll(rc.ifaces);
            }
            return owner;
        }

        /** A method's own loads and stores of locals, counted by opcode (ILOAD..ALOAD, ISTORE..ASTORE); null when it is not in the jar. */
        int[] locals(String owner, String name, String desc) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            int[] out = new int[256];
            boolean[] found = new boolean[1];
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                        return null;
                    }
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            out[opcode]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? out : null;
        }

        /** {kind, owner, name, desc} of each call and field access in a method's own code; null when it is not in the jar. */
        List<String[]> refs(String owner, String name, String desc) {
            String jarOwner = toJar.getOrDefault(owner, owner);
            byte[] file = bytes.apply(jarOwner);
            if (file == null) {
                return null;
            }
            List<String[]> out = new ArrayList<>();
            boolean[] found = new boolean[1];
            new ClassReader(file).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String signature, String[] exceptions) {
                    if (found[0] || !names.method(jarOwner, n, d).equals(name) || !names.desc(d).equals(desc)) {
                        return null;
                    }
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String o, String mn, String md, boolean itf) {
                            out.add(new String[] { "m", names.cls(o), names.method(declarer(o, mn, md, false), mn, md), names.desc(md) });
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String o, String fn, String fd) {
                            out.add(new String[] { "f", names.cls(o), names.field(declarer(o, fn, fd, true), fn), names.desc(fd) });
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? out : null;
        }
    }

    final Retype retype = new Retype();

    /**
     * How the game turns a method's new parameters into its old ones, learned from its own code. A game method whose
     * parameter changed type (1.20's Screen.render(PoseStack, ...) -> render(GuiGraphics, ...); 1.21.9's
     * keyPressed(int, int, int) -> keyPressed(KeyEvent)) is overridden by Mojang's own classes in both releases. Their
     * old and new bodies are read as GameDiff reads them (statements of value trees) and lined up statement by statement
     * the way GameDiff's learner lines them up: a statement that is the same on both sides except where the old body
     * used the old parameter says what the new body uses there instead. Where that is the new parameter read through
     * no-argument calls or fields (graphics.pose(), event.key()) giving the old parameter's type, it is a conversion; the
     * game's uses must agree (80%, see Retype.agreed). Statements line up through the route map, so a call whose
     * signature changed matches its new form.
     */
    final class Retype {

        final Map<String, String[][]> learned = new HashMap<>(); // game method key -> per old parameter: {from, steps...} or null

        /**
         * For each old parameter of a game method (old owner, name, desc) that paired leaves without a new parameter of the
         * same type (-2), the new parameter it comes from and the steps that give it ("m:owner.name()desc" a call,
         * "f:owner.name:desc" a field read, new names); null where the game's code shows none. moved is the method's new
         * owner, name and desc.
         */
        String[][] conversions(String owner, String name, String desc, String[] moved, int[] paired) {
            // The whole override family is the evidence: every game class that overrides the topmost declaration.
            String root = root(owner, name, desc);
            String[][] found = learned.computeIfAbsent(root + "." + name + desc + "->" + moved[1] + moved[2] + Arrays.toString(paired),
                k -> Objects.requireNonNullElse(learn(root, name, desc, moved, paired), NONE));
            if (filling) {
                return found == NONE ? null : found;
            }
            // A parameter its own family shows nothing for takes what the game's code uses for that type everywhere else
            // in this update, when that agrees and one new parameter has the type (1.20's list entries, whose render
            // passes the pose only to calls that changed with it, get graphics.pose() like every screen).
            Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
            String[][] out = found == NONE ? new String[was.length][] : found.clone();
            boolean any = found != NONE;
            for (int i = 0; i < was.length; i++) {
                if (paired[i] >= 0 || out[i] != null || was[i].getSort() < Type.ARRAY) {
                    continue;
                }
                String wanted = desc(was[i].getDescriptor());
                int only = -1, count = 0;
                String steps = null;
                for (int j = 0; j < now.length; j++) {
                    String best = now[j].getSort() == Type.OBJECT ? pooled(now[j].getDescriptor(), wanted) : null;
                    if (best != null) {
                        only = j;
                        steps = best;
                        count++;
                    }
                }
                if (count == 1) {
                    out[i] = (only + " " + steps).split(" ");
                    any = true;
                }
            }
            return any ? out : null;
        }

        static final String[][] NONE = new String[0][];
        final Map<String, Map<String, Integer>> pool = new HashMap<>(); // "new type>old type" -> steps -> the game's uses
        final Set<String> pooledPairs = new HashSet<>();
        boolean filling;

        /**
         * What the game's code reads from a value of type got (new names) where it used to have one of type had, agreed
         * over every family of game methods in this update whose parameters changed between the two.
         */
        String pooled(String got, String had) {
            String key = got + ">" + had;
            if (pooledPairs.add(key)) {
                filling = true;
                try {
                    for (var e : route.methods.entrySet()) {
                        String k = e.getKey();
                        int dot = k.lastIndexOf('.', k.indexOf('('));
                        String owner = k.substring(0, dot), rest = k.substring(dot + 1), name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(name.length());
                        String[] moved = route.method(owner, name, desc);
                        if (!a.classes.containsKey(owner) || !desc(desc).contains(had) || !moved[2].contains(got) || moved[2].equals(desc(desc))) {
                            continue;
                        }
                        Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
                        conversions(owner, name, desc, moved, pair(now, Arrays.stream(was).map(t -> Type.getType(desc(t.getDescriptor()))).toArray(Type[]::new)));
                    }
                } finally {
                    filling = false;
                }
            }
            String best = agreed(pool.getOrDefault(key, Map.of()));
            return best != null && stepsLink("0 " + best) ? best : null;
        }

        /** The class highest above owner, through superclasses and interfaces, that declares name+desc in the old release. */
        String root(String owner, String name, String desc) {
            String top = owner;
            Deque<String> todo = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                GameDiff.Cls c = a.classes.get(todo.poll());
                if (c == null || !seen.add(c.name)) {
                    continue;
                }
                if (c.methods.containsKey(name + desc) && subtype(a, top, c.name)) {
                    top = c.name;
                }
                if (c.sup != null) {
                    todo.add(c.sup);
                }
                todo.addAll(c.ifaces);
            }
            return top;
        }

        String[][] learn(String owner, String name, String desc, String[] moved, int[] paired) {
            GameCode oldCode = code(a), newCode = code(b);
            if (oldCode == null || newCode == null) {
                return null;
            }
            Type[] was = Type.getArgumentTypes(desc), now = Type.getArgumentTypes(moved[2]);
            int[] oldSlots = slots(was), newSlots = slots(now);
            Map<Integer, Integer> holes = new HashMap<>(); // old slot -> old parameter that needs a conversion
            Map<Integer, Integer> same = new HashMap<>(); // old slot -> new slot of the parameter paired with it
            same.put(0, 0);
            for (int i = 0; i < was.length; i++) {
                if (paired[i] >= 0) {
                    same.put(oldSlots[i], newSlots[paired[i]]);
                } else {
                    holes.put(oldSlots[i], i);
                }
            }
            Map<Integer, Integer> newParam = new HashMap<>(); // new slot -> new parameter
            for (int j = 0; j < now.length; j++) {
                newParam.put(newSlots[j], j);
            }
            List<Map<String, Integer>> votes = new ArrayList<>();
            for (int i = 0; i < was.length; i++) {
                votes.add(new HashMap<>());
            }
            for (String c : overriders(owner, name, desc)) {
                String nc = cls(c);
                GameDiff.Cls holder = b.classes.get(nc);
                if (holder == null || !holder.methods.containsKey(moved[1] + moved[2])) {
                    continue;
                }
                GameDiff.Body x = body(oldCode, c, name + desc), y = body(newCode, nc, moved[1] + moved[2]);
                if (x != null && y != null) {
                    new Lining(holes, same).pair(x, y, (i, m) -> {
                        String read = reading(m, newParam, was[holes.get(i)]);
                        if (read != null) {
                            votes.get(holes.get(i)).merge(read, 1, Integer::sum);
                        }
                    });
                }
            }
            for (int i = 0; i < was.length; i++) {
                for (var e : votes.get(i).entrySet()) {
                    String[] parts = e.getKey().split(" ", 2);
                    if (parts.length == 2) {
                        pool.computeIfAbsent(now[Integer.parseInt(parts[0])].getDescriptor() + ">" + desc(was[i].getDescriptor()), k -> new HashMap<>())
                            .merge(parts[1], e.getValue(), Integer::sum);
                    }
                }
            }
            String[][] out = new String[was.length][];
            boolean any = false;
            for (int i = 0; i < was.length; i++) {
                String best = agreed(votes.get(i));
                if (best != null && stepsLink(best)) {
                    out[i] = best.split(" ");
                    any = true;
                }
            }
            if (!LOG.isEmpty()) {
                try {
                    Files.writeString(Path.of(LOG), owner + "." + name + desc + " -> " + moved[1] + moved[2] + "\t" + votes + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return any ? out : null;
        }

        /** -Djarport.retypelog=FILE: each game method whose parameters changed type, with the game's readings for each old parameter. */
        static final String LOG = System.getProperty("jarport.retypelog", "");

        /** The reading most of the game's uses agree on: at least 80% of them, and two unless it is the only use. */
        static String agreed(Map<String, Integer> votes) {
            int total = votes.values().stream().mapToInt(Integer::intValue).sum();
            return votes.entrySet().stream()
                .filter(e -> e.getValue() * 10 >= total * 8 && (e.getValue() >= 2 || total == 1))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        /** Every step reads a member of the new release a mod may use. */
        boolean stepsLink(String reading) {
            String[] parts = reading.split(" ");
            for (int s = 1; s < parts.length; s++) {
                if (parts[s].startsWith("x:")) {
                    continue;
                }
                String[] step = step(parts[s]);
                if (step[0].equals("m")) {
                    GameDiff.Meth m = b.method(step[1], step[2], step[3]);
                    if (m == null || (m.flags & Opcodes.ACC_PUBLIC) == 0 || (m.flags & Opcodes.ACC_STATIC) != 0) {
                        return false;
                    }
                } else {
                    GameDiff.Fld f = b.field(step[1], step[2]);
                    if (f == null || (f.flags & Opcodes.ACC_PUBLIC) == 0 || (f.flags & Opcodes.ACC_STATIC) != 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** {kind, owner, name, desc} of one step. */
        static String[] step(String text) {
            int dot = text.lastIndexOf('.', text.indexOf(text.charAt(0) == 'm' ? '(' : ':', 2));
            String owner = text.substring(2, dot), rest = text.substring(dot + 1);
            if (text.charAt(0) == 'm') {
                int p = rest.indexOf('(');
                return new String[] { "m", owner, rest.substring(0, p), rest.substring(p) };
            }
            int colon = rest.indexOf(':');
            return new String[] { "f", owner, rest.substring(0, colon), rest.substring(colon + 1) };
        }

        /**
         * What the new body computes where the old one used a parameter, as a conversion: "j step..." for new parameter j
         * read through no-argument calls or fields, giving the old parameter's type. Anything else (the new parameter
         * passed as it is to a call that changed with it, or other code) is no conversion.
         */
        String reading(GameDiff.Node m, Map<Integer, Integer> newParam, Type wanted) {
            if (!(m.type.equals(desc(wanted.getDescriptor())) || wanted.getSort() == Type.OBJECT && m.type.startsWith("L")
                && subtype(b, m.type.substring(1, m.type.length() - 1), cls(wanted.getInternalName())))) {
                return null;
            }
            Deque<String> steps = new ArrayDeque<>();
            GameDiff.Node n = m;
            while (steps.size() < 4 && n.kids.length == 1 && (n.op == 'v' && n.value == null && n.ref.desc().startsWith("()") || n.op == 'f'
                || n.op == 'x' && CONVERT.containsKey(n.kids[0].type + n.type))) {
                steps.push(switch (n.op) {
                    case 'v' -> "m:" + n.ref.owner() + "." + n.ref.name() + n.ref.desc();
                    case 'f' -> "f:" + n.ref.owner() + "." + n.ref.name() + ":" + n.ref.desc();
                    default -> "x:" + n.kids[0].type + n.type; // charTyped's char from the event's int code point
                });
                n = n.kids[0];
            }
            Integer j = n.op == 'p' && n.value instanceof Integer slot ? newParam.get(slot) : null;
            // The new parameter itself is a conversion only where it is the old one's very type at another place
            // (mouseDragged's drag distances after the event that replaced x, y and button).
            if (j == null || steps.isEmpty() && !m.type.equals(desc(wanted.getDescriptor()))) {
                return null;
            }
            return steps.isEmpty() ? String.valueOf(j) : j + " " + String.join(" ", steps);
        }

        /** The old release's classes that declare name+desc and are owner or extend it. */
        List<String> overriders(String owner, String name, String desc) {
            List<String> out = new ArrayList<>();
            for (GameDiff.Cls c : a.classes.values()) {
                if (c.methods.containsKey(name + desc) && subtype(a, c.name, owner)) {
                    out.add(c.name);
                }
            }
            Collections.sort(out);
            return out;
        }

        /** A game method's body as GameDiff reads it (Mojang names), or null. */
        GameDiff.Body body(GameCode code, String owner, String key) {
            String jar = code.toJar.getOrDefault(owner, owner);
            byte[] bytes = code.bytes.apply(jar);
            if (bytes == null) {
                return null;
            }
            Map<String, GameDiff.RawCls> raw = new HashMap<>() {
                @Override
                public GameDiff.RawCls get(Object k) {
                    return code.raw((String) k);
                }
            };
            GameDiff.Origin o = new GameDiff.Origin(bytes, raw, null, code.names, new HashMap<>());
            for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
                String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
                if ((code.names.method(jar, rn, rd) + code.names.desc(rd)).equals(key)) {
                    return new GameDiff.Decoder(o, jar).run(mm);
                }
            }
            return null;
        }
    }

    /** The instruction that turns a value of one primitive type into another, by the two descriptors. */
    static final Map<String, Integer> CONVERT = Map.ofEntries(Map.entry("IC", Opcodes.I2C), Map.entry("IB", Opcodes.I2B),
        Map.entry("IS", Opcodes.I2S), Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("FD", Opcodes.F2D), Map.entry("DF", Opcodes.D2F), Map.entry("FI", Opcodes.F2I),
        Map.entry("DI", Opcodes.D2I));

    /** Whether class c is sup or extends or implements it in one release. */
    static boolean subtype(GameDiff.Snap s, String c, String sup) {
        Deque<String> todo = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String k = todo.poll();
            if (k.equals(sup)) {
                return true;
            }
            GameDiff.Cls held = seen.add(k) ? s.classes.get(k) : null;
            if (held != null) {
                if (held.sup != null) {
                    todo.add(held.sup);
                }
                todo.addAll(held.ifaces);
            }
        }
        return false;
    }

    /** Each parameter's local slot in an instance method. */
    static int[] slots(Type[] params) {
        return slots(params, 1);
    }

    /** Each parameter's local slot, the first at first (0 in a static method). */
    static int[] slots(Type[] params, int first) {
        int[] out = new int[params.length];
        int next = first;
        for (int i = 0; i < params.length; i++) {
            out[i] = next;
            next += params[i].getSize();
        }
        return out;
    }

    /**
     * Two bodies of one method, the old release's (x) and the new one's (y), lined up statement by statement as
     * GameDiff.Learner lines them up: equal statements (through the route map) are anchors, and in the gaps between them
     * a statement of x that uses a hole parameter is matched against each of y's; where it matches everywhere else, y's
     * code at each use of the parameter is reported. same maps old parameter slots to the new slots of the same values.
     */
    final class Lining {
        final Map<Integer, Integer> holes, same;
        final Map<GameDiff.Node, Long> hashX = new IdentityHashMap<>(), hashY = new IdentityHashMap<>();
        int budget;

        Lining(Map<Integer, Integer> holes, Map<Integer, Integer> same) {
            this.holes = holes;
            this.same = same;
        }

        boolean hole(GameDiff.Node n) {
            return n.op == 'p' && n.value instanceof Integer s && holes.containsKey(s);
        }

        void pair(GameDiff.Body x, GameDiff.Body y, java.util.function.BiConsumer<Integer, GameDiff.Node> found) {
            long[] tx = new long[x.statements.size()], ty = new long[y.statements.size()];
            for (int i = 0; i < tx.length; i++) {
                tx[i] = hash(x.statements.get(i), true);
            }
            for (int j = 0; j < ty.length; j++) {
                ty[j] = hash(y.statements.get(j), false);
            }
            List<int[]> bounds = new ArrayList<>(GameDiff.Learner.matches(tx, ty));
            bounds.add(new int[] { tx.length, ty.length });
            int i0 = 0, j0 = 0;
            for (int[] p : bounds) {
                int na = p[0] - i0, nb = p[1] - j0;
                for (int i = i0; i < p[0] && nb > 0; i++) {
                    GameDiff.Node sx = x.statements.get(i);
                    if (!holed(sx)) {
                        continue;
                    }
                    boolean bare = sx.kids.length == 1 && sx.kids[0].kids.length == 0;
                    for (int j = j0; j < p[1]; j++) {
                        if (bare && !(na == nb && j - j0 == i - i0 || nb == 1)) {
                            continue;
                        }
                        Map<GameDiff.Node, GameDiff.Node> at = new IdentityHashMap<>();
                        budget = 20_000;
                        if (match(sx, y.statements.get(j), at)) {
                            at.forEach((h, m) -> found.accept((Integer) h.value, m));
                        }
                    }
                }
                i0 = p[0] + 1;
                j0 = p[1] + 1;
            }
        }

        boolean holed(GameDiff.Node n) {
            if (hole(n)) {
                return true;
            }
            for (GameDiff.Node k : n.kids) {
                if (holed(k)) {
                    return true;
                }
            }
            return false;
        }

        /** A node's own part, in the new release's names: a member by its new name and descriptor. */
        String head(GameDiff.Node n, boolean old) {
            String ref = n.ref == null ? "" : switch (n.ref.kind()) {
                case 'c' -> old ? cls(n.ref.owner()) : n.ref.owner();
                case 'f' -> old ? route.field(n.ref.owner(), n.ref.name())[1] : n.ref.name();
                case 'm' -> {
                    if (!old) {
                        yield n.ref.name() + n.ref.desc();
                    }
                    String[] moved = route.method(n.ref.owner(), n.ref.name(), n.ref.desc());
                    yield moved[1] + moved[2];
                }
                default -> n.ref.owner();
            };
            return switch (n.op) {
                case 'p' -> (old ? same.getOrDefault((Integer) n.value, -1) : n.value) + ":" + (old ? desc(n.type) : n.type);
                case 'l' -> n.value + ":" + (old ? desc(n.type) : n.type);
                case 'k' -> n.type + ":" + n.value;
                case 'x' -> n.type;
                case 'o', 'S', 'y' -> n.value + ref;
                default -> String.valueOf(n.value) + ref;
            };
        }

        long hash(GameDiff.Node n, boolean old) {
            Map<GameDiff.Node, Long> memo = old ? hashX : hashY;
            Long known = memo.get(n);
            if (known != null) {
                return known;
            }
            long h;
            if (old && hole(n) || GameDiff.Decoder.phi(n)) {
                h = 0x9E3779B97F4A7C15L * (System.identityHashCode(n) + 1L) + (old ? 0 : 1);
            } else {
                h = GameDiff.fnv(n.op + head(n, old));
                for (GameDiff.Node k : n.kids) {
                    h = h * 1_000_003L + hash(k, old);
                }
            }
            memo.put(n, h);
            return h;
        }

        boolean match(GameDiff.Node x, GameDiff.Node y, Map<GameDiff.Node, GameDiff.Node> at) {
            if (--budget < 0) {
                return false;
            }
            if (hole(x)) {
                GameDiff.Node had = at.putIfAbsent(x, y);
                return had == null || had == y;
            }
            if (GameDiff.Decoder.phi(x) || GameDiff.Decoder.phi(y)) {
                return false;
            }
            if (hash(x, true) == hash(y, false)) {
                return true;
            }
            if (x.op != y.op || x.kids.length != y.kids.length || !head(x, true).equals(head(y, false))) {
                return false;
            }
            for (int i = 0; i < x.kids.length; i++) {
                if (!match(x.kids[i], y.kids[i], at)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The old release's method a mixin "method" string names in one of the targets, or null. */
    GameDiff.Meth oldMethod(List<String> targets, String text) {
        Matcher m = MEMBER.matcher(text);
        if (!m.matches() || m.group(4) != null) {
            return null;
        }
        for (String o : m.group(1) != null ? List.of(m.group(1)) : targets) {
            List<GameDiff.Meth> found = named(a, o, m.group(2)).stream().filter(x -> m.group(3) == null || x.desc.equals(m.group(3))).toList();
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    /** A mixin handler in two: its annotations go to the handler with the new parameters, its body to name$mmt, which the handler calls. */
    final class Split extends MethodVisitor {
        final MethodVisitor handler;
        final String owner, name, desc;
        final int access;
        final Adapter adapter;
        final boolean itf;

        Split(MethodVisitor handler, MethodVisitor body, String owner, String name, String desc, int access, Adapter adapter, boolean itf) {
            super(Opcodes.ASM9, body);
            this.itf = itf;
            this.handler = handler;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
            this.adapter = adapter;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String d, boolean visible) {
            return handler.visitAnnotation(d, visible);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            Type[] now = Type.getArgumentTypes(oldDesc(adapter.desc()));
            Type[] was = Type.getArgumentTypes(desc);
            int[] slots = new int[now.length];
            int next = isStatic ? 0 : 1;
            for (int i = 0; i < now.length; i++) {
                slots[i] = next;
                next += now[i].getSize();
            }
            handler.visitCode();
            int stack = isStatic ? 0 : 1;
            if (!isStatic) {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
            }
            for (int j = 0; j < adapter.captured(); j++) {
                int f = adapter.from()[j];
                if (f < 0) {
                    pushDefault(handler, was[j]);
                } else {
                    handler.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
                    steps(handler, adapter.via()[j]);
                }
                stack += was[j].getSize();
            }
            handler.visitVarInsn(Opcodes.ALOAD, slots[now.length - 1]);
            handler.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, name + "$mmt", desc, itf);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(stack + 1, next);
            handler.visitEnd();
        }
    }

    /**
     * A mod method whose game method's parameters changed type, written with the new signature (br): its old parameters
     * are worked out from the new ones first, its body follows with every local moved past the new parameters, and calls
     * in it that need what the game passes now get it (Sites.given). Read with expanded frames, which are rewritten with
     * the new parameters in front.
     */
    void moved(Mod mod, byte[] bytes, String owner, Bridge br, ClassVisitor out) {
        Set<Integer> stored = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return !name.equals(br.target()) || !desc.equals(br.targetDesc()) ? null : new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                            stored.add(var);
                        }
                    }

                    @Override
                    public void visitIincInsn(int var, int increment) {
                        stored.add(var);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(br.target()) || !desc.equals(br.targetDesc())) {
                    return null;
                }
                String newDesc = oldDesc(br.desc());
                Track track = new Track(owner, access, br.name(), newDesc, out.visitMethod(access, br.name(), newDesc, null, exceptions));
                Shift shift = new Shift(track, br, desc, stored, (access & Opcodes.ACC_STATIC) != 0);
                String key = owner + "." + name + desc;
                // A mixin handler's code runs in its targets, and its annotations name them (as in rewrite).
                List<String> targets = mod.mixinTargets.getOrDefault(owner, List.of());
                List<String> callers = new ArrayList<>(targets.isEmpty() ? List.of(owner) : targets.stream().map(JarPort.this::cls).toList());
                if (targets.stream().anyMatch(t -> !mod.classes.containsKey(t) && !a.classes.containsKey(t))) {
                    callers.add(owner);
                }
                Sites sites = new Sites(mod, shift, mod.maxLocals.getOrDefault(key, 0), targets, callers, key, Exposure.dotted(owner) + "." + name);
                sites.moved = shift;
                return sites;
            }
        }, ClassReader.EXPAND_FRAMES);
    }

    /**
     * The stack as the JVM's verifier sees it (asm-commons' AnalyzerAdapter), where a moved method's old parameters keep
     * who they are: each load of one pushes a mark, so a call can tell which of its arguments is which old parameter.
     */
    static final class Track extends org.objectweb.asm.commons.AnalyzerAdapter {
        final Map<Integer, Object> marks = new HashMap<>(); // slot -> mark
        final Map<Object, Integer> params = new IdentityHashMap<>(); // mark -> old parameter

        Track(String owner, int access, String name, String desc, MethodVisitor mv) {
            super(Opcodes.ASM9, owner, access, name, desc, mv);
        }

        void mark(int slot, int param, Type t) {
            Object mark = t.getSort() == Type.OBJECT ? new String(t.getInternalName()) : t.getSort() == Type.ARRAY ? new String(t.getDescriptor()) : new Object();
            marks.put(slot, mark);
            params.put(mark, param);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            Object mark = marks.get(var);
            // A long or double is two entries, its value first.
            int at = stack == null ? -1 : stack.size() - (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD ? 2 : 1);
            if (mark != null && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD && at >= 0) {
                stack.set(at, mark);
            }
        }

        /** For each argument of a call about to be made (on top of the stack), the old parameter it is, or null. */
        Integer[] sources(Type[] args) {
            if (stack == null) {
                return null;
            }
            Integer[] out = new Integer[args.length];
            int at = stack.size();
            for (int k = args.length - 1; k >= 0; k--) {
                at -= args[k].getSize();
                if (at < 0) {
                    return null;
                }
                out[k] = params.get(stack.get(at));
            }
            return out;
        }
    }

    /**
     * A moved body (Bridge.via): the old parameters worked out from the new ones and stored past them, and every local
     * of the old body moved by as much. loadNew pushes a new parameter itself.
     */
    final class Shift extends MethodVisitor {
        final Track track;
        final Bridge br;
        final Type[] now, was;
        final int[] nowSlots, wasSlots;
        final int base, first; // first: the first parameter's slot, 0 in a static method
        final Set<Integer> stored;

        Shift(Track track, Bridge br, String oldDesc, Set<Integer> stored, boolean isStatic) {
            super(Opcodes.ASM9, track);
            this.track = track;
            this.br = br;
            this.stored = stored;
            first = isStatic ? 0 : 1;
            now = Type.getArgumentTypes(oldDesc(br.desc()));
            was = Type.getArgumentTypes(oldDesc);
            nowSlots = slots(now, first);
            wasSlots = slots(was, first);
            base = first + Arrays.stream(now).mapToInt(Type::getSize).sum();
        }

        int at(int var) {
            return var < first ? var : var + base - first;
        }

        void loadNew(int j) {
            mv.visitVarInsn(now[j].getOpcode(Opcodes.ILOAD), nowSlots[j]);
        }

        /** Whether a call's arguments include an old parameter the body has only a default for. */
        boolean defaulted(Type[] args) {
            Integer[] src = track.sources(args);
            return src != null && Arrays.stream(src).anyMatch(s -> s != null && br.from()[s] < 0);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            for (int i = 0; i < was.length; i++) {
                int f = br.from()[i];
                if (f < 0) {
                    pushDefault(mv, was[i]);
                } else {
                    loadNew(f);
                    steps(mv, br.via()[i]);
                }
                mv.visitVarInsn(was[i].getOpcode(Opcodes.ISTORE), at(wasSlots[i]));
                if (!stored.contains(wasSlots[i])) {
                    track.mark(at(wasSlots[i]), i, was[i]);
                }
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, at(var));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            super.visitIincInsn(at(var), increment);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            super.visitLocalVariable(name, desc, signature, start, end, at(index));
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            if (type != Opcodes.F_NEW || nLocal < first) {
                super.visitFrame(type, nLocal, local, nStack, stack);
                return;
            }
            Object[] out = new Object[nLocal + now.length];
            System.arraycopy(local, 0, out, 0, first);
            for (int j = 0; j < now.length; j++) {
                out[first + j] = switch (now[j].getSort()) {
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                    case Type.FLOAT -> Opcodes.FLOAT;
                    case Type.LONG -> Opcodes.LONG;
                    case Type.DOUBLE -> Opcodes.DOUBLE;
                    case Type.ARRAY -> now[j].getDescriptor();
                    default -> now[j].getInternalName();
                };
            }
            System.arraycopy(local, first, out, first + now.length, nLocal - first);
            super.visitFrame(type, out.length, out, nStack, stack);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 2, maxLocals + base - first);
        }

        @Override
        public void visitParameter(String name, int access) {
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
            return null;
        }
    }

    /** The steps a conversion reads (Retype.step) on the value on top of the stack, written in the old release's names. */
    void steps(MethodVisitor mv, String[] steps) {
        for (String text : steps == null ? new String[0] : steps) {
            if (text.startsWith("x:")) {
                mv.visitInsn(CONVERT.get(text.substring(2)));
                continue;
            }
            String[] s = Retype.step(text);
            String owner = inverse.getOrDefault(s[1], s[1]);
            if (s[0].equals("m")) {
                GameDiff.Cls holder = b.classes.get(s[1]);
                boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, s[2], oldDesc(s[3]), itf);
            } else {
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, s[2], oldDesc(s[3]));
            }
        }
    }
    /** The bridge's code: the mod's own method called on this with the arguments it takes, its result returned. */
    void bridgeCode(MethodVisitor mv, String owner, boolean itf, Bridge br) {
        Type[] now = Type.getArgumentTypes(oldDesc(br.desc()));
        Type[] was = Type.getArgumentTypes(br.targetDesc());
        int[] slots = new int[now.length];
        int next = 1;
        for (int i = 0; i < now.length; i++) {
            slots[i] = next;
            next += now[i].getSize();
        }
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int stack = 1;
        for (int j = 0; j < was.length; j++) {
            int f = br.from()[j];
            if (f < 0) {
                pushDefault(mv, was[j]);
            } else {
                mv.visitVarInsn(now[f].getOpcode(Opcodes.ILOAD), slots[f]);
            }
            stack += was[j].getSize();
        }
        mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, br.target(), br.targetDesc(), itf);
        Type ret = Type.getReturnType(br.targetDesc());
        mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(Math.max(stack, ret.getSize()), next);
        mv.visitEnd();
    }

    /** The value Java gives a variable of type t that nothing set: 0, false or null. */
    static void pushDefault(MethodVisitor mv, Type t) {
        mv.visitInsn(switch (t.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.ICONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            default -> Opcodes.ACONST_NULL;
        });
    }

    /** Call sites, field access, method references and the types named inside one method body. */
    final class Sites extends MethodVisitor {
        final Mod mod;
        final List<String> targets;
        final List<String> callers;
        final String key;
        final String where;
        int nextLocal;
        int extraStack; // what code written in place of an instruction may push beyond it

        Sites(Mod mod, MethodVisitor mv, int locals, List<String> targets, List<String> callers, String key, String where) {
            super(Opcodes.ASM9, mv);
            this.mod = mod;
            this.nextLocal = locals;
            this.targets = targets;
            this.callers = callers;
            this.key = key;
            this.where = where;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(desc, visible);
            String target = mod.accessorTargets.get(key);
            if (ACCESSORS.containsKey(desc)) {
                // The accessor names its target in "value"; a renamed target is written there, so the method keeps its own name.
                return target == null || av == null ? av : new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public void visit(String n, Object v) {
                        if (!"value".equals(n)) {
                            super.visit(n, v);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        super.visit("value", target);
                        super.visitEnd();
                    }
                };
            }
            return mixinStrings(mod, targets, av, key);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 4 + extraStack, Math.max(maxLocals, nextLocal));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            fresh = false;
            types(mod, desc, where);
            String[] own = mod.renamedDecl.get(owner + "." + name);
            if (own != null) {
                super.visitFieldInsn(opcode, owner, own[0], desc);
                return;
            }
            Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'f', name, desc);
            if (decl == null || !decl.game()) {
                super.visitFieldInsn(opcode, owner, name, desc);
                return;
            }
            boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
            String[] t = target(mod, callers, owner, read ? 'r' : 'f', name, desc, decl);
            if (t == null && read && chain(decl.owner() + "." + name, Type.getType(desc), opcode == Opcodes.GETSTATIC, "")) {
                mod.change("field read through a chain");
                return;
            }
            if (t == null && read) {
                String[] added = fix(List.of(owner, decl.owner()), name, null, opcode == Opcodes.GETSTATIC ? "()" + desc : "(L" + owner + ";)" + desc, opcode == Opcodes.GETFIELD);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("field read replaced by a port rule");
                    return;
                }
            }
            if (t == null && !read) {
                // A write through what replaces it ("name=": $0 the receiver unless static, then the value).
                String[] added = fix(List.of(owner, decl.owner()), name + "=", null, opcode == Opcodes.PUTSTATIC ? "(" + desc + ")V" : "(L" + owner + ";" + desc + ")V", opcode == Opcodes.PUTFIELD);
                if (added != null) {
                    invokeAdded(added);
                    mod.change("field write replaced by a port rule");
                    return;
                }
            }
            if (t == null) {
                mod.unlinked.add("field: " + Exposure.dotted(decl.owner()) + "." + name);
                super.visitFieldInsn(opcode, owner, name, desc);
                return;
            }
            if (!t[1].equals(name) || !t[0].equals(cls(owner))) {
                mod.change("field renamed");
            }
            super.visitFieldInsn(opcode, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            fresh = false;
            String[] closing = opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && !news.isEmpty() && news.peek()[0].equals(owner) ? news.peek() : null;
            call(opcode, owner, name, desc, itf);
            if (closing != null) {
                news.remove(closing);
            }
        }

        void call(int opcode, String owner, String name, String desc, boolean itf) {
            String[] own = mod.renamedDecl.get(owner + "." + name + desc);
            if (own != null) {
                super.visitMethodInsn(opcode, owner, own[0], oldDesc(own[1]), itf);
                return;
            }
            Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'm', name, desc);
            Bridge body = decl == null || decl.game() ? null : mod.bridges.getOrDefault(decl.owner(), List.of()).stream()
                .filter(br -> br.via() != null && br.target().equals(name) && br.targetDesc().equals(desc)).findFirst().orElse(null);
            if (body != null) {
                // The mod's own method moved to the game's new parameter types: a call made where they are at hand follows it.
                if (moved == null || !given(opcode, owner, desc, List.<String[]>of(new String[] { decl.owner(), body.name(), body.desc(), "mod" }))) {
                    if (DEBUG) {
                        System.err.println("kept " + name + desc + " for a call in " + where + " moved=" + (moved != null) + " src=" + (moved == null ? "" : Arrays.toString(moved.track.sources(Type.getArgumentTypes(desc)))));
                    }
                    mod.kept.add(name + desc); // the old methods of that name stay for this call (see port)
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                }
                return;
            }
            if (moved != null && decl != null && !decl.game()) {
                twin(decl.owner(), name, desc);
            }
            if (decl == null || !decl.game()) {
                if (decl != null) {
                    String[] inherited = mod.renamedDecl.get(decl.owner() + "." + name + desc);
                    if (inherited != null) {
                        super.visitMethodInsn(opcode, owner, inherited[0], oldDesc(inherited[1]), itf);
                        return;
                    }
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            String[] t = target(mod, callers, owner, 'm', name, desc, decl);
            String key = decl.owner() + "." + name + desc;
            boolean superCall = opcode == Opcodes.INVOKESPECIAL;
            if (t == null && moved != null) {
                List<String[]> now = new ArrayList<>();
                String[] m = route.method(decl.owner(), name, desc);
                now.add(new String[] { m[0], m[1], m[2], opcode == Opcodes.INVOKESTATIC ? "static" : "" });
                for (Call c : rewrites.getOrDefault(key, List.of())) {
                    now.add(new String[] { c.owner(), c.name(), c.desc(), c.isStatic() ? "static" : "" });
                }
                if (given(opcode, owner, desc, now) || onOrigin(opcode, owner, decl.owner(), name, desc)) {
                    return;
                }
                if (moved.defaulted(Type.getArgumentTypes(desc))) {
                    // The body has no value to pass here: it is not moved after all (see port), and stays as it was.
                    mod.unmoved.add(Sites.this.key);
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                    return;
                }
            }
            if (t == null && !superCall && rewritten(key, owner, desc, opcode)) {
                return;
            }
            if (t == null && defaulted(key, owner, desc, opcode)) {
                return;
            }
            if (t == null && opcode == Opcodes.INVOKEVIRTUAL || t == null && opcode == Opcodes.INVOKEINTERFACE) {
                if (chain(key, Type.getReturnType(desc), false, desc)) {
                    mod.change("call through a chain");
                    return;
                }
            }
            if (t == null) {
                String[] moved = route.method(decl.owner(), name, desc);
                int[] keep = linksNew(mod, callers, moved[0], 'm', moved[1], moved[2]) ? kept(desc(desc), moved[2]) : null;
                if (keep != null) {
                    drop(desc, keep);
                    mod.change("call arguments dropped");
                    super.visitMethodInsn(opcode, inverse.getOrDefault(moved[0], moved[0]), moved[1], oldDesc(moved[2]), itf);
                    return;
                }
                if (removals.containsKey(decl.owner() + "." + name + desc) && Type.getReturnType(desc) == Type.VOID_TYPE) {
                    drop(desc, new int[0]);
                    if (opcode != Opcodes.INVOKESTATIC) {
                        super.visitInsn(Opcodes.POP);
                    }
                    mod.change("call removed");
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && constructed(owner, decl.owner(), desc)) {
                    mod.change("constructor arguments from a port rule");
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && made(owner, decl.owner(), desc)) {
                    mod.change("constructor replaced by a factory");
                    return;
                }
                if (opcode != Opcodes.INVOKESPECIAL) {
                    String opDesc = opcode == Opcodes.INVOKESTATIC ? desc : "(L" + owner + ";" + desc.substring(1);
                    String[] added = fix(List.of(owner, decl.owner()), name, desc, opDesc, opcode != Opcodes.INVOKESTATIC);
                    if (added != null) {
                        invokeAdded(added);
                        mod.change("call replaced by a port rule");
                        return;
                    }
                }
                mod.unlinked.add("call: " + Exposure.dotted(decl.owner()) + "." + name + desc + (WHERE ? " @ " + where + (Sites.this.moved != null ? " (moved)" : "") : ""));
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            if (!t[1].equals(name) || !t[2].equals(desc(desc)) || !t[0].equals(cls(owner))) {
                mod.change("call renamed");
            }
            boolean nowInterface = itf;
            GameDiff.Cls holder = b.classes.get(t[0]);
            // The class may have become an interface under the same name (1.21.2's InteractionResult was an enum).
            if (holder != null) {
                nowInterface = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                if (opcode == Opcodes.INVOKEVIRTUAL && nowInterface) {
                    opcode = Opcodes.INVOKEINTERFACE;
                } else if (opcode == Opcodes.INVOKEINTERFACE && !nowInterface) {
                    opcode = Opcodes.INVOKEVIRTUAL;
                }
            }
            super.visitMethodInsn(opcode, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), nowInterface);
        }

        /**
         * A call the game's own code switched to another (Registry.getHolder -> HolderGetter.get): taken when the receiver
         * is one, the arguments and the result still fit, and the mod may call it.
         */
        boolean rewritten(String key, String owner, String desc, int opcode) {
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            for (Call c : rewrites.getOrDefault(key, List.of())) {
                if (c.isStatic() != isStatic || !widens(desc(desc), c.desc()) || !linksNew(mod, callers, c.owner(), 'm', c.name(), c.desc())) {
                    continue;
                }
                if (!isStatic && !isA(cls(owner), c.owner())) {
                    continue;
                }
                invoke(isStatic, c);
                mod.change("call rewritten from the game's code");
                return true;
            }
            return false;
        }

        /** A call to a method that gained one parameter, with the one value the game's own code passes there inserted. */
        boolean defaulted(String key, String owner, String desc, int opcode) {
            Default d = defaults.get(key);
            if (d == null || opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) {
                return false;
            }
            Type[] now = Type.getArgumentTypes(d.to().desc());
            Type[] before = Type.getArgumentTypes(desc(desc));
            if (now.length != before.length + 1 || !isA(cls(owner), d.to().owner()) || !linksNew(mod, callers, d.to().owner(), 'm', d.to().name(), d.to().desc())) {
                return false;
            }
            Runnable value = value(d.value(), now[d.index()]);
            if (value == null) {
                return false;
            }
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, d.index());
            value.run();
            for (int i = d.index(); i < args.length; i++) {
                super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
            invoke(false, d.to());
            mod.change("call given the game's default argument");
            return true;
        }

        /** Code that pushes the value the game's code passes ("a.b.C::m" as an instance of wanted, "a.b.C.FIELD", "a.b.C.m()"), or null. */
        Runnable value(String text, Type wanted) {
            Matcher ref = REFERENCE.matcher(text);
            if (ref.matches()) {
                Object[] indy = lambda(mod, callers, ref.group(1).replace('.', '/'), ref.group(2), wanted);
                return indy == null ? null : () -> super.visitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], (Object[]) indy[3]);
            }
            Matcher call = STATIC_CALL.matcher(text);
            Matcher field = STATIC_FIELD.matcher(text);
            boolean isCall = call.matches();
            if (!isCall && !field.matches()) {
                return null;
            }
            String owner = (isCall ? call : field).group(1).replace('.', '/');
            String name = (isCall ? call : field).group(2);
            GameDiff.Cls holder = b.classes.get(owner);
            if (holder == null) {
                return null;
            }
            if (isCall) {
                GameDiff.Meth m = holder.methods.values().stream()
                    .filter(x -> x.name.equals(name) && x.desc.startsWith("()") && (x.flags & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
                if (m == null || !usable(Type.getReturnType(m.desc), wanted) || !memberOpen(mod, callers, owner, m.name + m.desc, m.flags)) {
                    return null;
                }
                boolean itf = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                return () -> super.visitMethodInsn(Opcodes.INVOKESTATIC, inverse.getOrDefault(owner, owner), m.name, oldDesc(m.desc), itf);
            }
            GameDiff.Fld f = holder.fields.get(name);
            if (f == null || (f.flags & Opcodes.ACC_STATIC) == 0 || !usable(Type.getType(f.desc), wanted) || !memberOpen(mod, callers, owner, name, f.flags)) {
                return null;
            }
            return () -> super.visitFieldInsn(Opcodes.GETSTATIC, inverse.getOrDefault(owner, owner), name, oldDesc(f.desc));
        }

        /**
         * A member read from the same receiver, step by step (1.21.2: Input.jumping -> keyPresses.jump()). The receiver is
         * on the stack under the old call's arguments (desc; "" for a field read): they are set aside, the steps read
         * fields and call no-argument methods, and the last step takes them back. The result must serve as the old one.
         */
        boolean chain(String key, Type result, boolean isStatic, String desc) {
            Chain c = chains.get(key);
            if (c == null || isStatic) {
                return false;
            }
            String[] steps = c.steps().split(",");
            Type[] args = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(desc);
            Type[] newArgs = desc.isEmpty() ? new Type[0] : Type.getArgumentTypes(desc(desc));
            // Plan every step before writing any: each one must link against the new release.
            List<String[]> plan = new ArrayList<>();
            String type = c.newOwner();
            for (int i = 0; i < steps.length; i++) {
                String[] s = steps[i].split(":", 2);
                boolean last = i == steps.length - 1;
                if (s.length != 2 || type == null) {
                    return false;
                }
                if (s[0].equals("f")) {
                    FieldHit f = fieldNew(mod, callers, type, s[1]);
                    GameDiff.Cls holder = Exposure.declaring(b, type, 'f', s[1], null);
                    boolean instance = holder == null || (holder.fields.get(s[1]).flags & Opcodes.ACC_STATIC) == 0;
                    if (f == null || !f.open() || !instance || (last && !usable(Type.getType(f.desc()), Type.getType(desc(result.getDescriptor()))))) {
                        return false;
                    }
                    plan.add(new String[] { "f", type, s[1], f.desc() });
                    type = Type.getType(f.desc()).getSort() == Type.OBJECT ? Type.getType(f.desc()).getInternalName() : null;
                    continue;
                }
                GameDiff.Meth found = null;
                for (GameDiff.Cls k = b.classes.get(type); k != null && found == null; k = k.sup == null ? null : b.classes.get(k.sup)) {
                    for (GameDiff.Meth m : k.methods.values()) {
                        Type[] params = Type.getArgumentTypes(m.desc);
                        boolean argsFit = params.length == (last ? newArgs.length : 0);
                        for (int p = 0; argsFit && p < params.length; p++) {
                            argsFit = usable(newArgs[p], params[p]);
                        }
                        boolean resultFits = !last || usable(Type.getReturnType(m.desc), Type.getType(desc(result.getDescriptor())));
                        if (m.name.equals(s[1]) && (m.flags & Opcodes.ACC_STATIC) == 0 && argsFit && resultFits) {
                            found = m;
                            break;
                        }
                    }
                }
                if (found == null || !linksNew(mod, callers, type, 'm', found.name, found.desc)) {
                    return false;
                }
                plan.add(new String[] { "m", type, found.name, found.desc });
                Type ret = Type.getReturnType(found.desc);
                type = ret.getSort() == Type.OBJECT ? ret.getInternalName() : null;
            }
            if (!isA(cls(key.substring(0, key.indexOf('.'))), c.newOwner())) {
                return false;
            }
            int[] slots = spill(args, 0);
            for (int i = 0; i < plan.size(); i++) {
                String[] p = plan.get(i);
                if (i == plan.size() - 1) {
                    for (int a = 0; a < args.length; a++) {
                        super.visitVarInsn(args[a].getOpcode(Opcodes.ILOAD), slots[a]);
                    }
                }
                if (p[0].equals("f")) {
                    super.visitFieldInsn(Opcodes.GETFIELD, inverse.getOrDefault(p[1], p[1]), p[2], oldDesc(p[3]));
                } else {
                    invoke(false, new Call(p[1], p[2], p[3], false));
                }
            }
            return true;
        }

        boolean isA(String c, String target) {
            return JarPort.this.isA(mod, c, target);
        }

        /**
         * The replacement a port rule gives for the old member (owners: the class the site names and the one declaring the
         * member), compiled into a private static method of this class that takes the old operands (opDesc, old names).
         * Returns {name, desc} of that method, or null when no rule names the member or its replacement does not link and
         * type here. args is the member's descriptor a rule's "(args)" is matched against (null for a field). With
         * receiver, the first operand is the receiver ($0).
         */
        String[] fix(List<String> owners, String name, String args, String opDesc, boolean receiver) {
            return fix(fixes, owners, name, args, opDesc, receiver);
        }

        String[] fix(Map<String, List<Fix>> book, List<String> owners, String name, String args, String opDesc, boolean receiver) {
            if (mod.selfInterface && mod.selfVersion < Opcodes.V9) {
                return null;
            }
            Type[] ops = Type.getArgumentTypes(opDesc);
            Type ret = Type.getReturnType(opDesc);
            Type[] params = new Type[ops.length];
            for (int i = 0; i < ops.length; i++) {
                params[i] = Type.getType(desc(ops[i].getDescriptor()));
                if (!present(mod, params[i])) {
                    return null;
                }
            }
            Type newRet = Type.getType(desc(ret.getDescriptor()));
            for (String owner : new LinkedHashSet<>(owners)) {
                for (Fix f : book.getOrDefault(owner, List.of())) {
                    Matcher n = f.name().matcher(name);
                    if (!n.matches() || f.args() != null && (args == null || !args.startsWith(f.args()))) {
                        continue;
                    }
                    try {
                        String expr = FixExpr.fill(f.expr(), n);
                        FixExpr.Node node = parsed(expr);
                        if (dropsUnnamed(f, node, ops.length - (receiver ? 1 : 0))) {
                            continue;
                        }
                        Lowering low = new Lowering(mod, callers, params, receiver, 0);
                        Code code = new Code();
                        Type got = low.lower(node, code);
                        if (ret.getSort() == Type.VOID) {
                            if (got.getSize() > 0) {
                                code.add(-got.getSize(), mv -> mv.visitInsn(got.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                            }
                            code.add(0, mv -> mv.visitInsn(Opcodes.RETURN));
                        } else if (present(mod, newRet) && got.getSort() != Type.VOID && low.fits(got, newRet)) {
                            low.widen(got, newRet, code);
                            code.add(-newRet.getSize(), mv -> mv.visitInsn(newRet.getOpcode(Opcodes.IRETURN)));
                        } else {
                            continue;
                        }
                        String desc = Type.getMethodDescriptor(ret, ops);
                        Added m = mod.added.computeIfAbsent(expr + desc, k -> new Added(
                            "mmt$fix$" + mod.added.size() + "$" + Integer.toHexString(mod.self.hashCode()), desc, code, low.locals
                        ));
                        return new String[] { m.name(), m.desc() };
                    } catch (Refused | IllegalArgumentException notHere) {
                        // Another rule may still give one that links.
                    }
                }
            }
            return null;
        }

        /**
         * new X(old arguments), or super(...), where X's constructor changed: a rule's "new X(...)" gives the new
         * arguments from the old ones, which are set aside in fresh locals while the object being made stays on the
         * stack, and X's new constructor is called on it. The object cannot be passed to a method before its constructor
         * runs, so this is written in place rather than in an added method.
         */
        boolean constructed(String owner, String declOwner, String desc) {
            String now = cls(owner);
            if (!b.classes.containsKey(now)) {
                return false;
            }
            Type[] old = Type.getArgumentTypes(desc);
            Type[] params = new Type[old.length];
            for (int i = 0; i < old.length; i++) {
                params[i] = Type.getType(desc(old[i].getDescriptor()));
                if (!present(mod, params[i])) {
                    return false;
                }
            }
            for (String o : new LinkedHashSet<>(List.of(owner, declOwner))) {
                for (Fix f : fixes.getOrDefault(o, List.of())) {
                    Matcher n = f.name().matcher("<init>");
                    if (!n.matches() || f.args() != null && !desc.startsWith(f.args())) {
                        continue;
                    }
                    try {
                        FixExpr.Node node = parsed(FixExpr.fill(f.expr(), n));
                        if (!(node instanceof FixExpr.New made) || !made.type().replace('.', '/').equals(now) || dropsUnnamed(f, node, old.length)) {
                            continue;
                        }
                        Lowering low = new Lowering(mod, callers, params, false, nextLocal);
                        Member m = low.choose(low.methods(now, "<init>"), made.args(), now + ".<init>");
                        Code code = new Code();
                        low.arguments(m, made.args(), code);
                        for (int i = old.length - 1; i >= 0; i--) {
                            super.visitVarInsn(old[i].getOpcode(Opcodes.ISTORE), low.slots[i]);
                        }
                        nextLocal = low.locals;
                        extraStack = Math.max(extraStack, code.max);
                        code.steps.forEach(step -> step.accept(mv));
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, inverse.getOrDefault(now, now), "<init>", oldDesc(m.desc()), false);
                        return true;
                    } catch (Refused | IllegalArgumentException notHere) {
                        // Another rule may still give one that links.
                    }
                }
            }
            return false;
        }

        void invokeAdded(String[] added) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, mod.self, added[0], added[1], mod.selfInterface);
        }

        /** A call in the new release's names, written in the old ones (the class renamer runs after), as an interface call where it is one. */
        void invoke(boolean isStatic, Call c) {
            GameDiff.Cls holder = b.classes.get(c.owner());
            boolean itf = holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
            int op = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            super.visitMethodInsn(op, inverse.getOrDefault(c.owner(), c.owner()), c.name(), oldDesc(c.desc()), itf);
        }

        /** Stores the arguments from index from on, which are on top of the stack, in fresh locals; returns each one's slot. */
        int[] spill(Type[] args, int from) {
            int[] slots = new int[args.length];
            for (int i = args.length - 1; i >= from; i--) {
                slots[i] = nextLocal;
                nextLocal += args[i].getSize();
                super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slots[i]);
            }
            return slots;
        }

        Shift moved; // set when this body moved to a game method's new parameter types

        /** -Djarport.where=true: each unlinked call says which method makes it. */
        static final boolean WHERE = Boolean.getBoolean("jarport.where");

        /**
         * A call that does not link, made in a body moved to a game method's new parameter types (moved), to a method that
         * now takes what the game passes: each candidate {owner, name, desc, "static" | "" | "mod"} (new names) is tried in
         * order. Each new parameter is an argument of its type (paired as convert pairs them), or the moved method's own new
         * parameter that the old arguments of the call were worked out from (renderBackground(poseStack) inside render(
         * GuiGraphics...) becomes renderBackground(graphics); keyPressed(key, scan, mods) becomes keyPressed(event)), or the
         * moved method's new parameter that no old one came from, the one of its type. Every old argument must be passed
         * or be worked out from a new parameter that is passed. A static call may become a call on that parameter
         * (GuiComponent.fill(poseStack, ...) -> graphics.fill(...)).
         */
        boolean given(int opcode, String owner, String desc, List<String[]> candidates) {
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            if (src == null || Arrays.stream(src).allMatch(Objects::isNull)) {
                return false;
            }
            Type[] mine = Type.getArgumentTypes(moved.br.desc());
            int[] from = moved.br.from();
            Type[] argsNew = Arrays.stream(args).map(t -> Type.getType(desc(t.getDescriptor()))).toArray(Type[]::new);
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            for (String[] c : candidates) {
                // The mod's own moved method is static exactly where the call is (a helper moved with its parameter).
                boolean own = c[3].equals("mod"), cStatic = c[3].equals("static") || own && isStatic;
                if (!isStatic && cStatic || !own && !linksNew(mod, callers, c[0], 'm', c[1], c[2])) {
                    continue;
                }
                List<Type> params = new ArrayList<>();
                boolean receiver = isStatic && !cStatic;
                if (receiver) {
                    params.add(Type.getObjectType(c[0]));
                }
                params.addAll(Arrays.asList(Type.getArgumentTypes(c[2])));
                Type[] want = params.toArray(Type[]::new);
                int[] argOf = pair(argsNew, want), newOf = new int[want.length];
                boolean[] used = new boolean[args.length];
                Set<Integer> passed = new HashSet<>();
                boolean ok = true;
                for (int j = 0; j < want.length && ok; j++) {
                    newOf[j] = -1;
                    // An old parameter the moved body has no value for (a default) is never passed on as a value.
                    if (argOf[j] >= 0 && !used[argOf[j]] && (src[argOf[j]] == null || from[src[argOf[j]]] >= 0)) {
                        used[argOf[j]] = true;
                        continue;
                    }
                    argOf[j] = -1;
                    for (int r = 0; r < mine.length && newOf[j] < 0; r++) {
                        if (!mine[r].equals(want[j]) || passed.contains(r)) {
                            continue;
                        }
                        for (int k = 0; k < args.length; k++) {
                            if (!used[k] && src[k] != null && from[src[k]] == r) {
                                newOf[j] = r;
                            }
                        }
                    }
                    if (newOf[j] < 0) {
                        List<Integer> free = new ArrayList<>();
                        for (int r = 0; r < mine.length; r++) {
                            int rr = r;
                            if (mine[r].equals(want[j]) && Arrays.stream(from).noneMatch(f -> f == rr)) {
                                free.add(r);
                            }
                        }
                        newOf[j] = free.size() == 1 ? free.get(0) : -1;
                    }
                    ok = newOf[j] >= 0;
                    if (ok) {
                        passed.add(newOf[j]);
                    }
                }
                for (int k = 0; k < args.length && ok; k++) {
                    ok = used[k] || src[k] != null && (from[src[k]] < 0 || passed.contains(from[src[k]]));
                }
                Type was = Type.getType(desc(Type.getReturnType(desc).getDescriptor())), now = Type.getReturnType(c[2]);
                if (!ok || !(was.equals(now) || was.getSort() == Type.VOID || usable(now, was))) {
                    continue;
                }
                int[] slots = spill(args, 0);
                for (int j = 0; j < want.length; j++) {
                    if (argOf[j] >= 0) {
                        super.visitVarInsn(args[argOf[j]].getOpcode(Opcodes.ILOAD), slots[argOf[j]]);
                    } else {
                        moved.loadNew(newOf[j]);
                    }
                }
                extraStack = Math.max(extraStack, 2 * want.length);
                GameDiff.Cls holder = b.classes.get(c[0]);
                boolean itf = own ? mod.classes.get(c[0]) != null && (mod.classes.get(c[0]).flags & Opcodes.ACC_INTERFACE) != 0
                    : holder != null && (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                int op = opcode == Opcodes.INVOKESPECIAL ? opcode : cStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                String site = opcode == Opcodes.INVOKESPECIAL ? owner : own ? c[0] : inverse.getOrDefault(c[0], c[0]);
                super.visitMethodInsn(op, site, c[1], oldDesc(c[2]), itf);
                if (was.getSort() == Type.VOID && now.getSort() != Type.VOID) {
                    super.visitInsn(now.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                }
                mod.change("call given what the game passes now");
                return true;
            }
            return false;
        }

        /**
         * A call that does not link, in a moved body, one of whose arguments is an old parameter worked out from a new one
         * (pose = graphics.pose()): written as the game's own code wrote it with the new value there (GameDiff.Retyped's
         * reading, font.draw(pose, ...) -> graphics.drawString(font, ...)), the new parameter passed in the old one's place.
         */
        /**
         * A call from a moved body to one of the mod's own helpers that cannot be overridden (static, private or final),
         * passing an old parameter worked out from a new one (renderLabel(pose, ...) inside render(GuiGraphics, ...)): the
         * helper is wanted with that parameter as the new type too, so the calls it makes on it get what the game passes
         * now. port adds it and rewrites the mod again (Mod.twins).
         */
        void twin(String owner, String name, String desc) {
            GameDiff.RawCls rc = mod.classes.get(owner);
            GameDiff.RawMeth m = rc == null ? null : rc.methods.get(name + desc);
            if (m == null || name.startsWith("<") || (m.flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || (m.flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0 && (rc.flags & Opcodes.ACC_FINAL) == 0) {
                return;
            }
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            for (int k = 0; src != null && k < args.length; k++) {
                if (src[k] == null || moved.br.via()[src[k]] == null || moved.br.from()[src[k]] < 0) {
                    continue;
                }
                Type q = Type.getArgumentTypes(moved.br.desc())[moved.br.from()[src[k]]];
                mod.twins.putIfAbsent(owner + "." + name + desc, new Object[] { k, q.getDescriptor(), moved.br.via()[src[k]] });
                return;
            }
        }

        static final boolean DEBUG = Boolean.getBoolean("jarport.debug");

        boolean onOrigin(int opcode, String owner, String declOwner, String name, String desc) {
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            Type[] args = Type.getArgumentTypes(desc);
            Integer[] src = moved.track.sources(args);
            if (DEBUG && name.equals("draw")) {
                System.err.println("onOrigin " + declOwner + "." + name + desc + " src=" + Arrays.toString(src) + " calls=" + retypedCalls.getOrDefault(declOwner, List.of()).stream().map(RetypedCall::desc).toList());
            }
            for (RetypedCall c : retypedCalls.getOrDefault(declOwner, List.of())) {
                int k = c.at() - 1; // $0 is the receiver, $1 the first argument (static calls have no $0)
                if (!c.name().equals(name) || !c.desc().equals(desc) || src == null || k < 0 || k >= args.length || src[k] == null) {
                    continue;
                }
                int r = moved.br.from()[src[k]];
                if (r < 0 || moved.br.via()[src[k]] == null || !Type.getArgumentTypes(moved.br.desc())[r].getDescriptor().equals(c.as())) {
                    continue;
                }
                List<Type> ops = new ArrayList<>();
                if (!isStatic) {
                    ops.add(Type.getObjectType(owner));
                }
                ops.addAll(Arrays.asList(args));
                ops.set(isStatic ? k : c.at(), Type.getType(oldDesc(c.as())));
                String opDesc = Type.getMethodDescriptor(Type.getReturnType(desc), ops.toArray(Type[]::new));
                Fix fix = new Fix("game " + name, Pattern.compile(Pattern.quote(name)), null, c.expr());
                String[] added = fix(Map.of(declOwner, List.of(fix)), List.of(declOwner), name, desc, opDesc, !isStatic);
                if (DEBUG) {
                    System.err.println("  fix " + c.expr() + " " + opDesc + " -> " + Arrays.toString(added));
                }
                if (added == null) {
                    continue;
                }
                int[] slots = spill(args, 0);
                for (int i = 0; i < args.length; i++) {
                    if (i == k) {
                        moved.loadNew(r);
                    } else {
                        super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
                    }
                }
                invokeAdded(added);
                mod.change("call written on what the game passes now, as the game's own code writes it");
                return true;
            }
            return false;
        }

        /** Takes the arguments not in keep off the stack: all are stored in fresh locals and the kept ones loaded back. */
        void drop(String desc, int[] keep) {
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, 0);
            for (int k : keep) {
                super.visitVarInsn(args[k].getOpcode(Opcodes.ILOAD), slots[k]);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            types(mod, desc, where);
            Object[] mapped = args.clone();
            for (int i = 0; i < mapped.length; i++) {
                if (mapped[i] instanceof Handle h) {
                    mapped[i] = handle(h);
                }
            }
            for (Object o : mapped) {
                if (o instanceof Type t) {
                    types(mod, t.getDescriptor(), where);
                }
            }
            super.visitInvokeDynamicInsn(name, desc, bsm, mapped);
        }

        @Override
        public void visitLdcInsn(Object value) {
            fresh = false;
            if (value instanceof Type t) {
                types(mod, t.getDescriptor(), where);
            }
            super.visitLdcInsn(value instanceof Handle h ? handle(h) : value);
        }

        /** A method reference keeps its call site's types, so only a rename with the same descriptor (after class renames) links. */
        Handle handle(Handle h) {
            if (h.getTag() > Opcodes.H_PUTSTATIC) {
                String[] own = mod.renamedDecl.get(h.getOwner() + "." + h.getName() + h.getDesc());
                if (own != null) {
                    return new Handle(h.getTag(), h.getOwner(), own[0], oldDesc(own[1]), h.isInterface());
                }
                Decl decl = h.getOwner().startsWith("[") ? null : declaringOld(mod, h.getOwner(), 'm', h.getName(), h.getDesc());
                if (decl == null || !decl.game()) {
                    return h;
                }
                String[] t = target(mod, callers, h.getOwner(), 'm', h.getName(), h.getDesc(), decl);
                if (t == null || !t[2].equals(desc(h.getDesc()))) {
                    int tag = h.getTag();
                    if (tag == Opcodes.H_INVOKESTATIC || tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_NEWINVOKESPECIAL) {
                        // X::new takes the arguments and gives an X, as the factory replacing X's constructor does.
                        String opDesc = tag == Opcodes.H_INVOKESTATIC ? h.getDesc()
                            : tag == Opcodes.H_NEWINVOKESPECIAL ? h.getDesc().substring(0, h.getDesc().indexOf(')') + 1) + "L" + h.getOwner() + ";"
                            : "(L" + h.getOwner() + ";" + h.getDesc().substring(1);
                        String[] added = fix(List.of(h.getOwner(), decl.owner()), h.getName(), h.getDesc(), opDesc, tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_NEWINVOKESPECIAL);
                        if (added != null) {
                            mod.change("method reference replaced by a port rule");
                            return new Handle(Opcodes.H_INVOKESTATIC, mod.self, added[0], added[1], mod.selfInterface);
                        }
                    }
                    mod.unlinked.add("method reference: " + Exposure.dotted(decl.owner()) + "." + h.getName() + h.getDesc());
                    return h;
                }
                if (!t[1].equals(h.getName()) || !t[0].equals(cls(h.getOwner()))) {
                    mod.change("method reference renamed");
                }
                GameDiff.Cls holder = b.classes.get(t[0]);
                boolean itf = holder == null ? h.isInterface() : (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                int tag = h.getTag() == Opcodes.H_INVOKEVIRTUAL && itf ? Opcodes.H_INVOKEINTERFACE
                    : h.getTag() == Opcodes.H_INVOKEINTERFACE && !itf ? Opcodes.H_INVOKEVIRTUAL : h.getTag();
                return new Handle(tag, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), itf);
            }
            return h;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            types(mod, type.startsWith("[") ? type : "L" + type + ";", where);
            super.visitTypeInsn(opcode, type);
            if (opcode == Opcodes.NEW) {
                news.push(new String[] { type, null });
                fresh = true;
            } else {
                fresh = false;
            }
        }

        // Objects made with new and not yet constructed, innermost first: {class, "dup" when new was followed by dup}.
        final Deque<String[]> news = new ArrayDeque<>();
        boolean fresh; // the last instruction was a new

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP && fresh && !news.isEmpty()) {
                news.peek()[1] = "dup";
            }
            fresh = false;
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            fresh = false;
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            fresh = false;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            fresh = false;
            super.visitJumpInsn(opcode, label);
        }

        /**
         * new X(args) where X's constructor does not link and a replacement gives a value of X some other way (1.21:
         * ResourceLocation.fromNamespaceAndPath for the closed constructor): the arguments are set aside, the two
         * references to the object not yet constructed that new and dup left are dropped, and the replacement is called.
         * Only where new was followed directly by dup, as javac and kotlinc write it.
         */
        boolean made(String owner, String declOwner, String desc) {
            String[] pending = news.isEmpty() ? null : news.peek();
            if (pending == null || !pending[0].equals(owner) || pending[1] == null) {
                return false;
            }
            String[] added = fix(List.of(owner, declOwner), "<init>", desc, desc.substring(0, desc.indexOf(')') + 1) + "L" + owner + ";", false);
            if (added == null) {
                return false;
            }
            news.pop();
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = spill(args, 0);
            super.visitInsn(Opcodes.POP);
            super.visitInsn(Opcodes.POP);
            for (int i = 0; i < args.length; i++) {
                super.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
            }
            invokeAdded(added);
            return true;
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            types(mod, desc, where);
            super.visitMultiANewArrayInsn(desc, dims);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                types(mod, "L" + type + ";", where);
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }
    }

    /** Every game class a descriptor or signature names that the new release no longer has (and no rename covers) is reported. */
    void types(Mod mod, String desc, String where) {
        if (desc == null) {
            return;
        }
        Matcher m = Pattern.compile("L([^;<]+)[;<]").matcher(desc);
        while (m.find()) {
            String t = m.group(1);
            if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t)) && provided(t) == null) {
                mod.unlinked.add("class gone: " + Exposure.dotted(t) + " (in " + where + ")");
            }
        }
    }

    // ---- Port rules applied in the class.

    /** A port rule's replacement for game members of one old class ("inClass"): name is a regex, args a descriptor's start or null. */
    record Fix(String rule, Pattern name, String args, String expr) {}

    final Map<String, List<Fix>> fixes = new HashMap<>(); // old owner (internal) -> replacements its rules give

    static final Pattern DESCRIBED = Pattern.compile("([\\w$<>]+)(\\((?:\\[*(?:[ZBCSIJFD]|L[\\w/$]+;))*\\)\\S*)");

    /**
     * Reads every rule's "inClass" from a rule book (knowledge/fixes.json): "a.b.Owner.name[(args)]" -> expression (FixExpr),
     * or a class the new release dropped -> the class its values become.
     */
    void fixes(Path book) throws IOException {
        for (Object o : Json.array(Json.read(book).get("rules"))) {
            var rule = Json.object(o);
            if (!covers(Json.text(rule, "jump", ""), Json.text(rule, "loader", ""))) {
                continue;
            }
            for (var e : Json.object(rule.getOrDefault("inClass", Map.of())).entrySet()) {
                // A class the new release dropped, and the one its values become (BlockEntityType$Builder -> BlockEntityType).
                String gone = e.getKey().replace('.', '/');
                String now = String.valueOf(e.getValue()).replace('.', '/');
                if (a.classes.containsKey(gone) && !b.classes.containsKey(gone) && b.classes.containsKey(now)) {
                    classMap.putIfAbsent(gone, now);
                    continue;
                }
                int dot = e.getKey().lastIndexOf('.');
                String rest = e.getKey().substring(dot + 1);
                Matcher d = DESCRIBED.matcher(rest);
                boolean described = d.matches();
                Fix fix = new Fix(Json.text(rule, "id", ""), Pattern.compile(described ? Pattern.quote(d.group(1)) : rest), described ? d.group(2) : null, String.valueOf(e.getValue()));
                fixes.computeIfAbsent(e.getKey().substring(0, dot).replace('.', '/'), k -> new ArrayList<>()).add(fix);
            }
        }
    }

    /**
     * Whether a rule written for "A -> B" (and one loader, when it names one) belongs on this port: its update lies
     * between the releases ported from and to, and the loader is the one ported for.
     */
    boolean covers(String jump, String loader) {
        if (!loader.isEmpty() && !loader.equals(this.loader)) {
            return false;
        }
        String[] range = jump.replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
        if (range.length != 2) {
            return true;
        }
        return compareVersions(a.version, range[1]) < 0 && compareVersions(b.version, range[0]) > 0;
    }

    /** Releases in order: 1.21.1 < 1.21.2 < 1.21.10 < 26.1. */
    static int compareVersions(String x, String y) {
        String[] p = x.split("[.-]");
        String[] q = y.split("[.-]");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int a = i < p.length && p[i].matches("\\d+") ? Integer.parseInt(p[i]) : 0;
            int b = i < q.length && q[i].matches("\\d+") ? Integer.parseInt(q[i]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    /** A private static method added to the class being rewritten: a rule's replacement, taking the old member's operands. */
    record Added(String name, String desc, Code code, int locals) {}

    /** The type of null, which fits any reference. */
    static final Type NULL = Type.getObjectType("null");

    /** Code being built: each step writes to a method, and the stack it needs is counted as it goes. */
    static final class Code {
        final List<java.util.function.Consumer<MethodVisitor>> steps = new ArrayList<>();
        int depth;
        int max;

        void add(int change, java.util.function.Consumer<MethodVisitor> step) {
            steps.add(step);
            depth += change;
            max = Math.max(max, depth);
        }

        void append(Code other) {
            steps.addAll(other.steps);
            max = Math.max(max, depth + other.max);
            depth += other.depth;
        }
    }

    /** A replacement that does not link or type in the new release; it is not written. */
    static final class Refused extends RuntimeException {
        Refused(String why) {
            super(why, null, false, false);
        }
    }

    final Map<String, FixExpr.Node> parsed = new HashMap<>(); // a rule's expression, filled, once parsed

    FixExpr.Node parsed(String expr) {
        FixExpr.Node n = parsed.get(expr);
        if (n == null) {
            n = FixExpr.parse(expr);
            parsed.put(expr, n);
        }
        return n;
    }

    /**
     * An argument left out is a decision about one overload, so only a rule naming that overload's arguments may drop
     * one: spawnAtLocation(stack) -> ...($1) must not also take spawnAtLocation(stack, offset).
     */
    static boolean dropsUnnamed(Fix f, FixExpr.Node node, int arguments) {
        Set<Integer> used = FixExpr.operands(node);
        for (int i = 1; i <= arguments; i++) {
            if (!used.contains(i)) {
                return f.args() == null;
            }
        }
        return false;
    }

    /** A JDK class by internal name, or null. Only java.* is looked up: the game's own classes come from the snapshot. */
    static Class<?> jdk(String internal) {
        if (!internal.startsWith("java/")) {
            return null;
        }
        try {
            return Class.forName(internal.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** Whether a type exists for code running on the new release: the new release or the mod has it, or the old release never did (a library's). */
    boolean present(Mod mod, Type t) {
        Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
        if (e.getSort() != Type.OBJECT) {
            return true;
        }
        String n = e.getInternalName();
        return b.classes.containsKey(n) || mod.classes.containsKey(n) || !a.classes.containsKey(n);
    }

    boolean isInterface(Mod mod, String internal) {
        GameDiff.Cls game = b.classes.get(internal);
        if (game != null) {
            return (game.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        GameDiff.RawCls own = mod.classes.get(internal);
        if (own != null) {
            return (own.flags & Opcodes.ACC_INTERFACE) != 0;
        }
        Class<?> k = jdk(internal);
        return k != null && k.isInterface();
    }

    /** A method or constructor as a call names it (owner is the class the instruction names), and whether the caller may use it. */
    record Member(String owner, String name, String desc, boolean isStatic, boolean itf, boolean open) {}

    /** The pieces of an invokedynamic making a functional interface wanted from a static method owner::name, or null. */
    Object[] lambda(Mod mod, List<String> callers, String owner, String name, Type wanted) {
        String[] sam = wanted.getSort() == Type.OBJECT ? functional(wanted.getInternalName()) : null;
        GameDiff.Cls holder = b.classes.get(owner);
        if (sam == null || holder == null) {
            return null;
        }
        int arity = Type.getArgumentTypes(sam[1]).length;
        List<GameDiff.Meth> impls = holder.methods.values().stream()
            .filter(m -> m.name.equals(name) && (m.flags & Opcodes.ACC_STATIC) != 0 && Type.getArgumentTypes(m.desc).length == arity)
            .toList();
        if (impls.size() != 1 || !memberOpen(mod, callers, owner, impls.get(0).name + impls.get(0).desc, impls.get(0).flags)) {
            return null;
        }
        GameDiff.Meth impl = impls.get(0);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Handle target = new Handle(Opcodes.H_INVOKESTATIC, inverse.getOrDefault(owner, owner), impl.name, oldDesc(impl.desc), (holder.flags & Opcodes.ACC_INTERFACE) != 0);
        return new Object[] { sam[0], "()" + oldDesc(wanted.getDescriptor()), factory, new Object[] { Type.getType(sam[1]), target, Type.getType(oldDesc(impl.desc)) } };
    }

    /**
     * One rule's replacement compiled for one site, the way javac would compile it against the new release: names are
     * resolved there (or in the JDK), overloads chosen by the operands' types, and every member must be one the caller may
     * use. Anything else is Refused. Code is written in the old release's names; the class renamer runs after.
     */
    final class Lowering {
        final Mod mod;
        final List<String> callers;
        final Type[] params;
        final boolean receiver;
        final int[] slots;
        int locals;

        /**
         * params: the old member's operands in new names, held in locals from first on; with receiver the first is $0,
         * else $1 is the first.
         */
        Lowering(Mod mod, List<String> callers, Type[] params, boolean receiver, int first) {
            this.mod = mod;
            this.callers = callers;
            this.params = params;
            this.receiver = receiver;
            slots = new int[params.length];
            locals = first;
            for (int i = 0; i < params.length; i++) {
                slots[i] = locals;
                locals += params[i].getSize();
            }
        }

        Type lower(FixExpr.Node n, Code out) {
            return switch (n) {
                case FixExpr.Arg a -> arg(a.index(), out);
                case FixExpr.Lit l -> literal(l, out);
                case FixExpr.Path p -> path(p.dotted(), out);
                case FixExpr.Get g -> field(lower(g.target(), out), g.name(), false, out);
                case FixExpr.Invoke i -> invoke(i, out);
                case FixExpr.New w -> construct(w, out);
                case FixExpr.Cast c -> cast(c, out);
                case FixExpr.Ref r -> throw new Refused("a method reference is taken only as an argument");
            };
        }

        Type arg(int index, Code out) {
            int k = receiver ? index : index - 1;
            if (k < 0 || k >= params.length) {
                throw new Refused("$" + index + " is not an operand of the old member");
            }
            Type t = params[k];
            int slot = slots[k];
            out.add(t.getSize(), mv -> mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot));
            return t;
        }

        Type literal(FixExpr.Lit l, Code out) {
            if (l.type() == null) {
                out.add(1, mv -> mv.visitInsn(Opcodes.ACONST_NULL));
                return NULL;
            }
            Type t = Type.getType(l.type());
            Object v = l.value();
            if (v instanceof Boolean z) {
                out.add(1, mv -> mv.visitInsn(z ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            } else {
                out.add(t.getSize(), mv -> mv.visitLdcInsn(v));
            }
            return t;
        }

        boolean known(String internal) {
            return b.classes.containsKey(internal) || jdk(internal) != null;
        }

        String type(String dotted) {
            String internal = dotted.replace('.', '/');
            if (!known(internal)) {
                throw new Refused(dotted + " is not a class of the new release");
            }
            return internal;
        }

        /** a.b.C.F.G: the longest start that is a class, then its static field F and G of that. */
        Type path(String dotted, Code out) {
            String[] parts = dotted.split("\\.");
            for (int i = parts.length - 1; i >= 1; i--) {
                String owner = String.join("/", Arrays.copyOf(parts, i));
                if (!known(owner)) {
                    continue;
                }
                Type t = field(Type.getObjectType(owner), parts[i], true, out);
                for (int k = i + 1; k < parts.length; k++) {
                    t = field(t, parts[k], false, out);
                }
                return t;
            }
            throw new Refused(dotted + " is not a field of the new release");
        }

        Type field(Type owner, String name, boolean isStatic, Code out) {
            if (owner.getSort() != Type.OBJECT || owner == NULL) {
                throw new Refused("no field " + name + " on " + owner);
            }
            String o = owner.getInternalName();
            String desc;
            Class<?> k = jdk(o);
            if (k != null) {
                java.lang.reflect.Field f;
                try {
                    f = k.getField(name);
                } catch (NoSuchFieldException absent) {
                    throw new Refused("no field " + name + " on " + o);
                }
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) != isStatic) {
                    throw new Refused(name + " is not a " + (isStatic ? "static" : "instance") + " field");
                }
                desc = Type.getDescriptor(f.getType());
            } else {
                FieldHit f = fieldNew(mod, callers, o, name);
                GameDiff.Cls holder = Exposure.declaring(b, o, 'f', name, null);
                if (f == null || !f.open() || holder == null || ((holder.fields.get(name).flags & Opcodes.ACC_STATIC) != 0) != isStatic) {
                    throw new Refused("no field " + name + " the caller may read on " + o);
                }
                desc = f.desc();
            }
            Type t = Type.getType(desc);
            String ownerOld = inverse.getOrDefault(o, o);
            String descOld = oldDesc(desc);
            out.add(t.getSize() - (isStatic ? 0 : 1), mv -> mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, ownerOld, name, descOld));
            return t;
        }

        /** Every method named name that a call on owner reaches, most derived first; static ones are named on their own class. */
        List<Member> methods(String owner, String name) {
            List<Member> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean itf = isInterface(mod, owner);
            boolean open = classOpen(mod, callers, owner);
            Deque<String> queue = new ArrayDeque<>(List.of(owner));
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                String c = queue.poll();
                if (!visited.add(c)) {
                    continue;
                }
                GameDiff.RawCls own = mod.classes.get(c);
                GameDiff.Cls game = own == null ? b.classes.get(c) : null;
                Class<?> k = own == null && game == null ? jdk(c) : null;
                if (game != null) {
                    for (GameDiff.Meth m : game.methods.values()) {
                        if (m.name.equals(name) && seen.add(m.desc)) {
                            boolean st = (m.flags & Opcodes.ACC_STATIC) != 0;
                            boolean usable = open && memberOpen(mod, callers, c, m.name + m.desc, m.flags) && (m.flags & Opcodes.ACC_SYNTHETIC) == 0;
                            out.add(new Member(st ? c : owner, name, m.desc, st, st ? isInterface(mod, c) : itf, usable));
                        }
                    }
                } else if (k != null) {
                    boolean pub = java.lang.reflect.Modifier.isPublic(k.getModifiers());
                    if (name.equals("<init>")) {
                        for (var ctor : k.getConstructors()) {
                            if (seen.add(Type.getConstructorDescriptor(ctor))) {
                                out.add(new Member(c, name, Type.getConstructorDescriptor(ctor), false, false, pub));
                            }
                        }
                    }
                    for (var m : k.getMethods()) {
                        String d = Type.getMethodDescriptor(m);
                        if (m.getName().equals(name) && !m.isBridge() && seen.add(d)) {
                            boolean st = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                            String decl = Type.getInternalName(m.getDeclaringClass());
                            out.add(new Member(st ? decl : owner, name, d, st, st ? m.getDeclaringClass().isInterface() : itf, pub));
                        }
                    }
                }
                if (name.equals("<init>")) {
                    break;
                }
                String sup = own != null ? (own.sup == null ? null : cls(own.sup)) : game != null ? game.sup : null;
                if (sup != null) {
                    queue.add(sup);
                }
                if (own != null) {
                    own.ifaces.forEach(i -> queue.add(cls(i)));
                } else if (game != null) {
                    queue.addAll(game.ifaces);
                }
            }
            return out;
        }

        Type invoke(FixExpr.Invoke i, Code out) {
            Code target = new Code();
            String owner;
            boolean isStatic = i.target() instanceof FixExpr.Path p && known(p.dotted().replace('.', '/'));
            if (isStatic) {
                owner = ((FixExpr.Path) i.target()).dotted().replace('.', '/');
            } else {
                Type recv = lower(i.target(), target);
                if (recv.getSort() != Type.OBJECT || recv == NULL) {
                    throw new Refused("no method " + i.name() + " on " + recv);
                }
                owner = recv.getInternalName();
            }
            boolean wantStatic = isStatic;
            Member m = choose(methods(owner, i.name()).stream().filter(x -> x.isStatic() == wantStatic).toList(), i.args(), owner + "." + i.name());
            out.append(target);
            int popped = arguments(m, i.args(), out) + (isStatic ? 0 : 1);
            Type ret = Type.getReturnType(m.desc());
            int op = isStatic ? Opcodes.INVOKESTATIC : m.itf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            out.add(ret.getSize() - popped, mv -> mv.visitMethodInsn(op, inverse.getOrDefault(m.owner(), m.owner()), m.name(), oldDesc(m.desc()), m.itf()));
            return ret;
        }

        Type construct(FixExpr.New w, Code out) {
            String owner = type(w.type());
            GameDiff.Cls game = b.classes.get(owner);
            Class<?> k = jdk(owner);
            int flags = game != null ? game.flags : k.getModifiers();
            if ((flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
                throw new Refused(w.type() + " cannot be made with new");
            }
            Member m = choose(methods(owner, "<init>"), w.args(), owner + ".<init>");
            String old = inverse.getOrDefault(owner, owner);
            out.add(1, mv -> mv.visitTypeInsn(Opcodes.NEW, old));
            out.add(1, mv -> mv.visitInsn(Opcodes.DUP));
            int popped = arguments(m, w.args(), out) + 1;
            out.add(-popped, mv -> mv.visitMethodInsn(Opcodes.INVOKESPECIAL, old, "<init>", oldDesc(m.desc()), false));
            return Type.getObjectType(owner);
        }

        /** Writes each argument converted to m's parameter; returns the stack slots they take. */
        int arguments(Member m, List<FixExpr.Node> args, Code out) {
            Type[] ps = Type.getArgumentTypes(m.desc());
            int slots = 0;
            for (int k = 0; k < ps.length; k++) {
                Type p = ps[k];
                if (args.get(k) instanceof FixExpr.Ref r) {
                    Object[] indy = lambda(mod, callers, type(r.type()), r.name(), p);
                    Object[] bsmArgs = (Object[]) indy[3];
                    out.add(1, mv -> mv.visitInvokeDynamicInsn((String) indy[0], (String) indy[1], (Handle) indy[2], bsmArgs));
                } else {
                    Type got = lower(args.get(k), out);
                    widen(got, p, out);
                }
                slots += p.getSize();
            }
            return slots;
        }

        /** The one most specific candidate the arguments fit, as javac picks it (no varargs expansion, no boxing). */
        Member choose(List<Member> candidates, List<FixExpr.Node> args, String what) {
            List<Type> types = new ArrayList<>();
            for (FixExpr.Node a : args) {
                types.add(a instanceof FixExpr.Ref ? null : lower(a, new Code()));
            }
            List<Member> fit = new ArrayList<>();
            for (Member m : candidates) {
                Type[] ps = Type.getArgumentTypes(m.desc());
                boolean ok = ps.length == args.size();
                for (int k = 0; ok && k < ps.length; k++) {
                    ok = types.get(k) == null
                        ? args.get(k) instanceof FixExpr.Ref r && known(r.type().replace('.', '/')) && lambda(mod, callers, r.type().replace('.', '/'), r.name(), ps[k]) != null
                        : fits(types.get(k), ps[k]);
                }
                if (ok) {
                    fit.add(m);
                }
            }
            List<Member> best = fit.stream().filter(m -> fit.stream().allMatch(o -> o == m || narrower(m, o))).toList();
            if (best.size() != 1) {
                throw new Refused(what + (fit.isEmpty() ? " takes no such arguments" : " is ambiguous for these arguments"));
            }
            if (!best.get(0).open()) {
                throw new Refused(what + " is closed to the caller");
            }
            return best.get(0);
        }

        boolean narrower(Member m, Member o) {
            Type[] a = Type.getArgumentTypes(m.desc());
            Type[] b = Type.getArgumentTypes(o.desc());
            for (int k = 0; k < a.length; k++) {
                if (!fits(a[k], b[k])) {
                    return false;
                }
            }
            return true;
        }

        Type cast(FixExpr.Cast c, Code out) {
            Type got = lower(c.expr(), out);
            if (FixExpr.PRIMITIVES.contains(c.type())) {
                Type to = Type.getType(switch (c.type()) {
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "char" -> "C";
                    case "short" -> "S";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    default -> "D";
                });
                if (got.getSort() < Type.CHAR || got.getSort() > Type.DOUBLE || to.getSort() == Type.BOOLEAN) {
                    throw new Refused("cannot cast " + got + " to " + c.type());
                }
                convert(got, to, out);
                return to;
            }
            String to = type(c.type());
            if (got.getSort() != Type.OBJECT && got.getSort() != Type.ARRAY) {
                throw new Refused("cannot cast " + got + " to " + c.type());
            }
            String old = inverse.getOrDefault(to, to);
            out.add(0, mv -> mv.visitTypeInsn(Opcodes.CHECKCAST, old));
            return Type.getObjectType(to);
        }

        /** Whether a value of type from may be passed where to is wanted, as Java allows without a cast (no boxing). */
        boolean fits(Type from, Type to) {
            if (from == NULL) {
                return to.getSort() == Type.OBJECT || to.getSort() == Type.ARRAY;
            }
            if (from.equals(to)) {
                return true;
            }
            if (from.getSort() < Type.ARRAY || to.getSort() < Type.ARRAY) {
                return from.getSort() >= Type.CHAR && to.getSort() > Type.CHAR && from.getSort() <= Type.DOUBLE && to.getSort() <= Type.DOUBLE
                    && WIDER.getOrDefault(from.getSort(), Set.of()).contains(to.getSort());
            }
            if (to.getDescriptor().equals("Ljava/lang/Object;")) {
                return true;
            }
            if (from.getSort() == Type.ARRAY) {
                if (to.getSort() != Type.ARRAY) {
                    return to.getInternalName().equals("java/lang/Cloneable") || to.getInternalName().equals("java/io/Serializable");
                }
                Type fe = Type.getType(from.getDescriptor().substring(1));
                Type te = Type.getType(to.getDescriptor().substring(1));
                return fe.getSort() >= Type.ARRAY && te.getSort() >= Type.ARRAY && fits(fe, te);
            }
            return to.getSort() == Type.OBJECT && isA(mod, from.getInternalName(), to.getInternalName());
        }

        /** The conversion Java makes when a value is passed or returned where a wider primitive is wanted. */
        void widen(Type from, Type to, Code out) {
            if (from.getSort() < Type.ARRAY && to.getSort() < Type.ARRAY && !from.equals(to)) {
                convert(from, to, out);
            }
        }

        void convert(Type from, Type to, Code out) {
            char f = from.getSort() <= Type.INT ? 'I' : from.getDescriptor().charAt(0);
            char t = to.getSort() <= Type.INT ? 'I' : to.getDescriptor().charAt(0);
            String pair = "" + f + t;
            Integer op = CONVERSIONS.get(pair);
            if (op != null) {
                out.add(to.getSize() - from.getSize(), mv -> mv.visitInsn(op));
            }
            int narrow = switch (to.getSort()) {
                case Type.BYTE -> Opcodes.I2B;
                case Type.CHAR -> Opcodes.I2C;
                case Type.SHORT -> Opcodes.I2S;
                default -> 0;
            };
            if (narrow != 0 && from.getSort() != to.getSort()) {
                out.add(0, mv -> mv.visitInsn(narrow));
            }
        }
    }

    static final Map<Integer, Set<Integer>> WIDER = Map.of(
        Type.BYTE, Set.of(Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.SHORT, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.CHAR, Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.INT, Set.of(Type.LONG, Type.FLOAT, Type.DOUBLE),
        Type.LONG, Set.of(Type.FLOAT, Type.DOUBLE),
        Type.FLOAT, Set.of(Type.DOUBLE)
    );

    static final Map<String, Integer> CONVERSIONS = Map.ofEntries(
        Map.entry("IJ", Opcodes.I2L), Map.entry("IF", Opcodes.I2F), Map.entry("ID", Opcodes.I2D),
        Map.entry("JI", Opcodes.L2I), Map.entry("JF", Opcodes.L2F), Map.entry("JD", Opcodes.L2D),
        Map.entry("FI", Opcodes.F2I), Map.entry("FJ", Opcodes.F2L), Map.entry("FD", Opcodes.F2D),
        Map.entry("DI", Opcodes.D2I), Map.entry("DJ", Opcodes.D2L), Map.entry("DF", Opcodes.D2F)
    );

    // ---- Metadata.

    static final Pattern BLOCK = Pattern.compile("(?ms)^\\s*\\[\\[dependencies\\.[^\\]]+\\]\\].*?(?=^\\s*\\[|\\z)");

    /** The loader metadata asks for the new release: the game's and the loader's version ranges of each dependency block. */
    String metadata(String toml) {
        Matcher m = BLOCK.matcher(toml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            Matcher id = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
            if (id.find()) {
                String range = switch (id.group(1)) {
                    case "minecraft" -> "[" + to + "]";
                    case "neoforge", "forge" -> loaderTo == null ? null : "[" + loaderTo.replaceAll("^(\\d+\\.\\d+).*", "$1") + ",)";
                    default -> null;
                };
                if (range != null) {
                    block = block.replaceFirst("versionRange\\s*=\\s*\"[^\"]*\"", Matcher.quoteReplacement("versionRange=\"" + range + "\""));
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(block));
        }
        return m.appendTail(sb).toString();
    }

    /** The access widener a fabric.mod.json names, or null. */
    static String widenerFile(byte[] fmj) {
        try {
            return Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8))).get("accessWidener") instanceof String w ? w : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * fabric.mod.json asks for the new release: its "minecraft" dependency becomes the release ported to, and it names the
     * access widener the port wrote when the mod had none. Anything unreadable is left as it is.
     */
    byte[] fabricMetadata(byte[] data, String widener) {
        Map<String, Object> meta;
        try {
            meta = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8)));
        } catch (RuntimeException unreadable) {
            return data;
        }
        if (meta.get("depends") instanceof Map<?, ?> depends && depends.containsKey("minecraft")) {
            Json.object(depends).put("minecraft", to);
        }
        if (widener != null && !meta.containsKey("accessWidener")) {
            meta.put("accessWidener", widener);
        }
        return Json.write(meta).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Access widener lines name game members in the old release (Mojang's names, as ModNames reads them); each one is moved
     * to its new name, and a line whose class or member is gone is commented out, as in an access transformer.
     */
    String accessWidener(Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length < 3 || p[0].equals("accessWidener") || p[0].equals("classTweaker")) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[2].replace('.', '/');
            String newOwner = cls(owner);
            String moved = null;
            boolean known = a.classes.containsKey(owner);
            if (!known) {
                moved = body; // another mod's class, or one the old release did not have: left as it is
            } else if (!b.classes.containsKey(newOwner)) {
                moved = null;
            } else if (p[1].equals("class")) {
                moved = p[0] + " class " + newOwner;
            } else if (p[1].equals("method") && p.length >= 5) {
                String[] m = route.method(owner, p[3], p[4]);
                GameDiff.Cls holder = Exposure.declaring(b, newOwner, 'm', m[1], m[2]);
                moved = holder == null ? null : p[0] + " method " + newOwner + " " + m[1] + " " + m[2];
            } else if (p[1].equals("field") && p.length >= 4) {
                String[] f = route.field(owner, p[3]);
                GameDiff.Cls holder = Exposure.declaring(b, newOwner, 'f', f[1], null);
                moved = holder == null ? null : p[0] + " field " + newOwner + " " + f[1] + " " + holder.fields.get(f[1]).desc;
            } else {
                moved = body;
            }
            if (moved == null) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(to).append('\n');
                mod.change("access widener line for a gone member commented out");
            } else {
                out.append(moved.equals(body) ? line : moved).append('\n');
            }
        }
        String s = out.toString();
        return s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s;
    }

    /** The port's own grants ("public a.b.C [member]") as access widener lines; a field's type comes from the new release. */
    String widenerLines(Set<String> grants) {
        StringBuilder out = new StringBuilder();
        for (String g : grants) {
            String[] p = g.split(" ");
            String owner = p[1].replace('.', '/');
            if (p.length == 2) {
                out.append("accessible class ").append(owner).append('\n');
            } else if (p[2].contains("(")) {
                int paren = p[2].indexOf('(');
                out.append("accessible method ").append(owner).append(' ').append(p[2], 0, paren).append(' ').append(p[2].substring(paren)).append('\n');
            } else {
                GameDiff.Cls holder = Exposure.declaring(b, owner, 'f', p[2], null);
                if (holder != null) {
                    out.append("accessible field ").append(owner).append(' ').append(p[2]).append(' ').append(holder.fields.get(p[2]).desc).append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * What an access transformer makes public, in the new release's names, so linking does not count it as closed: a class
     * ("owner"), a member ("owner.name" or "owner.name+desc"), every field ("owner.*") or every method but constructors ("owner.*()").
     * What it makes protected (Fabric's extendable, written protected-f) is kept with a leading "~": a subclass may use it.
     */
    void widen(Set<String> grants, String text) {
        for (String line : text.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            boolean open = p[0].startsWith("public");
            if (p.length < 2 || !open && !p[0].startsWith("protected")) {
                continue;
            }
            String mark = open ? "" : "~";
            String owner = p[1].replace('.', '/');
            String newOwner = cls(owner);
            if (p.length == 2) {
                grants.add(mark + newOwner);
                continue;
            }
            int paren = p[2].indexOf('(');
            if (p[2].equals("*") || p[2].equals("*()")) {
                grants.add(mark + newOwner + "." + p[2]);
            } else if (paren < 0) {
                grants.add(mark + newOwner + "." + route.field(owner, p[2])[1]);
            } else {
                String[] moved = route.method(owner, p[2].substring(0, paren), p[2].substring(paren));
                grants.add(mark + newOwner + "." + moved[1] + moved[2]);
            }
        }
    }

    /**
     * Access transformer lines name game members in the old release; each one is moved to its new name. A line whose
     * class or member the new release no longer has opens nothing, so it is commented out: whatever in the mod used
     * that member is reported where it is used.
     */
    String accessTransformer(Mod mod, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length < 2) {
                out.append(line).append('\n');
                continue;
            }
            String owner = p[1].replace('.', '/');
            String rest = p.length > 2 ? p[2] : null;
            String newOwner = cls(owner);
            String newRest = rest;
            boolean gone = a.classes.containsKey(owner) && !b.classes.containsKey(newOwner);
            if (!gone && rest != null && !rest.startsWith("*") && a.classes.containsKey(owner)) {
                int paren = rest.indexOf('(');
                if (paren < 0) {
                    String[] moved = route.field(owner, rest);
                    gone = Exposure.declaring(b, newOwner, 'f', moved[1], null) == null;
                    newRest = moved[1];
                } else {
                    String name = rest.substring(0, paren), d = rest.substring(paren);
                    String[] moved = route.method(owner, name, d);
                    gone = Exposure.declaring(b, newOwner, 'm', moved[1], moved[2]) == null;
                    newRest = moved[1] + moved[2];
                }
            }
            if (gone) {
                out.append("# ").append(body).append("  # mmt port: gone in ").append(to).append('\n');
                mod.change("access transformer line for a gone member commented out");
                continue;
            }
            String moved = p[0] + " " + newOwner.replace('/', '.') + (newRest == null ? "" : " " + newRest);
            out.append(moved.equals(body) ? line : moved).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    static byte[] unsigned(byte[] manifest) throws IOException {
        java.util.jar.Manifest m = new java.util.jar.Manifest(new ByteArrayInputStream(manifest));
        // Per-entry digests belong to the signature that was removed; everything else stays.
        for (var entry : m.getEntries().values()) {
            entry.keySet().removeIf(k -> k.toString().endsWith("-Digest"));
        }
        m.getEntries().values().removeIf(java.util.jar.Attributes::isEmpty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.write(out);
        return out.toByteArray();
    }
}
