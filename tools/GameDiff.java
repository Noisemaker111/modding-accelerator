import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * GameDiff - what Minecraft renamed, moved or changed between releases, derived from the game itself.
 *
 * No Fabric intermediary, yarn or other third-party mapping is read. Each release is the vanilla
 * client jar from Mojang's version manifest, named with Mojang's own client_mappings (ProGuard
 * txt) while the game was obfuscated, and by the jar's own names from 26.1 on. Every class, method
 * and field of the first release gets an id of our own (class_g1, method_g1, field_g1); each next
 * release is matched to the one before it from bytecode evidence and inherits those ids, and
 * anything unmatched gets a fresh id. VersionMap.Chain then turns the consistently keyed tables
 * into the usual map (classes, methods, methodsMoved, signatures, ..., chain, history).
 *
 * Evidence, strongest first; a link needs a mutual best match above a threshold, and an ambiguous
 * candidate is left unlinked (a missing rename is reported as removed, never guessed):
 *   classes  same full name; a nested class of a linked outer class with the same nested name;
 *            then member names (weighted by rarity), identical method bodies, superclass, simple
 *            name (a class whose content moved into a new parent keeps its namesake: Slime -> Slime).
 *   methods  same name and descriptor (descriptors read through the class links), also into a new
 *            parent class; same descriptor and a similar body (opcodes plus every referenced class,
 *            method, field and constant, read through the links found so far; disjoint string
 *            constants veto); the only unlinked method of that name on each side (signature change);
 *            an override of a linked method; linked callers that call it at the same place on each
 *            side (same descriptor); an identical non-trivial body found exactly once on each side
 *            of the jar (a move).
 *   fields   same name and type; same compile-time constant; the same linked methods reading or
 *            writing it; linked callers; finally same name with a new type.
 * The rounds repeat until nothing new links, so each link sharpens the next comparison.
 *
 * --loader neoforge adds NeoForge's own classes (net/neoforged/**) from its published universal jar
 * (maven.neoforged.net, Mojang names already) and links them the same way. A release with no
 * NeoForge build carries the previous build. Fabric API is not covered yet: its jars use
 * intermediary names before 26.1, and this tool makes no claim it cannot back from Mojang names.
 *
 *   java GameDiff --chain v1,v2,... --out map.json [--loader neoforge] [--loader-versions a,b,...] [--cache dir]
 *   java GameDiff --compare A B [--all] [--report file]   (validation only: an obfuscated pair, our
 *        linking scored against VersionMap's intermediary diff; --all includes private members)
 *
 * Compile with tools/VersionMap.java, JDK 25 (java.lang.classfile). Downloads are cached in
 * VersionMap.cache (~/.mmt/mappings). Exit: 0 ok, 2 usage or I/O error.
 */
public final class GameDiff {

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_STATIC = 0x0008;
    static final int ACC_BRIDGE = 0x0040;
    static final int ACC_INTERFACE = 0x0200;
    static final int ACC_ANNOTATION = 0x2000;
    static final int ACC_ENUM = 0x4000;
    static final int ACC_SYNTHETIC = 0x1000;
    static boolean verbose = false;
    static final String WHY = System.getProperty("gamediff.why", ""); // -Dgamediff.why=<internal class name>: print its class candidates

    public static void main(String[] args) throws Exception {
        String chain = null;
        String out = null;
        String loader = null;
        String loaderVersions = null;
        List<String> compare = new ArrayList<>();
        boolean all = false;
        String report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--chain" -> chain = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--loader-versions" -> loaderVersions = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                case "--compare" -> {
                    compare.add(args[++i]);
                    compare.add(args[++i]);
                }
                case "--all" -> all = true;
                case "--report" -> report = args[++i];
                case "--verbose" -> verbose = true;
                default -> usage();
            }
        }
        if (loader != null && !loader.equals("neoforge")) {
            System.err.println("only --loader neoforge is supported");
            System.exit(2);
        }
        if (!compare.isEmpty()) {
            Compare.run(compare.get(0), compare.get(1), !all, report == null ? null : Path.of(report));
            return;
        }
        if (chain == null || out == null) {
            usage();
        }
        long t0 = System.nanoTime();
        List<String> versions = List.of(chain.split(","));
        Driver driver = new Driver(versions, loader, loaderVersions == null ? null : List.of(loaderVersions.split(",")));
        VersionMap.Chain c = VersionMap.Chain.of(versions, driver);
        Path target = Path.of(out);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, driver.json(c), StandardCharsets.UTF_8);
        VersionMap.Diff d = c.diff;
        System.out.printf(
            "chain %s (game-bytecode%s, %d updates): classes %d renamed or moved, %d removed; methods %d renamed, " +
            "%d moved, %d signature changes, %d removed; fields %d renamed, %d removed; %d history events (%.1fs)%n",
            String.join(" -> ", versions),
            loader == null ? "" : " + " + loader,
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
    }

    static void usage() {
        System.err.println(
            "usage: java GameDiff --chain <v1,v2,...> --out map.json [--loader neoforge] [--loader-versions a,b,...] [--cache dir]\n" +
            "       java GameDiff --compare <A> <B> [--all] [--report disagreements.txt]"
        );
        System.exit(2);
    }

    // ------------------------------------------------------------------ model

    /** A reference from a method body: c class, m method, f field, k constant (value in owner). */
    record Ref(char kind, String owner, String name, String desc) {}

    static final class Meth {

        final Cls owner;
        final String name;
        final String desc;
        final int flags;
        String ops = "";
        Ref[] refs = new Ref[0];
        boolean hasCode;
        String id;
        int[] bigrams;
        long[] tokens;
        long hash;

        Meth(Cls owner, String name, String desc, int flags) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.flags = flags;
        }

        boolean special() {
            return name.startsWith("<");
        }

        boolean isStatic() {
            return (flags & ACC_STATIC) != 0;
        }

        boolean trivial() {
            return !hasCode || (refs.length == 0 && ops.length() < 6);
        }

        /** Strong enough to link alone, anywhere in the jar. */
        boolean distinctive() {
            return hasCode && ops.length() >= 8 && refs.length >= 2;
        }

        int[] bigrams() {
            if (bigrams == null) {
                int n = Math.max(0, ops.length() - 1);
                int[] g = new int[n];
                for (int i = 0; i < n; i++) {
                    g[i] = (ops.charAt(i) << 16) | ops.charAt(i + 1);
                }
                Arrays.sort(g);
                bigrams = g;
            }
            return bigrams;
        }

        String sig() {
            return owner.name + "." + name + desc;
        }
    }

    static final class Fld {

        final Cls owner;
        final String name;
        final String desc;
        final int flags;
        final List<Meth> accessors = new ArrayList<>();
        String constant; // a compile-time constant's value: javac inlines these, so nothing reads the field
        String id; // our stable id (field_g<n>)
        String regId; // the id it is registered under: the first string <clinit> loads for it ("doMobLoot"), no "minecraft:"

        Fld(Cls owner, String name, String desc, int flags) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.flags = flags;
        }
    }

    static final class Cls {

        final String name;
        final int flags;
        final String sup;
        final List<String> ifaces;
        final boolean loader;
        final Map<String, Meth> methods = new LinkedHashMap<>(); // name + desc
        final Map<String, Fld> fields = new LinkedHashMap<>(); // name
        final List<Cls> children = new ArrayList<>();
        String id;

        Cls(String name, int flags, String sup, List<String> ifaces, boolean loader) {
            this.name = name;
            this.flags = flags;
            this.sup = sup;
            this.ifaces = ifaces;
            this.loader = loader;
        }

        int kind() {
            int k = flags & (ACC_INTERFACE | ACC_ANNOTATION | ACC_ENUM);
            return "java/lang/Record".equals(sup) ? k | 0x10000 : k;
        }

        String simple() {
            return name.substring(name.lastIndexOf('/') + 1);
        }
    }

    /** Every class of one release (and of its loader build) worth linking, in Mojang names. */
    static final class Snap {

        final String version;
        String loaderVersion;
        final Map<String, Cls> classes = new LinkedHashMap<>();
        final Set<String> fixPairs = new HashSet<>(); // "old\0new" ids from the game's own datafixes

        Snap(String version) {
            this.version = version;
        }

        Meth method(String owner, String name, String desc) {
            Cls c = classes.get(owner);
            return c == null ? null : c.methods.get(name + desc);
        }

        Fld field(String owner, String name) {
            Cls c = classes.get(owner);
            return c == null ? null : c.fields.get(name);
        }

        int methodCount() {
            int n = 0;
            for (Cls c : classes.values()) {
                n += c.methods.size();
            }
            return n;
        }

        int fieldCount() {
            int n = 0;
            for (Cls c : classes.values()) {
                n += c.fields.size();
            }
            return n;
        }
    }

    // ------------------------------------------------------------------ reading class files

    record RawRef(char kind, String owner, String name, String desc) {}

    static final class RawMeth {

        String name;
        String desc;
        int flags;
        boolean hasCode;
        final StringBuilder ops = new StringBuilder();
        final List<RawRef> refs = new ArrayList<>();
        final List<String> lambdas = new ArrayList<>(); // name + desc in the same class
    }

    static final class RawCls {

        String name;
        int flags;
        String sup;
        List<String> ifaces;
        final Map<String, RawMeth> methods = new LinkedHashMap<>();
        final Map<String, String[]> fields = new LinkedHashMap<>(); // name -> desc, flags, constant value or null
        final Map<String, List<String>> fieldIds = new HashMap<>(); // static field -> strings loaded before its putstatic
        final List<String[]> stringPairs = new ArrayList<>(); // consecutive string constants
        Set<String> methodKeys;

        Set<String> methodKeys() {
            return methodKeys != null ? methodKeys : methods.keySet();
        }
    }

    static String internal(ClassDesc d) {
        String s = d.descriptorString();
        return s.startsWith("L") ? s.substring(1, s.length() - 1) : s;
    }

    static char op(int code) {
        return (char) (0x100 + code);
    }

    static RawCls parse(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        RawCls rc = new RawCls();
        rc.name = cm.thisClass().asInternalName();
        rc.flags = cm.flags().flagsMask();
        rc.sup = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
        List<String> ifs = new ArrayList<>();
        for (ClassEntry e : cm.interfaces()) {
            ifs.add(e.asInternalName());
        }
        rc.ifaces = ifs;
        for (FieldModel fm : cm.fields()) {
            rc.fields.put(
                fm.fieldName().stringValue(),
                new String[] {
                    fm.fieldType().stringValue(),
                    Integer.toString(fm.flags().flagsMask()),
                    fm.findAttribute(Attributes.constantValue()).map(a -> String.valueOf(a.constant().constantValue())).orElse(null),
                }
            );
        }
        for (MethodModel mm : cm.methods()) {
            RawMeth m = new RawMeth();
            m.name = mm.methodName().stringValue();
            m.desc = mm.methodType().stringValue();
            m.flags = mm.flags().flagsMask();
            Optional<CodeModel> code = mm.code();
            if (code.isPresent()) {
                m.hasCode = true;
                boolean clinit = m.name.equals("<clinit>");
                List<String> pending = new ArrayList<>();
                String previous = null;
                for (CodeElement el : code.get()) {
                    if (el instanceof Instruction ins) {
                        instruction(rc.name, m, ins);
                        String text = ins instanceof ConstantInstruction ci && ci.constantValue() instanceof String str ? str : null;
                        // Two string constants in a row: a datafix's (old id, new id) when the class is a fix.
                        if (text != null && previous != null) {
                            rc.stringPairs.add(new String[] { previous, text });
                        }
                        previous = text;
                        if (!clinit) {
                            continue;
                        }
                        // The ids a static field is registered under: strings loaded since the previous putstatic.
                        if (text != null) {
                            pending.add(text);
                        } else if (ins instanceof FieldInstruction fi && ins.opcode() == Opcode.PUTSTATIC) {
                            if (fi.owner().asInternalName().equals(rc.name) && !pending.isEmpty()) {
                                rc.fieldIds.putIfAbsent(fi.name().stringValue(), List.copyOf(pending));
                            }
                            pending.clear();
                        }
                    }
                }
            }
            rc.methods.put(m.name + m.desc, m);
        }
        return rc;
    }

    static void instruction(String self, RawMeth m, Instruction ins) {
        switch (ins) {
            case LoadInstruction li -> m.ops.append(op(300 + li.typeKind().ordinal()));
            case StoreInstruction si -> m.ops.append(op(320 + si.typeKind().ordinal()));
            case ConstantInstruction ci -> {
                m.ops.append(op(340 + ci.typeKind().ordinal()));
                ConstantDesc v = ci.constantValue();
                if (v instanceof ClassDesc cd) {
                    m.refs.add(new RawRef('c', internal(cd), null, null));
                } else if (v instanceof String s) {
                    m.refs.add(new RawRef('k', "s:" + s, null, null));
                } else if (v instanceof Number n) {
                    m.refs.add(new RawRef('k', "n:" + n, null, null));
                }
            }
            case InvokeInstruction ii -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(
                    new RawRef('m', ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue())
                );
            }
            case FieldInstruction fi -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(
                    new RawRef('f', fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue())
                );
            }
            case NewObjectInstruction ni -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ni.className().asInternalName(), null, null));
            }
            case TypeCheckInstruction ti -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ti.type().asInternalName(), null, null));
            }
            case NewReferenceArrayInstruction ai -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ai.componentType().asInternalName(), null, null));
            }
            case NewMultiArrayInstruction ai -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('c', ai.arrayType().asInternalName(), null, null));
            }
            case InvokeDynamicInstruction di -> {
                m.ops.append(op(ins.opcode().ordinal()));
                m.refs.add(new RawRef('k', "indy:" + di.name().stringValue(), null, null));
                for (ConstantDesc arg : di.bootstrapArgs()) {
                    if (arg instanceof DirectMethodHandleDesc h) {
                        String owner = internal(h.owner());
                        if (owner.equals(self) && h.methodName().startsWith("lambda$")) {
                            m.lambdas.add(h.methodName() + h.lookupDescriptor());
                        } else {
                            m.refs.add(new RawRef('m', owner, h.methodName(), h.lookupDescriptor()));
                        }
                    } else if (arg instanceof String s) {
                        m.refs.add(new RawRef('k', "s:" + s, null, null));
                    }
                }
            }
            case BranchInstruction bi -> {
                Opcode o = bi.opcode();
                m.ops.append(op((o == Opcode.GOTO_W ? Opcode.GOTO : o).ordinal()));
            }
            default -> m.ops.append(op(ins.opcode().ordinal()));
        }
    }

    /** Names of one jar: obfuscated (ProGuard) or already Mojang's (identity). */
    static final class Names {

        final VersionMap.Proguard pg;
        final Map<String, String> descCache = new ConcurrentHashMap<>();

        Names(VersionMap.Proguard pg) {
            this.pg = pg;
        }

        String cls(String raw) {
            if (pg == null) {
                return raw;
            }
            if (raw.startsWith("[")) {
                return desc(raw);
            }
            return pg.classes.getOrDefault(raw, raw);
        }

        String desc(String raw) {
            if (pg == null) {
                return raw;
            }
            return descCache.computeIfAbsent(raw, d -> VersionMap.Tiny.remapDesc(d, pg.classes));
        }

        String method(String declarer, String name, String desc) {
            return pg == null ? name : pg.methods.getOrDefault(declarer + "." + name + desc, name);
        }

        String field(String declarer, String name) {
            return pg == null ? name : pg.fields.getOrDefault(declarer + "." + name, name);
        }
    }

    /** The class that declares a member a reference names: the owner itself or one of its supertypes. */
    static String declarer(Map<String, RawCls> g, Map<String, RawCls> fallback, String owner, String name, String desc, boolean field) {
        ArrayDeque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            RawCls rc = g.get(c);
            if (rc == null && fallback != null) {
                rc = fallback.get(c);
            }
            if (rc == null) {
                continue;
            }
            if (field ? rc.fields.containsKey(name) : rc.methodKeys().contains(name + desc)) {
                return c;
            }
            if (rc.sup != null) {
                todo.add(rc.sup);
            }
            todo.addAll(rc.ifaces);
        }
        return null;
    }

    static boolean gameClass(String named) {
        return named.startsWith("net/minecraft/") || named.startsWith("com/mojang/");
    }

    /** Foo$1 and Foo$1Local are compiler-made classes no mod can name. */
    static boolean anonymous(String internal) {
        int d = internal.lastIndexOf('$');
        return d >= 0 && d + 1 < internal.length() && Character.isDigit(internal.charAt(d + 1));
    }

    /** Read class files into a snapshot: game classes (names through pg if obfuscated) and loader classes (Mojang names). */
    static Snap read(String version, List<byte[]> game, VersionMap.Proguard pg, List<byte[]> loader) {
        Snap s = new Snap(version);
        Names names = new Names(pg);
        Map<String, RawCls> raw = parseAll(game);
        // The game's classes in Mojang names, for resolving loader references through game supertypes.
        Map<String, RawCls> named = new HashMap<>();
        for (RawCls rc : raw.values()) {
            RawCls n = new RawCls();
            n.name = names.cls(rc.name);
            n.sup = rc.sup == null ? null : names.cls(rc.sup);
            n.ifaces = rc.ifaces.stream().map(names::cls).toList();
            Set<String> keys = new HashSet<>();
            for (RawMeth m : rc.methods.values()) {
                keys.add(names.method(rc.name, m.name, m.desc) + names.desc(m.desc));
            }
            n.methodKeys = keys;
            for (var f : rc.fields.entrySet()) {
                n.fields.put(names.field(rc.name, f.getKey()), f.getValue());
            }
            named.put(n.name, n);
        }
        convert(s, raw, null, names, false);
        if (loader != null && !loader.isEmpty()) {
            Map<String, RawCls> lraw = parseAll(loader);
            convert(s, lraw, named, new Names(null), true);
        }
        for (Cls c : s.classes.values()) {
            for (Meth m : c.methods.values()) {
                for (Ref r : m.refs) {
                    if (r.kind() == 'f') {
                        Fld f = s.field(r.owner(), r.name());
                        if (f != null && !f.accessors.contains(m)) {
                            f.accessors.add(m);
                        }
                    }
                }
            }
            for (String sup : supertypes(c)) {
                Cls p = s.classes.get(sup);
                if (p != null) {
                    p.children.add(c);
                }
            }
        }
        return s;
    }

    /** A registry id without the default namespace: "minecraft:mob_drops" and "mob_drops" are one id. */
    static String id(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    static List<String> supertypes(Cls c) {
        List<String> l = new ArrayList<>();
        if (c.sup != null) {
            l.add(c.sup);
        }
        l.addAll(c.ifaces);
        return l;
    }

    static Map<String, RawCls> parseAll(List<byte[]> classes) {
        Map<String, RawCls> raw = new ConcurrentHashMap<>();
        classes.parallelStream().forEach(b -> {
            RawCls rc = parse(b);
            raw.put(rc.name, rc);
        });
        return raw;
    }

    static void convert(Snap s, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, boolean loader) {
        Map<String, Ref> resolved = new ConcurrentHashMap<>();
        List<RawCls> keep = new ArrayList<>();
        for (RawCls rc : raw.values()) {
            String n = names.cls(rc.name);
            if (!loader && n.startsWith("net/minecraft/util/datafix/fixes/")) {
                for (String[] p : rc.stringPairs) {
                    s.fixPairs.add(id(p[0]) + "\0" + id(p[1]));
                }
            }
            boolean inside = loader ? n.startsWith("net/neoforged/") : gameClass(n);
            if (!inside || anonymous(n) || (rc.flags & ACC_SYNTHETIC) != 0) {
                continue;
            }
            keep.add(rc);
        }
        keep.sort(Comparator.comparing(rc -> names.cls(rc.name)));
        List<Cls> made = keep
            .parallelStream()
            .map(rc -> {
                Cls c = new Cls(
                    names.cls(rc.name),
                    rc.flags,
                    rc.sup == null ? null : names.cls(rc.sup),
                    rc.ifaces.stream().map(names::cls).toList(),
                    loader
                );
                for (RawMeth rm : rc.methods.values()) {
                    if ((rm.flags & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0 || rm.name.contains("$")) {
                        continue;
                    }
                    Meth m = new Meth(c, names.method(rc.name, rm.name, rm.desc), names.desc(rm.desc), rm.flags);
                    StringBuilder ops = new StringBuilder();
                    List<Ref> refs = new ArrayList<>();
                    body(rc, rm, ops, refs, raw, fallback, names, resolved, new HashSet<>(), 0);
                    m.hasCode = rm.hasCode;
                    m.ops = ops.toString();
                    m.refs = refs.toArray(new Ref[0]);
                    c.methods.put(m.name + m.desc, m);
                }
                for (var f : rc.fields.entrySet()) {
                    int ff = Integer.parseInt(f.getValue()[1]);
                    if ((ff & ACC_SYNTHETIC) != 0 || f.getKey().contains("$")) {
                        continue;
                    }
                    Fld fd = new Fld(c, names.field(rc.name, f.getKey()), names.desc(f.getValue()[0]), ff);
                    fd.constant = f.getValue()[2];
                    List<String> loaded = rc.fieldIds.get(f.getKey());
                    fd.regId = loaded == null ? null : id(loaded.get(0));
                    c.fields.put(fd.name, fd);
                }
                return c;
            })
            .toList();
        for (Cls c : made) {
            s.classes.put(c.name, c);
        }
    }

    /** One method's opcodes and references, with the bodies of its lambdas inlined. */
    static void body(
        RawCls rc,
        RawMeth rm,
        StringBuilder ops,
        List<Ref> refs,
        Map<String, RawCls> raw,
        Map<String, RawCls> fallback,
        Names names,
        Map<String, Ref> resolved,
        Set<String> seen,
        int depth
    ) {
        if (!seen.add(rm.name + rm.desc)) {
            return;
        }
        ops.append(rm.ops);
        for (RawRef r : rm.refs) {
            refs.add(resolve(r, raw, fallback, names, resolved));
        }
        if (depth > 4) {
            return;
        }
        for (String l : rm.lambdas) {
            RawMeth lm = rc.methods.get(l);
            if (lm != null) {
                body(rc, lm, ops, refs, raw, fallback, names, resolved, seen, depth + 1);
            }
        }
    }

    static Ref resolve(RawRef r, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, Map<String, Ref> cache) {
        switch (r.kind()) {
            case 'k' -> {
                return new Ref('k', r.owner(), null, null);
            }
            case 'c' -> {
                return new Ref('c', names.cls(r.owner()), null, null);
            }
            default -> {
                String key = r.kind() + r.owner() + "." + r.name() + r.desc();
                Ref hit = cache.get(key);
                if (hit != null) {
                    return hit;
                }
                boolean field = r.kind() == 'f';
                String d = r.owner().startsWith("[") ? null : declarer(raw, fallback, r.owner(), r.name(), r.desc(), field);
                Ref out;
                if (d == null) {
                    out = new Ref(r.kind(), names.cls(r.owner()), r.name(), names.desc(r.desc()));
                } else if (field) {
                    out = new Ref('f', names.cls(d), names.field(d, r.name()), names.desc(r.desc()));
                } else {
                    out = new Ref('m', names.cls(d), names.method(d, r.name(), r.desc()), names.desc(r.desc()));
                }
                cache.put(key, out);
                return out;
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    static String manifest;

    static String versionJson(String version) throws Exception {
        if (manifest == null) {
            Path m = VersionMap.cache.resolve("version_manifest_v2.json");
            String text = Files.isRegularFile(m) ? Files.readString(m) : "";
            if (!text.contains("\"" + version + "\"")) {
                Files.deleteIfExists(m);
            }
            manifest = VersionMap.fetchText(MANIFEST, "version_manifest_v2.json");
        }
        Matcher at = Pattern.compile("\"id\"\\s*:\\s*\"" + Pattern.quote(version) + "\"").matcher(manifest);
        if (!at.find()) {
            throw new IOException("Minecraft " + version + " is not in Mojang's version list");
        }
        return VersionMap.fetchText(VersionMap.jsonString(manifest, "url", at.start()), "version-" + version + ".json");
    }

    /** The url and sha1 of one entry under "downloads" in a version json. */
    static String[] download(String versionJson, String key) {
        int d = versionJson.indexOf("\"downloads\"");
        int k = d < 0 ? -1 : versionJson.indexOf("\"" + key + "\"", d);
        if (k < 0) {
            return null;
        }
        int end = versionJson.indexOf('}', k);
        String obj = versionJson.substring(k, end);
        return new String[] { VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "sha1", 0) };
    }

    static Path verified(String[] dl, String name) throws Exception {
        Path p = VersionMap.fetch(dl[0], name);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p)));
        if (!sha.equalsIgnoreCase(dl[1])) {
            Files.delete(p);
            throw new IOException(name + " failed its sha1 check");
        }
        return p;
    }

    static List<byte[]> classes(Path jar, String prefix) throws IOException {
        List<byte[]> l = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) {
                    continue;
                }
                if (n.endsWith("package-info.class") || (prefix != null && !n.startsWith(prefix))) {
                    continue;
                }
                l.add(z.getInputStream(ze).readAllBytes());
            }
        }
        return l;
    }

    /** One release from Mojang, plus a loader jar when given. */
    static Snap load(String version, Path loaderJar, String loaderVersion) throws Exception {
        long t0 = System.nanoTime();
        String vj = versionJson(version);
        Path jar = verified(download(vj, "client"), "client-" + version + ".jar");
        String[] m = download(vj, "client_mappings");
        VersionMap.Proguard pg = null;
        if (m != null) {
            pg = VersionMap.Proguard.parse(Files.readString(verified(m, "client-" + version + ".txt"), StandardCharsets.UTF_8));
        }
        List<byte[]> loader = loaderJar == null ? List.of() : classes(loaderJar, "net/neoforged/");
        Snap s = read(version, classes(jar, null), pg, loader);
        s.loaderVersion = loaderVersion;
        System.out.printf(
            "  read %s%s: %,d classes, %,d methods, %,d fields (%s names, %.1fs)%n",
            version,
            loaderVersion == null ? "" : " + neoforge " + loaderVersion,
            s.classes.size(),
            s.methodCount(),
            s.fieldCount(),
            pg == null ? "jar" : "Mojang mapped",
            (System.nanoTime() - t0) / 1e9
        );
        return s;
    }

    /** NeoForge's own versions: 1.21.4 -> 21.4.x, 1.21 -> 21.0.x, 26.1.2 -> 26.1.2.x, 26.2 -> 26.2.0.x. */
    static String neoforgeFor(String mc, String metadata) {
        String[] p = mc.split("\\.");
        String prefix;
        if (p[0].equals("1")) {
            if (p.length < 2) {
                return null;
            }
            prefix = p[1] + "." + (p.length > 2 ? p[2] : "0") + ".";
        } else {
            prefix = p.length == 2 ? mc + ".0." : mc + ".";
        }
        Matcher vm = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        String best = null;
        long bestNum = -1;
        boolean bestStable = false;
        while (vm.find()) {
            String v = vm.group(1);
            if (!v.startsWith(prefix)) {
                continue;
            }
            String rest = v.substring(prefix.length());
            Matcher rm = Pattern.compile("(\\d+)(-beta)?").matcher(rest);
            if (!rm.matches()) {
                continue;
            }
            long n = Long.parseLong(rm.group(1));
            boolean stable = rm.group(2) == null;
            if ((stable && !bestStable) || (stable == bestStable && n > bestNum)) {
                best = v;
                bestNum = n;
                bestStable = stable;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ hashing

    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Multiset Jaccard of two sorted arrays. */
    static double jaccard(long[] a, long[] b) {
        if (a.length == 0 && b.length == 0) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                common++;
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (double) common / (a.length + b.length - common);
    }

    static double jaccard(int[] a, int[] b) {
        if (a.length == 0 && b.length == 0) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                common++;
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (double) common / (a.length + b.length - common);
    }

    // ------------------------------------------------------------------ linking two releases

    /** Links release b to release a (the one before it). */
    static final class Hop {

        static final double RENAME = 0.75;
        static final double RENAME_SIG = 0.9;
        static final double MARGIN = 0.08;

        final Snap a;
        final Snap b;
        final Map<Cls, Cls> cAB = new HashMap<>();
        final Map<Cls, Cls> cBA = new HashMap<>();
        final Map<Meth, Meth> mAB = new HashMap<>();
        final Map<Meth, Meth> mBA = new HashMap<>();
        final Map<Fld, Fld> fAB = new HashMap<>();
        final Map<Fld, Fld> fBA = new HashMap<>();
        final Map<Object, String> how = new HashMap<>();
        final Map<Cls, Cls> outerVote = new HashMap<>();
        Map<String, String> descCache = new HashMap<>();
        Map<String, String> idRenames;

        Hop(Snap a, Snap b) {
            this.a = a;
            this.b = b;
        }

        int total() {
            return cAB.size() + mAB.size() + fAB.size();
        }

        boolean link(Cls x, Cls y, String why) {
            if (cAB.containsKey(x) || cBA.containsKey(y)) {
                return false;
            }
            cAB.put(x, y);
            cBA.put(y, x);
            how.put(x, why);
            return true;
        }

        boolean link(Meth x, Meth y, String why) {
            if (mAB.containsKey(x) || mBA.containsKey(y)) {
                return false;
            }
            mAB.put(x, y);
            mBA.put(y, x);
            how.put(x, why);
            return true;
        }

        boolean link(Fld x, Fld y, String why) {
            if (fAB.containsKey(x) || fBA.containsKey(y)) {
                return false;
            }
            fAB.put(x, y);
            fBA.put(y, x);
            how.put(x, why);
            return true;
        }

        void run() {
            idRenames = idRenames(a, b);
            for (Cls c : a.classes.values()) {
                for (Meth m : c.methods.values()) {
                    tokens(m, true);
                }
            }
            for (Cls ca : a.classes.values()) {
                Cls cb = b.classes.get(ca.name);
                if (cb != null) {
                    link(ca, cb, "name");
                }
            }
            for (int round = 0; round < 8; round++) {
                int before = total();
                descCache = new HashMap<>();
                fingerprintB();
                nested();
                fuzzyClasses();
                descCache = new HashMap<>();
                members();
                fingerprintB();
                renames(false);
                sameName();
                fields();
                hierarchy();
                callers();
                descCache = new HashMap<>();
                fingerprintB();
                moves();
                renames(true);
                if (verbose) {
                    System.out.printf("    round %d: %d classes, %d methods, %d fields linked%n", round, cAB.size(), mAB.size(), fAB.size());
                }
                if (total() == before) {
                    break;
                }
            }
        }

        // -------------------------------------------------------------- translating b into a's names

        String clsBA(String n) {
            if (n.startsWith("[")) {
                return descBA(n);
            }
            Cls cb = b.classes.get(n);
            Cls ca = cb == null ? null : cBA.get(cb);
            return ca == null ? n : ca.name;
        }

        String descBA(String d) {
            String hit = descCache.get(d);
            if (hit != null) {
                return hit;
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < d.length()) {
                char ch = d.charAt(i);
                if (ch == 'L') {
                    int semi = d.indexOf(';', i);
                    sb.append('L').append(clsBA(d.substring(i + 1, semi))).append(';');
                    i = semi + 1;
                } else {
                    sb.append(ch);
                    i++;
                }
            }
            String out = sb.toString();
            descCache.put(d, out);
            return out;
        }

        String token(Ref r, boolean sideA) {
            switch (r.kind()) {
                case 'k' -> {
                    return "k" + r.owner();
                }
                case 'c' -> {
                    return "c" + (sideA ? r.owner() : clsBA(r.owner()));
                }
                case 'm' -> {
                    if (!sideA) {
                        Meth mb = b.method(r.owner(), r.name(), r.desc());
                        Meth ma = mb == null ? null : mBA.get(mb);
                        if (ma != null) {
                            return "m" + ma.owner.name + "." + ma.name + ma.desc;
                        }
                        return "m" + clsBA(r.owner()) + "." + r.name() + descBA(r.desc());
                    }
                    return "m" + r.owner() + "." + r.name() + r.desc();
                }
                default -> {
                    if (!sideA) {
                        Fld fb = b.field(r.owner(), r.name());
                        Fld fa = fb == null ? null : fBA.get(fb);
                        if (fa != null) {
                            return "f" + fa.owner.name + "." + fa.name;
                        }
                        return "f" + clsBA(r.owner()) + "." + r.name();
                    }
                    return "f" + r.owner() + "." + r.name();
                }
            }
        }

        void tokens(Meth m, boolean sideA) {
            long[] t = new long[m.refs.length];
            long h = fnv(m.ops);
            for (int i = 0; i < t.length; i++) {
                t[i] = fnv(token(m.refs[i], sideA));
                h = h * 31 + t[i];
            }
            Arrays.sort(t);
            m.tokens = t;
            m.hash = h;
        }

        void fingerprintB() {
            for (Cls c : b.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (!mBA.containsKey(m)) {
                        tokens(m, false);
                    }
                }
            }
        }

        static double similarity(Meth x, Meth y) {
            if (x.hash == y.hash) {
                return 1;
            }
            if (stringsDisagree(x, y)) {
                return 0;
            }
            double ops = jaccard(x.bigrams(), y.bigrams());
            if (x.tokens.length + y.tokens.length == 0) {
                return 0.8 * ops;
            }
            return 0.6 * jaccard(x.tokens, y.tokens) + 0.4 * ops;
        }

        /** Both bodies name string constants (ids, translation keys) and share none: different things. */
        static boolean stringsDisagree(Meth x, Meth y) {
            Set<String> sx = new HashSet<>();
            for (Ref r : x.refs) {
                if (r.kind() == 'k' && r.owner().startsWith("s:")) {
                    sx.add(r.owner());
                }
            }
            if (sx.isEmpty()) {
                return false;
            }
            boolean any = false;
            for (Ref r : y.refs) {
                if (r.kind() == 'k' && r.owner().startsWith("s:")) {
                    any = true;
                    if (sx.contains(r.owner())) {
                        return false;
                    }
                }
            }
            return any;
        }

        // -------------------------------------------------------------- classes

        /** A nested class follows its outer class: Outer$Inner -> NewOuter$Inner; and the reverse vote. */
        void nested() {
            for (Cls ca : a.classes.values()) {
                if (cAB.containsKey(ca)) {
                    continue;
                }
                int d = ca.name.lastIndexOf('$');
                if (d < 0) {
                    continue;
                }
                Cls oa = a.classes.get(ca.name.substring(0, d));
                Cls ob = oa == null ? null : cAB.get(oa);
                if (ob == null) {
                    continue;
                }
                Cls cb = b.classes.get(ob.name + ca.name.substring(d));
                if (cb != null && !cBA.containsKey(cb) && cb.kind() == ca.kind()) {
                    link(ca, cb, "nested");
                }
            }
            // Nested classes that moved together vote for their outer classes; the vote is evidence for fuzzyClasses, not a link.
            outerVote.clear();
            Map<Cls, Set<Cls>> votes = new HashMap<>();
            for (var e : cAB.entrySet()) {
                String o = e.getKey().name;
                String n = e.getValue().name;
                int io = o.lastIndexOf('$');
                int in = n.lastIndexOf('$');
                if (io < 0 || in < 0 || !o.substring(io).equals(n.substring(in))) {
                    continue;
                }
                Cls oa = a.classes.get(o.substring(0, io));
                Cls ob = b.classes.get(n.substring(0, in));
                if (oa == null || ob == null || cAB.containsKey(oa) || cBA.containsKey(ob)) {
                    continue;
                }
                votes.computeIfAbsent(oa, x -> new HashSet<>()).add(ob);
            }
            Map<Cls, Integer> claimed = new HashMap<>();
            for (Set<Cls> v : votes.values()) {
                if (v.size() == 1) {
                    claimed.merge(v.iterator().next(), 1, Integer::sum);
                }
            }
            for (var e : votes.entrySet()) {
                if (e.getValue().size() != 1) {
                    continue;
                }
                Cls ob = e.getValue().iterator().next();
                if (claimed.get(ob) == 1 && ob.kind() == e.getKey().kind()) {
                    outerVote.put(e.getKey(), ob);
                }
            }
        }

        static Set<String> memberNames(Cls c) {
            Set<String> s = new HashSet<>();
            for (Meth m : c.methods.values()) {
                if (!m.special()) {
                    s.add("m" + m.name);
                }
            }
            for (Fld f : c.fields.values()) {
                s.add("f" + f.name);
            }
            return s;
        }

        static Set<Long> bodies(Cls c) {
            Set<Long> s = new HashSet<>();
            for (Meth m : c.methods.values()) {
                if (!m.special() && m.distinctive()) {
                    s.add(m.hash);
                }
            }
            return s;
        }

        record Score(double s, int evidence) {}

        void fuzzyClasses() {
            List<Cls> ga = new ArrayList<>();
            List<Cls> gb = new ArrayList<>();
            for (Cls c : a.classes.values()) {
                if (!cAB.containsKey(c)) {
                    ga.add(c);
                }
            }
            for (Cls c : b.classes.values()) {
                if (!cBA.containsKey(c)) {
                    gb.add(c);
                }
            }
            if (ga.isEmpty() || gb.isEmpty()) {
                return;
            }
            Map<Cls, Set<String>> names = new HashMap<>();
            Map<Cls, Set<Long>> hashes = new HashMap<>();
            Map<String, Integer> df = new HashMap<>();
            for (Cls c : ga) {
                names.put(c, memberNames(c));
                hashes.put(c, bodies(c));
            }
            for (Cls c : gb) {
                // A class whose body moved up into a new abstract parent (Slime -> AbstractCubeMob + Slime)
                // still offers those members through the parent; that keeps Slime -> Slime the best match.
                Set<String> n = memberNames(c);
                Set<Long> h = bodies(c);
                for (Cls p : newSupers(c)) {
                    n.addAll(memberNames(p));
                    h.addAll(bodies(p));
                }
                names.put(c, n);
                hashes.put(c, h);
            }
            for (Cls c : names.keySet()) {
                for (String n : names.get(c)) {
                    df.merge(n, 1, Integer::sum);
                }
            }
            double total = names.size();
            Map<String, Double> w = new HashMap<>();
            for (var e : df.entrySet()) {
                w.put(e.getKey(), Math.log(1 + total / e.getValue()));
            }
            Map<String, List<Cls>> index = new HashMap<>();
            for (Cls c : gb) {
                for (String n : names.get(c)) {
                    if (df.get(n) <= 40) {
                        index.computeIfAbsent(n, x -> new ArrayList<>()).add(c);
                    }
                }
                for (Long h : hashes.get(c)) {
                    index.computeIfAbsent("#" + h, x -> new ArrayList<>()).add(c);
                }
                index.computeIfAbsent("@" + c.simple(), x -> new ArrayList<>()).add(c);
            }
            Map<Cls, Map<Cls, Score>> scores = new HashMap<>();
            for (Cls ca : ga) {
                Set<Cls> cand = new HashSet<>();
                for (String n : names.get(ca)) {
                    cand.addAll(index.getOrDefault(n, List.of()));
                }
                for (Long h : hashes.get(ca)) {
                    cand.addAll(index.getOrDefault("#" + h, List.of()));
                }
                if (outerVote.containsKey(ca)) {
                    cand.add(outerVote.get(ca));
                }
                cand.addAll(index.getOrDefault("@" + ca.simple(), List.of()));
                Map<Cls, Score> row = new HashMap<>();
                for (Cls cb : cand) {
                    if (cb.loader != ca.loader || cb.kind() != ca.kind()) {
                        continue;
                    }
                    Score sc = score(ca, cb, names, hashes, w);
                    if (sc != null) {
                        row.put(cb, sc);
                    }
                }
                scores.put(ca, row);
            }
            Map<Cls, double[]> bestB = new HashMap<>(); // b -> best, second
            Map<Cls, Cls> bestBA = new HashMap<>();
            for (var e : scores.entrySet()) {
                for (var f : e.getValue().entrySet()) {
                    double s = f.getValue().s();
                    double[] bb = bestB.computeIfAbsent(f.getKey(), x -> new double[] { -1, -1 });
                    if (s > bb[0]) {
                        bb[1] = bb[0];
                        bb[0] = s;
                        bestBA.put(f.getKey(), e.getKey());
                    } else if (s > bb[1]) {
                        bb[1] = s;
                    }
                }
            }
            for (var e : scores.entrySet()) {
                Cls ca = e.getKey();
                Cls best = null;
                double s1 = -1;
                double s2 = -1;
                for (var f : e.getValue().entrySet()) {
                    double s = f.getValue().s();
                    if (s > s1) {
                        s2 = s1;
                        s1 = s;
                        best = f.getKey();
                    } else if (s > s2) {
                        s2 = s;
                    }
                }
                if (ca.name.equals(WHY)) {
                    System.out.println("    why " + ca.name + ":");
                    for (var f : e.getValue().entrySet()) {
                        Cls back = bestBA.get(f.getKey());
                        System.out.printf(
                            "      %.3f evidence %d %s (its best: %s)%n",
                            f.getValue().s(),
                            f.getValue().evidence(),
                            f.getKey().name,
                            back == null ? null : back.name
                        );
                    }
                }
                if (best == null || bestBA.get(best) != ca) {
                    continue;
                }
                Score sc = e.getValue().get(best);
                double[] bb = bestB.get(best);
                if (sc.s() < 0.5 || s1 - s2 < 0.1 || bb[0] - bb[1] < 0.1) {
                    continue;
                }
                boolean sameSimple = ca.simple().equals(best.simple());
                if (sc.evidence() < 3 && !(sameSimple && sc.evidence() >= 1)) {
                    continue;
                }
                // A class of the same simple name is still there: the content moved, the class did not.
                boolean namesake = false;
                for (var f : e.getValue().entrySet()) {
                    if (!sameSimple && f.getKey().simple().equals(ca.simple()) && f.getValue().s() >= 0.15) {
                        namesake = true;
                    }
                }
                if (namesake) {
                    continue;
                }
                link(ca, best, "fuzzy");
            }
            // Same simple name, the only one on each side, and nothing to tell them apart (empty classes).
            Map<String, List<Cls>> sa = new HashMap<>();
            Map<String, List<Cls>> sb = new HashMap<>();
            for (Cls c : ga) {
                if (!cAB.containsKey(c)) {
                    sa.computeIfAbsent(c.simple(), x -> new ArrayList<>()).add(c);
                }
            }
            for (Cls c : gb) {
                if (!cBA.containsKey(c)) {
                    sb.computeIfAbsent(c.simple(), x -> new ArrayList<>()).add(c);
                }
            }
            for (var e : sa.entrySet()) {
                List<Cls> l = sb.get(e.getKey());
                if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                    continue;
                }
                Cls ca = e.getValue().get(0);
                Cls cb = l.get(0);
                if (ca.loader != cb.loader || ca.kind() != cb.kind()) {
                    continue;
                }
                Score sc = score(ca, cb, names, hashes, w);
                boolean empty = names.get(ca).size() + names.get(cb).size() <= 2;
                if (sc != null && (sc.s() >= 0.2 || (empty && Objects.equals(ca.sup, clsBA(cb.sup == null ? "" : cb.sup))))) {
                    link(ca, cb, "simple-name");
                }
            }
        }

        Score score(Cls ca, Cls cb, Map<Cls, Set<String>> names, Map<Cls, Set<Long>> hashes, Map<String, Double> w) {
            Set<String> na = names.get(ca);
            Set<String> nb = names.get(cb);
            double inter = 0;
            double union = 0;
            int common = 0;
            for (String n : na) {
                double x = w.getOrDefault(n, 1.0);
                union += x;
                if (nb.contains(n)) {
                    inter += x;
                    common++;
                }
            }
            for (String n : nb) {
                if (!na.contains(n)) {
                    union += w.getOrDefault(n, 1.0);
                }
            }
            double nameJ = union == 0 ? 0 : inter / union;
            Set<Long> ha = hashes.get(ca);
            Set<Long> hb = hashes.get(cb);
            int hc = 0;
            for (Long h : ha) {
                if (hb.contains(h)) {
                    hc++;
                }
            }
            int hu = ha.size() + hb.size() - hc;
            double hashJ = hu == 0 ? 0 : (double) hc / hu;
            double s = 0.75 * Math.max(nameJ, hashJ) + 0.25 * Math.min(nameJ, hashJ);
            String supB = cb.sup == null ? null : clsBA(cb.sup);
            boolean gameSupA = ca.sup != null && a.classes.containsKey(ca.sup);
            boolean gameSupB = cb.sup != null && b.classes.containsKey(cb.sup);
            if (gameSupA && Objects.equals(ca.sup, supB)) {
                s += 0.1;
            } else if (gameSupA && gameSupB) {
                s -= 0.2;
            }
            if (ca.simple().equals(cb.simple())) {
                s += 0.15;
            }
            int evidence = common + hc;
            if (outerVote.get(ca) == cb) {
                s += 0.25;
                evidence += 2;
            }
            return new Score(s, evidence);
        }

        // -------------------------------------------------------------- members of linked classes

        void members() {
            for (var e : new ArrayList<>(cAB.entrySet())) {
                Cls ca = e.getKey();
                Cls cb = e.getValue();
                Map<String, Meth> byKey = new HashMap<>();
                Map<String, Fld> fieldsB = new HashMap<>();
                List<Cls> owners = new ArrayList<>();
                owners.add(cb);
                owners.addAll(newSupers(cb));
                for (Cls o : owners) {
                    for (Meth mb : o.methods.values()) {
                        if (!mBA.containsKey(mb) && (o == cb || !mb.special())) {
                            byKey.putIfAbsent(mb.name + descBA(mb.desc), mb);
                        }
                    }
                    for (Fld fb : o.fields.values()) {
                        if (!fBA.containsKey(fb)) {
                            fieldsB.putIfAbsent(fb.name, fb);
                        }
                    }
                }
                for (Meth ma : ca.methods.values()) {
                    if (mAB.containsKey(ma)) {
                        continue;
                    }
                    Meth mb = byKey.get(ma.name + ma.desc);
                    if (mb != null) {
                        link(ma, mb, mb.owner == cb ? "name" : "pulled-up");
                    }
                }
                for (Fld fa : ca.fields.values()) {
                    Fld fb = fieldsB.get(fa.name);
                    if (fb != null && !fAB.containsKey(fa) && fa.desc.equals(descBA(fb.desc))) {
                        link(fa, fb, fb.owner == cb ? "name" : "pulled-up");
                    }
                }
            }
        }

        /** Superclasses of c (in b) that no class of a links to: parents new in this release. */
        List<Cls> newSupers(Cls c) {
            List<Cls> out = new ArrayList<>();
            Cls p = c.sup == null ? null : b.classes.get(c.sup);
            while (p != null && !cBA.containsKey(p) && out.size() < 4) {
                out.add(p);
                p = p.sup == null ? null : b.classes.get(p.sup);
            }
            return out;
        }

        /** The only unlinked method of a name on each side of a linked class pair: its signature changed. */
        void sameName() {
            for (var e : cAB.entrySet()) {
                Map<String, List<Meth>> ua = new HashMap<>();
                Map<String, List<Meth>> ub = new HashMap<>();
                for (Meth ma : e.getKey().methods.values()) {
                    if (!mAB.containsKey(ma)) {
                        ua.computeIfAbsent(ma.name, x -> new ArrayList<>()).add(ma);
                    }
                }
                for (Meth mb : e.getValue().methods.values()) {
                    if (!mBA.containsKey(mb)) {
                        ub.computeIfAbsent(mb.name, x -> new ArrayList<>()).add(mb);
                    }
                }
                for (var g : ua.entrySet()) {
                    List<Meth> l = ub.get(g.getKey());
                    if (g.getValue().size() == 1 && l != null && l.size() == 1) {
                        Meth ma = g.getValue().get(0);
                        Meth mb = l.get(0);
                        if (ma.isStatic() == mb.isStatic()) {
                            link(ma, mb, "name-signature");
                        }
                    }
                }
                // A field keeps its name when its type changes (after renames of the same type had their chance).
                for (Fld fa : e.getKey().fields.values()) {
                    Fld fb = e.getValue().fields.get(fa.name);
                    if (fb != null && !fAB.containsKey(fa) && !fBA.containsKey(fb) && ((fa.flags ^ fb.flags) & ACC_STATIC) == 0) {
                        link(fa, fb, "name-type");
                    }
                }
            }
        }

        /** Renamed methods in linked classes: same descriptor (or, with sig, any) and a similar body. */
        void renames(boolean sig) {
            for (var e : cAB.entrySet()) {
                Cls ca = e.getKey();
                Cls cb = e.getValue();
                List<Meth> la = new ArrayList<>();
                List<Meth> lb = new ArrayList<>();
                for (Meth m : ca.methods.values()) {
                    if (!mAB.containsKey(m) && !m.special() && !m.trivial()) {
                        la.add(m);
                    }
                }
                for (Meth m : cb.methods.values()) {
                    if (!mBA.containsKey(m) && !m.special() && !m.trivial()) {
                        lb.add(m);
                    }
                }
                if (la.isEmpty() || lb.isEmpty()) {
                    continue;
                }
                double[][] s = new double[la.size()][lb.size()];
                for (int i = 0; i < la.size(); i++) {
                    Meth x = la.get(i);
                    for (int j = 0; j < lb.size(); j++) {
                        Meth y = lb.get(j);
                        boolean sameDesc = x.desc.equals(descBA(y.desc));
                        if (x.isStatic() != y.isStatic() || (!sig && !sameDesc)) {
                            s[i][j] = -1;
                            continue;
                        }
                        s[i][j] = similarity(x, y);
                    }
                }
                mutual(s, sig ? RENAME_SIG : RENAME, (i, j) -> link(la.get(i), lb.get(j), sig ? "body-signature" : "body"));
            }
        }

        interface Accept {
            void accept(int i, int j);
        }

        static void mutual(double[][] s, double threshold, Accept accept) {
            int n = s.length;
            int m = n == 0 ? 0 : s[0].length;
            int[] bestRow = new int[n];
            double[] firstRow = new double[n];
            double[] secondRow = new double[n];
            int[] bestCol = new int[m];
            double[] firstCol = new double[m];
            double[] secondCol = new double[m];
            Arrays.fill(bestRow, -1);
            Arrays.fill(firstRow, -1);
            Arrays.fill(secondRow, -1);
            Arrays.fill(bestCol, -1);
            Arrays.fill(firstCol, -1);
            Arrays.fill(secondCol, -1);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    double v = s[i][j];
                    if (v < 0) {
                        continue;
                    }
                    if (v > firstRow[i]) {
                        secondRow[i] = firstRow[i];
                        firstRow[i] = v;
                        bestRow[i] = j;
                    } else if (v > secondRow[i]) {
                        secondRow[i] = v;
                    }
                    if (v > firstCol[j]) {
                        secondCol[j] = firstCol[j];
                        firstCol[j] = v;
                        bestCol[j] = i;
                    } else if (v > secondCol[j]) {
                        secondCol[j] = v;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                int j = bestRow[i];
                if (j < 0 || bestCol[j] != i || firstRow[i] < threshold) {
                    continue;
                }
                if (firstRow[i] - secondRow[i] < MARGIN || firstCol[j] - secondCol[j] < MARGIN) {
                    continue;
                }
                accept.accept(i, j);
            }
        }

        /** Renamed fields: same type, read and written by the same linked methods. */
        void fields() {
            for (var e : cAB.entrySet()) {
                Cls ca = e.getKey();
                Cls cb = e.getValue();
                List<Fld> la = new ArrayList<>();
                List<Fld> lb = new ArrayList<>();
                for (Fld f : ca.fields.values()) {
                    if (!fAB.containsKey(f)) {
                        la.add(f);
                    }
                }
                for (Fld f : cb.fields.values()) {
                    if (!fBA.containsKey(f)) {
                        lb.add(f);
                    }
                }
                if (la.isEmpty() || lb.isEmpty()) {
                    continue;
                }
                registered(la, lb);
                if (la.isEmpty() || lb.isEmpty()) {
                    continue;
                }
                // Compile-time constants: same type and value, the only such pair in the class.
                Map<String, List<Fld>> ka = new HashMap<>();
                Map<String, List<Fld>> kb = new HashMap<>();
                for (Fld f : la) {
                    if (distinctConstant(f)) {
                        ka.computeIfAbsent(f.desc + "=" + f.constant, x -> new ArrayList<>()).add(f);
                    }
                }
                for (Fld f : lb) {
                    if (distinctConstant(f)) {
                        kb.computeIfAbsent(descBA(f.desc) + "=" + f.constant, x -> new ArrayList<>()).add(f);
                    }
                }
                for (var k : ka.entrySet()) {
                    List<Fld> l = kb.get(k.getKey());
                    if (k.getValue().size() == 1 && l != null && l.size() == 1 && link(k.getValue().get(0), l.get(0), "constant")) {
                        la.remove(k.getValue().get(0));
                        lb.remove(l.get(0));
                    }
                }
                if (la.isEmpty() || lb.isEmpty()) {
                    continue;
                }
                double[][] s = new double[la.size()][lb.size()];
                for (int i = 0; i < la.size(); i++) {
                    Fld x = la.get(i);
                    for (int j = 0; j < lb.size(); j++) {
                        Fld y = lb.get(j);
                        boolean enumConstant = (x.flags & ACC_ENUM) != 0 || (y.flags & ACC_ENUM) != 0;
                        if (enumConstant || ((x.flags ^ y.flags) & ACC_STATIC) != 0 || !x.desc.equals(descBA(y.desc))) {
                            s[i][j] = -1;
                            continue;
                        }
                        s[i][j] = accessorScore(x, y);
                    }
                }
                mutual(s, 0.5, (i, j) -> link(la.get(i), lb.get(j), "accessors"));
            }
        }

        /**
         * The id renames the newer release's datafixes add (pairs the older release does not have yet),
         * kept only where an old id maps to one new id and a new id comes from one old id.
         */
        static Map<String, String> idRenames(Snap a, Snap b) {
            Map<String, Set<String>> forward = new HashMap<>();
            Map<String, Set<String>> backward = new HashMap<>();
            for (String p : b.fixPairs) {
                if (a.fixPairs.contains(p)) {
                    continue;
                }
                int z = p.indexOf('\0');
                String from = p.substring(0, z);
                String to = p.substring(z + 1);
                if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
                    continue;
                }
                forward.computeIfAbsent(from, x -> new HashSet<>()).add(to);
                backward.computeIfAbsent(to, x -> new HashSet<>()).add(from);
            }
            Map<String, String> out = new HashMap<>();
            for (var e : forward.entrySet()) {
                String to = e.getValue().iterator().next();
                if (e.getValue().size() == 1 && backward.get(to).size() == 1) {
                    out.put(e.getKey(), to);
                }
            }
            return out;
        }

        /**
         * Static fields registered under ids (the first string loaded before their putstatic in <clinit>): the same id
         * on both sides, or an id the newer release's datafixes rename (doMobLoot -> mob_drops), is the same
         * field. Every id must name one unlinked field on each side, and each field must get one partner.
         */
        void registered(List<Fld> la, List<Fld> lb) {
            Map<String, List<Fld>> ia = new HashMap<>();
            Map<String, List<Fld>> ib = new HashMap<>();
            for (Fld f : la) {
                if (f.regId != null) {
                    ia.computeIfAbsent(f.regId, x -> new ArrayList<>()).add(f);
                }
            }
            for (Fld f : lb) {
                if (f.regId != null) {
                    ib.computeIfAbsent(f.regId, x -> new ArrayList<>()).add(f);
                }
            }
            if (ia.isEmpty() || ib.isEmpty()) {
                return;
            }
            for (int pass = 0; pass < 2; pass++) {
                Map<Fld, Set<Fld>> ab = new HashMap<>();
                Map<Fld, Set<Fld>> ba = new HashMap<>();
                for (var e : ia.entrySet()) {
                    String other = pass == 0 ? e.getKey() : idRenames.get(e.getKey());
                    List<Fld> l = other == null ? null : ib.get(other);
                    if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                        continue;
                    }
                    Fld x = e.getValue().get(0);
                    Fld y = l.get(0);
                    if (fAB.containsKey(x) || fBA.containsKey(y) || ((x.flags ^ y.flags) & ACC_STATIC) != 0) {
                        continue;
                    }
                    ab.computeIfAbsent(x, k -> new HashSet<>()).add(y);
                    ba.computeIfAbsent(y, k -> new HashSet<>()).add(x);
                }
                for (var e : ab.entrySet()) {
                    Fld y = e.getValue().iterator().next();
                    if (e.getValue().size() == 1 && ba.get(y).size() == 1) {
                        link(e.getKey(), y, pass == 0 ? "registered-id" : "datafix-id");
                    }
                }
            }
            la.removeIf(fAB::containsKey);
            lb.removeIf(fBA::containsKey);
        }

        /** A constant worth matching on: any string, or a number that is not a small whole number (0, 1, 16...). */
        static boolean distinctConstant(Fld f) {
            if (f.constant == null) {
                return false;
            }
            if (f.desc.equals("Ljava/lang/String;")) {
                return !f.constant.isEmpty();
            }
            try {
                double v = Double.parseDouble(f.constant);
                return v != Math.rint(v) || Math.abs(v) > 64;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /**
         * Call sites: two linked methods whose bodies line up (same opcodes, or the same references around a
         * gap of equal length) reference an unlinked thing at the same place on each side; that is a vote
         * for linking the two. A link needs the votes on both sides to agree.
         */
        void callers() {
            Map<Object, Map<Object, Integer>> votes = new HashMap<>();
            Map<Object, Integer> totals = new HashMap<>();
            for (var e : mAB.entrySet()) {
                Meth x = e.getKey();
                Meth y = e.getValue();
                if (x.refs.length == 0 || y.refs.length == 0 || !unlinkedRef(x, true) || !unlinkedRef(y, false)) {
                    continue;
                }
                long[] ta = new long[x.refs.length];
                long[] tb = new long[y.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = fnv(token(x.refs[i], true));
                }
                for (int j = 0; j < tb.length; j++) {
                    tb[j] = fnv(token(y.refs[j], false));
                }
                List<int[]> pairs = align(ta, tb, x.ops.equals(y.ops));
                for (int[] p : pairs) {
                    vote(x.refs[p[0]], y.refs[p[1]], votes, totals);
                }
            }
            for (var e : votes.entrySet()) {
                Object x = e.getKey();
                Object best = null;
                int c1 = 0;
                for (var f : e.getValue().entrySet()) {
                    if (f.getValue() > c1) {
                        c1 = f.getValue();
                        best = f.getKey();
                    }
                }
                int tx = totals.get(x);
                int ty = totals.get(best);
                if (c1 * 10 < tx * 8 || c1 * 10 < ty * 8) {
                    continue;
                }
                if (x instanceof Meth ma && best instanceof Meth mb) {
                    boolean sameDesc = ma.desc.equals(descBA(mb.desc));
                    boolean sameOwner = cAB.get(ma.owner) == mb.owner;
                    if (ma.isStatic() != mb.isStatic() || ma.special() || mb.special()) {
                        continue;
                    }
                    // A call site that now calls something with another signature is a rewrite, not a rename.
                    if (!sameDesc || (!sameOwner && c1 < 3 && !ma.name.equals(mb.name))) {
                        continue;
                    }
                    link(ma, mb, sameOwner ? "callers" : "callers-moved");
                } else if (x instanceof Fld fa && best instanceof Fld fb) {
                    boolean sameDesc = fa.desc.equals(descBA(fb.desc));
                    if (((fa.flags ^ fb.flags) & ACC_STATIC) != 0 || !sameDesc) {
                        continue;
                    }
                    if (c1 < 2 && ((fa.flags & ACC_ENUM) != 0 || accessorScore(fa, fb) < 0.5)) {
                        continue;
                    }
                    if (cAB.get(fa.owner) != fb.owner && !(fa.name.equals(fb.name) && c1 >= 2)) {
                        continue;
                    }
                    link(fa, fb, "callers");
                } else if (x instanceof Cls ca && best instanceof Cls cb) {
                    if (c1 >= 4 && ca.kind() == cb.kind() && ca.loader == cb.loader) {
                        link(ca, cb, "callers");
                    }
                }
            }
        }

        /** A call the game rewrote: what its own code called in a's release and calls at the same place in b's. */
        record Rewrite(Meth from, Meth to, int votes, int total) {}

        /**
         * Call-site rewrites, read from the game's own code once linking is done. In every pair of linked methods the
         * references are aligned (through the links, so what stayed the same lines up); where a method of a that has no
         * link (removed, or its signature changed) sits opposite a method of b, that is a vote for b's method as its
         * replacement (1.20.2: PacketByteBuf.getVarIntLength -> VarInt.getByteSize). Unlike a link, the replacement may
         * have another owner, descriptor or static-ness: the porter maps the arguments. Kept when at least two call sites
         * agree and 80% of the removed method's aligned call sites name the same replacement.
         */
        List<Rewrite> rewrites() {
            Map<Meth, Map<Meth, Integer>> votes = new HashMap<>();
            Map<Meth, Integer> totals = new HashMap<>();
            for (var e : mAB.entrySet()) {
                Meth x = e.getKey();
                Meth y = e.getValue();
                if (x.refs.length == 0 || y.refs.length == 0) {
                    continue;
                }
                long[] ta = new long[x.refs.length];
                long[] tb = new long[y.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = fnv(token(x.refs[i], true));
                }
                for (int j = 0; j < tb.length; j++) {
                    tb[j] = fnv(token(y.refs[j], false));
                }
                for (int[] p : align(ta, tb, x.ops.equals(y.ops))) {
                    Ref ra = x.refs[p[0]];
                    Ref rb = y.refs[p[1]];
                    if (ra.kind() != 'm' || rb.kind() != 'm') {
                        continue;
                    }
                    Meth ma = a.method(ra.owner(), ra.name(), ra.desc());
                    Meth mb = b.method(rb.owner(), rb.name(), rb.desc());
                    if (ma == null || mb == null || mAB.containsKey(ma) || ma.special() || mb.special()) {
                        continue;
                    }
                    votes.computeIfAbsent(ma, k -> new HashMap<>()).merge(mb, 1, Integer::sum);
                    totals.merge(ma, 1, Integer::sum);
                }
            }
            // Calls the game deleted: a removed void method whose call sites in linked methods lost the call while what was
            // around it stayed (1.21.2: RenderSystem.applyModelViewMatrix()). Counted against every call site seen.
            Map<Meth, Integer> dropped = new HashMap<>(), seen = new HashMap<>();
            for (var e : mAB.entrySet()) {
                Meth x = e.getKey();
                Meth y = e.getValue();
                if (x.refs.length == 0) {
                    continue;
                }
                long[] ta = new long[x.refs.length];
                long[] tb = new long[y.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = fnv(token(x.refs[i], true));
                }
                for (int j = 0; j < tb.length; j++) {
                    tb[j] = fnv(token(y.refs[j], false));
                }
                Set<Integer> gone = droppedIndices(ta, tb);
                for (int i = 0; i < x.refs.length; i++) {
                    Ref ra = x.refs[i];
                    if (ra.kind() != 'm' || !ra.desc().endsWith(")V")) {
                        continue;
                    }
                    Meth ma = a.method(ra.owner(), ra.name(), ra.desc());
                    if (ma == null || mAB.containsKey(ma) || ma.special()) {
                        continue;
                    }
                    seen.merge(ma, 1, Integer::sum);
                    if (gone.contains(i)) {
                        dropped.merge(ma, 1, Integer::sum);
                    }
                }
            }
            List<Rewrite> out = new ArrayList<>();
            for (var e : dropped.entrySet()) {
                int all = seen.get(e.getKey());
                if (e.getValue() >= 2 && e.getValue() * 10 >= all * 8 && !votes.containsKey(e.getKey())) {
                    out.add(new Rewrite(e.getKey(), null, e.getValue(), all));
                }
            }
            for (var e : votes.entrySet()) {
                Meth best = null;
                int c1 = 0;
                for (var f : e.getValue().entrySet()) {
                    if (f.getValue() > c1) {
                        c1 = f.getValue();
                        best = f.getKey();
                    }
                }
                int total = totals.get(e.getKey());
                // The game's own code may call a private helper in place of what it removed; a mod cannot.
                boolean reachable = (best.flags & ACC_PRIVATE) == 0 || (e.getKey().flags & ACC_PRIVATE) != 0;
                if (reachable && receiverCarries(e.getKey(), best) && (c1 >= 2 && c1 * 10 >= total * 8 || c1 == 1 && total == 1 && typeSafe(e.getKey(), best))) {
                    out.add(new Rewrite(e.getKey(), best, c1, total));
                }
            }
            out.sort(Comparator.comparing((Rewrite r) -> r.from().sig()));
            return out;
        }

        /**
         * What the game's own code says about one member of a that has no link: how many places in a use it, how many of
         * those sit in methods that b still has (linked), how many of those line up with something in b, and the
         * reference of b most of them line up with. Says why the miner gave no replacement: nothing in the game used it,
         * every user was redesigned, or the users disagree.
         */
        record Evidence(int sites, int kept, int aligned, String best, int votes, String template, int templateVotes, int templateSites) {}

        /**
         * Removed methods whose old code can go with the mod: every class, method and field the body uses is outside the
         * game (the JDK, a library) or is still in b, linked and public, or is another removed method of the same class
         * that passes the same test. Such a body, copied into the mod as a helper and renamed through the map, still
         * compiles and does what it did. Keyed as Exposure keys them.
         */
        Set<String> portable() {
            Set<String> out = new HashSet<>();
            Map<Meth, Boolean> memo = new HashMap<>();
            for (Cls c : a.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (!mAB.containsKey(m) && (m.flags & ACC_PUBLIC) != 0 && portable(m, memo, 0)) {
                        out.add(m.owner.name.replace('/', '.') + "." + m.name + m.desc);
                    }
                }
            }
            return out;
        }

        boolean portable(Meth m, Map<Meth, Boolean> memo, int depth) {
            Boolean known = memo.get(m);
            if (known != null) {
                return known;
            }
            if (!m.hasCode || depth > 4) {
                return false;
            }
            memo.put(m, false); // a cycle is not portable
            boolean ok = m.isStatic() || publicInB(cAB.get(m.owner));
            for (Ref r : m.refs) {
                if (!ok) {
                    break;
                }
                Cls owner = a.classes.get(r.owner());
                if (owner == null) {
                    continue; // the JDK or a library
                }
                switch (r.kind()) {
                    case 'm' -> {
                        Meth x = null;
                        for (Cls k = owner; k != null && x == null; k = k.sup == null ? null : a.classes.get(k.sup)) {
                            x = k.methods.get(r.name() + r.desc());
                        }
                        if (x == null) {
                            ok = false;
                        } else if (mAB.containsKey(x)) {
                            Meth y = mAB.get(x);
                            ok = (y.flags & ACC_PUBLIC) != 0 && publicInB(y.owner);
                        } else {
                            ok = x.owner == m.owner && portable(x, memo, depth + 1);
                        }
                    }
                    case 'f' -> {
                        Fld x = null;
                        for (Cls k = owner; k != null && x == null; k = k.sup == null ? null : a.classes.get(k.sup)) {
                            x = k.fields.get(r.name());
                        }
                        Fld y = x == null ? null : fAB.get(x);
                        ok = y != null && (y.flags & ACC_PUBLIC) != 0 && publicInB(y.owner);
                    }
                    default -> ok = publicInB(cAB.get(owner));
                }
            }
            memo.put(m, ok);
            return ok;
        }

        static boolean publicInB(Cls c) {
            return c != null && (c.flags & ACC_PUBLIC) != 0;
        }

        /** The references of b each template string stands for, and the reference of a each evidence key came from. */
        final Map<String, List<Ref>> templateRefs = new HashMap<>();
        final Map<String, Ref> unlinkedMember = new HashMap<>();

        /**
         * A member the game replaced with a chain on the same receiver (1.21.2: input.up -> input.keyPresses.forward()):
         * steps are "f:name" or "m:name" joined by commas; the last takes the old call's arguments.
         */
        record Chain(String owner, String newOwner, String name, String desc, boolean field, String steps, int votes, int of) {}

        /**
         * Templates the game's own code agrees on (two uses, 60%) that are a type-correct chain: the first step is a member
         * of the old owner's class in b, every step is an instance member of what the step before returns, every step but
         * the last is a field or a no-argument method, and the last gives what the old member gave (same field type, or
         * the same parameters and return type) after the map's renames.
         */
        /** A method moved to an unrelated class that one holder keeps beside the old one, each in a public field of its own. */
        record Sibling(Meth from, Meth to, String holder, String fromField, String toField) {}

        /**
         * 26.2 moved LevelRenderer.blockChanged to LevelExtractor, and Minecraft keeps both (levelRenderer, levelExtractor):
         * a call through holder.levelRenderer goes through holder.levelExtractor. Only a holder with exactly one public
         * field of each type in b counts, and only a move between classes neither of which extends the other.
         */
        List<Sibling> siblings() {
            List<Sibling> out = new ArrayList<>();
            for (var e : mAB.entrySet()) {
                Meth a = e.getKey(), m = e.getValue();
                if (a.isStatic() || m.isStatic() || (m.flags & ACC_PUBLIC) == 0) {
                    continue;
                }
                Cls mapped = cAB.get(a.owner);
                String oldOwner = mapped == null ? null : mapped.name, newOwner = m.owner.name;
                if (oldOwner == null || oldOwner.equals(newOwner) || subtypeB(oldOwner, newOwner) || subtypeB(newOwner, oldOwner)) {
                    continue;
                }
                Sibling found = null;
                int holders = 0;
                for (Cls c : b.classes.values()) {
                    String fromField = null, toField = null;
                    int fromCount = 0, toCount = 0;
                    for (Fld f : c.fields.values()) {
                        if ((f.flags & ACC_PUBLIC) == 0 || (f.flags & ACC_STATIC) != 0) {
                            continue;
                        }
                        if (f.desc.equals("L" + oldOwner + ";")) {
                            fromField = f.name;
                            fromCount++;
                        } else if (f.desc.equals("L" + newOwner + ";")) {
                            toField = f.name;
                            toCount++;
                        }
                    }
                    if (fromCount == 1 && toCount == 1) {
                        holders++;
                        found = new Sibling(a, m, c.name.replace('/', '.'), fromField, toField);
                    }
                }
                if (holders == 1) {
                    out.add(found);
                }
            }
            out.sort(Comparator.comparing((Sibling s) -> s.from().owner.name + "." + s.from().name + s.from().desc));
            return out;
        }

        List<Chain> chains() {
            Map<String, Evidence> ev = evidence();
            List<Chain> out = new ArrayList<>();
            for (var e : ev.entrySet()) {
                Evidence x = e.getValue();
                Ref old = unlinkedMember.get(e.getKey());
                boolean agreed = x.templateVotes() >= 2 && x.templateVotes() * 10 >= x.templateSites() * 6 || x.templateSites() == 1;
                if (old == null || x.template() == null || !agreed) {
                    continue;
                }
                String steps = chain(old, templateRefs.get(x.template()));
                if (steps == null) {
                    continue;
                }
                String key = e.getKey();
                int cut = old.kind() == 'm' ? key.lastIndexOf('.', key.indexOf('(')) : key.lastIndexOf('.');
                String owner = key.substring(0, cut);
                Cls oa = a.classes.get(owner.replace('.', '/'));
                Cls ob = oa == null ? null : cAB.get(oa);
                out.add(new Chain(owner, ob == null ? owner : ob.name.replace('/', '.'), old.name(), old.kind() == 'm' ? old.desc() : "",
                    old.kind() != 'm', steps, x.templateVotes(), x.templateSites()));
            }
            out.sort(Comparator.comparing(Chain::owner).thenComparing(Chain::name));
            return out;
        }

        /** The steps of a type-correct chain from old's receiver, or null. */
        String chain(Ref old, List<Ref> refs) {
            if (refs == null || refs.isEmpty() || old.kind() == 'c' || old.kind() == 'k') {
                return null;
            }
            Cls oa = a.classes.get(old.owner());
            Cls cur = oa == null ? null : cAB.get(oa);
            String want;
            if (old.kind() == 'm') {
                Meth m = a.method(old.owner(), old.name(), old.desc());
                if (m == null || m.isStatic() || m.special()) {
                    return null;
                }
                want = old.desc();
            } else {
                Fld f = a.field(old.owner(), old.name());
                if (f == null || (f.flags & ACC_STATIC) != 0) {
                    return null;
                }
                want = f.desc;
            }
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < refs.size(); i++) {
                Ref r = refs.get(i);
                boolean last = i == refs.size() - 1;
                Meth stepMethod = r.kind() == 'm' ? b.method(r.owner(), r.name(), r.desc()) : null;
                Fld stepField = r.kind() == 'f' ? b.field(r.owner(), r.name()) : null;
                boolean hidden = stepMethod != null && (stepMethod.flags & ACC_PRIVATE) != 0 || stepField != null && (stepField.flags & ACC_PRIVATE) != 0;
                if (cur == null || hidden || (r.kind() != 'm' && r.kind() != 'f') || r.name().contains("$") || !subtypeB(cur.name, r.owner())) {
                    return null;
                }
                String type;
                if (r.kind() == 'm') {
                    Meth m = b.method(r.owner(), r.name(), r.desc());
                    if (m != null && m.isStatic() || r.name().startsWith("<") || !last && !r.desc().startsWith("()")) {
                        return null;
                    }
                    String mapped = descBA(r.desc());
                    if (last && (old.kind() == 'm' ? !mapped.equals(want) : !mapped.equals("()" + want))) {
                        return null;
                    }
                    type = r.desc().substring(r.desc().indexOf(')') + 1);
                } else {
                    Fld f = b.field(r.owner(), r.name());
                    if (f != null && (f.flags & ACC_STATIC) != 0 || last && (old.kind() == 'm' || !descBA(r.desc()).equals(want))) {
                        return null;
                    }
                    type = r.desc();
                }
                steps.append(i == 0 ? "" : ",").append(r.kind()).append(':').append(r.name());
                cur = type.startsWith("L") ? b.classes.get(type.substring(1, type.length() - 1)) : null;
            }
            return steps.toString();
        }

        /** sub is sup, or extends or implements it, in b. */
        boolean subtypeB(String sub, String sup) {
            Deque<String> todo = new ArrayDeque<>(List.of(sub));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.pop();
                if (c.equals(sup)) {
                    return true;
                }
                Cls k = b.classes.get(c);
                if (k == null || !seen.add(c)) {
                    continue;
                }
                if (k.sup != null) {
                    todo.push(k.sup);
                }
                k.ifaces.forEach(todo::push);
            }
            return false;
        }

        /** Evidence for every unlinked class, method and field of a, keyed as Exposure keys them (dotted owner, name, desc). */
        Map<String, Evidence> evidence() {
            Map<String, int[]> counts = new HashMap<>();
            Map<String, Map<String, Integer>> votes = new HashMap<>();
            Map<String, Map<String, Integer>> templates = new HashMap<>();
            templateRefs.clear();
            unlinkedMember.clear();
            for (Cls c : a.classes.values()) {
                for (Meth x : c.methods.values()) {
                    Meth y = mAB.get(x);
                    long[] ta = new long[x.refs.length];
                    for (int i = 0; i < ta.length; i++) {
                        ta[i] = fnv(token(x.refs[i], true));
                    }
                    Map<Integer, Ref> opposite = new HashMap<>();
                    if (y != null && y.refs.length > 0) {
                        long[] tb = new long[y.refs.length];
                        for (int j = 0; j < tb.length; j++) {
                            tb[j] = fnv(token(y.refs[j], false));
                        }
                        for (int[] p : align(ta, tb, x.ops.equals(y.ops))) {
                            opposite.put(p[0], y.refs[p[1]]);
                        }
                        // A stretch the game rewrote into another number of references (a field read that became a call
                        // chain): the new stretch, in b's names, is a vote for a template for every unlinked thing in it.
                        for (int[] g : gaps(ta, tb)) {
                            int na = g[1] - g[0], nb = g[3] - g[2];
                            if (na == nb || na == 0 || na > 3 || nb > 4) {
                                continue;
                            }
                            StringBuilder t = new StringBuilder();
                            for (int j = g[2]; j < g[3]; j++) {
                                Ref rb = y.refs[j];
                                t.append(t.length() == 0 ? "" : " ; ").append(rb.kind()).append(' ')
                                    .append(rb.kind() == 'm' ? rb.owner() + "." + rb.name() + rb.desc() : rb.kind() == 'c' || rb.kind() == 'k' ? rb.owner() : rb.owner() + "." + rb.name());
                            }
                            String ts = t.toString().replace('/', '.');
                            templateRefs.putIfAbsent(ts, List.of(Arrays.copyOfRange(y.refs, g[2], g[3])));
                            for (int k = g[0]; k < g[1]; k++) {
                                String key = unlinkedKey(x.refs[k]);
                                if (key != null) {
                                    templates.computeIfAbsent(key, z -> new HashMap<>()).merge(ts, 1, Integer::sum);
                                    unlinkedMember.putIfAbsent(key, x.refs[k]);
                                }
                            }
                        }
                    }
                    for (int i = 0; i < x.refs.length; i++) {
                        String key = unlinkedKey(x.refs[i]);
                        if (key == null) {
                            continue;
                        }
                        int[] n = counts.computeIfAbsent(key, k -> new int[3]);
                        n[0]++;
                        if (y != null) {
                            n[1]++;
                        }
                        Ref rb = opposite.get(i);
                        if (rb != null) {
                            n[2]++;
                            String to = rb.kind() == 'm' ? rb.owner() + "." + rb.name() + rb.desc() : rb.kind() == 'c' ? rb.owner() : rb.owner() + "." + rb.name();
                            votes.computeIfAbsent(key, k -> new HashMap<>()).merge(to.replace('/', '.'), 1, Integer::sum);
                        }
                    }
                }
            }
            Map<String, Evidence> out = new HashMap<>();
            for (var e : counts.entrySet()) {
                String best = null;
                int top = 0;
                for (var v : votes.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                    if (v.getValue() > top) {
                        top = v.getValue();
                        best = v.getKey();
                    }
                }
                String template = null;
                int tTop = 0, tAll = 0;
                for (var v : templates.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                    tAll += v.getValue();
                    if (v.getValue() > tTop) {
                        tTop = v.getValue();
                        template = v.getKey();
                    }
                }
                int[] n = e.getValue();
                out.put(e.getKey(), new Evidence(n[0], n[1], n[2], best, top, template, tTop, tAll));
            }
            Evidence none = new Evidence(0, 0, 0, null, 0, null, 0, 0);
            for (Cls c : a.classes.values()) {
                if (!cAB.containsKey(c)) {
                    out.putIfAbsent(c.name.replace('/', '.'), none);
                }
                for (Meth m : c.methods.values()) {
                    if (!mAB.containsKey(m)) {
                        out.putIfAbsent(m.owner.name.replace('/', '.') + "." + m.name + m.desc, none);
                    }
                }
                for (Fld f : c.fields.values()) {
                    if (!fAB.containsKey(f)) {
                        out.putIfAbsent(f.owner.name.replace('/', '.') + "." + f.name, none);
                    }
                }
            }
            return out;
        }

        /** The Exposure key of what a reference names, when that is a class, method or field of a with no link to b. */
        String unlinkedKey(Ref r) {
            if (r.kind() == 'k') {
                return null;
            }
            if (r.kind() == 'c') {
                Cls c = a.classes.get(r.owner());
                return c == null || cAB.containsKey(c) ? null : c.name.replace('/', '.');
            }
            Meth m = null;
            Fld f = null;
            // A reference names the class it was compiled against; the member may be declared by a supertype.
            for (Cls c = a.classes.get(r.owner()); c != null && m == null && f == null; c = c.sup == null ? null : a.classes.get(c.sup)) {
                if (r.kind() == 'm') {
                    m = c.methods.get(r.name() + r.desc());
                } else {
                    f = c.fields.get(r.name());
                }
            }
            if (m != null) {
                return mAB.containsKey(m) ? null : m.owner.name.replace('/', '.') + "." + m.name + m.desc;
            }
            return f == null || fAB.containsKey(f) ? null : f.owner.name.replace('/', '.') + "." + f.name;
        }

        /**
         * A replacement seen at a single call site is kept only when the types prove the porter can write it: it returns
         * what the old method returned (after the map's renames), each of its parameters is filled by an old argument or
         * the old receiver of the same type, and an instance replacement's receiver is the old receiver or an old argument.
         */
        boolean typeSafe(Meth from, Meth to) {
            if (from.special() || to.special()) {
                return false;
            }
            String oldRet = from.desc.substring(from.desc.indexOf(')') + 1);
            String newRet = descBA(to.desc.substring(to.desc.indexOf(')') + 1));
            if (!oldRet.equals(newRet)) {
                return false;
            }
            List<String> have = new ArrayList<>(VersionMap.Fix.paramTypes(from.desc));
            if (!from.isStatic()) {
                have.add(from.owner.name.replace('/', '.'));
            }
            if (!to.isStatic()) {
                Cls owner = cBA.get(to.owner);
                String receiver = owner == null ? null : owner.name.replace('/', '.');
                if (receiver == null || !have.remove(receiver)) {
                    return false;
                }
            }
            for (String p : VersionMap.Fix.paramTypes(descBA(to.desc))) {
                if (!have.remove(p)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * An instance call the game moved to another instance method keeps its receiver only when the new owner is the old
         * class (as b has it) or related to it: 1.21.2's game replaced player.sendSystemMessage(c) with
         * minecraft.gui.getChat().addMessage(c), a call on another object a mod's receiver cannot stand in for. A call that
         * becomes or stops being static carries its receiver as an argument, which the porter checks by type.
         */
        boolean receiverCarries(Meth from, Meth to) {
            if (to == null || from.isStatic() || to.isStatic()) {
                return true;
            }
            // A class b no longer has goes with it (ItemInteractionResult -> InteractionResult): the mod's variable is
            // retyped by the class rewrite, so its value is the new owner's.
            Cls mapped = cAB.get(from.owner);
            if (mapped == null && !b.classes.containsKey(from.owner.name)) {
                return true;
            }
            String old = mapped == null ? from.owner.name : mapped.name;
            return subtypeB(old, to.owner.name) || subtypeB(to.owner.name, old);
        }

        static String simpleName(String internal) {
            return internal.substring(Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$')) + 1);
        }

        /** A parameter the game added to a method, and the one value its own code passes there (a Java expression). */
        record ArgDefault(Meth from, Meth to, int index, String value, int votes, int of) {}

        /**
         * Added arguments with one value, read from the game's own calls: where a linked method gained exactly one
         * parameter, the code b inserted before each of its calls (in methods both releases have, aligned through the
         * links) is the value b passes. A method reference, a static field or a static no-argument call that two calls
         * agree on, at 80%, is the value (1.21.2: GuiGraphics.blitSprite(RenderType::guiTextured, ...)).
         */
        List<ArgDefault> argDefaults(List<Rewrite> rewrites) {
            Map<List<Meth>, Map<String, Integer>> votes = new HashMap<>();
            Map<List<Meth>, Integer> totals = new HashMap<>();
            for (var e : mAB.entrySet()) {
                Meth x = e.getKey();
                Meth y = e.getValue();
                if (x.refs.length == 0 || y.refs.length == 0) {
                    continue;
                }
                long[] ta = new long[x.refs.length];
                long[] tb = new long[y.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = fnv(token(x.refs[i], true));
                }
                for (int j = 0; j < tb.length; j++) {
                    tb[j] = fnv(token(y.refs[j], false));
                }
                // What a's code called at each place of b's: the same method linked, or another the game rewrote it to.
                Map<Integer, Ref> before = new HashMap<>();
                for (int[] p : align(ta, tb, x.ops.equals(y.ops))) {
                    before.put(p[1], x.refs[p[0]]);
                }
                for (int[] g : gaps(ta, tb)) {
                    if (g[1] != g[0] || g[3] - g[2] < 1 || g[3] - g[2] > 2) {
                        continue; // only code b inserted, one or two references long
                    }
                    // The first call after it that both sides make, to a method that gained one parameter.
                    for (int j = g[3]; j < y.refs.length && j < g[3] + 6; j++) {
                        Ref rb = y.refs[j];
                        if (rb.kind() != 'm') {
                            continue;
                        }
                        Meth mb = b.method(rb.owner(), rb.name(), rb.desc());
                        Meth ma = mb == null ? null : mBA.get(mb);
                        Ref ra = before.get(j);
                        if (ma == null && ra != null && ra.kind() == 'm') {
                            ma = a.method(ra.owner(), ra.name(), ra.desc());
                        }
                        if (ma == null || mb == null || !gainedOne(ma, mb)) {
                            continue;
                        }
                        String value = valueOf(Arrays.copyOfRange(y.refs, g[2], g[3]));
                        List<Meth> pair = List.of(ma, mb);
                        totals.merge(pair, 1, Integer::sum);
                        if (value != null) {
                            votes.computeIfAbsent(pair, k -> new HashMap<>()).merge(value, 1, Integer::sum);
                        }
                        break;
                    }
                }
            }
            List<ArgDefault> out = new ArrayList<>();
            for (var e : votes.entrySet()) {
                String best = null;
                int c1 = 0;
                for (var v : e.getValue().entrySet()) {
                    if (v.getValue() > c1) {
                        c1 = v.getValue();
                        best = v.getKey();
                    }
                }
                int total = totals.get(e.getKey());
                if (c1 >= 2 && c1 * 10 >= total * 8) {
                    Meth ma = e.getKey().get(0), mb = e.getKey().get(1);
                    out.add(new ArgDefault(ma, mb, addedIndex(ma, mb), best, c1, total));
                }
            }
            out.addAll(passedByNewCalls(rewrites, out));
            out.sort(Comparator.comparing((ArgDefault d) -> d.to().sig()));
            return out;
        }

        /**
         * Added arguments that b's own calls fill with one method reference, counted over every call b makes to the new
         * method. For a method the game changed in more than one way at once, its old and new calls do not line up, so
         * argDefaults() sees no inserted code (1.21.2: GuiGraphics.blit took a Function and float texture coordinates
         * together); its new calls still show the value (RenderType::guiTextured at 38 of 46). The value is the method
         * reference created last before the call in the same statement; at 80% of all calls, as above.
         */
        List<ArgDefault> passedByNewCalls(List<Rewrite> rewrites, List<ArgDefault> known) {
            Map<Meth, Meth> pairs = new HashMap<>();
            for (Rewrite r : rewrites) {
                if (r.to() != null && gainedOne(r.from(), r.to())) {
                    pairs.put(r.to(), r.from());
                }
            }
            for (ArgDefault d : known) {
                pairs.remove(d.to());
            }
            Map<Meth, Map<String, Integer>> votes = new HashMap<>();
            Map<Meth, Integer> totals = new HashMap<>();
            for (Cls c : b.classes.values()) {
                for (Meth y : c.methods.values()) {
                    for (int j = 0; j < y.refs.length; j++) {
                        Ref call = y.refs[j];
                        Meth mb = call.kind() == 'm' ? b.method(call.owner(), call.name(), call.desc()) : null;
                        if (mb == null || !pairs.containsKey(mb)) {
                            continue;
                        }
                        totals.merge(mb, 1, Integer::sum);
                        String value = null;
                        // Back to the statement before (a call returning nothing ends one); number constants count as references.
                        for (int k = j - 2; k >= Math.max(0, j - 32); k--) {
                            Ref r = y.refs[k];
                            if (r.kind() == 'm' && r.desc() != null && r.desc().endsWith(")V")) {
                                break;
                            }
                            if (r.kind() == 'k' && String.valueOf(r.owner()).startsWith("indy:")) {
                                value = valueOf(new Ref[] { r, y.refs[k + 1] });
                                break;
                            }
                        }
                        if (value != null) {
                            votes.computeIfAbsent(mb, x -> new HashMap<>()).merge(value, 1, Integer::sum);
                        }
                    }
                }
            }
            List<ArgDefault> out = new ArrayList<>();
            for (var e : votes.entrySet()) {
                var best = e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).get();
                int total = totals.get(e.getKey());
                Meth ma = pairs.get(e.getKey());
                int index = addedIndex(ma, e.getKey());
                if (index >= 0 && best.getValue() >= 2 && best.getValue() * 10 >= total * 8) {
                    out.add(new ArgDefault(ma, e.getKey(), index, best.getKey(), best.getValue(), total));
                }
            }
            return out;
        }

        /** b's method takes a's parameters (after the map's renames) with exactly one more, inserted anywhere. */
        boolean gainedOne(Meth ma, Meth mb) {
            return addedIndex(ma, mb) >= 0;
        }

        int addedIndex(Meth ma, Meth mb) {
            List<String> before = VersionMap.Fix.paramTypes(ma.desc), after = VersionMap.Fix.paramTypes(descBA(mb.desc));
            if (after.size() != before.size() + 1) {
                return -1;
            }
            for (int i = 0; i < after.size(); i++) {
                List<String> rest = new ArrayList<>(after);
                rest.remove(i);
                if (rest.equals(before)) {
                    return i;
                }
            }
            return -1;
        }

        /** Java for a value b's code made from these references: Owner::method, Owner.FIELD or Owner.method(). */
        String valueOf(Ref[] refs) {
            String owner;
            if (refs.length == 2 && refs[0].kind() == 'k' && refs[0].owner().startsWith("indy:") && refs[1].kind() == 'm') {
                owner = refs[1].owner();
                return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")
                    ? owner.replace('/', '.').replace('$', '.') + "::" + refs[1].name()
                    : null;
            }
            if (refs.length != 1) {
                return null;
            }
            Ref r = refs[0];
            owner = r.owner() == null ? "" : r.owner().replace('/', '.').replace('$', '.');
            if (!owner.startsWith("net.minecraft.") && !owner.startsWith("com.mojang.")) {
                return null;
            }
            if (r.kind() == 'f') {
                Fld f = b.field(r.owner(), r.name());
                return f != null && (f.flags & ACC_STATIC) != 0 ? owner + "." + r.name() : null;
            }
            if (r.kind() == 'm' && r.desc().startsWith("()")) {
                Meth m = b.method(r.owner(), r.name(), r.desc());
                return m != null && m.isStatic() ? owner + "." + r.name() + "()" : null;
            }
            return null;
        }

        /** A class the game stopped using and what its own code uses in its place. */
        record ClassRewrite(Cls from, Cls to, int votes, int total) {}

        /**
         * Removed classes and their replacements, read the same way as method rewrites: where an unlinked class of a is
         * referenced (a type, a cast, a new instance, the owner of a call) and the aligned reference of b names another
         * class, that is a vote (1.21.2: DirectionProperty -> EnumProperty, LootContextParam -> ContextKey). Kept with two
         * agreeing votes and 80% agreement.
         */
        List<ClassRewrite> classRewrites() {
            Map<Cls, Map<Cls, Integer>> votes = new HashMap<>();
            Map<Cls, Integer> totals = new HashMap<>();
            for (var e : mAB.entrySet()) {
                Meth x = e.getKey();
                Meth y = e.getValue();
                if (x.refs.length == 0 || y.refs.length == 0) {
                    continue;
                }
                long[] ta = new long[x.refs.length];
                long[] tb = new long[y.refs.length];
                for (int i = 0; i < ta.length; i++) {
                    ta[i] = fnv(token(x.refs[i], true));
                }
                for (int j = 0; j < tb.length; j++) {
                    tb[j] = fnv(token(y.refs[j], false));
                }
                for (int[] p : align(ta, tb, x.ops.equals(y.ops))) {
                    Ref ra = x.refs[p[0]];
                    Ref rb = y.refs[p[1]];
                    if (ra.kind() == 'k' || rb.kind() == 'k') {
                        continue;
                    }
                    Cls ca = a.classes.get(ra.owner());
                    Cls cb = b.classes.get(rb.owner());
                    if (ca == null || cb == null || cAB.containsKey(ca) || anonymous(ca.name)) {
                        continue;
                    }
                    votes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
                    totals.merge(ca, 1, Integer::sum);
                }
            }
            List<ClassRewrite> out = new ArrayList<>();
            for (var e : votes.entrySet()) {
                Cls best = null;
                int c1 = 0;
                for (var f : e.getValue().entrySet()) {
                    if (f.getValue() > c1) {
                        c1 = f.getValue();
                        best = f.getKey();
                    }
                }
                int total = totals.get(e.getKey());
                boolean moved = c1 == 1 && total == 1 && simpleName(best.name).equals(simpleName(e.getKey().name));
                if ((c1 >= 2 && c1 * 10 >= total * 8 || moved) && best.kind() == e.getKey().kind()) {
                    out.add(new ClassRewrite(e.getKey(), best, c1, total));
                }
            }
            out.sort(Comparator.comparing((ClassRewrite r) -> r.from().name));
            return out;
        }

        boolean unlinkedRef(Meth m, boolean sideA) {
            for (Ref r : m.refs) {
                if (target(r, sideA) != null) {
                    return true;
                }
            }
            return false;
        }

        /** The unlinked class, method or field a reference names, on one side. */
        Object target(Ref r, boolean sideA) {
            Snap s = sideA ? a : b;
            switch (r.kind()) {
                case 'm' -> {
                    Meth m = s.method(r.owner(), r.name(), r.desc());
                    return m == null || (sideA ? mAB : mBA).containsKey(m) ? null : m;
                }
                case 'f' -> {
                    Fld f = s.field(r.owner(), r.name());
                    return f == null || (sideA ? fAB : fBA).containsKey(f) ? null : f;
                }
                case 'c' -> {
                    Cls c = s.classes.get(r.owner());
                    return c == null || (sideA ? cAB : cBA).containsKey(c) ? null : c;
                }
                default -> {
                    return null;
                }
            }
        }

        void vote(Ref ra, Ref rb, Map<Object, Map<Object, Integer>> votes, Map<Object, Integer> totals) {
            if (ra.kind() != rb.kind()) {
                return;
            }
            Object x = target(ra, true);
            Object y = target(rb, false);
            if (x != null && y != null) {
                votes.computeIfAbsent(x, k -> new HashMap<>()).merge(y, 1, Integer::sum);
                totals.merge(x, 1, Integer::sum);
                totals.merge(y, 1, Integer::sum);
            }
            if (ra.kind() != 'c' && ra.kind() != 'k') {
                Cls ca = a.classes.get(ra.owner());
                Cls cb = b.classes.get(rb.owner());
                if (ca != null && cb != null && !cAB.containsKey(ca) && !cBA.containsKey(cb)) {
                    votes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
                    totals.merge(ca, 1, Integer::sum);
                    totals.merge(cb, 1, Integer::sum);
                }
            }
        }

        /**
         * Index pairs of references that line up and differ: positionally when the opcodes are identical,
         * otherwise inside equal-length gaps between the references both sides share (longest common subsequence).
         */
        static List<int[]> align(long[] ta, long[] tb, boolean sameOps) {
            List<int[]> out = new ArrayList<>();
            if (sameOps && ta.length == tb.length) {
                for (int i = 0; i < ta.length; i++) {
                    if (ta[i] != tb[i]) {
                        out.add(new int[] { i, i });
                    }
                }
                return out;
            }
            int n = ta.length;
            int m = tb.length;
            if ((long) n * m > 250_000) {
                return out;
            }
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    l[i][j] = ta[i] == tb[j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            int gi = 0;
            int gj = 0;
            while (i < n && j < m) {
                if (ta[i] == tb[j]) {
                    gap(gi, i, gj, j, out);
                    i++;
                    j++;
                    gi = i;
                    gj = j;
                } else if (l[i + 1][j] >= l[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
            gap(gi, n, gj, m, out);
            return out;
        }

        /** Indices of ta that the longest common subsequence leaves out where tb has nothing in their place (pure deletions). */
        static Set<Integer> droppedIndices(long[] ta, long[] tb) {
            Set<Integer> out = new HashSet<>();
            int n = ta.length;
            int m = tb.length;
            if ((long) n * m > 250_000) {
                return out;
            }
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    l[i][j] = ta[i] == tb[j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            int gi = 0;
            int gj = 0;
            while (i < n && j < m) {
                if (ta[i] == tb[j]) {
                    if (j == gj) {
                        for (int k = gi; k < i; k++) {
                            out.add(k);
                        }
                    }
                    i++;
                    j++;
                    gi = i;
                    gj = j;
                } else if (l[i + 1][j] >= l[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
            if (j == gj && m == gj) {
                for (int k = gi; k < n; k++) {
                    out.add(k);
                }
            }
            return out;
        }

        /** Every stretch the longest common subsequence leaves unmatched on both sides: {i0, i1, j0, j1}, either side possibly empty. */
        static List<int[]> gaps(long[] ta, long[] tb) {
            List<int[]> out = new ArrayList<>();
            int n = ta.length;
            int m = tb.length;
            if ((long) n * m > 250_000) {
                return out;
            }
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    l[i][j] = ta[i] == tb[j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            int gi = 0;
            int gj = 0;
            while (i < n && j < m) {
                if (ta[i] == tb[j]) {
                    if (i > gi || j > gj) {
                        out.add(new int[] { gi, i, gj, j });
                    }
                    i++;
                    j++;
                    gi = i;
                    gj = j;
                } else if (l[i + 1][j] >= l[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
            if (n > gi || m > gj) {
                out.add(new int[] { gi, n, gj, m });
            }
            return out;
        }

        static void gap(int i0, int i1, int j0, int j1, List<int[]> out) {
            if (i1 - i0 != j1 - j0) {
                return;
            }
            for (int k = 0; k < i1 - i0; k++) {
                out.add(new int[] { i0 + k, j0 + k });
            }
        }

        /**
         * How much the linked methods reading or writing x and y overlap (Jaccard), or -1 when they share no
         * ordinary method: constructors and static initialisers touch every field and prove nothing alone.
         */
        double accessorScore(Fld x, Fld y) {
            Set<Meth> ax = new HashSet<>();
            for (Meth m : x.accessors) {
                if (mAB.containsKey(m)) {
                    ax.add(m);
                }
            }
            int common = 0;
            int plain = 0;
            int by = 0;
            for (Meth m : y.accessors) {
                Meth back = mBA.get(m);
                if (back != null) {
                    by++;
                    if (ax.contains(back)) {
                        common++;
                        if (!back.special()) {
                            plain++;
                        }
                    }
                }
            }
            int union = ax.size() + by - common;
            return plain == 0 ? -1 : (double) common / union;
        }

        /** An override follows the method it overrides (and the reverse): bytecode overrides share name and descriptor. */
        void hierarchy() {
            for (var e : cAB.entrySet()) {
                Cls ca = e.getKey();
                Cls cb = e.getValue();
                for (Meth ma : ca.methods.values()) {
                    if (mAB.containsKey(ma) || ma.special() || ma.isStatic() || (ma.flags & ACC_PRIVATE) != 0) {
                        continue;
                    }
                    Set<Meth> candidates = new HashSet<>();
                    boolean conflict = false;
                    for (Meth rel : related(ca, ma.name + ma.desc)) {
                        Meth rb = mAB.get(rel);
                        if (rb == null) {
                            continue;
                        }
                        Meth cand = cb.methods.get(rb.name + rb.desc);
                        if (cand == null || mBA.containsKey(cand)) {
                            conflict = true;
                            continue;
                        }
                        candidates.add(cand);
                    }
                    if (candidates.size() == 1 && !conflict) {
                        link(ma, candidates.iterator().next(), "override");
                    }
                }
            }
        }

        /** Methods with the same name and descriptor in supertypes and subtypes of c (in a). */
        List<Meth> related(Cls c, String key) {
            List<Meth> out = new ArrayList<>();
            Set<Cls> seen = new HashSet<>();
            ArrayDeque<Cls> up = new ArrayDeque<>();
            for (String s : supertypes(c)) {
                Cls p = a.classes.get(s);
                if (p != null) {
                    up.add(p);
                }
            }
            while (!up.isEmpty()) {
                Cls p = up.poll();
                if (!seen.add(p)) {
                    continue;
                }
                Meth m = p.methods.get(key);
                if (m != null) {
                    out.add(m);
                }
                for (String s : supertypes(p)) {
                    Cls q = a.classes.get(s);
                    if (q != null) {
                        up.add(q);
                    }
                }
            }
            ArrayDeque<Cls> down = new ArrayDeque<>(c.children);
            while (!down.isEmpty()) {
                Cls p = down.poll();
                if (!seen.add(p)) {
                    continue;
                }
                Meth m = p.methods.get(key);
                if (m != null) {
                    out.add(m);
                }
                down.addAll(p.children);
            }
            return out;
        }

        /** An identical non-trivial body found exactly once among unlinked methods on each side. */
        void moves() {
            Map<Long, List<Meth>> ha = new HashMap<>();
            Map<Long, List<Meth>> hb = new HashMap<>();
            for (Cls c : a.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (!mAB.containsKey(m) && !m.special() && m.distinctive()) {
                        ha.computeIfAbsent(m.hash, x -> new ArrayList<>()).add(m);
                    }
                }
            }
            for (Cls c : b.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (!mBA.containsKey(m) && !m.special() && m.distinctive()) {
                        hb.computeIfAbsent(m.hash, x -> new ArrayList<>()).add(m);
                    }
                }
            }
            for (var e : ha.entrySet()) {
                List<Meth> l = hb.get(e.getKey());
                if (e.getValue().size() != 1 || l == null || l.size() != 1) {
                    continue;
                }
                Meth ma = e.getValue().get(0);
                Meth mb = l.get(0);
                if (ma.owner.loader != mb.owner.loader) {
                    continue;
                }
                link(ma, mb, cAB.get(ma.owner) == mb.owner ? "body-unique" : "moved");
            }
        }
    }

    // ------------------------------------------------------------------ ids and tables

    /** Walks the chain one release at a time, carrying ids forward, and hands VersionMap.Chain one table per release. */
    static final class Driver implements VersionMap.Chain.Loader {

        final List<String> versions;
        final String loader;
        final List<String> pinned;
        final Map<String, String> loaderUsed = new LinkedHashMap<>();
        final List<String> loaderNotes = new ArrayList<>();
        final List<String> hops = new ArrayList<>();
        /** Call-site rewrites of every hop, one JSON object each (Hop.rewrites). */
        final List<String> rewrites = new ArrayList<>();
        /** Removed classes and the class the game's code uses in their place (Hop.classRewrites). */
        final List<String> classRewrites = new ArrayList<>();
        /** Members the game replaced with a chain on the same receiver (Hop.chains). */
        final List<String> chains = new ArrayList<>();
        /** Methods moved to a class one holder keeps beside the old one (Hop.siblings). */
        final List<String> siblings = new ArrayList<>();
        /** Parameters the game added with one value its own code always passes (Hop.argDefaults). */
        final List<String> argDefaults = new ArrayList<>();
        Snap prev;
        Path prevLoaderJar;
        String prevLoaderVersion;
        int nextClass = 1;
        int nextMethod = 1;
        int nextField = 1;
        String metadata;

        Driver(List<String> versions, String loader, List<String> pinned) {
            this.versions = versions;
            this.loader = loader;
            this.pinned = pinned;
        }

        @Override
        public VersionMap.Table load(String version) throws Exception {
            Path jar = null;
            String lv = null;
            if (loader != null) {
                int i = versions.indexOf(version);
                lv = pinned != null && i < pinned.size() && !pinned.get(i).isBlank() ? pinned.get(i) : null;
                if (lv == null) {
                    if (metadata == null) {
                        metadata = VersionMap.fetchText(NEOFORGE + "maven-metadata.xml", "neoforge-maven-metadata.xml");
                    }
                    lv = neoforgeFor(version, metadata);
                }
                if (lv != null) {
                    jar = VersionMap.fetch(NEOFORGE + lv + "/neoforge-" + lv + "-universal.jar", "neoforge-" + lv + "-universal.jar");
                    loaderUsed.put(version, lv);
                } else if (prevLoaderJar != null) {
                    jar = prevLoaderJar;
                    loaderNotes.add(version + ": no NeoForge release; carried " + prevLoaderVersion);
                    loaderUsed.put(version, null);
                    lv = prevLoaderVersion;
                } else {
                    loaderNotes.add(version + ": no NeoForge release; loader classes start at a later release");
                    loaderUsed.put(version, null);
                }
            }
            Snap s = GameDiff.load(version, jar, lv);
            return next(s, jar, lv);
        }

        VersionMap.Table next(Snap s, Path jar, String lv) {
            if (prev == null) {
                for (Cls c : s.classes.values()) {
                    fresh(c);
                }
            } else {
                long t0 = System.nanoTime();
                Hop h = new Hop(prev, s);
                h.run();
                for (Cls c : s.classes.values()) {
                    Cls ca = h.cBA.get(c);
                    c.id = ca == null ? "class_g" + nextClass++ : ca.id;
                    for (Meth m : c.methods.values()) {
                        Meth ma = h.mBA.get(m);
                        m.id = ma == null ? "method_g" + nextMethod++ : ma.id;
                    }
                    for (Fld f : c.fields.values()) {
                        Fld fa = h.fBA.get(f);
                        f.id = fa == null ? "field_g" + nextField++ : fa.id;
                    }
                }
                String line = String.format(
                    Locale.ROOT,
                    "%s -> %s: linked %d/%d classes, %d/%d methods, %d/%d fields (%.1fs)",
                    prev.version,
                    s.version,
                    h.cAB.size(),
                    prev.classes.size(),
                    h.mAB.size(),
                    prev.methodCount(),
                    h.fAB.size(),
                    prev.fieldCount(),
                    (System.nanoTime() - t0) / 1e9
                );
                hops.add(line);
                System.out.println("  " + line);
                List<Hop.Rewrite> found = h.rewrites();
                for (Hop.Rewrite r : found) {
                    rewrites.add(
                        "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                        ", \"owner\": " + VersionMap.Diff.q(r.from().owner.name.replace('/', '.')) +
                        ", \"from\": " + VersionMap.Diff.q(r.from().name) +
                        ", \"desc\": " + VersionMap.Diff.q(r.from().desc) +
                        ", \"static\": " + r.from().isStatic() +
                        // A call the game deleted has no replacement: an empty "to".
                        ", \"toOwner\": " + VersionMap.Diff.q(r.to() == null ? "" : r.to().owner.name.replace('/', '.')) +
                        ", \"to\": " + VersionMap.Diff.q(r.to() == null ? "" : r.to().name) +
                        ", \"newDesc\": " + VersionMap.Diff.q(r.to() == null ? "" : r.to().desc) +
                        ", \"newStatic\": " + (r.to() != null && r.to().isStatic()) +
                        ", \"votes\": " + r.votes() + ", \"of\": " + r.total() + " }"
                    );
                }
                for (Hop.ArgDefault d : h.argDefaults(found)) {
                    argDefaults.add(
                        "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                        ", \"owner\": " + VersionMap.Diff.q(d.from().owner.name.replace('/', '.')) +
                        ", \"from\": " + VersionMap.Diff.q(d.from().name) +
                        ", \"desc\": " + VersionMap.Diff.q(d.from().desc) +
                        ", \"toOwner\": " + VersionMap.Diff.q(d.to().owner.name.replace('/', '.')) +
                        ", \"to\": " + VersionMap.Diff.q(d.to().name) +
                        ", \"newDesc\": " + VersionMap.Diff.q(d.to().desc) +
                        ", \"index\": " + d.index() +
                        ", \"value\": " + VersionMap.Diff.q(d.value()) +
                        ", \"votes\": " + d.votes() + ", \"of\": " + d.of() + " }"
                    );
                }
                for (Hop.Sibling c : h.siblings()) {
                    siblings.add(
                        "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                        ", \"owner\": " + VersionMap.Diff.q(c.from().owner.name.replace('/', '.')) +
                        ", \"from\": " + VersionMap.Diff.q(c.from().name) +
                        ", \"desc\": " + VersionMap.Diff.q(c.from().desc) +
                        ", \"holder\": " + VersionMap.Diff.q(c.holder()) +
                        ", \"fromField\": " + VersionMap.Diff.q(c.fromField()) +
                        ", \"toField\": " + VersionMap.Diff.q(c.toField()) +
                        ", \"newOwner\": " + VersionMap.Diff.q(c.to().owner.name.replace('/', '.')) +
                        ", \"newDesc\": " + VersionMap.Diff.q(c.to().desc) + " }"
                    );
                }
                for (Hop.Chain c : h.chains()) {
                    chains.add(
                        "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                        ", \"owner\": " + VersionMap.Diff.q(c.owner()) +
                        ", \"newOwner\": " + VersionMap.Diff.q(c.newOwner()) +
                        ", \"from\": " + VersionMap.Diff.q(c.name()) +
                        ", \"desc\": " + VersionMap.Diff.q(c.desc()) +
                        ", \"field\": " + c.field() +
                        ", \"steps\": " + VersionMap.Diff.q(c.steps()) +
                        ", \"votes\": " + c.votes() + ", \"of\": " + c.of() + " }"
                    );
                }
                for (Hop.ClassRewrite r : h.classRewrites()) {
                    classRewrites.add(
                        "{ \"step\": " + VersionMap.Diff.q(prev.version + " -> " + s.version) +
                        ", \"from\": " + VersionMap.Diff.q(r.from().name.replace('/', '.')) +
                        ", \"to\": " + VersionMap.Diff.q(r.to().name.replace('/', '.')) +
                        ", \"votes\": " + r.votes() + ", \"of\": " + r.total() + " }"
                    );
                }
            }
            prev = s;
            prevLoaderJar = jar;
            prevLoaderVersion = lv;
            return table(s);
        }

        void fresh(Cls c) {
            c.id = "class_g" + nextClass++;
            for (Meth m : c.methods.values()) {
                m.id = "method_g" + nextMethod++;
            }
            for (Fld f : c.fields.values()) {
                f.id = "field_g" + nextField++;
            }
        }

        static VersionMap.Table table(Snap s) {
            VersionMap.Table t = new VersionMap.Table(s.version);
            Map<String, String> toId = new HashMap<>();
            for (Cls c : s.classes.values()) {
                t.classes.put(c.id, c.name);
                toId.put(c.name, c.id);
            }
            for (Cls c : s.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (m.special()) {
                        continue;
                    }
                    String ids = VersionMap.Tiny.remapDesc(m.desc, toId);
                    String k = VersionMap.Table.key(c.id, m.id, ids);
                    t.methods.put(k, new VersionMap.Member(c.name, m.name, m.desc, ids));
                    t.methodsByInterName.computeIfAbsent(m.id, x -> new ArrayList<>()).add(k);
                }
                for (Fld f : c.fields.values()) {
                    String ids = VersionMap.Tiny.remapDesc(f.desc, toId);
                    t.fields.put(VersionMap.Table.key(c.id, f.id, ids), new VersionMap.Member(c.name, f.name, f.desc, ids));
                }
            }
            return t;
        }

        String json(VersionMap.Chain c) {
            String body = c.json("mojmap");
            int nl = body.indexOf('\n', 2);
            StringBuilder head = new StringBuilder();
            head.append("  \"linked\": \"game-bytecode\",\n");
            head.append(
                "  \"source\": " +
                VersionMap.Diff.q(
                    "Mojang client jars and client_mappings from piston-meta (jar names from 26.1); linked release to release by GameDiff from " +
                    "the game's own bytecode (names, bodies, references, overrides, field access); no intermediary or yarn"
                ) +
                ",\n"
            );
            head.append("  \"hops\": {");
            for (int i = 0; i < hops.size(); i++) {
                String h = hops.get(i);
                head.append(i == 0 ? "" : ", ").append(VersionMap.Diff.q(h.substring(0, h.indexOf(':')))).append(": ");
                head.append(VersionMap.Diff.q(h.substring(h.indexOf(':') + 2)));
            }
            head.append("},\n");
            // One per line, as the typed fixer reads the other lists (VersionMap.Known.load).
            head.append("  \"rewrites\": [");
            for (int i = 0; i < rewrites.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(rewrites.get(i));
            }
            head.append(rewrites.isEmpty() ? "],\n" : "\n  ],\n");
            head.append("  \"argDefaults\": [");
            for (int i = 0; i < argDefaults.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(argDefaults.get(i));
            }
            head.append(argDefaults.isEmpty() ? "],\n" : "\n  ],\n");
            head.append("  \"siblings\": [");
            for (int i = 0; i < siblings.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(siblings.get(i));
            }
            head.append(siblings.isEmpty() ? "],\n" : "\n  ],\n");
            head.append("  \"chains\": [");
            for (int i = 0; i < chains.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(chains.get(i));
            }
            head.append(chains.isEmpty() ? "],\n" : "\n  ],\n");
            head.append("  \"classRewrites\": [");
            for (int i = 0; i < classRewrites.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(classRewrites.get(i));
            }
            head.append(classRewrites.isEmpty() ? "],\n" : "\n  ],\n");
            if (loader != null) {
                head.append("  \"loader\": { \"name\": \"neoforge\", \"source\": ").append(VersionMap.Diff.q(NEOFORGE)).append(", \"versions\": {");
                int i = 0;
                for (var e : loaderUsed.entrySet()) {
                    head.append(i++ == 0 ? "" : ", ").append(VersionMap.Diff.q(e.getKey())).append(": ").append(VersionMap.Diff.q(e.getValue()));
                }
                head.append("}, \"notes\": {");
                for (int j = 0; j < loaderNotes.size(); j++) {
                    String n = loaderNotes.get(j);
                    head.append(j == 0 ? "" : ", ").append(VersionMap.Diff.q(n.substring(0, n.indexOf(':')))).append(": ");
                    head.append(VersionMap.Diff.q(n.substring(n.indexOf(':') + 2)));
                }
                head.append("}, \"fabricApi\": \"not covered\" },\n");
            }
            return body.substring(0, nl + 1) + head + body.substring(nl + 1);
        }
    }

    // ------------------------------------------------------------------ validation against intermediary

    /**
     * Our linking of an obfuscated pair, scored against VersionMap's intermediary diff of the same pair.
     * A claim is correct when it names the same target as intermediary. A wrong claim is a conflict when
     * intermediary links that thing elsewhere (or gives our target to something else); otherwise
     * intermediary has no link there at all and cannot judge it (unlinked). Where intermediary gives a
     * class a new id but carries most of its members to one class, that class is intermediary's answer.
     */
    static final class Compare {

        static final class Tally {

            int claims;
            int truth;
            int correct;
            int found;
            int conflicts;
            final List<String> wrong = new ArrayList<>();
            final List<String> missed = new ArrayList<>();

            void add(boolean mine, boolean truthSays, boolean agree, boolean conflict, String what) {
                if (mine) {
                    claims++;
                    if (agree) {
                        correct++;
                    } else if (conflict) {
                        conflicts++;
                        wrong.add("conflict " + what);
                    } else {
                        wrong.add("unlinked " + what);
                    }
                }
                if (truthSays) {
                    truth++;
                    if (agree) {
                        found++;
                    } else if (!mine) {
                        missed.add(what);
                    }
                }
            }

            String line(String name) {
                return String.format(
                    Locale.ROOT,
                    "  %-18s claimed %6d  agree %6d  conflict %5d  unlinked %5d  precision %6.2f%% (judged %6.2f%%)  truth %6d  recall %6.2f%%",
                    name,
                    claims,
                    correct,
                    conflicts,
                    claims - correct - conflicts,
                    claims == 0 ? 100.0 : 100.0 * correct / claims,
                    correct + conflicts == 0 ? 100.0 : 100.0 * correct / (correct + conflicts),
                    truth,
                    truth == 0 ? 100.0 : 100.0 * found / truth
                );
            }
        }

        static void run(String va, String vb, boolean nonPrivate, Path report) throws Exception {
            long t0 = System.nanoTime();
            Snap a = load(va, null, null);
            Snap b = load(vb, null, null);
            Hop h = new Hop(a, b);
            h.run();
            double secs = (System.nanoTime() - t0) / 1e9;
            if (b.classes.containsKey(WHY)) {
                Cls back = h.cBA.get(b.classes.get(WHY));
                System.out.println("    why-b " + WHY + " <- " + (back == null ? null : back.name + " (" + h.how.get(back) + ")"));
            }
            VersionMap.Table ta = VersionMap.Table.load(va, "mojmap");
            VersionMap.Table tb = VersionMap.Table.load(vb, "mojmap");
            Map<String, String> classInter = new HashMap<>();
            ta.classes.forEach((i, n) -> classInter.put(n, i));
            Map<String, String> methodKey = new HashMap<>();
            ta.methods.forEach((k, m) -> methodKey.put(m.owner() + "." + m.name() + m.desc(), k));
            Map<String, String> fieldKey = new HashMap<>();
            ta.fields.forEach((k, m) -> fieldKey.put(m.owner() + "." + m.name(), k));
            // Intermediary's answer for every class and member we read (all of them, for the conflict check).
            Map<Cls, String> classTruth = new HashMap<>();
            Map<Meth, VersionMap.Member> methodTruth = new HashMap<>();
            Map<Fld, VersionMap.Member> fieldTruth = new HashMap<>();
            Set<String> classTaken = new HashSet<>();
            Set<String> methodTaken = new HashSet<>();
            Set<String> fieldTaken = new HashSet<>();
            int adjusted = 0;
            for (Cls ca : a.classes.values()) {
                String inter = classInter.get(ca.name);
                if (inter == null) {
                    continue;
                }
                String truth = tb.classes.get(inter);
                if (truth == null) {
                    truth = memberVote(ca, tb, methodKey, fieldKey);
                    if (truth != null) {
                        adjusted++;
                    }
                }
                classTruth.put(ca, truth);
                if (truth != null) {
                    classTaken.add(truth);
                }
                for (Meth ma : ca.methods.values()) {
                    String k = ma.special() ? null : methodKey.get(ma.sig());
                    if (k != null) {
                        VersionMap.Member mt = methodTarget(tb, k);
                        methodTruth.put(ma, mt);
                        if (mt != null) {
                            methodTaken.add(mt.owner() + "." + mt.name() + mt.desc());
                        }
                    }
                }
                for (Fld fa : ca.fields.values()) {
                    String k = fieldKey.get(ca.name + "." + fa.name);
                    if (k != null) {
                        VersionMap.Member ft = tb.fields.get(k);
                        fieldTruth.put(fa, ft);
                        if (ft != null) {
                            fieldTaken.add(ft.owner() + "." + ft.name());
                        }
                    }
                }
            }
            Map<String, Tally> t = new LinkedHashMap<>();
            for (String k : List.of(
                "class renames",
                "class removals",
                "method renames",
                "method moves",
                "method signatures",
                "method removals",
                "field renames",
                "field removals"
            )) {
                t.put(k, new Tally());
            }
            for (Cls ca : a.classes.values()) {
                if (!classTruth.containsKey(ca) || (nonPrivate && (ca.flags & ACC_PRIVATE) != 0)) {
                    continue;
                }
                String truth = classTruth.get(ca);
                if (truth != null && !b.classes.containsKey(truth)) {
                    continue;
                }
                Cls cb = h.cAB.get(ca);
                String mine = cb == null ? null : cb.name;
                String what = ca.name + " -> mine " + mine + " truth " + truth + " (" + h.how.get(ca) + ")";
                boolean agree = Objects.equals(mine, truth);
                boolean conflict = !agree && (truth != null || classTaken.contains(mine));
                t.get("class renames").add(mine != null && !mine.equals(ca.name), truth != null && !truth.equals(ca.name), agree, conflict, what);
                t.get("class removals").add(mine == null, truth == null, agree, conflict, what);
            }
            for (Cls ca : a.classes.values()) {
                if (nonPrivate && (ca.flags & ACC_PRIVATE) != 0) {
                    continue;
                }
                String truthOwner = classTruth.get(ca);
                for (Meth ma : ca.methods.values()) {
                    if (!methodTruth.containsKey(ma) || VersionMap.Diff.noise(ma.name) || (nonPrivate && (ma.flags & ACC_PRIVATE) != 0)) {
                        continue;
                    }
                    VersionMap.Member mt = methodTruth.get(ma);
                    if (mt != null && b.method(mt.owner(), mt.name(), mt.desc()) == null) {
                        continue;
                    }
                    Meth mb = h.mAB.get(ma);
                    Cls ownerB = h.cAB.get(ca);
                    boolean mineMoved = mb != null && mb.owner != ownerB;
                    boolean truthMoved = mt != null && !mt.owner().equals(truthOwner);
                    String mineT = mb == null ? null : mb.sig();
                    String truthT = mt == null ? null : mt.owner() + "." + mt.name() + mt.desc();
                    boolean agree = Objects.equals(mineT, truthT);
                    boolean conflict = !agree && (truthT != null || methodTaken.contains(mineT));
                    String what = ma.sig() + " -> mine " + mineT + " truth " + truthT + " (" + h.how.get(ma) + ")";
                    t.get("method renames").add(
                        mb != null && !mineMoved && !mb.name.equals(ma.name),
                        mt != null && !truthMoved && !mt.name().equals(ma.name),
                        agree,
                        conflict,
                        what
                    );
                    t.get("method moves").add(mineMoved, truthMoved, agree, conflict, what);
                    VersionMap.Member ma0 = ta.methods.get(methodKey.get(ma.sig()));
                    t.get("method signatures").add(
                        mb != null && !h.descBA(mb.desc).equals(ma.desc),
                        mt != null && !mt.interDesc().equals(ma0.interDesc()),
                        agree,
                        conflict,
                        what
                    );
                    t.get("method removals").add(mb == null, mt == null, agree, conflict, what);
                }
                for (Fld fa : ca.fields.values()) {
                    if (!fieldTruth.containsKey(fa) || VersionMap.Diff.noise(fa.name) || (nonPrivate && (fa.flags & ACC_PRIVATE) != 0)) {
                        continue;
                    }
                    VersionMap.Member ft = fieldTruth.get(fa);
                    if (ft != null && b.field(ft.owner(), ft.name()) == null) {
                        continue;
                    }
                    Fld fb = h.fAB.get(fa);
                    String mineT = fb == null ? null : fb.owner.name + "." + fb.name;
                    String truthT = ft == null ? null : ft.owner() + "." + ft.name();
                    boolean agree = Objects.equals(mineT, truthT);
                    boolean conflict = !agree && (truthT != null || fieldTaken.contains(mineT));
                    String what = ca.name + "." + fa.name + " -> mine " + mineT + " truth " + truthT + " (" + h.how.get(fa) + ")";
                    t.get("field renames").add(fb != null && !fb.name.equals(fa.name), ft != null && !ft.name().equals(fa.name), agree, conflict, what);
                    t.get("field removals").add(fb == null, ft == null, agree, conflict, what);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(
                String.format(
                    Locale.ROOT,
                    "compare %s -> %s (%s, read + link %.1fs): game bytecode vs Fabric intermediary%n",
                    va,
                    vb,
                    nonPrivate ? "non-private" : "all members",
                    secs
                )
            );
            for (var e : t.entrySet()) {
                sb.append(e.getValue().line(e.getKey())).append('\n');
            }
            sb.append(
                "  agree: same target as intermediary; conflict: intermediary links it (or our target) elsewhere;\n" +
                "  unlinked: intermediary has no link to judge by (precision counts these as wrong, judged leaves them out).\n"
            );
            sb.append("  " + adjusted + " classes got a new intermediary id but most members went to one class: that class is the truth\n");
            System.out.print(sb);
            StringBuilder detail = new StringBuilder(sb);
            for (var e : t.entrySet()) {
                for (String w : e.getValue().wrong) {
                    detail.append("wrong  ").append(e.getKey()).append(": ").append(w).append('\n');
                }
                for (String w : e.getValue().missed) {
                    detail.append("missed ").append(e.getKey()).append(": ").append(w).append('\n');
                }
            }
            if (report != null) {
                if (report.toAbsolutePath().getParent() != null) {
                    Files.createDirectories(report.toAbsolutePath().getParent());
                }
                Files.writeString(report, detail, StandardCharsets.UTF_8);
                System.out.println("  every disagreement: " + report);
            }
        }

        static VersionMap.Member methodTarget(VersionMap.Table tb, String k) {
            VersionMap.Member mt = tb.methods.get(k);
            if (mt == null) {
                List<String> el = tb.methodsByInterName.get(k.substring(k.indexOf('.') + 1));
                if (el != null && !el.isEmpty()) {
                    mt = tb.methods.get(el.get(0));
                }
            }
            return mt;
        }

        /** The one class most of ca's members went to, by intermediary's own member ids. */
        static String memberVote(Cls ca, VersionMap.Table tb, Map<String, String> mk, Map<String, String> fk) {
            Map<String, Integer> votes = new HashMap<>();
            int members = 0;
            for (Meth m : ca.methods.values()) {
                String k = m.special() ? null : mk.get(m.sig());
                if (k == null || !k.substring(k.indexOf('.') + 1).startsWith("method_")) {
                    continue;
                }
                members++;
                VersionMap.Member t = methodTarget(tb, k);
                if (t != null) {
                    votes.merge(t.owner(), 1, Integer::sum);
                }
            }
            for (Fld f : ca.fields.values()) {
                String k = fk.get(ca.name + "." + f.name);
                if (k == null) {
                    continue;
                }
                members++;
                VersionMap.Member t = tb.fields.get(k);
                if (t != null) {
                    votes.merge(t.owner(), 1, Integer::sum);
                }
            }
            for (var e : votes.entrySet()) {
                if (e.getValue() >= 2 && e.getValue() * 10 >= members * 6) {
                    return e.getKey();
                }
            }
            return null;
        }
    }
}
