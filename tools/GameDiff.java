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
        if (loader != null && !loader.equals("neoforge") && !loader.equals("fabric")) {
            System.err.println("--loader is neoforge or fabric");
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
            "usage: java GameDiff --chain <v1,v2,...> --out map.json [--loader neoforge|fabric] [--loader-versions a,b,...] [--cache dir]\n" +
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
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
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
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
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
        int flags; // widened by the loader's access transformer (Exposure.loaderAccess)
        final String sup;
        final List<String> ifaces;
        final boolean loader;
        final Map<String, Meth> methods = new LinkedHashMap<>(); // name + desc
        final Map<String, Fld> fields = new LinkedHashMap<>(); // name
        final List<Cls> children = new ArrayList<>();
        String id;
        boolean library; // read from one of the release's library jars (addLibraries)

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
        final Map<String, Origin> origins = new HashMap<>(); // class -> its bytes, so Learner can read a body again
        final Map<String, Cls> libraries = new HashMap<>(); // the release's library classes, declarations only: what the learner may write calls to

        /** A library member as the JVM resolves it from owner: declared there or in a supertype. */
        Meth libraryMethod(String owner, String name, String desc) {
            for (String c = owner; c != null;) {
                Cls k = libraries.get(c);
                if (k == null) {
                    return null;
                }
                Meth m = k.methods.get(name + desc);
                if (m != null) {
                    return m;
                }
                c = k.sup;
            }
            return null;
        }

        Fld libraryField(String owner, String name) {
            Cls k = libraries.get(owner);
            return k == null ? null : k.fields.get(name);
        }

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

    /** Where a class of a snapshot came from: its bytes and how its references are named and resolved. */
    record Origin(byte[] bytes, Map<String, RawCls> raw, Map<String, RawCls> fallback, Names names, Map<String, Ref> resolved) {}

    static final class RawCls {

        byte[] bytes;
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
        rc.bytes = bytes;
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
            boolean inside = loader ? !gameClass(n) : gameClass(n); // the loader's classes are the ones its caller chose (Exposure.snap)
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
        for (int i = 0; i < made.size(); i++) {
            s.classes.put(made.get(i).name, made.get(i));
            s.origins.put(made.get(i).name, new Origin(keep.get(i).bytes, raw, fallback, names, resolved));
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

    /**
     * The jars of the libraries a release runs with (JOML, DataFixerUpper, Brigadier, fastutil, LWJGL...), as its
     * version json lists them, each fetched once and checked by its sha1. Natives are left out: they hold no classes.
     */
    static List<Path> libraries(String versionJson) throws Exception {
        List<Path> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"artifact\"\\s*:\\s*\\{([^}]*)}").matcher(versionJson);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String obj = m.group(1);
            String path = VersionMap.jsonString(obj, "path", 0);
            if (path == null || !path.endsWith(".jar") || path.contains("natives") || !seen.add(path)) {
                continue;
            }
            out.add(verified(new String[] { VersionMap.jsonString(obj, "url", 0), VersionMap.jsonString(obj, "sha1", 0) },
                "lib-" + path.substring(path.lastIndexOf('/') + 1)));
        }
        return out;
    }

    /**
     * Library classes as the JVM resolves a mod's references against them: declarations only, no code, marked as not the
     * game's (as the loader's are). A class the snapshot already has (the game's, the loader's, an earlier jar's) is kept.
     */
    static void addLibraries(Map<String, Cls> into, List<Path> jars) throws IOException {
        for (Path jar : jars) {
            for (byte[] bytes : classes(jar, null)) {
                ClassModel cm = ClassFile.of().parse(bytes);
                String name = cm.thisClass().asInternalName();
                if (into.containsKey(name)) {
                    continue;
                }
                List<String> ifs = new ArrayList<>();
                for (ClassEntry e : cm.interfaces()) {
                    ifs.add(e.asInternalName());
                }
                Cls c = new Cls(name, cm.flags().flagsMask(), cm.superclass().map(ClassEntry::asInternalName).orElse(null), ifs, true);
                c.library = true;
                for (FieldModel fm : cm.fields()) {
                    c.fields.put(fm.fieldName().stringValue(), new Fld(c, fm.fieldName().stringValue(), fm.fieldType().stringValue(), fm.flags().flagsMask()));
                }
                for (MethodModel mm : cm.methods()) {
                    Meth m = new Meth(c, mm.methodName().stringValue(), mm.methodType().stringValue(), mm.flags().flagsMask());
                    c.methods.put(m.name + m.desc, m);
                }
                into.put(name, c);
            }
        }
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
        return load(version, loaderJar == null ? List.of() : classes(loaderJar, "net/neoforged/"), loaderVersion);
    }

    /** One release from Mojang, plus the loader's classes (in Mojang names) when given. */
    static Snap load(String version, List<byte[]> loader, String loaderVersion) throws Exception {
        long t0 = System.nanoTime();
        String vj = versionJson(version);
        Path jar = verified(download(vj, "client"), "client-" + version + ".jar");
        String[] m = download(vj, "client_mappings");
        VersionMap.Proguard pg = null;
        if (m != null) {
            pg = VersionMap.Proguard.parse(Files.readString(verified(m, "client-" + version + ".txt"), StandardCharsets.UTF_8));
        }
       Snap s = read(version, classes(jar, null), pg, loader);
        addLibraries(s.libraries, libraries(vj));
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
         * reference created last before the call in the same statement that makes the added parameter's
         * functional interface; at 80% of all calls, as above.
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
                        String sam = samName(MethodTypeDesc.ofDescriptor(mb.desc).parameterList().get(addedIndex(pairs.get(mb), mb)).descriptorString());
                        // Back to the statement before (a call returning nothing ends one); number constants count as references.
                        for (int k = j - 2; k >= Math.max(0, j - 32); k--) {
                            Ref r = y.refs[k];
                            if (r.kind() == 'm' && r.desc() != null && r.desc().endsWith(")V")) {
                                break;
                            }
                            // The reference must make the added parameter's functional interface, not a later argument's.
                            if (r.kind() == 'k' && String.valueOf(r.owner()).equals("indy:" + sam)) {
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

        /** The name of the one abstract method of a functional interface (b's own or the JDK's), or null. */
        String samName(String type) {
            if (!type.startsWith("L")) {
                return null;
            }
            String internal = type.substring(1, type.length() - 1);
            Set<String> names = new HashSet<>();
            Cls c = b.classes.get(internal);
            if (c != null) {
                for (Meth m : c.methods.values()) {
                    if ((m.flags & 0x0400) != 0) {
                        names.add(m.name);
                    }
                }
            } else if (internal.startsWith("java/")) {
                try {
                    for (java.lang.reflect.Method m : Class.forName(internal.replace('/', '.'), false, null).getMethods()) {
                        if (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) && !Set.of("equals", "hashCode", "toString").contains(m.getName())) {
                            names.add(m.getName());
                        }
                    }
                } catch (ClassNotFoundException | LinkageError absent) {
                    return null;
                }
            }
            return names.size() == 1 ? names.iterator().next() : null;
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
    // ------------------------------------------------------------------ replacements learned from the game's own code

    /**
     * One value or statement of a method body, rebuilt from its bytecode. op: p a parameter (value: its slot), l a local
     * nothing was stored to, k a constant, n null, g a static field, f an instance field, s a static call, v an instance
     * call, w new (ref: the constructor), u an object not constructed yet, t a checkcast, x a primitive conversion, y a
     * lambda or method reference (value: the interface method), o any other operation (value: its name), S a statement
     * (value: its kind). type is the value's descriptor, in the snapshot's names.
     */
    static final class Node {

        final char op;
        final Ref ref;
        final Object value;
        final String type;
        final Node[] kids;
        String body; // a lambda of the method's own class: its raw name + desc
        int at = -1; // the instruction (by its number in the method) that made this call, field read or statement
        int slot = -1; // a store statement's local

        Node(char op, Ref ref, Object value, String type, Node... kids) {
            this.op = op;
            this.ref = ref;
            this.value = value;
            this.type = type;
            this.kids = kids;
        }
    }

    /** A method body as statements, a local's value written into where it is read; and its own lambda bodies, in order. */
    static final class Body {

        final List<Node> statements = new ArrayList<>();
        final List<String> lambdas = new ArrayList<>(); // raw name + desc in the same class
        boolean whole; // every instruction was read
    }

    /** Stack machine over one method's bytecode: builds each value as a tree of what it is computed from. */
    static final class Decoder {

        final Origin o;
        final String self;
        final Body body = new Body();
        final Map<Integer, Node> locals = new HashMap<>();
        final Map<Label, State> saved = new HashMap<>();
        List<Node> stack = new ArrayList<>();
        boolean live = true;
        int index = -1; // the instruction being read, counted from 0
        final Set<Integer> starts = new HashSet<>(); // instructions a jump or a handler reaches
        final Set<Integer> jumps = new HashSet<>(); // instructions that leave the straight line: jumps, switches, returns, throws

        Decoder(Origin o, String self) {
            this.o = o;
            this.self = self;
        }

        static String kind(TypeKind k) {
            return k.upperBound().descriptorString();
        }

        static boolean wide(Node n) {
            return n.type.equals("J") || n.type.equals("D");
        }

        Node pop() {
            if (stack.isEmpty()) {
                throw new IllegalStateException("stack underflow");
            }
            return stack.remove(stack.size() - 1);
        }

        Node[] pop(int n) {
            Node[] out = new Node[n];
            for (int i = n - 1; i >= 0; i--) {
                out[i] = pop();
            }
            return out;
        }

        void push(Node n) {
            if (n.at < 0 && "svwgf".indexOf(n.op) >= 0) {
                n.at = index;
            }
            stack.add(n);
        }

        Node statement(String kind, Ref ref, Node... kids) {
            Node s = new Node('S', ref, kind, "V", kids);
            s.at = index;
            body.statements.add(s);
            return s;
        }

        /** The operand stack and locals a jump carries to its target. */
        record State(List<Node> stack, Map<Integer, Node> locals) {}

        void save(Label l) {
            State have = saved.get(l);
            saved.put(l, have == null ? new State(new ArrayList<>(stack), new HashMap<>(locals)) : new State(merge(have.stack(), stack), merge(have.locals(), locals)));
        }

        /** A value that depends on which way control came: it stands for neither, and Learner never matches it. */
        static Node phi(String type) {
            return new Node('o', null, "phi", type);
        }

        static boolean phi(Node n) {
            return n.op == 'o' && "phi".equals(n.value);
        }

        static List<Node> merge(List<Node> x, List<Node> y) {
            if (x.size() != y.size()) {
                throw new IllegalStateException("stack heights differ");
            }
            List<Node> out = new ArrayList<>();
            for (int i = 0; i < x.size(); i++) {
                out.add(x.get(i) == y.get(i) ? x.get(i) : phi(x.get(i).type));
            }
            return out;
        }

        static Map<Integer, Node> merge(Map<Integer, Node> x, Map<Integer, Node> y) {
            Map<Integer, Node> out = new HashMap<>();
            Set<Integer> slots = new HashSet<>(x.keySet());
            slots.addAll(y.keySet());
            for (int s : slots) {
                Node a = x.get(s), b = y.get(s);
                out.put(s, a == b ? a : phi(a != null ? a.type : b.type));
            }
            return out;
        }

        Ref member(char kind, String owner, String name, String desc) {
            return resolve(new RawRef(kind, owner, name, desc), o.raw(), o.fallback(), o.names(), o.resolved());
        }

        Ref cls(String raw) {
            return new Ref('c', o.names().cls(raw), null, null);
        }

        String desc(String raw) {
            return o.names().desc(raw);
        }

        Body run(MethodModel mm) {
            CodeModel code = mm.code().orElse(null);
            if (code == null) {
                return null;
            }
            int slot = 0;
            if ((mm.flags().flagsMask() & ACC_STATIC) == 0) {
                locals.put(slot++, new Node('p', null, 0, "L" + o.names().cls(self) + ";"));
            }
            for (ClassDesc p : mm.methodTypeSymbol().parameterList()) {
                locals.put(slot, new Node('p', null, slot, desc(p.descriptorString())));
                slot += p.descriptorString().equals("J") || p.descriptorString().equals("D") ? 2 : 1;
            }
            Set<Label> handlers = new HashSet<>();
            Set<Label> targets = new HashSet<>(); // where control can arrive other than by falling in: a block starts there
            for (ExceptionCatch c : code.exceptionHandlers()) {
                handlers.add(c.handler());
                targets.addAll(List.of(c.handler(), c.tryStart(), c.tryEnd()));
            }
            // Loop heads (targets of a jump back) and every local the method stores to: at a loop head or a handler
            // those locals may hold any value they get later, so they are merged values there.
            Set<Label> seen = new HashSet<>(), loops = new HashSet<>();
            Set<Integer> stored = new HashSet<>();
            for (CodeElement el : code) {
                switch (el) {
                    case LabelTarget lt -> seen.add(lt.label());
                    case BranchInstruction bi -> {
                        targets.add(bi.target());
                        if (seen.contains(bi.target())) {
                            loops.add(bi.target());
                        }
                    }
                    case LookupSwitchInstruction ls -> {
                        targets.add(ls.defaultTarget());
                        ls.cases().forEach(c -> targets.add(c.target()));
                        ls.cases().stream().map(SwitchCase::target).filter(seen::contains).forEach(loops::add);
                    }
                    case TableSwitchInstruction ts -> {
                        targets.add(ts.defaultTarget());
                        ts.cases().forEach(c -> targets.add(c.target()));
                        ts.cases().stream().map(SwitchCase::target).filter(seen::contains).forEach(loops::add);
                    }
                    case StoreInstruction si -> stored.add(si.slot());
                    case IncrementInstruction ii -> stored.add(ii.slot());
                    default -> {
                    }
                }
            }
            boolean arrive = false;
            try {
                for (CodeElement el : code) {
                    if (el instanceof LabelTarget lt) {
                        arrive |= targets.contains(lt.label());
                        State s = saved.remove(lt.label());
                        if (handlers.contains(lt.label())) {
                            stack = new ArrayList<>(List.of(phi("Ljava/lang/Throwable;")));
                        } else if (!live) {
                            stack = s == null ? new ArrayList<>() : new ArrayList<>(s.stack());
                            if (s != null) {
                                locals.clear();
                                locals.putAll(s.locals());
                            }
                        } else if (s != null) {
                            stack = merge(stack, s.stack());
                            Map<Integer, Node> joined = merge(locals, s.locals());
                            locals.clear();
                            locals.putAll(joined);
                        }
                        if (handlers.contains(lt.label()) || loops.contains(lt.label())) {
                            for (int s2 : stored) {
                                Node had = locals.get(s2);
                                locals.put(s2, phi(had == null ? "Ljava/lang/Object;" : had.type));
                            }
                        }
                        live = true;
                    } else if (el instanceof Instruction ins) {
                        index++;
                        if (arrive) {
                            starts.add(index);
                            arrive = false;
                        }
                        if (ins instanceof BranchInstruction || ins instanceof LookupSwitchInstruction || ins instanceof TableSwitchInstruction
                            || ins instanceof ReturnInstruction || ins instanceof ThrowInstruction) {
                            jumps.add(index);
                        }
                        if (live) {
                            step(ins);
                        }
                    }
                }
            } catch (IllegalStateException | IllegalArgumentException unreadable) {
                return body.statements.isEmpty() ? null : body;
            }
            body.whole = true;
            return body;
        }

        void step(Instruction ins) {
            switch (ins) {
                case LoadInstruction li -> {
                    Node v = locals.get(li.slot());
                    push(v != null ? v : new Node('l', null, li.slot(), kind(li.typeKind())));
                }
                case StoreInstruction si -> {
                    Node v = pop();
                    locals.put(si.slot(), v);
                    statement("store", null, v).slot = si.slot();
                }
                case IncrementInstruction ii -> locals.put(ii.slot(), new Node('o', null, "iinc", "I"));
                case ConstantInstruction ci -> {
                    ConstantDesc v = ci.constantValue();
                    if (ci.opcode() == Opcode.ACONST_NULL) {
                        push(new Node('n', null, null, "null"));
                    } else if (v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof Double) {
                        push(new Node('k', null, v, kind(ci.typeKind())));
                    } else if (v instanceof String s) {
                        push(new Node('k', null, s, "Ljava/lang/String;"));
                    } else if (v instanceof ClassDesc cd) {
                        push(new Node('o', cls(internal(cd)), "class", "Ljava/lang/Class;"));
                    } else {
                        push(new Node('o', null, "ldc", kind(ci.typeKind())));
                    }
                }
                case StackInstruction si -> stack(si.opcode());
                case OperatorInstruction oi -> {
                    String name = oi.opcode().name();
                    int n = name.endsWith("NEG") || name.equals("ARRAYLENGTH") ? 1 : 2;
                    boolean compare = name.equals("ARRAYLENGTH") || name.endsWith("CMP") || name.endsWith("CMPL") || name.endsWith("CMPG");
                    push(new Node('o', null, name, compare ? "I" : kind(oi.typeKind()), pop(n)));
                }
                case ConvertInstruction ci -> push(new Node('x', null, null, kind(ci.toType()), pop()));
                case FieldInstruction fi -> {
                    Ref r = member('f', fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue());
                    switch (fi.opcode()) {
                        case GETSTATIC -> push(new Node('g', r, null, r.desc()));
                        case GETFIELD -> push(new Node('f', r, null, r.desc(), pop()));
                        case PUTFIELD -> statement("put", r, pop(2));
                        default -> statement("put", r, pop());
                    }
                }
                case InvokeInstruction ii -> invoke(ii);
                case InvokeDynamicInstruction di -> {
                    Node[] args = pop(di.typeSymbol().parameterCount());
                    String type = desc(di.typeSymbol().returnType().descriptorString());
                    if (di.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")
                        && di.bootstrapArgs().size() > 1 && di.bootstrapArgs().get(1) instanceof DirectMethodHandleDesc h) {
                        String owner = internal(h.owner());
                        if (owner.equals(self) && h.methodName().startsWith("lambda$")) {
                            body.lambdas.add(h.methodName() + h.lookupDescriptor());
                            Node lambda = new Node('y', null, di.name().stringValue(), type, args);
                            lambda.body = h.methodName() + h.lookupDescriptor();
                            push(lambda);
                        } else {
                            push(new Node('y', member('m', owner, h.methodName(), h.lookupDescriptor()), di.name().stringValue(), type, args));
                        }
                    } else {
                        push(new Node('o', null, "indy:" + di.bootstrapMethod().methodName(), type, args));
                    }
                }
                case NewObjectInstruction ni -> push(new Node('u', cls(ni.className().asInternalName()), null, "L" + o.names().cls(ni.className().asInternalName()) + ";"));
                case NewPrimitiveArrayInstruction na -> push(new Node('o', null, "newarray", "[" + kind(na.typeKind()), pop()));
                case NewReferenceArrayInstruction na -> push(new Node('o', cls(na.componentType().asInternalName()), "anewarray", "[Ljava/lang/Object;", pop()));
                case NewMultiArrayInstruction na -> push(new Node('o', cls(na.arrayType().asInternalName()), "multianewarray", desc(na.arrayType().asInternalName()), pop(na.dimensions())));
                case ArrayLoadInstruction al -> push(new Node('o', null, al.opcode().name(), kind(al.typeKind()), pop(2)));
                case ArrayStoreInstruction as -> statement("astore", null, pop(3));
                case TypeCheckInstruction tc -> {
                    Ref c = cls(tc.type().asInternalName());
                    String t = c.owner().startsWith("[") ? c.owner() : "L" + c.owner() + ";";
                    push(tc.opcode() == Opcode.CHECKCAST ? new Node('t', c, null, t, pop()) : new Node('o', c, "instanceof", "I", pop()));
                }
                case BranchInstruction bi -> {
                    String name = bi.opcode().name();
                    if (name.startsWith("GOTO")) {
                        save(bi.target());
                        live = false;
                        return;
                    }
                    int n = name.startsWith("IF_") ? 2 : 1;
                    statement("if:" + name, null, pop(n));
                    save(bi.target());
                }
                case LookupSwitchInstruction ls -> {
                    statement("switch", null, pop());
                    save(ls.defaultTarget());
                    ls.cases().forEach(c -> save(c.target()));
                    live = false;
                }
                case TableSwitchInstruction ts -> {
                    statement("switch", null, pop());
                    save(ts.defaultTarget());
                    ts.cases().forEach(c -> save(c.target()));
                    live = false;
                }
                case ReturnInstruction ri -> {
                    if (ri.typeKind() == TypeKind.VOID) {
                        statement("return", null);
                    } else {
                        statement("return", null, pop());
                    }
                    live = false;
                }
                case ThrowInstruction ti -> {
                    statement("throw", null, pop());
                    live = false;
                }
                case MonitorInstruction mi -> statement("monitor", null, pop());
                case NopInstruction nop -> {
                }
                default -> throw new IllegalStateException("not read: " + ins.opcode());
            }
        }

        void invoke(InvokeInstruction ii) {
            MethodTypeDesc t = ii.typeSymbol();
            String name = ii.name().stringValue();
            Ref r = member('m', ii.owner().asInternalName(), name, ii.type().stringValue());
            Node[] args = pop(t.parameterCount());
            String ret = desc(t.returnType().descriptorString());
            if (ii.opcode() == Opcode.INVOKESTATIC) {
                result(new Node('s', r, null, ret, args));
                return;
            }
            Node receiver = pop();
            if (name.equals("<init>") && receiver.op == 'u') {
                Node made = new Node('w', r, null, receiver.type, args);
                made.at = index;
                stack.replaceAll(n -> n == receiver ? made : n);
                locals.replaceAll((k, n) -> n == receiver ? made : n);
                return;
            }
            Node[] all = new Node[args.length + 1];
            all[0] = receiver;
            System.arraycopy(args, 0, all, 1, args.length);
            if (name.equals("<init>")) {
                statement("init", r, all);
                return;
            }
            result(new Node('v', r, ii.opcode() == Opcode.INVOKESPECIAL ? "special" : null, ret, all));
        }

        void result(Node call) {
            call.at = index;
            if (call.type.equals("V")) {
                statement("expr", null, call);
            } else {
                push(call);
            }
        }

        void stack(Opcode op) {
            switch (op) {
                case POP -> discard(pop());
                case POP2 -> {
                    Node v = pop();
                    discard(v);
                    if (!wide(v)) {
                        discard(pop());
                    }
                }
                case DUP -> {
                    Node v = pop();
                    push(v);
                    push(v);
                }
                case DUP_X1 -> {
                    Node v1 = pop(), v2 = pop();
                    push(v1);
                    push(v2);
                    push(v1);
                }
                case DUP_X2 -> {
                    Node v1 = pop(), v2 = pop();
                    if (wide(v2)) {
                        push(v1);
                        push(v2);
                        push(v1);
                    } else {
                        Node v3 = pop();
                        push(v1);
                        push(v3);
                        push(v2);
                        push(v1);
                    }
                }
                case DUP2 -> {
                    Node v1 = pop();
                    if (wide(v1)) {
                        push(v1);
                        push(v1);
                    } else {
                        Node v2 = pop();
                        push(v2);
                        push(v1);
                        push(v2);
                        push(v1);
                    }
                }
                case DUP2_X1 -> {
                    Node v1 = pop();
                    if (wide(v1)) {
                        Node v2 = pop();
                        push(v1);
                        push(v2);
                        push(v1);
                    } else {
                        Node v2 = pop(), v3 = pop();
                        push(v2);
                        push(v1);
                        push(v3);
                        push(v2);
                        push(v1);
                    }
                }
                case DUP2_X2 -> throw new IllegalStateException("dup2_x2");
                case SWAP -> {
                    Node v1 = pop(), v2 = pop();
                    push(v1);
                    push(v2);
                }
                default -> throw new IllegalStateException("stack op " + op);
            }
        }

        /** A value computed and dropped: a call made for what it does is a statement. */
        void discard(Node v) {
            if (v.op == 's' || v.op == 'v' || v.op == 'w') {
                statement("expr", null, v);
            }
        }
    }

    /**
     * Replacements for changed or removed game members, learned from how the game ported its own code. In every pair of
     * linked methods where a uses such a member, both bodies are rebuilt as statements of value trees (Decoder) and
     * lined up statement by statement through the links. Where a statement of a differs from its counterpart in b only
     * at a use of the member, what b computes there is the replacement; written over the member's own operands (its
     * receiver $0 and arguments $1..), it is kept as a Java expression (FixExpr, in b's names) when it reads nothing
     * else of the method: only operands, constants, static fields, and calls, fields, constructors, casts and static
     * method references over those. Every use that lines up is a vote, and the game's own uses must agree.
     */
    static final class Learner {

        /** A replacement for old (a member of a); linked: the member has a counterpart whose signature changed. */
        record Learned(String owner, String name, String desc, boolean field, boolean linked, String expr, int votes, int of) {

            /** Its line in the route map's "learned" list, which JarPort reads. */
            String json(String step) {
                return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + VersionMap.Diff.q(owner) + ", \"from\": " + VersionMap.Diff.q(name) +
                    ", \"desc\": " + VersionMap.Diff.q(desc) + ", \"field\": " + field + ", \"linked\": " + linked + ", \"expr\": " + VersionMap.Diff.q(expr) +
                    ", \"votes\": " + votes + ", \"of\": " + of + " }";
            }
        }

        final Hop h;
        final Map<String, ClassModel> models = new HashMap<>();
        final Map<Node, Long> hashA = new IdentityHashMap<>(), hashB = new IdentityHashMap<>();
        final Map<Ref, Boolean> brokenRefs = new HashMap<>();
        final Map<Node, Boolean> holed = new IdentityHashMap<>();
        final Map<String, Map<Node, Set<String>>> sites = new HashMap<>(); // member key -> use in a -> its readings in b
        final Map<Node, String> aside = new IdentityHashMap<>(); // an unread use -> the call or field b makes there on a value of its own method
        final Map<String, Ref> keys = new HashMap<>();
        int budget;

        Learner(Hop h) {
            this.h = h;
        }

        List<Learned> learn() {
            for (var e : h.mAB.entrySet()) {
                Meth x = e.getKey(), y = e.getValue();
                if (!x.hasCode || !y.hasCode) {
                    continue;
                }
                boolean uses = false;
                for (Ref r : x.refs) {
                    if (broken(r)) {
                        uses = true;
                        String key = r.owner() + "." + r.name() + (r.kind() == 'm' ? r.desc() : "");
                        keys.putIfAbsent(key, r);
                        sites.computeIfAbsent(key, k -> new IdentityHashMap<>());
                    }
                }
                if (uses) {
                    Body bx = decode(h.a, x.owner.name, x.name + x.desc, false);
                    Body by = decode(h.b, y.owner.name, y.name + y.desc, false);
                    pair(x.owner.name, bx, y.owner.name, by, 0);
                }
            }
            List<Node[]> again2 = later;
            later = null;
            for (Node[] d : again2) {
                read(d[0], d[1]);
            }
            // With the class map settled, the uses whose new value had a type only it explains are read again.
            typeMap();
            List<Node[]> again = deferred;
            deferred = null;
            for (Node[] d : again) {
                read(d[0], d[1]);
            }
            List<Learned> out = new ArrayList<>();
            StringBuilder log = LEARN_LOG.isEmpty() ? null : new StringBuilder();
            for (var e : sites.entrySet()) {
                Ref old = keys.get(e.getKey());
                int[] tally = new int[2];
                Map<Node, Set<String>> uses = e.getValue();
                String best = vote(uses.values(), tally);
                String bestNow = best;
                java.util.function.Predicate<Node> same = u -> bestNow != null && aside.containsKey(u) && bestNow.endsWith("." + aside.get(u));
                if (best != null && uses.keySet().stream().anyMatch(u -> same.test(u) && e.getValue().get(u).isEmpty())) {
                    // A use b wrote as the same call on a value only its own method has (1.21.9's keyPressed(KeyEvent) asks
                    // event.hasShiftDown() where Screen.hasShiftDown() was; other places ask Minecraft.getInstance()): a
                    // mod's call has no such value, but the use says what is called: it counts for the reading.
                    Map<Node, Set<String>> kept = new IdentityHashMap<>(e.getValue());
                    kept.keySet().removeIf(u -> same.test(u) && kept.get(u).isEmpty());
                    String narrowed = vote(kept.values(), tally);
                    if (best.equals(narrowed)) {
                        // Those uses agree on what is called and differ only in on what: each is a vote for the reading.
                        int also = uses.size() - kept.size();
                        tally[0] += also;
                        tally[1] += also;
                        uses = kept;
                    } else {
                        best = vote(uses.values(), tally);
                    }
                } else if (uses.keySet().stream().anyMatch(u -> aside.containsKey(u) && e.getValue().get(u).isEmpty())) {
                    // The plain vote picked a reading no such use backs (one place lined up with something else): the
                    // reading the most of them back, as written in the places that could be read, if it has no choices.
                    String pick = null;
                    long most = 0;
                    boolean tie = false;
                    for (String r : uses.values().stream().flatMap(Set::stream).filter(r -> r.indexOf(OPEN) < 0).distinct().toList()) {
                        long backs = uses.keySet().stream().filter(u -> aside.containsKey(u) && e.getValue().get(u).isEmpty() && r.endsWith("." + aside.get(u))).count();
                        if (backs > most) {
                            pick = r;
                            most = backs;
                            tie = false;
                        } else if (backs == most && backs > 0) {
                            tie = true;
                        }
                    }
                    if (pick != null && !tie) {
                        String r = pick;
                        long read = uses.values().stream().filter(v -> v.contains(r)).count();
                        best = pick;
                        tally[0] = (int) (read + most);
                        tally[1] = uses.size();
                        Map<Node, Set<String>> kept = new IdentityHashMap<>(uses);
                        kept.keySet().removeIf(u -> aside.containsKey(u) && kept.get(u).isEmpty() && r.endsWith("." + aside.get(u)));
                        uses = kept;
                    }
                }
                int top = tally[0], of = tally[1];
                boolean agreed = top >= 2 && top * 10 >= of * 8 || top == 1 && of == 1 && usesAll(best, old, operands(old));
                agreed = agreed && beyondLiterals(best, uses);
                if (!agreed && best != null) {
                    // The game wrote different things by what it passed (1.21's new ResourceLocation(s): withDefaultNamespace
                    // where s was a path it could see, parse where s came from data). A mod's argument is a value no one
                    // can see in advance, so the reading for those is the general one: the uses whose every argument is
                    // a call's or a field's result vote alone, and must agree as any vote must and keep every operand.
                    Map<Node, Set<String>> opaque = new IdentityHashMap<>();
                    uses.forEach((u, r) -> {
                        if (opaque(u)) {
                            // Only readings that pass a value from the operands everywhere they choose one: a fixed value
                            // there (bySeparator(s, ':')) is how one place was written, not what any value needs.
                            Set<String> keep = new LinkedHashSet<>();
                            for (String one : r) {
                                Matcher c = CHOICE.matcher(one);
                                boolean fixed = false;
                                while (c.find()) {
                                    fixed |= !c.group(1).contains("$");
                                }
                                if (!fixed) {
                                    keep.add(one);
                                }
                            }
                            opaque.put(u, keep);
                        }
                    });
                    int[] general = new int[2];
                    String g = opaque.size() >= 2 && opaque.size() < uses.size() ? vote(opaque.values(), general) : null;
                    if (g != null && general[0] >= 2 && general[0] * 10 >= general[1] * 8 && usesAll(g, old, operands(old))) {
                        best = g;
                        top = general[0];
                        of = general[1];
                        agreed = true;
                    }
                }
                if (log != null) {
                    log.append(e.getKey()).append('\t').append(e.getValue().size()).append('\t').append(of).append('\t').append(top).append('\t')
                        .append(agreed).append('\t').append(best == null ? "" : best).append('\t');
                    e.getValue().values().stream().flatMap(Set::stream).distinct().limit(4).forEach(r -> log.append(r.replace(OPEN, '{').replace(OR, '|').replace(CLOSE, '}')).append(" ;; "));
                    log.append('\n');
                }
                if (best == null || !agreed) {
                    continue;
                }
                boolean field = old.kind() == 'f';
                // A library member is not one of the game's gone members: it is listed as linked, so it is not counted as one.
                boolean linked = !h.a.classes.containsKey(old.owner())
                    || (field ? h.fAB.containsKey(h.a.field(old.owner(), old.name())) : h.mAB.containsKey(h.a.method(old.owner(), old.name(), old.desc())));
                out.add(new Learned(old.owner().replace('/', '.'), old.name(), field ? "" : old.desc(), field, linked, best, top, of));
            }
            voteChains(log);
            // Y of a late chain is not dropped on its own: its value went into X's new call, so alone it says nothing.
            out.removeIf(l -> chains.stream().anyMatch(c -> c.at() == -2 && c.y().owner().replace('/', '.').equals(l.owner()) && c.y().name().equals(l.name())
                && c.y().desc().equals(l.desc())));
            out.addAll(sameMeaning(out, log));
            out.addAll(throughAccessor(out));
            out.addAll(throughClassMap(out));
            out.addAll(renamedFamily(out));
            // A chain both of whose members are learned alone is already ported member by member.
            Set<String> alone = new HashSet<>();
            for (Learned l : out) {
                alone.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            }
            chains.removeIf(c -> alone.contains(c.x().owner() + "." + c.x().name() + (c.x().kind() == 'f' ? "" : c.x().desc()))
                && alone.contains(c.y().owner() + "." + c.y().name() + c.y().desc()));
            out.addAll(accessors(out));
            out.addAll(overrides(out));
            out.sort(Comparator.comparing(Learned::owner).thenComparing(Learned::name).thenComparing(Learned::desc));
            if (log != null) {
                try {
                    Files.writeString(Path.of(LEARN_LOG + "-" + h.a.version + "-" + h.b.version + ".tsv"), log, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return out;
        }

        /**
         * A call names the class it was made on, which may override the method the game's code was read for (a mod calls
         * clientLevel.getRecipeManager(), the game level.getRecipeManager()). An override that changed the same way and
         * has no reading of its own takes its nearest supertype's: the call dispatches the same way in both releases.
         */
        List<Learned> overrides(List<Learned> found) {
            Map<String, Learned> byKey = new HashMap<>();
            for (Learned l : found) {
                if (!l.field()) {
                    byKey.put(l.owner().replace('.', '/') + "." + l.name() + l.desc(), l);
                }
            }
            List<Learned> out = new ArrayList<>();
            for (Cls c : h.a.classes.values()) {
                for (Meth m : c.methods.values()) {
                    String key = c.name + "." + m.name + m.desc;
                    // An override the game's own code shows something else for (its uses lined up, read or not) keeps
                    // that evidence: it was rejected, so it is left unresolved rather than given its supertype's reading.
                    if (m.isStatic() || m.special() || byKey.containsKey(key) || !sites.getOrDefault(key, Map.of()).isEmpty()
                        || !broken(new Ref('m', c.name, m.name, m.desc))) {
                        continue;
                    }
                    Learned up = null;
                    Meth upMethod = null;
                    Deque<String> todo = new ArrayDeque<>(supertypes(c));
                    Set<String> seen = new HashSet<>();
                    while (up == null && !todo.isEmpty()) {
                        String s = todo.poll();
                        Cls sc = h.a.classes.get(s);
                        if (sc == null || !seen.add(s)) {
                            continue;
                        }
                        up = byKey.get(s + "." + m.name + m.desc);
                        upMethod = sc.methods.get(m.name + m.desc);
                        todo.addAll(supertypes(sc));
                    }
                    if (up != null && stillOverrides(m, upMethod)) {
                        out.add(new Learned(c.name.replace('/', '.'), m.name, m.desc, false, h.mAB.containsKey(m), up.expr(), up.votes(), up.of()));
                    }
                }
            }
            return out;
        }

        /**
         * A member of a class the class map replaced, where the game's uses showed nothing: the one public member of the
         * new class (or its supertypes, the libraries' too) with the same name, staticness and number of parameters that
         * javac would pick for the old arguments, mapped: each old parameter fits the new one, and the result fits the old
         * one unless the old member gave nothing. 1.19.3: Vector3f.set(FFF)V is org.joml.Vector3f.set(FFF), which returns
         * the vector; Quaternion.mul(Quaternion) is Quaternionf.mul(Quaternionfc).
         */
        /**
         * Static methods a class made instance ones, reached through the accessor the game's own code uses for the class's
         * other such methods. NeoForge 1.21.9 made FMLLoader's statics instance methods of FMLLoader.getCurrent(), and its
         * own code calls getCurrent().getDist(), getCurrent().isProduction(), ... where it called FMLLoader.getDist(): a
         * changed static it never calls (getBindings()) is FMLLoader.getCurrent().getBindings() too. At least two methods
         * show the same accessor (a public static method of the class, no arguments, giving the class) and none another.
         */
        List<Learned> throughAccessor(List<Learned> found) {
            Map<String, Set<String>> accessors = new HashMap<>(); // owner (a, internal) -> accessors its learned lines use
            Map<String, Integer> shown = new HashMap<>();
            Set<String> have = new HashSet<>();
            for (Learned l : found) {
                String owner = l.owner().replace('.', '/');
                have.add(owner + "." + l.name() + l.desc());
                Meth x = l.field() ? null : h.a.method(owner, l.name(), l.desc());
                Meth y = x == null ? null : h.mAB.get(x);
                if (x == null || y == null || !x.isStatic() || y.isStatic()) {
                    continue;
                }
                StringBuilder args = new StringBuilder();
                for (int i = 1; i <= MethodTypeDesc.ofDescriptor(x.desc).parameterCount(); i++) {
                    args.append(i == 1 ? "" : ", ").append("$").append(i);
                }
                String prefix = dotted(y.owner.name) + ".";
                String tail = "()." + y.name + "(" + args + ")";
                if (l.expr().startsWith(prefix) && l.expr().endsWith(tail)) {
                    String accessor = l.expr().substring(prefix.length(), l.expr().length() - tail.length());
                    Meth acc = accessor.matches("[\\w$]+") ? h.b.method(y.owner.name, accessor, "()L" + y.owner.name + ";") : null;
                    if (acc != null && acc.isStatic() && (acc.flags & ACC_PUBLIC) != 0) {
                        accessors.computeIfAbsent(x.owner.name, k -> new HashSet<>()).add(accessor);
                        shown.merge(x.owner.name, 1, Integer::sum);
                        continue;
                    }
                }
                // Another static path (FMLEnvironment.isProduction()) says nothing about the accessor.
            }
            List<Learned> out = new ArrayList<>();
            for (var e : accessors.entrySet()) {
                if (e.getValue().size() != 1 || shown.getOrDefault(e.getKey(), 0) < 2) {
                    continue;
                }
                String accessor = e.getValue().iterator().next();
                Cls c = h.a.classes.get(e.getKey());
                for (Meth x : c == null ? List.<Meth>of() : c.methods.values()) {
                    Meth y = h.mAB.get(x);
                    if (!x.isStatic() || x.special() || y == null || y.isStatic() || (y.flags & ACC_PUBLIC) == 0 || !h.descBA(y.desc).equals(x.desc)
                        || have.contains(x.sig())) {
                        continue;
                    }
                    StringBuilder args = new StringBuilder();
                    for (int i = 1; i <= MethodTypeDesc.ofDescriptor(x.desc).parameterCount(); i++) {
                        args.append(i == 1 ? "" : ", ").append("$").append(i);
                    }
                    out.add(new Learned(dotted(x.owner.name), x.name, x.desc, false, true, dotted(y.owner.name) + "." + accessor + "()." + y.name + "(" + args + ")", 1, 1));
                }
            }
            return out;
        }

        List<Learned> throughClassMap(List<Learned> found) {
            Set<String> have = new HashSet<>();
            for (Learned l : found) {
                have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            }
            List<Learned> out = new ArrayList<>();
            for (var e : classRewrites().entrySet()) {
                Cls ca = e.getKey(), cb = e.getValue();
                if (h.cAB.containsKey(ca) || (ca.flags & ACC_PUBLIC) == 0) {
                    continue;
                }
                for (Meth m : ca.methods.values()) {
                    if ((m.flags & (ACC_PUBLIC | 0x0004)) == 0 || m.name.equals("<clinit>") || have.contains(ca.name + "." + m.name + m.desc)
                        || !broken(new Ref('m', ca.name, m.name, m.desc))) {
                        continue;
                    }
                    MethodTypeDesc old = MethodTypeDesc.ofDescriptor(m.desc);
                    List<Meth> picks = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    Deque<String> todo = new ArrayDeque<>(List.of(cb.name));
                    while (!todo.isEmpty()) {
                        String c = todo.poll();
                        Cls k = h.b.classes.getOrDefault(c, h.b.libraries.get(c));
                        if (k == null || !seen.add(c) || m.special() && !c.equals(cb.name)) {
                            continue;
                        }
                        for (Meth n : k.methods.values()) {
                            if (n.name.equals(m.name) && n.isStatic() == m.isStatic() && (n.flags & ACC_PUBLIC) != 0 && (n.flags & ACC_SYNTHETIC) == 0
                                && accepts(old, MethodTypeDesc.ofDescriptor(n.desc), m.special()) && picks.stream().noneMatch(p -> p.desc.equals(n.desc))) {
                                picks.add(n);
                            }
                        }
                        todo.addAll(supertypes(k));
                    }
                    if (picks.size() != 1) {
                        continue;
                    }
                    StringJoiner args = new StringJoiner(", ");
                    for (int i = 1; i <= old.parameterCount(); i++) {
                        args.add("$" + i);
                    }
                    String expr = m.special() ? "new " + dotted(cb.name) + "(" + args + ")"
                        : (m.isStatic() ? dotted(picks.get(0).owner.name) : "$0") + "." + m.name + "(" + args + ")";
                    out.add(new Learned(dotted(ca.name), m.name, m.desc, false, false, expr, 1, 1));
                }
            }
            return out;
        }

        /**
         * An overload the game removed while renaming the rest of its family: 1.21 renamed VertexConsumer.vertex(Matrix4f,
         * FFF) and vertex(Pose, FFF) to addVertex and dropped vertex(DDD), whose counterpart is addVertex(FFF). Every renamed
         * sibling must take the same new name; the one method of that name in the new class (or its supertypes) with the
         * same number of parameters takes the old arguments, each the same or a double where a float is wanted, narrowed as
         * javac would be told to, and its result serves where the old one did.
         */
        List<Learned> renamedFamily(List<Learned> found) {
            Set<String> have = new HashSet<>();
            for (Learned l : found) {
                have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            }
            List<Learned> out = new ArrayList<>();
            for (Cls ca : h.a.classes.values()) {
                Cls cb = h.cAB.get(ca);
                if (cb == null || (ca.flags & ACC_PUBLIC) == 0) {
                    continue;
                }
                for (Meth m : ca.methods.values()) {
                    if ((m.flags & (ACC_PUBLIC | 0x0004)) == 0 || m.special() || h.mAB.get(m) != null || have.contains(ca.name + "." + m.name + m.desc)) {
                        continue;
                    }
                    String renamed = null;
                    boolean one = true;
                    for (Meth s : ca.methods.values()) {
                        Meth t = s == m || !s.name.equals(m.name) ? null : h.mAB.get(s);
                        if (t != null && !t.name.equals(m.name)) {
                            one &= renamed == null || renamed.equals(t.name);
                            renamed = t.name;
                        }
                    }
                    if (renamed == null || !one) {
                        continue;
                    }
                    MethodTypeDesc old = MethodTypeDesc.ofDescriptor(m.desc);
                    List<Meth> picks = new ArrayList<>();
                    for (Cls k = cb; k != null; k = k.sup == null ? null : h.b.classes.get(k.sup)) {
                        for (Meth n : k.methods.values()) {
                            if (n.name.equals(renamed) && n.isStatic() == m.isStatic() && (n.flags & ACC_PUBLIC) != 0 && narrows(old, MethodTypeDesc.ofDescriptor(n.desc))
                                && picks.stream().noneMatch(p -> p.desc.equals(n.desc))) {
                                picks.add(n);
                            }
                        }
                    }
                    if (picks.size() != 1) {
                        continue;
                    }
                    MethodTypeDesc now = MethodTypeDesc.ofDescriptor(picks.get(0).desc);
                    StringJoiner args = new StringJoiner(", ");
                    for (int i = 0; i < old.parameterCount(); i++) {
                        boolean cast = old.parameterType(i).descriptorString().equals("D") && now.parameterType(i).descriptorString().equals("F");
                        args.add((cast ? "(float) " : "") + "$" + (i + 1));
                    }
                    String expr = (m.isStatic() ? dotted(picks.get(0).owner.name) : "$0") + "." + renamed + "(" + args + ")";
                    out.add(new Learned(dotted(ca.name), m.name, m.desc, false, false, expr, 1, 1));
                }
            }
            return out;
        }

        /** The new parameters take the old arguments, a double where a float is wanted allowed, and the new result serves. */
        boolean narrows(MethodTypeDesc old, MethodTypeDesc now) {
            if (old.parameterCount() != now.parameterCount()) {
                return false;
            }
            boolean narrowed = false;
            for (int i = 0; i < old.parameterCount(); i++) {
                String o = mapType(old.parameterType(i).descriptorString()), n = now.parameterType(i).descriptorString();
                if (o.equals("D") && n.equals("F")) {
                    narrowed = true;
                } else if (!assignableB(o, n)) {
                    return false;
                }
            }
            String had = old.returnType().descriptorString();
            return narrowed && (had.equals("V") || assignableB(now.returnType().descriptorString(), mapType(had)));
        }

        /** Old arguments, mapped, fit the new parameters one by one, and the new result serves where the old one did. */
        boolean accepts(MethodTypeDesc old, MethodTypeDesc now, boolean constructor) {
            if (old.parameterCount() != now.parameterCount()) {
                return false;
            }
            for (int i = 0; i < old.parameterCount(); i++) {
                if (!assignableB(mapType(old.parameterType(i).descriptorString()), now.parameterType(i).descriptorString())) {
                    return false;
                }
            }
            String had = old.returnType().descriptorString();
            return constructor || had.equals("V") || assignableB(now.returnType().descriptorString(), mapType(had));
        }

        /** An a descriptor in b's names, through the links and the class map. */
        String mapType(String desc) {
            return desc.startsWith("L") ? "L" + mappedName(desc).replace('.', '/') + ";" : desc;
        }

        /** A value of b type from serves where b type to is wanted: the same, or a subtype through b's classes and libraries. */
        boolean assignableB(String from, String to) {
            if (from.equals(to)) {
                return true;
            }
            if (!from.startsWith("L") || !to.startsWith("L")) {
                return false;
            }
            String want = to.substring(1, to.length() - 1);
            if (want.equals("java/lang/Object")) {
                return true;
            }
            Deque<String> todo = new ArrayDeque<>(List.of(from.substring(1, from.length() - 1)));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (c.equals(want)) {
                    return true;
                }
                Cls k = h.b.classes.getOrDefault(c, h.b.libraries.get(c));
                if (k != null && seen.add(c)) {
                    todo.addAll(supertypes(k));
                }
            }
            return false;
        }

        /**
         * The new release keeps sub overriding sup: both dropped, or both linked to methods of the same name and
         * descriptor where the one's class extends the other's. Anything else changed the two apart.
         */
        boolean stillOverrides(Meth sub, Meth sup) {
            Meth ys = h.mAB.get(sub), yp = sup == null ? null : h.mAB.get(sup);
            if (ys == null || yp == null) {
                return ys == null && yp == null;
            }
            return ys.name.equals(yp.name) && ys.desc.equals(yp.desc) && h.subtypeB(ys.owner.name, yp.owner.name);
        }

        /** -Dgamediff.learnlog=PREFIX: every member's uses lined up, uses read, votes and readings, one file per update. */
        static final String LEARN_LOG = System.getProperty("gamediff.learnlog", "");

        /**
         * A public method or constructor the game closed or dropped, read from what it computed: the public member of its
         * class in b that takes the same parameters and gives the same type, and whose body (with the game methods it
         * calls written in, three deep) does what the old body did. 1.21 made new ResourceLocation(ns, path) private: it
         * validated both parts and stored them, which is what fromNamespaceAndPath does once createUntrusted is written
         * in; withDefaultNamespace, which takes no namespace, and tryBuild, which checks without throwing, do less alike.
         * Taken only when it is clearly the closest (see SAME, MARGIN) and nothing was learned from the game's uses.
         */
        List<Learned> sameMeaning(List<Learned> found, StringBuilder log) {
            Set<String> have = new HashSet<>();
            for (Learned l : found) {
                have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            }
            List<Learned> out = new ArrayList<>();
            for (Cls ca : h.a.classes.values()) {
                Cls cb = h.cAB.get(ca);
                if (cb == null || (ca.flags & ACC_PUBLIC) == 0) {
                    continue;
                }
                for (Meth m : ca.methods.values()) {
                    if ((m.flags & (ACC_PUBLIC | 0x0004)) == 0 || m.name.equals("<clinit>") || !m.hasCode || have.contains(ca.name + "." + m.name + m.desc)
                        || !broken(new Ref('m', ca.name, m.name, m.desc))) {
                        continue;
                    }
                    String params = m.desc.substring(0, m.desc.indexOf(')') + 1);
                    String result = m.special() ? "L" + ca.name + ";" : m.desc.substring(m.desc.indexOf(')') + 1);
                    long[] before = meaning(m, true);
                    // A candidate is a public wrapper of the old member where b still has it (closed), taken as it is; or
                    // what the game's own new code wrote at a use of it, weighed by what its body does.
                    Meth alive = h.mAB.get(m);
                    int n = MethodTypeDesc.ofDescriptor(m.desc).parameterCount();
                    StringJoiner args = new StringJoiner(", ");
                    for (int i = 1; i <= n; i++) {
                        args.add("$" + i);
                    }
                    // What the game's uses wrote, choices settled every way they allow: only a plain call of a candidate
                    // with the old operands in order counts (not one inside a conversion, as getPixel inside ARGB's).
                    Set<String> written = new HashSet<>();
                    for (Set<String> readings : sites.getOrDefault(ca.name + "." + m.name + m.desc, Map.of()).values()) {
                        for (String r : readings) {
                            written.add(CHOICE.matcher(r).replaceAll(x -> Matcher.quoteReplacement(x.group(1).split(String.valueOf(OR))[0])));
                        }
                    }
                    Meth best = null;
                    double top = 0, second = 0;
                    for (Meth c : cb.methods.values()) {
                        boolean wantStatic = m.special() || m.isStatic();
                        if ((c.flags & ACC_PUBLIC) == 0 || c.special() || c.isStatic() != wantStatic || !c.hasCode
                            || !h.descBA(c.desc).equals(params + result)) {
                            continue;
                        }
                        String call = (c.isStatic() ? cb.name.replace('/', '.') : "$0") + "." + c.name + "(" + args + ")";
                        if (alive != null && wraps(c, alive)) {
                            // It is the closed member, called with the same arguments: nothing to weigh.
                            best = c;
                            top = 1;
                            second = 0;
                            break;
                        }
                        // Where b still has the old member, only a wrapper of it is the same thing; anything else calls
                        // it and changes the result (getPixel converts what getPixelABGR returns).
                        if (alive != null || !written.contains(call) || before.length < 3) {
                            continue;
                        }
                        // Most of what the old body did, and not much else: a caller that also does it (die for
                        // dropAllDeathLoot) covers the old body but is mostly something more.
                        long[] now = meaning(c, false);
                        double s = covered(now, before) < REST ? 0 : covered(before, now);
                        if (s > top) {
                            second = top;
                            top = s;
                            best = c;
                        } else if (s > second) {
                            second = s;
                        }
                    }
                    if (log != null && best != null) {
                        log.append("same meaning\t").append(ca.name).append('.').append(m.name).append(m.desc).append('\t').append(best.name)
                            .append(String.format(Locale.ROOT, "\t%.2f\t%.2f\t%.2f\n", top, second, covered(meaning(best, false), before)));
                    }
                    if (best == null || top < SAME || top - second < MARGIN) {
                        continue;
                    }
                    String expr = (best.isStatic() ? cb.name.replace('/', '.') : "$0") + "." + best.name + "(" + args + ")";
                    out.add(new Learned(ca.name.replace('/', '.'), m.name, m.desc, false, h.mAB.containsKey(m), expr, 1, 1));
                }
            }
            return out;
        }

        /**
         * c's body is only `return alive(args)` (private one-line helpers written in), each argument c's own parameter in
         * order, or passed through a check that returns it unchanged: fromNamespaceAndPath(ns, path) is createUntrusted,
         * which is new ResourceLocation(assertValidNamespace(ns, path), assertValidPath(ns, path)), and each assert
         * returns what it was given. getPixel, which converts what getPixelABGR returns, is not one.
         */
        boolean wraps(Meth c, Meth alive) {
            Body body = decode(h.b, c.owner.name, c.name + c.desc, false);
            if (body == null || body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
                return false;
            }
            Node e = body.statements.get(0).kids[0];
            for (int depth = 0; depth < 3 && e.op == 's' && !same(e.ref, alive); depth++) {
                Node inner = oneLine(e);
                if (inner == null) {
                    return false;
                }
                e = inner;
            }
            boolean call = (e.op == 'w' || e.op == 's' || e.op == 'v') && same(e.ref, alive);
            if (!call) {
                return false;
            }
            int[] slots = slots(c);
            int first = e.op == 'v' ? 1 : 0;
            if (e.op == 'v' && !(e.kids[0].op == 'p' && e.kids[0].value.equals(0))) {
                return false;
            }
            if (e.kids.length - first != slots.length) {
                return false;
            }
            for (int i = first; i < e.kids.length; i++) {
                Node arg = through(e.kids[i], 0);
                if (arg == null || arg.op != 'p' || !arg.value.equals(slots[i - first])) {
                    return false;
                }
            }
            return true;
        }

        boolean same(Ref r, Meth m) {
            return r != null && r.kind() == 'm' && r.owner().equals(m.owner.name) && r.name().equals(m.name) && r.desc().equals(m.desc);
        }

        /** The local slot of each parameter of m. */
        static int[] slots(Meth m) {
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            int[] out = new int[ps.size()];
            int slot = m.isStatic() ? 0 : 1;
            for (int i = 0; i < out.length; i++) {
                out[i] = slot;
                slot += ps.get(i).descriptorString().matches("[JD]") ? 2 : 1;
            }
            return out;
        }

        /** A static game call whose body is one `return expr`: expr with the call's arguments for its parameters. */
        Node oneLine(Node call) {
            Meth callee = h.b.method(call.ref.owner(), call.ref.name(), call.ref.desc());
            Body body = callee == null ? null : decode(h.b, callee.owner.name, callee.name + callee.desc, false);
            if (body == null || body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
                return null;
            }
            int[] slots = slots(callee);
            Map<Object, Node> bySlot = new HashMap<>();
            for (int i = 0; i < slots.length; i++) {
                bySlot.put(slots[i], call.kids[i]);
            }
            return substitute(body.statements.get(0).kids[0], bySlot);
        }

        static Node substitute(Node n, Map<Object, Node> bySlot) {
            if (n.op == 'p') {
                return bySlot.get(n.value);
            }
            Node[] kids = new Node[n.kids.length];
            for (int i = 0; i < kids.length; i++) {
                kids[i] = substitute(n.kids[i], bySlot);
                if (kids[i] == null) {
                    return null;
                }
            }
            return new Node(n.op, n.ref, n.value, n.type, kids);
        }

        /** A value passed through checks that return what they were given, to what it was before them; or null. */
        Node through(Node n, int depth) {
            if (n.op == 'p' || depth > 3) {
                return n.op == 'p' ? n : null;
            }
            if (n.op != 's') {
                return null;
            }
            Meth callee = h.b.method(n.ref.owner(), n.ref.name(), n.ref.desc());
            Body body = callee == null ? null : decode(h.b, callee.owner.name, callee.name + callee.desc, false);
            if (body == null) {
                return null;
            }
            int[] slots = slots(callee);
            Object given = null;
            for (Node s : body.statements) {
                if (!"return".equals(s.value)) {
                    continue;
                }
                if (s.kids.length != 1 || s.kids[0].op != 'p' || given != null && !given.equals(s.kids[0].value)) {
                    return null;
                }
                given = s.kids[0].value;
            }
            for (int i = 0; given != null && i < slots.length; i++) {
                if (given.equals(slots[i])) {
                    return through(n.kids[i], depth + 1);
                }
            }
            return null;
        }

        /**
         * How much of the old body the candidate must also do, how far ahead of the next candidate, and how much of the
         * candidate must be what the old body did.
         */
        static final double SAME = 0.8, MARGIN = 0.15, REST = 0.5;

        /**
         * A field b still has but closed, read or written through the accessor its class now has for it: a public method
         * whose whole body is `return this.field` (1.17's Player.getInventory() for inventory) or `this.field = value`
         * (1.19.3's AbstractWidget.setY for y). Exact, so no vote is needed. A write is learned as "name=" with $1 the
         * value written.
         */
        List<Learned> accessors(List<Learned> found) {
            Set<String> have = new HashSet<>();
            for (Learned l : found) {
                have.add(l.owner().replace('.', '/') + "." + l.name());
            }
            List<Learned> out = new ArrayList<>();
            for (var e : h.fAB.entrySet()) {
                Fld f = e.getKey(), y = e.getValue();
                Cls ca = h.a.classes.get(f.owner.name);
                if (!closed(f.flags, y.flags) || !h.descBA(y.desc).equals(f.desc) || ca == null || (ca.flags & ACC_PUBLIC) == 0) {
                    continue;
                }
                boolean isStatic = (f.flags & ACC_STATIC) != 0;
                String owner = y.owner.name.replace('/', '.');
                for (Meth c : y.owner.methods.values()) {
                    if ((c.flags & ACC_PUBLIC) == 0 || c.isStatic() != isStatic || c.special() || !c.hasCode) {
                        continue;
                    }
                    boolean getter = c.desc.equals("()" + y.desc), setter = c.desc.equals("(" + y.desc + ")V");
                    if (!getter && !setter) {
                        continue;
                    }
                    Body body = decode(h.b, y.owner.name, c.name + c.desc, false);
                    String call = (isStatic ? owner : "$0") + "." + c.name;
                    String read = f.owner.name + "." + f.name, write = read + "=";
                    if (getter && body != null && !have.contains(read) && reads(body, y, isStatic)) {
                        out.add(new Learned(f.owner.name.replace('/', '.'), f.name, "", true, true, call + "()", 1, 1));
                        have.add(read);
                    } else if (setter && body != null && !have.contains(write) && writes(body, y, isStatic)) {
                        out.add(new Learned(f.owner.name.replace('/', '.'), f.name + "=", "", true, true, call + "($1)", 1, 1));
                        have.add(write);
                    }
                }
            }
            return out;
        }

        static boolean reads(Body body, Fld y, boolean isStatic) {
            if (body.statements.size() != 1 || !"return".equals(body.statements.get(0).value) || body.statements.get(0).kids.length != 1) {
                return false;
            }
            Node v = body.statements.get(0).kids[0];
            return v.ref != null && v.ref.owner().equals(y.owner.name) && v.ref.name().equals(y.name)
                && (isStatic ? v.op == 'g' : v.op == 'f' && v.kids[0].op == 'p' && v.kids[0].value.equals(0));
        }

        static boolean writes(Body body, Fld y, boolean isStatic) {
            if (body.statements.size() != 2 || !"return".equals(body.statements.get(1).value) || body.statements.get(1).kids.length != 0) {
                return false;
            }
            Node put = body.statements.get(0);
            if (!"put".equals(put.value) || put.ref == null || !put.ref.owner().equals(y.owner.name) || !put.ref.name().equals(y.name)) {
                return false;
            }
            boolean receiver = isStatic || put.kids[0].op == 'p' && put.kids[0].value.equals(0);
            Node v = put.kids[put.kids.length - 1];
            return receiver && v.op == 'p' && v.value.equals(isStatic ? 0 : 1);
        }

        /** The share of what the old body did that the candidate also does (both sorted, distinct). */
        static double covered(long[] old, long[] now) {
            int i = 0, j = 0, both = 0;
            while (i < old.length && j < now.length) {
                if (old[i] == now[j]) {
                    both++;
                    i++;
                    j++;
                } else if (old[i] < now[j]) {
                    i++;
                } else {
                    j++;
                }
            }
            return old.length == 0 ? 0 : (double) both / old.length;
        }

        final Map<Meth, long[]> meanings = new HashMap<>();

        /**
         * The distinct things a body references, with the game methods it calls written in five deep, each in a's names: a
         * wrapper one level deeper (fromNamespaceAndPath calls createUntrusted, which does the work) reads the same.
         */
        long[] meaning(Meth m, boolean sideA) {
            long[] known = meanings.get(m);
            if (known != null) {
                return known;
            }
            Set<Long> out = new HashSet<>();
            gather(m, sideA, 0, new HashSet<>(), out);
            long[] t = out.stream().mapToLong(Long::longValue).sorted().toArray();
            meanings.put(m, t);
            return t;
        }

        final Map<Node, Boolean> mergedMemo = new IdentityHashMap<>();

        /** The tree holds a value that depends on the path taken. */
        boolean merged(Node n) {
            Boolean known = mergedMemo.get(n);
            if (known != null) {
                return known;
            }
            boolean out = Decoder.phi(n);
            for (int i = 0; i < n.kids.length && !out; i++) {
                out = merged(n.kids[i]);
            }
            mergedMemo.put(n, out);
            return out;
        }

        /** The argument a b method gives back as its result, by its generic signature (`<T> T m(T)`), or null. */
        Integer givesArgument(Ref r) {
            Meth mb = h.b.method(r.owner(), r.name(), r.desc());
            ClassModel cm = mb == null ? null : model(h.b, mb.owner.name);
            if (cm == null) {
                return null;
            }
            Origin o = h.b.origins.get(mb.owner.name);
            String self = cm.thisClass().asInternalName();
            for (MethodModel mm : cm.methods()) {
                String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
                if (!(o.names().method(self, rn, rd) + o.names().desc(rd)).equals(mb.name + mb.desc)) {
                    continue;
                }
                var sig = mm.findAttribute(Attributes.signature());
                if (sig.isEmpty()) {
                    return null;
                }
                MethodSignature ms = MethodSignature.parseFrom(sig.get().signature().stringValue());
                if (!(ms.result() instanceof Signature.TypeVarSig t)) {
                    return null;
                }
                Integer found = null;
                for (int i = 0; i < ms.arguments().size(); i++) {
                    if (ms.arguments().get(i) instanceof Signature.TypeVarSig a && a.identifier().equals(t.identifier())) {
                        if (found != null) {
                            return null;
                        }
                        found = i;
                    }
                }
                return found;
            }
            return null;
        }

        /** An a descriptor's class in b's names, dotted (through the links, or a class rewrite). */
        String mappedName(String desc) {
            String old = desc.substring(1, desc.length() - 1);
            Cls ca = h.a.classes.get(old);
            Cls cb = ca == null ? null : h.cAB.get(ca);
            if (cb == null && ca != null) {
                cb = classRewrites().get(ca);
            }
            return (cb != null ? cb.name : old).replace('/', '.');
        }

        void gather(Meth m, boolean sideA, int depth, Set<Meth> seen, Set<Long> out) {
            if (!seen.add(m)) {
                return;
            }
            Snap s = sideA ? h.a : h.b;
            for (Ref r : m.refs) {
                out.add(fnv(h.token(r, sideA)));
                if (r.kind() == 'm' && depth < 5) {
                    Meth callee = s.method(r.owner(), r.name(), r.desc());
                    if (callee != null && callee.hasCode) {
                        gather(callee, sideA, depth + 1, seen, out);
                    }
                }
            }
        }

        /**
         * A use that passed a literal shows the replacement only for that value: the game passed new ResourceLocation("stone")
         * and now writes withDefaultNamespace("stone"), which is wrong for a mod's "mymod:stone". So the reading must also
         * win among the uses whose operands were all computed, when there are two or more, and may not drop an operand the
         * game passed as a literal at every use it read.
         */
        static boolean beyondLiterals(String best, Map<Node, Set<String>> uses) {
            return beyondLiterals(best, uses, false);
        }

        static boolean beyondLiterals(String best, Map<Node, Set<String>> uses, boolean ownEdits) {
            List<Set<String>> computed = new ArrayList<>();
            Node any = null;
            for (var u : uses.entrySet()) {
                any = u.getKey();
                if (Arrays.stream(any.kids).noneMatch(k -> k.op == 'k')) {
                    computed.add(u.getValue());
                }
            }
            // A member with no operands passes no literal: there is nothing to recount.
            if (computed.size() >= 2 && any.kids.length > 0) {
                int[] tally = new int[2];
                String again = vote(computed, tally, ownEdits);
                if (!best.equals(again) || tally[0] * 10 < tally[1] * 8) {
                    return false;
                }
            }
            int first = any.op == 'v' || any.op == 'f' ? 0 : 1;
            for (int i = 0; i < any.kids.length; i++) {
                int k = i;
                boolean used = Pattern.compile("\\$" + (i + first) + "(?!\\d)").matcher(best).find();
                if (!used && uses.entrySet().stream().filter(u -> !u.getValue().isEmpty()).allMatch(u -> u.getKey().kids[k].op == 'k')) {
                    return false;
                }
            }
            return true;
        }

        /** A choice inside a reading: a constant b passes that an operand also held, or code two operands share. */
        static final char OPEN = '\u0001', OR = '\u0002', CLOSE = '\u0003';
        static final Pattern CHOICE = Pattern.compile(OPEN + "([^" + CLOSE + "]*)" + CLOSE);
        /** Every operand and constant is a choice, even of one option, so uses that pass a constant and uses that pass code share a shape. */
        static String choice(List<String> options) {
            return options.isEmpty() ? null : OPEN + String.join(String.valueOf(OR), options) + CLOSE;
        }

        /**
         * The reading most uses agree on. Uses are grouped by the reading's shape (choices left open); in the shape most
         * uses have, each choice takes the option most of them allow (an operand before a constant, then the next operand
         * in order), and a use agrees when it allows every option taken. tally: {agreeing uses, uses read}.
         */
        static String vote(Collection<Set<String>> uses, int[] tally) {
            return vote(uses, tally, false);
        }

        /**
         * With ownEdits, a use that differs from the reading only where it passes a fixed value (no operand) in place of an
         * operand the reading passes abstains: the game changed that argument in that one place of its own code.
         */
        static String vote(Collection<Set<String>> uses, int[] tally, boolean ownEdits) {
            Map<String, Integer> shapes = new HashMap<>();
            // Every use that lined up counts, read or not: a use the new code rewrote into something no expression over
            // the operands says (another parameter, a local) is evidence against every reading.
            int of = uses.size();
            for (Set<String> readings : uses) {
                if (!readings.isEmpty()) {
                    readings.stream().map(r -> CHOICE.matcher(r).replaceAll(OPEN + "" + CLOSE)).distinct().forEach(s -> shapes.merge(s, 1, Integer::sum));
                }
            }
            tally[1] = of;
            String shape = shapes.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(null);
            if (shape == null) {
                return null;
            }
            // For each use with this shape, the options it allows at each choice.
            List<List<Set<String>>> allowed = new ArrayList<>();
            for (Set<String> readings : uses) {
                List<Set<String>> per = null;
                for (String r : readings) {
                    if (!CHOICE.matcher(r).replaceAll(OPEN + "" + CLOSE).equals(shape)) {
                        continue;
                    }
                    Matcher m = CHOICE.matcher(r);
                    List<Set<String>> mine = new ArrayList<>();
                    while (m.find()) {
                        mine.add(new HashSet<>(List.of(m.group(1).split(String.valueOf(OR)))));
                    }
                    if (per == null) {
                        per = mine;
                    } else {
                        for (int i = 0; i < per.size(); i++) {
                            per.get(i).addAll(mine.get(i));
                        }
                    }
                }
                if (per != null) {
                    allowed.add(per);
                }
            }
            int choices = allowed.get(0).size();
            List<String> taken = new ArrayList<>();
            int lastOperand = -1;
            for (int i = 0; i < choices; i++) {
                Map<String, Integer> count = new HashMap<>();
                for (List<Set<String>> per : allowed) {
                    per.get(i).forEach(o -> count.merge(o, 1, Integer::sum));
                }
                int last = lastOperand;
                String pick = count.entrySet().stream().max(Comparator
                    .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparing(o -> o.getKey().contains("$"))
                    .thenComparing(o -> operandIndex(o.getKey()) > last)
                    .thenComparing(o -> -operandIndex(o.getKey()))).get().getKey();
                taken.add(pick);
                lastOperand = Math.max(lastOperand, operandIndex(pick));
            }
            // A fixed value most uses of this shape agree on, where the old member had no operand (1.21.5's getInt(key) is
            // getIntOr(key, 0) in most places): a use that passes another fixed value there chose its own default and
            // abstains, as the game's own edit of that one place.
            boolean[] settled = new boolean[choices];
            for (int i = 0; i < choices; i++) {
                int k = i;
                long with = allowed.stream().filter(per -> per.get(k).contains(taken.get(k))).count();
                settled[i] = !taken.get(i).contains("$") && with * 2 > allowed.size();
            }
            int agree = 0;
            for (List<Set<String>> per : allowed) {
                boolean all = true, own = true, chosen = true;
                for (int i = 0; i < choices; i++) {
                    if (!per.get(i).contains(taken.get(i))) {
                        all = false;
                        own &= taken.get(i).contains("$") && per.get(i).stream().noneMatch(o -> o.contains("$"));
                        chosen &= settled[i] && per.get(i).stream().noneMatch(o -> o.contains("$"));
                    }
                }
                agree += all ? 1 : 0;
                tally[1] -= !all && (own && ownEdits || chosen) ? 1 : 0;
            }
            tally[0] = agree;
            if (ownEdits) {
                // Uses of another shape that are this one with a fixed value (a constant or a static field, no operand) where
                // this one passes an operand: the game changed that argument itself there.
                StringBuilder rx = new StringBuilder();
                Matcher at = Pattern.compile(OPEN + "" + CLOSE).matcher(shape);
                int last = 0, k = 0;
                while (at.find()) {
                    rx.append(Pattern.quote(shape.substring(last, at.start())));
                    rx.append(taken.get(k++).contains("$") ? "(?:" + OPEN + "[^" + CLOSE + "]*" + CLOSE + "|[\\w.$]+)" : "" + OPEN + "[^" + CLOSE + "]*" + CLOSE);
                    last = at.end();
                }
                rx.append(Pattern.quote(shape.substring(last)));
                Pattern own = Pattern.compile(rx.toString());
                for (Set<String> readings : uses) {
                    boolean sameShape = readings.stream().anyMatch(r -> CHOICE.matcher(r).replaceAll(OPEN + "" + CLOSE).equals(shape));
                    if (!sameShape && readings.stream().anyMatch(r -> own.matcher(r).matches() && !r.contains("$" + "$"))) {
                        tally[1]--;
                    }
                }
            }
            Matcher m = Pattern.compile(OPEN + "" + CLOSE).matcher(shape);
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(taken.get(i++)));
            }
            return m.appendTail(out).toString();
        }

        static int operandIndex(String option) {
            Matcher m = Pattern.compile("\\$(\\d+)").matcher(option);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        /** The old member's operands: its receiver (unless static) and its arguments. */
        int operands(Ref old) {
            if (old.kind() == 'f') {
                Fld f = h.a.field(old.owner(), old.name());
                return f == null || (f.flags & ACC_STATIC) != 0 ? 0 : 1;
            }
            Meth m = h.a.method(old.owner(), old.name(), old.desc());
            m = m != null ? m : h.a.libraryMethod(old.owner(), old.name(), old.desc());
            int n = MethodTypeDesc.ofDescriptor(old.desc()).parameterCount();
            return m == null || m.isStatic() || m.special() ? n : n + 1;
        }

        /** A reading seen once is kept only when it uses every operand and takes no constant: nothing in it is a guess. */
        boolean usesAll(String expr, Ref old, int operands) {
            Meth m = old.kind() == 'm' ? h.a.method(old.owner(), old.name(), old.desc()) : null;
            m = m != null || old.kind() != 'm' ? m : h.a.libraryMethod(old.owner(), old.name(), old.desc());
            int first = old.kind() == 'f' || m != null && !m.isStatic() && !m.special() ? 0 : 1;
            for (int i = first; i < first + operands; i++) {
                if (!Pattern.compile("\\$" + i + "(?!\\d)").matcher(expr).find()) {
                    return false;
                }
            }
            return !Pattern.compile("(^|[(, ])(-?\\d|\"|true|false|null)").matcher(expr).find();
        }

        ClassModel model(Snap s, String cls) {
            Origin o = s.origins.get(cls);
            if (o == null) {
                return null;
            }
            return models.computeIfAbsent((s == h.a ? "a:" : "b:") + cls, k -> ClassFile.of().parse(o.bytes()));
        }

        /** One method's body, by its Mojang name + desc, or by its raw name + desc (a lambda of the same class). */
        Body decode(Snap s, String cls, String key, boolean raw) {
            ClassModel cm = model(s, cls);
            if (cm == null) {
                return null;
            }
            Origin o = s.origins.get(cls);
            String self = cm.thisClass().asInternalName();
            for (MethodModel mm : cm.methods()) {
                String rn = mm.methodName().stringValue(), rd = mm.methodType().stringValue();
                String k = raw ? rn + rd : o.names().method(self, rn, rd) + o.names().desc(rd);
                if (k.equals(key)) {
                    return new Decoder(o, self).run(mm);
                }
            }
            return null;
        }

        /** Lines up two bodies and reads a replacement at every use of a changed member that lines up; then their lambdas. */
        void pair(String ca, Body x, String cb, Body y, int depth) {
            if (x == null || y == null) {
                return;
            }
            consumers(x);
            long[] ta = new long[x.statements.size()], tb = new long[y.statements.size()];
            for (int i = 0; i < ta.length; i++) {
                ta[i] = hash(x.statements.get(i), true);
            }
            for (int j = 0; j < tb.length; j++) {
                tb[j] = hash(y.statements.get(j), false);
            }
            List<int[]> same = matches(ta, tb);
            // Lambdas are paired where the statements that make them line up; the game renumbers them freely.
            Map<String, String> lambdas = new LinkedHashMap<>();
            for (int[] p : same) {
                lambdas(x.statements.get(p[0]), y.statements.get(p[1]), lambdas);
            }
            List<int[]> bounds = new ArrayList<>(same);
            bounds.add(new int[] { ta.length, tb.length });
            int i0 = 0, j0 = 0;
            for (int[] p : bounds) {
                int na = p[0] - i0, nb = p[1] - j0;
                boolean dropped = dropped(x, i0, p[0], y, j0, p[1]);
                for (int i = i0; i < p[0] && nb > 0; i++) {
                    Node sa = x.statements.get(i);
                    if (!holed(sa) || dropped && droppable(sa)) {
                        continue;
                    }
                    // A statement that is only the use (a call made for what it does, a value stored) has no context to
                    // line up by: only the statement in the same place of a gap of the same length counts.
                    boolean bare = sa.kids.length == 1 && hole(sa.kids[0]) && (sa.value.equals("expr") || sa.value.equals("store"));
                    for (int j = j0; j < p[1]; j++) {
                        // Next to the same anchor on both sides: a store against a store lines up; a call against a call is weak, read
                        // only for chains and for the value it lines up, never as a vote on one member.
                        boolean edge = (i == i0 && j == j0 || p[0] - i == 1 && p[1] - j == 1) && sa.value.equals(y.statements.get(j).value);
                        // A bare call with no operands has nothing to check a pairing by (the statement next to a dropped call reads as
                        // its replacement): such pairings are weak, and its drops come from the gap check.
                        boolean operandless = bare && sa.kids[0].kids.length == 0;
                        boolean strong = !operandless && (!bare || na == nb && j - j0 == i - i0 || nb == 1 || edge && "store".equals(sa.value));
                        edge |= operandless && (na == nb && j - j0 == i - i0 || nb == 1);
                        if (bare && !strong && !edge) {
                            continue;
                        }
                        Map<Node, Node> holes = new IdentityHashMap<>();
                        budget = 20_000;
                        if (match(sa, y.statements.get(j), holes)) {
                            weak = !strong;
                            holes.forEach(this::read);
                            weak = false;
                            lambdas(sa, y.statements.get(j), lambdas);
                        }
                    }
                }
                i0 = p[0] + 1;
                j0 = p[1] + 1;
            }
            if (x.lambdas.size() == y.lambdas.size()) {
                Set<String> taken = new HashSet<>(lambdas.values());
                for (int i = 0; i < x.lambdas.size(); i++) {
                    if (!lambdas.containsKey(x.lambdas.get(i)) && !taken.contains(y.lambdas.get(i))) {
                        lambdas.put(x.lambdas.get(i), y.lambdas.get(i));
                    }
                }
            }
            if (depth < 3) {
                lambdas.forEach((la, lb) -> pair(ca, decode(h.a, ca, la, true), cb, decode(h.b, cb, lb, true), depth + 1));
            }
        }

        /**
         * A call the game simply removed: between two statements that line up, a's gap is b's gap plus statements that
         * are only a call of a member b no longer has, made for what it does (1.21's endVertex(), 1.19.4's
         * disableTexture()). Each such call either was dropped or lines up with a statement of b that is only its
         * receiver (the vertex chain endVertex() ended); every other statement of the gap must line up with b's, in
         * order. Each such call is a use whose reading is "nothing": empty for a void call, its receiver for one that
         * returns it.
         */
        boolean dropped(Body x, int i0, int i1, Body y, int j0, int j1) {
            boolean any = false;
            for (int i = i0; i < i1 && !any; i++) {
                any = droppable(x.statements.get(i));
            }
            long cells = (long) (i1 - i0 + 1) * (j1 - j0 + 1);
            if (!any || cells > 40_000) {
                return false;
            }
            byte[][] memo = new byte[i1 - i0 + 1][j1 - j0 + 1]; // 0 unknown, 1 explained, 2 not
            if (!explained(x, i0, i1, y, j0, j1, i0, j0, memo)) {
                return false;
            }
            List<Node> gone = new ArrayList<>();
            for (int i = i0, j = j0; i < i1;) {
                Node s = x.statements.get(i);
                if (j < j1 && pairs(s, y.statements.get(j)) && explained(x, i0, i1, y, j0, j1, i + 1, j + 1, memo)) {
                    j++;
                } else if (!skippable(s)) {
                    return false;
                }
                if (droppable(s)) {
                    gone.add(s);
                }
                i++;
            }
            for (Node s : gone) {
                Node use = s.kids[0];
                String key = use.ref.owner() + "." + use.ref.name() + use.ref.desc();
                keys.putIfAbsent(key, use.ref);
                String reading = use.type.equals("V") ? "" : use.op == 'v' && returnsReceiver(use.ref) ? choice(List.of("$0")) : null;
                Set<String> readings = sites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(use, k -> new LinkedHashSet<>());
                if (reading != null) {
                    readings.add(reading);
                }
            }
            return true;
        }

        /** a's statements from i and b's from j, to the gap's ends, line up with some removed calls left out. */
        boolean explained(Body x, int i0, int i1, Body y, int j0, int j1, int i, int j, byte[][] memo) {
            if (i == i1) {
                return j == j1;
            }
            if (memo[i - i0][j - j0] != 0) {
                return memo[i - i0][j - j0] == 1;
            }
            Node s = x.statements.get(i);
            boolean ok = j < j1 && pairs(s, y.statements.get(j)) && explained(x, i0, i1, y, j0, j1, i + 1, j + 1, memo)
                || skippable(s) && explained(x, i0, i1, y, j0, j1, i + 1, j, memo);
            memo[i - i0][j - j0] = (byte) (ok ? 1 : 2);
            return ok;
        }

        /** s lines up with t; a removed call only with a statement that is its receiver's value, as the game wrote it. */
        boolean pairs(Node s, Node t) {
            budget = 20_000;
            if (!droppable(s)) {
                return match(s, t, new IdentityHashMap<>());
            }
            Node use = s.kids[0];
            if (!"expr".equals(t.value) || t.kids.length != 1 || use.op != 'v') {
                return false;
            }
            return match(use.kids[0], t.kids[0], null) || linesUpThrough(use.kids[0], t.kids[0]) || use.kids.length == 1 && endsLike(use, t.kids[0]);
        }

        /**
         * A removed call left out with nothing in b in its place: only one whose operands hold no other changed member's
         * value. Where they do, the game may have folded the call into that member's replacement (1.19.3's
         * matrix.multiply(q) after pose.copy() became new Matrix4f(pose).rotate(q)), which is not a drop.
         */
        boolean skippable(Node s) {
            if (!droppable(s)) {
                return false;
            }
            for (Node k : s.kids[0].kids) {
                if (holed(k)) {
                    return false;
                }
            }
            return true;
        }
        /** A statement that is only a call, made for what it does, of a member b removed (no counterpart at all). */
        boolean droppable(Node s) {
            if (!"expr".equals(s.value) || s.kids.length != 1 || !hole(s.kids[0]) || s.kids[0].op != 's' && s.kids[0].op != 'v') {
                return false;
            }
            Meth m = h.a.method(s.kids[0].ref.owner(), s.kids[0].ref.name(), s.kids[0].ref.desc());
            return m != null && !m.special() && h.mAB.get(m) == null;
        }

        /** A method that gives back the object it was called on, by its type: a builder's fluent step. */
        boolean returnsReceiver(Ref r) {
            String ret = r.desc().substring(r.desc().indexOf(')') + 1);
            Meth m = h.a.method(r.owner(), r.name(), r.desc());
            if (!ret.startsWith("L") || m == null || m.isStatic() || !subtypeA(r.owner(), ret.substring(1, ret.length() - 1))) {
                return false;
            }
            // Where a has the body, every return gives back `this`; an interface's abstract step is taken by its type.
            Body body = m.hasCode ? decode(h.a, m.owner.name, m.name + m.desc, false) : null;
            return !m.hasCode || body != null && body.statements.stream().filter(s -> "return".equals(s.value))
                .allMatch(s -> s.kids.length == 1 && s.kids[0].op == 'p' && s.kids[0].value.equals(0));
        }

        boolean subtypeA(String sub, String sup) {
            Deque<String> todo = new ArrayDeque<>(List.of(sub));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (c.equals(sup)) {
                    return true;
                }
                Cls k = h.a.classes.get(c);
                if (k != null && seen.add(c)) {
                    todo.addAll(supertypes(k));
                }
            }
            return false;
        }

        /** The lambdas two lined-up trees make at the same place (not inside a use of a changed member). */
        void lambdas(Node x, Node y, Map<String, String> out) {
            if (hole(x) || x.kids.length != y.kids.length) {
                return;
            }
            if (x.body != null && y.body != null) {
                out.putIfAbsent(x.body, y.body);
            }
            for (int i = 0; i < x.kids.length; i++) {
                lambdas(x.kids[i], y.kids[i], out);
            }
        }

        /**
         * Index pairs of statements that are the same on both sides, in order: common start and end, then the longest
         * common subsequence when that is small enough, else statements found exactly once on each side as anchors (in
         * increasing order on both), and the same again between anchors.
         */
        static List<int[]> matches(long[] ta, long[] tb) {
            List<int[]> out = new ArrayList<>();
            matches(ta, 0, ta.length, tb, 0, tb.length, out, 0);
            return out;
        }

        static void matches(long[] ta, int a0, int a1, long[] tb, int b0, int b1, List<int[]> out, int depth) {
            List<int[]> tail = new ArrayList<>();
            while (a0 < a1 && b0 < b1 && ta[a0] == tb[b0]) {
                out.add(new int[] { a0++, b0++ });
            }
            while (a0 < a1 && b0 < b1 && ta[a1 - 1] == tb[b1 - 1]) {
                tail.add(0, new int[] { --a1, --b1 });
            }
            int n = a1 - a0, m = b1 - b0;
            if (n > 0 && m > 0 && (long) n * m <= 250_000) {
                int[][] l = new int[n + 1][m + 1];
                for (int i = n - 1; i >= 0; i--) {
                    for (int j = m - 1; j >= 0; j--) {
                        l[i][j] = ta[a0 + i] == tb[b0 + j] ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
                    }
                }
                for (int i = 0, j = 0; i < n && j < m;) {
                    if (ta[a0 + i] == tb[b0 + j]) {
                        out.add(new int[] { a0 + i++, b0 + j++ });
                    } else if (l[i + 1][j] >= l[i][j + 1]) {
                        i++;
                    } else {
                        j++;
                    }
                }
            } else if (n > 0 && m > 0 && depth < 8) {
                Map<Long, int[]> once = new HashMap<>(); // hash -> {count a, index a, count b, index b}
                for (int i = a0; i < a1; i++) {
                    int[] c = once.computeIfAbsent(ta[i], k -> new int[4]);
                    c[0]++;
                    c[1] = i;
                }
                for (int j = b0; j < b1; j++) {
                    int[] c = once.computeIfAbsent(tb[j], k -> new int[4]);
                    c[2]++;
                    c[3] = j;
                }
                List<int[]> anchors = new ArrayList<>();
                for (int[] c : once.values()) {
                    if (c[0] == 1 && c[2] == 1) {
                        anchors.add(new int[] { c[1], c[3] });
                    }
                }
                anchors.sort(Comparator.comparingInt(p -> p[0]));
                // Longest run increasing on b as well (patience sorting).
                int[] prev = new int[anchors.size()];
                List<Integer> piles = new ArrayList<>();
                for (int k = 0; k < anchors.size(); k++) {
                    int lo = 0, hi = piles.size();
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        if (anchors.get(piles.get(mid))[1] < anchors.get(k)[1]) {
                            lo = mid + 1;
                        } else {
                            hi = mid;
                        }
                    }
                    prev[k] = lo == 0 ? -1 : piles.get(lo - 1);
                    if (lo == piles.size()) {
                        piles.add(k);
                    } else {
                        piles.set(lo, k);
                    }
                }
                List<int[]> chain = new ArrayList<>();
                for (int k = piles.isEmpty() ? -1 : piles.get(piles.size() - 1); k >= 0; k = prev[k]) {
                    chain.add(0, anchors.get(k));
                }
                int i = a0, j = b0;
                for (int[] p : chain) {
                    matches(ta, i, p[0], tb, j, p[1], out, depth + 1);
                    out.add(p);
                    i = p[0] + 1;
                    j = p[1] + 1;
                }
                if (!chain.isEmpty()) {
                    matches(ta, i, a1, tb, j, b1, out, depth + 1);
                }
            }
            out.addAll(tail);
        }

        /** b computes m where a used the changed member in use: every way to write m over use's operands. */
        void read(Node use, Node m) {
            String key = use.ref.owner() + "." + use.ref.name() + (use.ref.kind() == 'm' ? use.ref.desc() : "");
            keys.putIfAbsent(key, use.ref);
            if (!weak) {
                lined.computeIfAbsent(use, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(m);
            }
            chain(use, m);
            if (weak) {
                return;
            }
            Set<String> readings = sites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(use, k -> new LinkedHashSet<>());
            int first = use.op == 'v' || use.op == 'f' ? 0 : 1;
            String cast = "";
            if (!fits(m.type, use.type)) {
                // A generic call gives back its argument's own type (<T extends GuiEventListener> T addRenderableWidget(T)):
                // erased it says GuiEventListener, but javac gives the argument's type, so the value is cast back to it.
                Integer j = (m.op == 'v' || m.op == 's') ? givesArgument(m.ref) : null;
                Node arg = j == null ? null : m.kids[j + (m.op == 'v' ? 1 : 0)];
                if (arg == null || !use.type.startsWith("L") || !fits(arg.type, use.type)) {
                    if (deferred != null && typeVote(use.type, m.type)) {
                        deferred.add(new Node[] { use, m });
                    }
                    return;
                }
                cast = "(" + mappedName(use.type) + ") ";
            }
            String body = express(m, use.kids, first, use.type, 0);
            if (use.type.equals("V") && endsLike(use, m)) {
                // A void call whose place b fills with only its receiver's value (the vertex chain endVertex() ended,
                // even where the game also rewrote that chain: color(255, 255, 255, 255) became color(-1)) did nothing
                // b still does: it is dropped.
                body = "";
                align(use.kids[0], m);
            }
            if (body == null && later != null && Arrays.stream(use.kids).anyMatch(k -> hole(k) && !lined.containsKey(k))) {
                // An operand is a changed member's value not lined up yet: read again once every statement has been.
                later.add(new Node[] { use, m });
            }
            boolean unsure = false;
            for (int i = 0; body != null && !body.isEmpty() && i < use.kids.length; i++) {
                // An operand that depends on the path taken, or holds a changed member b showed nothing for, left out of the
                // reading: b's code may be that operand's value
                // written out (a builder made on one branch), which says nothing about the member, so the use abstains.
                if ((merged(use.kids[i]) || holed(use.kids[i]) && !lined.containsKey(use.kids[i])) && !Pattern.compile("\\$" + (i + first) + "(?!\\d)").matcher(body).find()
                    && MADE.matcher(body).find()) {
                    body = null;
                    unsure = true;
                }
            }
            String reading = body == null ? null : cast.isEmpty() ? body : cast + receiver(body);
            if (reading == null && (m.op == 'v' || m.op == 'f') && m.ref != null && m.kids.length > 0 && local(m.kids[0])) {
                // Only the receiver is the method's own: the same call with the same arguments, read over the operands.
                String args = m.op == 'v' ? arguments(m, 1, use.kids, first, 0) : "";
                if (args != null) {
                    aside.put(use, m.op == 'v' ? m.ref.name() + "(" + args + ")" : m.ref.name());
                }
            }
            if (reading == null && readings.isEmpty() && (unsure || merged(m) || Arrays.stream(use.kids).anyMatch(this::merged))) {
                // Unreadable only because a value depends on the path taken (a loop's variable): that says nothing
                // either way, so the use abstains instead of counting against.
                sites.get(key).remove(use);
                return;
            }
            if (reading != null) {
                readings.add(reading);
                // A one-line static wrapper reads the same as its body: uses that wrote either agree
                // (FMLEnvironment.getDist() is FMLLoader.getCurrent().getDist()).
                Node inner = m.op == 's' && cast.isEmpty() ? oneLine(m) : null;
                String alt = inner == null ? null : express(inner, use.kids, first, use.type, 0);
                if (alt != null) {
                    readings.add(alt);
                }
            }
        }

        /**
         * Two changed members on one value, replaced together: the value of X used only as operand `at` of Y (0 is Y's
         * receiver). "last": Y gives the value (1.19.3's Vector3f.XP.rotationDegrees(f) is Axis.XP.rotationDegrees(f); 1.21's
         * MultiBufferSource.immediate(tesselator.getBuilder()) is immediate(new ByteBufferBuilder(1536))); "first": Y is a
         * void call on X's value and X's value is what b computes (1.21's tesselator.getBuilder() then
         * builder.begin(mode, format) is tesselator.begin(mode, format)). The reading is written over X's operands, then
         * Y's others, from $1.
         */
        record Chained(Ref x, Ref y, int at, boolean first, String expr, int votes, int of) {

            String json(String step) {
                String q = VersionMap.Diff.q(x.owner().replace('/', '.'));
                return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + q + ", \"from\": " + VersionMap.Diff.q(x.name())
                    + ", \"desc\": " + VersionMap.Diff.q(x.kind() == 'f' ? "" : x.desc()) + ", \"field\": " + (x.kind() == 'f') + ", \"linked\": false"
                    + ", \"expr\": " + VersionMap.Diff.q(expr) + ", \"votes\": " + votes + ", \"of\": " + of
                    + ", \"then\": { \"owner\": " + VersionMap.Diff.q(y.owner().replace('/', '.')) + ", \"from\": " + VersionMap.Diff.q(y.name())
                    + ", \"desc\": " + VersionMap.Diff.q(y.desc()) + ", \"at\": " + at + " }, \"result\": " + VersionMap.Diff.q(at == -2 ? "late" : at < 0 ? "sibling" : first ? "first" : "last") + " }";
            }
        }

        final Map<Node, List<Node>> consumers = new IdentityHashMap<>(); // a changed member's value -> void changed calls made on it
        // A changed member's use -> what b computes where it lined up: that value stands for it as an operand elsewhere
        // (1.21: the builder getBuilder() gave is the one begin(mode, format) gives, so builder.end() reads as $0.buildOrThrow()).
        final Map<Node, Set<Node>> lined = new IdentityHashMap<>();
        final Map<String, Map<Node, Set<String>>> chainSites = new HashMap<>();
        final Map<Object, List<Node>> byReceiver = new HashMap<>(); // (receiver, body) -> the changed calls made on it that give a value
        final Map<Node, Body> bodyOf = new IdentityHashMap<>();

        /** A receiver as one value: the node itself, or the member a no-argument static call or static field read names (Tesselator.getInstance()). */
        static Object same(Node r) {
            return (r.op == 's' && r.kids.length == 0 || r.op == 'g') && r.ref != null ? r.op + r.ref.toString() : new IdentityKey(r);
        }

        record IdentityKey(Node n) {

            @Override
            public boolean equals(Object o) {
                return o instanceof IdentityKey k && k.n == n;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(n);
            }
        }
        final Map<Node, Map<Node, Node>> siblingUse = new IdentityHashMap<>();

        /**
         * A value made from nothing of the old member's: a call or a new with no operand among its arguments
         * (UniformGenerator.between(0.0F, 1.0F), new ByteBufferBuilder(1536)). Where a reading leaves an operand out and has
         * one, b may have written that operand's own replacement there; a fixed flag in its place (false) is the member's.
         */
        /** A use whose every argument (its receiver apart) is a call's or a field's result: a value its writer could not see. */
        static boolean opaque(Node use) {
            int from = use.op == 'v' || use.op == 'f' ? 1 : 0;
            if (use.kids.length <= from) {
                return false;
            }
            for (int i = from; i < use.kids.length; i++) {
                Node k = use.kids[i].op == 't' && use.kids[i].kids.length > 0 ? use.kids[i].kids[0] : use.kids[i];
                if (k.op != 'v' && k.op != 's' && k.op != 'f' && k.op != 'g') {
                    return false;
                }
            }
            return true;
        }

        /** A value read from the method's own parameters or locals, nothing else: what a mod's call site does not have. */
        static boolean local(Node n) {
            if (n.op == 'p' || n.op == 'l') {
                return true;
            }
            if ((n.op == 'v' || n.op == 'f' || n.op == 't') && n.kids.length > 0) {
                return local(n.kids[0]);
            }
            return false;
        }

        static final Pattern MADE = Pattern.compile("(?:new\\s+[\\w.]+|(?<![\\w$])[A-Za-z_]\\w*\\.\\w+)\\([^()$]*\\)");

        /** A reading that leaves out an operand depending on the path taken, or holding a changed member b showed nothing for: abstain. */
        boolean unsure(String r, Node[] ops) {
            for (int i = 0; i < ops.length; i++) {
                if ((merged(ops[i]) || holed(ops[i]) && !lined.containsKey(ops[i])) && (r == null || !Pattern.compile("\\$" + (i + 1) + "(?!\\d)").matcher(r).find() && MADE.matcher(r).find())) {
                    return true;
                }
            }
            return false;
        }

        void siblingRead(Node x, Node y, Node m) {
            if (!fits(m.type, y.type)) {
                return;
            }
            Node[] ops = new Node[y.kids.length + 1];
            ops[0] = y.kids[0];
            ops[1] = x;
            System.arraycopy(y.kids, 1, ops, 2, y.kids.length - 1);
            Node site = siblingUse.computeIfAbsent(y, k -> new IdentityHashMap<>()).computeIfAbsent(x, k -> new Node('s', null, null, y.type, ops));
            String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " -1 sibling";
            chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, -1, 1 });
            Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
            String r = express(m, site.kids, 1, y.type, 0);
            // Without X's value the reading shows b making its own, not what became of X's: it says nothing either way.
            if (unsure(r, site.kids) || r != null && !Pattern.compile("\\$2(?!\\d)").matcher(r).find()) {
                chainSites.get(key).remove(site);
                return;
            }
            if (r != null) {
                readings.add(r);
            }
        }
        final Map<String, Object[]> chainRefs = new HashMap<>(); // key -> {X, Y, at}
        final Map<Node, Node> chainUse = new IdentityHashMap<>(); // Y -> one use standing for both calls, over their operands
        final List<Chained> chains = new ArrayList<>();

        /** The void changed calls a body makes on a changed member's value, as chains read them. */
        void consumers(Body x) {
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Node> todo = new ArrayDeque<>(x.statements);
            while (!todo.isEmpty()) {
                Node n = todo.pop();
                if (seen.add(n)) {
                    bodyOf.putIfAbsent(n, x);
                    if (n.op == 'v' && !n.type.equals("V") && n.kids.length > 0 && chainable(n)) {
                        byReceiver.computeIfAbsent(List.of(same(n.kids[0]), System.identityHashCode(x)), k -> new ArrayList<>()).add(n);
                    }
                    todo.addAll(List.of(n.kids));
                }
            }
            for (Node s : x.statements) {
                if ("expr".equals(s.value) && s.kids.length == 1) {
                    Node c = s.kids[0];
                    if (c.op == 'v' && c.type.equals("V") && hole(c) && c.kids.length > 0 && chainable(c.kids[0])) {
                        consumers.computeIfAbsent(c.kids[0], k -> new ArrayList<>()).add(c);
                    }
                }
            }
        }

        boolean chainable(Node x) {
            return "svgf".indexOf(x.op) >= 0 && hole(x) && !x.ref.name().startsWith("<");
        }

        void chain(Node use, Node m) {
            late(use, m);
            // "sibling": a call on the receiver an earlier changed call was made on, read with that call's value among its
            // operands (1.21's tesselator.end() after tesselator.getBuilder() is BufferUploader.drawWithShader(builder.buildOrThrow())).
            if (use.op == 'v' && hole(use) && use.kids.length > 0) {
                Body in = bodyOf.get(use);
                for (Node x : in == null ? List.<Node>of() : byReceiver.getOrDefault(List.of(same(use.kids[0]), System.identityHashCode(in)), List.<Node>of())) {
                    if (x != use && x.at >= 0 && use.at > x.at && !x.ref.equals(use.ref)) {
                        siblingRead(x, use, m);
                    }
                }
            }
            if ((use.op == 'v' || use.op == 's') && !use.type.equals("V")) {
                for (int k = 0; k < use.kids.length; k++) {
                    if (chainable(use.kids[k])) {
                        chainRead(use.kids[k], use, k, m, use.type);
                    }
                }
            }
            for (Node y : consumers.getOrDefault(use, List.of())) {
                chainRead(use, y, 0, m, use.type);
            }
        }

        /**
         * "late": a value the new release takes where the object is made, which old code set later on the same object.
         * 1.21.11's CycleButton.builder(f) then withInitialValue(v), after other calls that give the builder back
         * (withValues(...)), is CycleButton.builder(f, v), and withInitialValue is gone. Y, a changed call on a value
         * made by X (a changed call) through calls that return their receiver; b's node in Y's place is the same calls
         * on X's new call, which is read over both calls' operands.
         */
        void late(Node y, Node m) {
            if (y.op != 'v' || !hole(y) || y.kids.length < 2 || y.type.equals("V") || !y.type.equals(y.kids[0].type)) {
                return;
            }
            Node x = y.kids[0], now = m;
            while (x.op == 'v' && !hole(x) && x.kids.length > 0 && x.type.equals(x.kids[0].type)) {
                if (now.op != 'v' || now.ref == null || !now.ref.name().equals(x.ref.name()) || now.kids.length != x.kids.length) {
                    return;
                }
                x = x.kids[0];
                now = now.kids[0];
            }
            if (!chainable(x) || x.op != 's' && x.op != 'v' || now.op != x.op || now.ref == null || x.at < 0 || y.at < x.at) {
                return;
            }
            Node[] ops = new Node[x.kids.length + y.kids.length - 1];
            System.arraycopy(x.kids, 0, ops, 0, x.kids.length);
            System.arraycopy(y.kids, 1, ops, x.kids.length, y.kids.length - 1);
            String made = x.type;
            Node site = lateUse.computeIfAbsent(y, k -> new Node('s', null, null, made, ops));
            String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " -2 late";
            chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, -2, x.kids.length });
            Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
            String r = express(now, site.kids, 1, x.type, 0);
            if (r != null) {
                readings.add(r);
            }
        }

        final Map<Node, Node> lateUse = new IdentityHashMap<>(); // Y -> one use standing for X and Y of a late chain

        void chainRead(Node x, Node y, int at, Node m, String type) {
            if (!fits(m.type, type)) {
                return;
            }
            Node[] ops = new Node[x.kids.length + y.kids.length - 1];
            System.arraycopy(x.kids, 0, ops, 0, x.kids.length);
            for (int i = 0, j = x.kids.length; i < y.kids.length; i++) {
                if (i != at) {
                    ops[j++] = y.kids[i];
                }
            }
            Node site = chainUse.computeIfAbsent(y, k -> new Node('s', null, null, type, ops));
            boolean first = y.type.equals("V");
            String key = x.ref.owner() + "." + x.ref.name() + x.ref.desc() + " " + y.ref.owner() + "." + y.ref.name() + y.ref.desc() + " " + at + (first ? " first" : " last");
            chainRefs.putIfAbsent(key, new Object[] { x.ref, y.ref, at, x.kids.length });
            Set<String> readings = chainSites.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(site, k -> new LinkedHashSet<>());
            String r = express(m, site.kids, 1, type, 0);
            if (unsure(r, site.kids)) {
                chainSites.get(key).remove(site);
                return;
            }
            if (r != null) {
                readings.add(r);
            }
        }

        /**
         * Each chain the game's uses agree on: two or more of them, as many as for one member's reading, and not a reading
         * shown only for literal arguments. A "first" chain must use every argument of Y: Y's effect may not be lost (where
         * b applies it elsewhere, X's value alone reads as something that leaves it out). Operands a "last" chain leaves out
         * are still computed where they were.
         */
        void voteChains(StringBuilder log) {
            for (var e : chainSites.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue; // every use abstained
                }
                int[] tally = new int[2];
                String best = vote(e.getValue().values(), tally, true);
                Object[] r = chainRefs.get(e.getKey());
                // A late chain keeps Y's value too: it is what moved into X's new call.
                boolean first = e.getKey().endsWith(" first") || e.getKey().endsWith(" sibling") || e.getKey().endsWith(" late");
                int n = e.getValue().keySet().iterator().next().kids.length;
                boolean all = best != null;
                for (int i = first ? (int) r[3] + 1 : n + 1; all && i <= n; i++) {
                    all = Pattern.compile("\\$" + i + "(?!\\d)").matcher(best).find();
                }
                boolean agreed = all && tally[0] >= 2 && tally[0] * 10 >= tally[1] * 8 && beyondLiterals(best, e.getValue(), true);
                if (log != null) {
                    log.append("chain\t").append(e.getKey()).append('\t').append(tally[1]).append('\t').append(tally[0]).append('\t').append(agreed).append('\t')
                        .append(best == null ? "" : best).append('\t');
                    e.getValue().values().stream().map(s -> s.isEmpty() ? "-" : String.join(" | ", s)).limit(9).forEach(r2 -> log.append(r2.replace(OPEN, '{').replace(OR, '|').replace(CLOSE, '}')).append(" ;; "));
                    log.append('\n');
                }
                if (agreed) {
                    chains.add(new Chained((Ref) r[0], (Ref) r[1], (int) r[2], (int) r[2] != -2 && first, best, tally[0], tally[1]));
                }
            }
            chains.sort(Comparator.comparing((Chained c) -> c.x().owner() + c.x().name() + c.y().name() + c.at()));
        }

        /** Two trees the same in shape: each use of a changed member in a, with the value b has in its place. */
        void align(Node x, Node y) {
            if (hole(x)) {
                lined.computeIfAbsent(x, z -> Collections.newSetFromMap(new IdentityHashMap<>())).add(y);
            } else if (x.op == y.op && x.kids.length == y.kids.length) {
                for (int i = 0; i < x.kids.length; i++) {
                    align(x.kids[i], y.kids[i]);
                }
            }
        }

        /** use is an instance call and m is the same call its receiver ends with: same member, same number of operands. */
        boolean endsLike(Node use, Node m) {
            if (use.op != 'v' || use.kids.length != 1) {
                return false;
            }
            Node r = use.kids[0];
            return (r.op == 'v' || r.op == 's') && !hole(r) && r.op == m.op && r.kids.length == m.kids.length && head(r, true).equals(head(m, false));
        }

        /** b's value of type got can stand where a's code had a value of type had: anything for nothing, else the same type or a subtype. */
        boolean fits(String got, String had) {
            if (had.equals("V")) {
                return true;
            }
            if (got.equals("null")) {
                return had.startsWith("L") || had.startsWith("[");
            }
            if (h.descBA(got).equals(had)) {
                return true;
            }
            if (!got.startsWith("L") || !had.startsWith("L")) {
                return false;
            }
            String old = had.substring(1, had.length() - 1);
            Cls ca = h.a.classes.get(old);
            Cls cb = ca == null ? null : h.cAB.get(ca);
            if (cb == null && ca != null) {
                cb = classRewrites().get(ca);
            }
            return h.subtypeB(got.substring(1, got.length() - 1), cb != null ? cb.name : old);
        }

        Map<Cls, Cls> rewritten;

        /** Classes b dropped and the class the game's own code uses in their place: a mod's values of one become the other. */
        Map<Cls, Cls> classRewrites() {
            if (rewritten == null) {
                rewritten = new HashMap<>();
                for (Hop.ClassRewrite r : h.classRewrites()) {
                    rewritten.put(r.from(), r.to());
                }
            }
            return rewritten;
        }

        List<Node[]> deferred = new ArrayList<>(); // uses whose new value fits only once the class map is known
        List<Node[]> later = new ArrayList<>(); // uses with an operand whose new value no statement had shown yet
        boolean weak; // the statement lined up only by standing next to the same anchor: chains only, no vote
        final Map<Cls, Map<Cls, Integer>> typeVotes = new HashMap<>();
        final List<Hop.ClassRewrite> typeRewrites = new ArrayList<>();

        /**
         * A gone class of a where b has a value of another class: one vote that b's class took its place. Only a game class
         * b dropped with no class rewrite yet, and a game class of b of the same kind.
         */
        boolean typeVote(String had, String got) {
            if (!had.startsWith("L") || !got.startsWith("L")) {
                return false;
            }
            Cls ca = h.a.classes.get(had.substring(1, had.length() - 1));
            Cls cb = h.b.classes.getOrDefault(got.substring(1, got.length() - 1), h.b.libraries.get(got.substring(1, got.length() - 1)));
            if (ca == null || cb == null || h.cAB.containsKey(ca) || classRewrites().containsKey(ca) || anonymous(ca.name) || ca.kind() != cb.kind()) {
                return false;
            }
            typeVotes.computeIfAbsent(ca, k -> new HashMap<>()).merge(cb, 1, Integer::sum);
            return true;
        }

        /**
         * The class map from the types the game's own code switched to: each linked method's parameters and result and
         * each linked field's type, place by place, where a's is a class b dropped (1.19's TextComponent fields and
         * results became MutableComponent), with the values b computes where a's code made one (votes from read). Kept
         * as a class rewrite with two votes and 80% agreement, as the route map's own class rewrites are.
         */
        void typeMap() {
            for (var e : h.mAB.entrySet()) {
                List<ClassDesc> pa = MethodTypeDesc.ofDescriptor(e.getKey().desc).parameterList();
                List<ClassDesc> pb = MethodTypeDesc.ofDescriptor(e.getValue().desc).parameterList();
                if (pa.size() == pb.size()) {
                    for (int i = 0; i < pa.size(); i++) {
                        typeVote(pa.get(i).descriptorString(), pb.get(i).descriptorString());
                    }
                }
                typeVote(MethodTypeDesc.ofDescriptor(e.getKey().desc).returnType().descriptorString(),
                    MethodTypeDesc.ofDescriptor(e.getValue().desc).returnType().descriptorString());
            }
            for (var e : h.fAB.entrySet()) {
                typeVote(e.getKey().desc, e.getValue().desc);
            }
            for (var e : typeVotes.entrySet()) {
                Cls best = null;
                int top = 0, of = 0;
                for (var v : e.getValue().entrySet()) {
                    of += v.getValue();
                    if (v.getValue() > top || v.getValue() == top && best != null && v.getKey().name.compareTo(best.name) < 0) {
                        top = v.getValue();
                        best = v.getKey();
                    }
                }
                if (top >= 2 && top * 10 >= of * 8) {
                    classRewrites().put(e.getKey(), best);
                    typeRewrites.add(new Hop.ClassRewrite(e.getKey(), best, top, of));
                }
            }
            typeRewrites.sort(Comparator.comparing((Hop.ClassRewrite r) -> r.from().name));
        }

        /** Whether a's reference names a member a mod could use that b dropped or gave another signature. */
        boolean broken(Ref r) {
            if (r == null || r.kind() != 'm' && r.kind() != 'f') {
                return false;
            }
            Boolean known = brokenRefs.get(r);
            if (known != null) {
                return known;
            }
            boolean out = false;
            if (r.kind() == 'm') {
                Meth m = h.a.method(r.owner(), r.name(), r.desc());
                if (m != null && (m.flags & ACC_PRIVATE) == 0 && !m.name.equals("<clinit>")) {
                    Meth y = h.mAB.get(m);
                    out = y == null || y.isStatic() != m.isStatic() || !h.descBA(y.desc).equals(m.desc) || closed(m.flags, y.flags);
                }
            } else {
                Fld f = h.a.field(r.owner(), r.name());
                if (f != null && (f.flags & ACC_PRIVATE) == 0) {
                    Fld y = h.fAB.get(f);
                    // A field that moved to another class: the route map carries moved methods, not moved fields
                    // (1.19.3's Registry.BLOCK, now BuiltInRegistries.BLOCK).
                    out = y == null || ((y.flags ^ f.flags) & ACC_STATIC) != 0 || !h.descBA(y.desc).equals(f.desc) || closed(f.flags, y.flags)
                        || y.owner != h.cAB.get(f.owner);
                }
            }
            if (!out && !h.a.classes.containsKey(r.owner()) && h.a.libraries.containsKey(r.owner())) {
                // A library member the new release's copy of that library no longer has (1.21.9's authlib made GameProfile a
                // record: getName() is name()), read from the game's own uses like a game member.
                if (r.kind() == 'm') {
                    Meth m = h.a.libraryMethod(r.owner(), r.name(), r.desc());
                    out = m != null && (m.flags & (ACC_PUBLIC | 0x0004)) != 0 && !m.name.startsWith("<cl") && h.b.libraries.containsKey(r.owner())
                        && h.b.libraryMethod(r.owner(), r.name(), r.desc()) == null;
                } else {
                    Fld f = h.a.libraryField(r.owner(), r.name());
                    out = f != null && (f.flags & (ACC_PUBLIC | 0x0004)) != 0 && h.b.libraries.containsKey(r.owner()) && h.b.libraryField(r.owner(), r.name()) == null;
                }
            }
            brokenRefs.put(r, out);
            return out;
        }

        /** A member a mod could reach (public or protected) that b made private or package-private (1.21.2's PartPose.x). */
        static boolean closed(int before, int after) {
            return (before & (ACC_PUBLIC | 0x0004)) != 0 && (after & (ACC_PUBLIC | 0x0004)) == 0;
        }

        boolean hole(Node n) {
            return (n.op == 'g' || n.op == 'f' || n.op == 's' || n.op == 'v' || n.op == 'w') && broken(n.ref);
        }

        boolean holed(Node n) {
            Boolean known = holed.get(n);
            if (known != null) {
                return known;
            }
            boolean out = hole(n);
            for (int i = 0; i < n.kids.length && !out; i++) {
                out = holed(n.kids[i]);
            }
            holed.put(n, out);
            return out;
        }

        String head(Node n, boolean sideA) {
            return switch (n.op) {
                case 'g', 'f', 's', 'v', 'w', 't' -> h.token(n.ref, sideA);
                case 'y' -> (n.ref == null ? "lambda" : h.token(n.ref, sideA)) + n.value;
                case 'k' -> n.type + ":" + n.value;
                case 'x' -> n.type;
                case 'o', 'S' -> n.value + (n.ref == null ? "" : h.token(n.ref, sideA));
                case 'p', 'l' -> n.value + ":" + (sideA ? n.type : h.descBA(n.type));
                default -> "";
            };
        }

        /** A tree's hash in a's names; a use of a changed member hashes to itself alone, so it never equals b's code. */
        long hash(Node n, boolean sideA) {
            Map<Node, Long> memo = sideA ? hashA : hashB;
            Long known = memo.get(n);
            if (known != null) {
                return known;
            }
            long x;
            if (sideA && hole(n) || Decoder.phi(n)) {
                x = 0x9E3779B97F4A7C15L * (System.identityHashCode(n) + 1L) + (sideA ? 0 : 1);
            } else {
                x = fnv(n.op + head(n, sideA));
                for (Node k : n.kids) {
                    x = x * 1_000_003L + hash(k, sideA);
                }
            }
            memo.put(n, x);
            return x;
        }

        /** a's x and b's y are the same code but where x uses a changed member; holes gets each such use and b's code there. */
        boolean match(Node x, Node y, Map<Node, Node> holes) {
            if (--budget < 0) {
                return false;
            }
            if (holes != null && hole(x)) {
                Node had = holes.putIfAbsent(x, y);
                return had == null || had == y;
            }
            if (Decoder.phi(x) || Decoder.phi(y)) {
                return false;
            }
            if (hash(x, true) == hash(y, false)) {
                return true;
            }
            if (x.op != y.op || x.kids.length != y.kids.length || !head(x, true).equals(head(y, false))) {
                return false;
            }
            for (int i = 0; i < x.kids.length; i++) {
                if (!match(x.kids[i], y.kids[i], holes)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * b's value m written over the old member's operands ($first.. for ops), or null: an operand where m is that
         * operand's code, a constant as itself or as an operand that held the same number (a choice, settled by vote);
         * nothing that reads the method's parameters, locals or a member a mod may not use.
         */
        String express(Node m, Node[] ops, int first, String want, int depth) {
            List<String> same = new ArrayList<>();
            for (int i = 0; i < ops.length; i++) {
                budget = 2_000;
                if (match(ops[i], m, null) || linesUpThrough(ops[i], m) || lined.getOrDefault(ops[i], Set.of()).contains(m)) {
                    same.add("$" + (i + first));
                } else if (boxed(ops[i]) && primitive(m.type) != null) {
                    // An operand the old code boxed (withInitialValue(Boolean.valueOf(x))) where b passes the primitive itself
                    // (booleanBuilder(a, b, x)): the operand unboxed.
                    budget = 2_000;
                    if (match(ops[i].kids[0], m, null)) {
                        same.add("((" + dotted(ops[i].ref.owner()) + ") $" + (i + first) + ")." + primitive(m.type) + "Value()");
                    }
                }
            }
            if (m.op == 'k') {
                String p = primitive(m.type);
                for (int i = 0; i < ops.length; i++) {
                    if (ops[i].op == 'k' && !ops[i].type.equals(m.type) && p != null && ops[i].value instanceof Number u && m.value instanceof Number v
                        && u.doubleValue() == v.doubleValue()) {
                        same.add("(" + p + ") $" + (i + first));
                    }
                }
                String lit = literal(m, want);
                if (lit != null) {
                    same.add(lit);
                }
                return choice(same);
            }
            if (!same.isEmpty() || depth > 8) {
                return choice(same);
            }
            return switch (m.op) {
                case 'n' -> "null";
                case 'g' -> open(m.ref, true) ? dotted(m.ref.owner()) + "." + m.ref.name() : null;
                case 'f' -> {
                    String t = open(m.ref, false) ? express(m.kids[0], ops, first, null, depth + 1) : null;
                    yield t == null ? null : receiver(t) + "." + m.ref.name();
                }
                case 's', 'w' -> {
                    String args = open(m.ref, m.op == 's') ? arguments(m, 0, ops, first, depth) : null;
                    yield args == null ? null : (m.op == 's' ? dotted(m.ref.owner()) + "." + m.ref.name() : "new " + dotted(m.ref.owner())) + "(" + args + ")";
                }
                case 'v' -> {
                    String t = open(m.ref, false) && m.value == null ? express(m.kids[0], ops, first, null, depth + 1) : null;
                    String args = t == null ? null : arguments(m, 1, ops, first, depth);
                    yield args == null ? null : receiver(t) + "." + m.ref.name() + "(" + args + ")";
                }
                case 't' -> {
                    String t = m.ref.owner().startsWith("[") ? null : express(m.kids[0], ops, first, null, depth + 1);
                    yield t == null ? null : "(" + dotted(m.ref.owner()) + ") " + t;
                }
                case 'x' -> {
                    String p = primitive(m.type);
                    String t = p == null ? null : express(m.kids[0], ops, first, null, depth + 1);
                    if (t != null && t.charAt(0) == OPEN && t.indexOf(CLOSE) == t.length() - 1) {
                        // A converted operand is one choice, as a constant that operand held would be: (float) $2.
                        yield OPEN + "(" + p + ") " + t.substring(1, t.length() - 1).replace(String.valueOf(OR), OR + "(" + p + ") ") + CLOSE;
                    }
                    yield t == null ? null : "(" + p + ") " + t;
                }
                case 'y' -> {
                    Meth impl = m.ref == null || m.kids.length > 0 ? null : h.b.method(m.ref.owner(), m.ref.name(), m.ref.desc());
                    yield impl != null && impl.isStatic() && open(m.ref, true) ? dotted(m.ref.owner()) + "::" + m.ref.name() : null;
                }
                default -> null;
            };
        }

        /**
         * An operand that is b's code m except inside uses of other changed members, each where b has a value that can
         * stand in its place: a mod's operand, ported, is what b computes there. 1.21's vertex chain
         * vertex(x, y, z).color(c) before endVertex() is addVertex(x, y, z).setColor(c), vertex having changed too. The
         * operand itself must line up, not be such a use.
         */
        boolean linesUpThrough(Node op, Node m) {
            if (hole(op) || !holed(op)) {
                return false;
            }
            Map<Node, Node> inner = new IdentityHashMap<>();
            budget = 2_000;
            if (!match(op, m, inner)) {
                return false;
            }
            for (var e : inner.entrySet()) {
                if (!fits(e.getValue().type, e.getKey().type)) {
                    return false;
                }
            }
            inner.forEach((k, v) -> lined.computeIfAbsent(k, z -> Collections.newSetFromMap(new IdentityHashMap<>())).add(v));
            return true;
        }

        /** A call's arguments (m's kids from index from), each typed for its parameter, or null. */
        String arguments(Node m, int from, Node[] ops, int first, int depth) {
            List<ClassDesc> params = MethodTypeDesc.ofDescriptor(m.ref.desc()).parameterList();
            StringJoiner out = new StringJoiner(", ");
            for (int i = from; i < m.kids.length; i++) {
                int p = i - from;
                String one = express(m.kids[i], ops, first, p < params.size() ? params.get(p).descriptorString() : null, depth + 1);
                if (one == null) {
                    return null;
                }
                out.add(one);
            }
            return out.toString();
        }

        /** A member of b a mod may name: b's own and not private, or the JDK's. */
        boolean open(Ref r, boolean wantStatic) {
            if (r.name() != null && r.name().contains("$")) {
                return false;
            }
            if (r.kind() == 'm') {
                Meth m = h.b.method(r.owner(), r.name(), r.desc());
                m = m != null ? m : h.b.libraryMethod(r.owner(), r.name(), r.desc());
                if (m == null) {
                    return r.owner().startsWith("java/");
                }
                return (m.flags & (ACC_PUBLIC | 0x0004)) != 0 && (m.flags & ACC_SYNTHETIC) == 0 && (m.special() || m.isStatic() == wantStatic) && !m.name.equals("<clinit>");
            }
            Fld f = h.b.field(r.owner(), r.name());
            f = f != null ? f : h.b.libraryField(r.owner(), r.name());
            if (f == null) {
                return r.owner().startsWith("java/");
            }
            return (f.flags & (ACC_PUBLIC | 0x0004)) != 0 && ((f.flags & ACC_STATIC) != 0) == wantStatic;
        }

        static String dotted(String internal) {
            return internal.replace('/', '.');
        }

        /** A cast written as a receiver needs its own parentheses: ((T) x).m(). */
        static String receiver(String t) {
            return t.startsWith("(") ? "(" + t + ")" : t;
        }

        /** A primitive boxed by its wrapper's valueOf (Boolean.valueOf(x)). */
        static boolean boxed(Node n) {
            return n.op == 's' && n.kids.length == 1 && n.ref != null && n.ref.name().equals("valueOf") && n.ref.owner().startsWith("java/lang/")
                && n.kids[0].type.length() == 1;
        }

        static String primitive(String desc) {
            return switch (desc) {
                case "Z" -> "boolean";
                case "C" -> "char";
                case "B" -> "byte";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                case "F" -> "float";
                case "D" -> "double";
                default -> null;
            };
        }

        /** A constant as FixExpr reads it, typed for where it goes (an int constant passed as a boolean is true or false). */
        static String literal(Node k, String want) {
            Object v = k.value;
            if (v instanceof String s) {
                return s.chars().anyMatch(c -> c < 0x20) ? null : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            if (v instanceof Integer i) {
                if ("Z".equals(want)) {
                    return i == 0 ? "false" : i == 1 ? "true" : null;
                }
                if ("C".equals(want) || "B".equals(want) || "S".equals(want)) {
                    return "(" + primitive(want) + ") " + i;
                }
                return Integer.toString(i);
            }
            if (v instanceof Long l) {
                return l + "L";
            }
            String text = v instanceof Float f ? Float.toString(f) + "F" : v instanceof Double d ? Double.toString(d) + "D" : null;
            return text == null || text.contains("E") || text.contains("N") || text.contains("I") ? null : text;
        }
    }

    /**
     * What a call on a parameter the game retyped becomes, read from the game's own methods that took the new type. 1.20's
     * screens took a GuiGraphics where they took a PoseStack, and where the old body called font.draw(poseStack, text, x, y,
     * color) the new one calls graphics.drawString(font, text, x, y, color, false). In every pair of linked methods whose
     * parameter changed type (the rest of the signature the same), both bodies are lined up as Learner lines them up; at a
     * use of a changed member one of whose operands is that parameter, what the new body computes is written over the old
     * member's operands, with that operand standing for the new parameter ($n is the GuiGraphics where it was the
     * PoseStack), and a new value that is an old operand without its widening (int x where the old code passed (float) x)
     * as that operand narrowed back. JarPort applies it where a mod's override moved to the new type passes the parameter
     * the game passed (the "retyped" list of the route map). Uses vote as Learner's do.
     */
    static final class Retyped {

        /** A call (owner, name, desc of a) whose operand at ($0 receiver) was the old parameter; expr has the new type as there. */
        record Reading(String owner, String name, String desc, int at, String as, String expr, int votes, int of) {

            String json(String step) {
                return "{ \"step\": " + VersionMap.Diff.q(step) + ", \"owner\": " + VersionMap.Diff.q(owner) + ", \"from\": " + VersionMap.Diff.q(name) +
                    ", \"desc\": " + VersionMap.Diff.q(desc) + ", \"at\": " + at + ", \"as\": " + VersionMap.Diff.q(as) + ", \"expr\": " + VersionMap.Diff.q(expr) +
                    ", \"votes\": " + votes + ", \"of\": " + of + " }";
            }
        }

        final Hop h;
        final Learner l;
        final Map<String, Map<Node, Set<String>>> sites = new LinkedHashMap<>(); // owner.name+desc#at#as -> use -> readings
        final Map<String, Map<Node, Set<String>>> loaderSites = new LinkedHashMap<>(); // the same, read from the loader's code
        final Map<String, Ref> refs = new HashMap<>();
        boolean fromLoader; // the method pair being read is the loader's

        Retyped(Hop h, Learner l) {
            this.h = h;
            this.l = l;
        }

        List<Reading> learn() {
            for (var e : h.mAB.entrySet()) {
                Meth x = e.getKey(), y = e.getValue();
                if (!x.hasCode || !y.hasCode || x.isStatic() != y.isStatic()) {
                    continue;
                }
                Map<Integer, Object[]> retyped = retyped(x, y);
                if (!retyped.isEmpty()) {
                    fromLoader = x.owner.loader;
                    pair(l.decode(h.a, x.owner.name, x.name + x.desc, false), l.decode(h.b, y.owner.name, y.name + y.desc, false), retyped);
                }
            }
            // The game's own code says what a call becomes; the loader's code (Fabric API reading Minecraft's font where the
            // game reads the screen's) is heard only for a call the game never makes there.
            loaderSites.forEach((key, uses) -> {
                if (sites.getOrDefault(key, Map.of()).isEmpty()) {
                    sites.put(key, uses);
                }
            });
            List<Reading> out = new ArrayList<>();
            StringBuilder log = Learner.LEARN_LOG.isEmpty() ? null : new StringBuilder();
            for (String key : misses.keySet()) {
                sites.putIfAbsent(key, new IdentityHashMap<>());
            }
            for (var e : sites.entrySet()) {
                int[] tally = new int[2];
                String best = Learner.vote(e.getValue().values(), tally);
                if (log != null) {
                    log.append(e.getKey()).append('\t').append(tally[1]).append('\t').append(tally[0]).append('\t').append(best == null ? "" : best).append('\t');
                    e.getValue().values().stream().flatMap(Set::stream).distinct().limit(4).forEach(r -> log.append(r).append(" ;; "));
                    misses.getOrDefault(e.getKey(), List.of()).stream().limit(3).forEach(m -> log.append("MISS ").append(m).append(" ;; "));
                    log.append('\n');
                }
                // Kept with 80% of the uses agreeing, as Learner keeps one; or when every use that reads as anything agrees
                // and two thirds read: a use the game rewrote past any reading (centred another way, its float math
                // folded) is not another answer.
                long read = e.getValue().values().stream().filter(s -> !s.isEmpty()).count();
                boolean unanimous = tally[0] == read && tally[0] >= 2 && tally[0] * 3 >= tally[1] * 2;
                if (best == null || !(tally[0] >= 2 && tally[0] * 10 >= tally[1] * 8 || unanimous || tally[0] == 1 && tally[1] == 1)) {
                    continue;
                }
                String[] key = e.getKey().split("#");
                Ref r = refs.get(key[0]);
                out.add(new Reading(r.owner().replace('/', '.'), r.name(), r.desc(), Integer.parseInt(key[1]), key[2], best, tally[0], tally[1]));
            }
            if (log != null) {
                try {
                    Files.writeString(Path.of(Learner.LEARN_LOG + "-retyped-" + h.a.version + "-" + h.b.version + ".tsv"), log, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return out;
        }

        /**
         * The parameters of x whose type y changed, by x's slot: {y's slot, y's type}. Only where both take as many
         * parameters and each other one keeps its type, so the place says which new parameter took the old one's.
         */
        Map<Integer, Object[]> retyped(Meth x, Meth y) {
            Map<Integer, Object[]> out = new HashMap<>();
            List<ClassDesc> was = MethodTypeDesc.ofDescriptor(x.desc).parameterList(), now = MethodTypeDesc.ofDescriptor(y.desc).parameterList();
            if (was.size() != now.size()) {
                return out;
            }
            int sx = x.isStatic() ? 0 : 1, sy = sx;
            for (int i = 0; i < was.size(); i++) {
                String a = was.get(i).descriptorString(), b = now.get(i).descriptorString();
                if (!h.descBA(b).equals(a)) {
                    if (!a.startsWith("L") || !b.startsWith("L")) {
                        return new HashMap<>();
                    }
                    out.put(sx, new Object[] { sy, b });
                }
                sx += a.equals("J") || a.equals("D") ? 2 : 1;
                sy += b.equals("J") || b.equals("D") ? 2 : 1;
            }
            return out;
        }

        void pair(Body x, Body y, Map<Integer, Object[]> retyped) {
            if (x == null || y == null) {
                return;
            }
            long[] ta = new long[x.statements.size()], tb = new long[y.statements.size()];
            for (int i = 0; i < ta.length; i++) {
                ta[i] = l.hash(x.statements.get(i), true);
            }
            for (int j = 0; j < tb.length; j++) {
                tb[j] = l.hash(y.statements.get(j), false);
            }
            List<int[]> bounds = new ArrayList<>(Learner.matches(ta, tb));
            bounds.add(new int[] { ta.length, tb.length });
            int i0 = 0, j0 = 0;
            for (int[] p : bounds) {
                int na = p[0] - i0, nb = p[1] - j0;
                for (int i = i0; i < p[0] && nb > 0; i++) {
                    Node sa = x.statements.get(i);
                    if (!l.holed(sa)) {
                        continue;
                    }
                    boolean bare = sa.kids.length == 1 && l.hole(sa.kids[0]) && (sa.value.equals("expr") || sa.value.equals("store"));
                    for (int j = j0; j < p[1]; j++) {
                        if (bare && !(na == nb && j - j0 == i - i0 || nb == 1)) {
                            continue;
                        }
                        Map<Node, Node> holes = new IdentityHashMap<>();
                        l.budget = 20_000;
                        if (l.match(sa, y.statements.get(j), holes)) {
                            holes.forEach((use, m) -> read(use, m, retyped));
                        }
                    }
                }
                i0 = p[0] + 1;
                j0 = p[1] + 1;
            }
        }

        /** b computes m where a used a changed member in use: a reading for each operand of use that is a retyped parameter. */
        void read(Node use, Node m, Map<Integer, Object[]> retyped) {
            if (use.op != 'v' && use.op != 's') {
                return;
            }
            int first = use.op == 'v' ? 0 : 1;
            for (int k = 0; k < use.kids.length; k++) {
                Node op = use.kids[k];
                Object[] to = op.op == 'p' && op.value instanceof Integer slot ? retyped.get(slot) : null;
                if (to == null) {
                    continue;
                }
                // Each operand (but a constant, which Learner reads as a choice) becomes a token both sides read alike, and
                // so does the new parameter, standing where the old one was.
                Node[] tokens = new Node[use.kids.length];
                for (int i = 0; i < tokens.length; i++) {
                    Node kid = use.kids[i];
                    // A constant widened ((float) 0) is that constant of the operand's type, so b's 0 there is a choice too.
                    tokens[i] = kid.op == 'k' ? kid : kid.op == 'x' && kid.kids.length == 1 && kid.kids[0].op == 'k'
                        ? new Node('k', null, kid.kids[0].value, kid.type) : new Node('p', null, -1000 - i, "V");
                }
                Node over = tokens(m, use.kids, tokens, k, (Integer) to[0]);
                String key = use.ref.owner() + "." + use.ref.name() + use.ref.desc() + "#" + (k + first) + "#" + to[1];
                refs.putIfAbsent(use.ref.owner() + "." + use.ref.name() + use.ref.desc(), use.ref);
                Map<String, Map<Node, Set<String>>> heard = fromLoader ? loaderSites : sites;
                Set<String> readings = heard.computeIfAbsent(key, x -> new IdentityHashMap<>()).computeIfAbsent(use, x -> new LinkedHashSet<>());
                String reading = use.type.equals("V") || l.fits(m.type, use.type) ? l.express(over, tokens, first, use.type, 0) : null;
                if (reading != null) {
                    readings.add(reading);
                } else if (readings.isEmpty() && (l.merged(m) || Arrays.stream(use.kids).anyMatch(l::merged))) {
                    // Unreadable only because a value depends on the path taken (a loop's variable): it abstains, as in Learner.
                    heard.get(key).remove(use);
                } else {
                    misses.computeIfAbsent(key, x -> new ArrayList<>()).add(show(over, 0) + "  <-  " + show(use, 0));
                }
            }
        }

        final Map<String, List<String>> misses = new HashMap<>(); // uses that lined up and read as nothing, for the log

        /** A value tree in short, for the log. */
        static String show(Node n, int depth) {
            String head = n.op + (n.ref != null ? n.ref.name() == null ? n.ref.owner() : n.ref.name() : "") + (n.value != null ? "=" + n.value : "");
            if (n.kids.length == 0 || depth > 4) {
                return head;
            }
            StringJoiner kids = new StringJoiner(", ", head + "(", ")");
            for (Node k : n.kids) {
                kids.add(show(k, depth + 1));
            }
            return kids.toString();
        }

        /**
         * b's value n with what stands for an old operand written as that operand's token: b's parameter in slot as the
         * retyped operand k; code the same as an operand (synthetic accessors renumbered between releases count as the
         * same: access$1900 in a is access$2000 in b); and an operand without its widening ((float) x passed, int x now) as
         * that operand converted back.
         */
        Node tokens(Node n, Node[] ops, Node[] tokens, int k, int slot) {
            if (n.op == 'p' && Integer.valueOf(slot).equals(n.value)) {
                return tokens[k];
            }
            if (n.op == 'k') {
                return n; // a constant b passes is read as Learner reads one: itself, or a choice among operands holding it
            }
            for (int i = 0; i < ops.length; i++) {
                l.budget = 2_000;
                if (ops[i].op != 'k' && (l.match(ops[i], n, null) || sameAccess(ops[i], n))) {
                    return tokens[i];
                }
            }
            for (int i = 0; i < ops.length; i++) {
                l.budget = 2_000;
                Node op = ops[i];
                if (op.op == 'x' && op.kids.length == 1 && (l.match(op.kids[0], n, null) || sameAccess(op.kids[0], n)) && Learner.primitive(n.type) != null) {
                    return new Node('x', null, null, n.type, tokens[i]);
                }
            }
            Node[] kids = n.kids.clone();
            boolean changed = false;
            for (int i = 0; i < kids.length; i++) {
                kids[i] = tokens(n.kids[i], ops, tokens, k, slot);
                changed |= kids[i] != n.kids[i];
            }
            if (!changed) {
                return n;
            }
            Node out = new Node(n.op, n.ref, n.value, n.type, kids);
            out.body = n.body;
            return out;
        }

        /** a's x and b's y are the same code, where calls to synthetic accessors (access$N) may carry other numbers. */
        boolean sameAccess(Node x, Node y) {
            if (x.op == 's' && y.op == 's' && x.ref != null && y.ref != null && x.ref.name() != null && y.ref.name() != null
                && x.ref.name().startsWith("access$") && y.ref.name().startsWith("access$") && x.kids.length == y.kids.length && h.descBA(y.type).equals(x.type)) {
                for (int i = 0; i < x.kids.length; i++) {
                    l.budget = 2_000;
                    if (!l.match(x.kids[i], y.kids[i], null) && !sameAccess(x.kids[i], y.kids[i])) {
                        return false;
                    }
                }
                return true;
            }
            if (x.op != y.op || x.kids.length != y.kids.length || x.kids.length == 0 || !l.head(x, true).equals(l.head(y, false))) {
                return false;
            }
            for (int i = 0; i < x.kids.length; i++) {
                l.budget = 2_000;
                if (!l.match(x.kids[i], y.kids[i], null) && !sameAccess(x.kids[i], y.kids[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A method the game kept with other parameters (the "call, game: linked" break), called as its old self behaved: each
     * parameter of the new method is read from where the two bodies line up. Where the new body uses a parameter, the old
     * body's value at that place is what to pass: one of the old operands, a fixed value, or the fixed value solved through
     * a sum (1.21.2's Biome.getHeightAdjustedTemperature(pos, seaLevel) tests seaLevel + 17 where the old one tested 80, so
     * the old call is the new one with 63). A parameter only handed on to a callee that changed the same way is solved in
     * that callee first (getTemperature(pos) passes seaLevel to getHeightAdjustedTemperature). A value of another type is
     * made with the new type's own public constructor or its one static factory from the old type (TriState.from(boolean)),
     * and a parameter the new body never reads takes its type's zero. Every place a parameter is used must agree, every
     * other statement must line up, and the result must serve where the old one did; otherwise nothing is learned.
     */
    static final class SameCall {

        /** A value over the old method's operands, and its type (a descriptor; in b's names when inB). */
        record R(String text, String type, boolean inB) {}

        final Hop h;
        final Learner learner;
        final Map<Meth, List<String>> solved = new HashMap<>();
        final Set<Meth> solving = new HashSet<>();
        final Map<String, List<Cls>> declaring = new HashMap<>(); // name + desc -> b's classes that declare it
        static final String WHY = System.getProperty("gamediff.samecall", ""); // a method name: why it is or is not solved

        SameCall(Hop h, Learner learner) {
            this.h = h;
            this.learner = learner;
            for (Cls c : h.b.classes.values()) {
                for (Meth m : c.methods.values()) {
                    if (!m.isStatic() && !m.special()) {
                        declaring.computeIfAbsent(m.name + m.desc, k -> new ArrayList<>()).add(c);
                    }
                }
            }
        }

        /**
         * Whether a call of y always runs y's own body: static, a constructor, final, or nothing in b overrides it. An
         * override can read a parameter the base body ignores, so only a body the call surely runs says what to pass.
         */
        boolean runs(Meth y) {
            if (y.isStatic() || y.special() || (y.flags & 0x0010) != 0 || (y.owner.flags & 0x0010) != 0) {
                return true;
            }
            for (Cls c : declaring.getOrDefault(y.name + y.desc, List.of())) {
                if (c != y.owner && below(c, y.owner.name)) {
                    return false;
                }
            }
            return (y.flags & 0x0400) == 0;
        }

        boolean below(Cls c, String owner) {
            Deque<String> todo = new ArrayDeque<>();
            if (c.sup != null) {
                todo.push(c.sup);
            }
            c.ifaces.forEach(todo::push);
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String n = todo.pop();
                if (!seen.add(n)) {
                    continue;
                }
                if (n.equals(owner)) {
                    return true;
                }
                Cls k = h.b.classes.get(n);
                if (k != null) {
                    if (k.sup != null) {
                        todo.push(k.sup);
                    }
                    k.ifaces.forEach(todo::push);
                }
            }
            return false;
        }

        /** A learned line for every kept method whose parameters changed and whose old behaviour the new one can give. */
        List<Learner.Learned> learn(List<Learner.Learned> known) {
            Set<String> have = new HashSet<>();
            for (Learner.Learned l : known) {
                have.add(l.owner().replace('.', '/') + "." + l.name() + l.desc());
            }
            List<Learner.Learned> out = new ArrayList<>();
            for (var e : h.mAB.entrySet()) {
                Meth x = e.getKey(), y = e.getValue();
                // A private method is reached through a mod's own access transformer, which JarPort reads.
                if (x.isStatic() != y.isStatic() || !x.hasCode || !y.hasCode || x.name.equals("<clinit>")
                    || h.descBA(y.desc).equals(x.desc) || have.contains(x.sig())
                    || !h.descBA(MethodTypeDesc.ofDescriptor(y.desc).returnType().descriptorString())
                        .equals(MethodTypeDesc.ofDescriptor(x.desc).returnType().descriptorString())) {
                    continue;
                }
                List<String> r = solve(x, y, 0);
                if (r == null) {
                    continue;
                }
                String args = String.join(", ", r.subList(1, r.size()));
                String expr = y.name.equals("<init>") ? "new " + Learner.dotted(y.owner.name) + "(" + args + ")"
                    : y.isStatic() ? Learner.dotted(y.owner.name) + "." + y.name + "(" + args + ")" : "$0." + y.name + "(" + args + ")";
                out.add(new Learner.Learned(Learner.dotted(x.owner.name), x.name, x.desc, false, true, expr, 1, 1));
            }
            return out;
        }

        /** m's operand types ($0 the receiver, null when static or a constructor's this) and each parameter slot's operand. */
        static String[] operands(Meth m) {
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            String[] out = new String[ps.size() + 1];
            out[0] = m.isStatic() || m.special() ? null : "L" + m.owner.name + ";";
            for (int i = 0; i < ps.size(); i++) {
                out[i + 1] = ps.get(i).descriptorString();
            }
            return out;
        }

        static int operand(Meth m, Object slot) {
            if (!(slot instanceof Integer s)) {
                return -1;
            }
            int at = m.isStatic() ? 0 : 1;
            if (!m.isStatic() && s == 0) {
                return 0;
            }
            List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
            for (int i = 0; i < ps.size(); i++) {
                if (at == s) {
                    return i + 1;
                }
                String d = ps.get(i).descriptorString();
                at += d.equals("J") || d.equals("D") ? 2 : 1;
            }
            return -1;
        }

        /** Each parameter of y ($0 its receiver) as a value over x's operands, in b's names; null when the bodies do not say. */
        List<String> solve(Meth x, Meth y, int depth) {
            if (solved.containsKey(x)) {
                return solved.get(x);
            }
            if (depth > 4 || !runs(y) || !solving.add(x)) {
                if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                    System.err.println("samecall " + x.sig() + ": depth " + depth + ", runs " + runs(y) + ", solving " + solving.contains(x));
                }
                return null;
            }
            List<String> out = null;
            try {
                out = work(x, y, depth);
            } catch (RuntimeException unreadable) {
                out = null;
            } finally {
                solving.remove(x);
            }
            if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                System.err.println("samecall " + x.sig() + " -> " + y.sig() + ": " + out);
            }
            solved.put(x, out);
            return out;
        }

        final class Ctx {
            final Meth x, y;
            final int depth;
            final Map<Integer, Set<R>> read = new HashMap<>();

            Ctx(Meth x, Meth y, int depth) {
                this.x = x;
                this.y = y;
                this.depth = depth;
            }

            void read(int j, R r) {
                read.computeIfAbsent(j, k -> new LinkedHashSet<>()).add(r);
            }

            R ofX(Node n) {
                return ofX(n, 0);
            }

            /**
             * A value of the old body as an expression in b's names: an operand, a constant, or calls, fields and casts over
             * those that both releases have (1.21.2's onCrystalDestroyed took the ServerLevel its old body read as
             * this.level()).
             */
            R ofX(Node n, int depth) {
                if (depth > 4) {
                    return null;
                }
                return switch (n.op) {
                    case 'v', 'f', 's', 'g' -> {
                        R recv = n.op == 'v' || n.op == 'f' ? (n.kids.length == 0 ? null : ofX(n.kids[0], depth + 1)) : null;
                        if ((n.op == 'v' || n.op == 'f') && recv == null) {
                            yield null;
                        }
                        String name, owner, type;
                        if (n.op == 'v' || n.op == 's') {
                            Meth m = h.a.method(n.ref.owner(), n.ref.name(), n.ref.desc());
                            Meth my = m == null ? null : h.mAB.get(m);
                            if (my == null || !h.descBA(my.desc).equals(m.desc) || my.isStatic() != (n.op == 's') || (my.flags & ACC_PUBLIC) == 0) {
                                yield null;
                            }
                            StringJoiner args = new StringJoiner(", ");
                            for (int i = n.op == 'v' ? 1 : 0; i < n.kids.length; i++) {
                                R a = ofX(n.kids[i], depth + 1);
                                if (a == null) {
                                    yield null;
                                }
                                args.add(a.text());
                            }
                            name = my.name + "(" + args + ")";
                            owner = my.owner.name;
                            type = MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString();
                        } else {
                            Fld f = h.a.field(n.ref.owner(), n.ref.name());
                            Fld fy = f == null ? null : h.fAB.get(f);
                            if (fy == null || !h.descBA(fy.desc).equals(f.desc) || (fy.flags & ACC_PUBLIC) == 0) {
                                yield null;
                            }
                            name = fy.name;
                            owner = fy.owner.name;
                            type = f.desc;
                        }
                        yield new R((recv == null ? Learner.dotted(owner) : Learner.receiver(recv.text())) + "." + name, type, false);
                    }
                    default -> one(n);
                };
            }

            R one(Node n) {
                return switch (n.op) {
                    case 'p' -> {
                        int i = operand(x, n.value);
                        String t = i < 0 ? null : operands(x)[i];
                        yield t == null && !(i == 0 && !x.isStatic() && !x.special()) ? null : new R("$" + i, i == 0 ? "L" + x.owner.name + ";" : t, false);
                    }
                    case 'k' -> {
                        String lit = Learner.literal(n, n.type);
                        yield lit == null ? null : new R(lit, n.type, false);
                    }
                    case 'n' -> new R("null", null, false);
                    default -> null;
                };
            }
        }

        List<String> work(Meth x, Meth y, int depth) {
            Body bx = learner.decode(h.a, x.owner.name, x.name + x.desc, false), by = learner.decode(h.b, y.owner.name, y.name + y.desc, false);
            if (bx == null || by == null || !bx.whole || !by.whole || bx.statements.size() > 400 || by.statements.size() > 400) {
                return null;
            }
            List<Node> xs = bx.statements, ys = by.statements;
            int[][] lcs = new int[xs.size() + 1][ys.size() + 1];
            for (int i = xs.size() - 1; i >= 0; i--) {
                for (int j = ys.size() - 1; j >= 0; j--) {
                    lcs[i][j] = key(xs.get(i)).equals(key(ys.get(j))) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            Ctx c = new Ctx(x, y, depth);
            List<Node> extra = new ArrayList<>();
            int i = 0, j = 0;
            while (i < xs.size() || j < ys.size()) {
                if (i < xs.size() && j < ys.size() && key(xs.get(i)).equals(key(ys.get(j)))) {
                    if (!match(xs.get(i), ys.get(j), c)) {
                        if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                            System.err.println("samecall   no match at statement " + i + ": " + key(xs.get(i)));
                        }
                        return null;
                    }
                    i++;
                    j++;
                } else if (j < ys.size() && (i == xs.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    // A statement only the new body has: a local it keeps (a value it reads again later) and nothing else.
                    if (!"store".equals(ys.get(j).value)) {
                        return null;
                    }
                    extra.add(ys.get(j++));
                } else {
                    return null;
                }
            }
            String[] ty = operands(y);
            if (!WHY.isEmpty() && x.sig().contains(WHY)) {
                System.err.println("samecall   read " + c.read);
            }
            List<String> out = new ArrayList<>();
            for (int k = 0; k < ty.length; k++) {
                Set<R> rs = c.read.get(k);
                if (k == 0) {
                    if (rs != null && !rs.stream().allMatch(r -> r.text().equals("$0"))) {
                        return null;
                    }
                    out.add("$0");
                    continue;
                }
                if (rs == null) {
                    int kk = k;
                    if (extra.stream().anyMatch(n -> reads(n, y, kk))) {
                        return null;
                    }
                    out.add(zero(ty[k]));
                    continue;
                }
                if (rs.size() != 1) {
                    return null;
                }
                String v = fit(rs.iterator().next(), ty[k]);
                if (v == null) {
                    return null;
                }
                out.add(v);
            }
            return out;
        }

        boolean reads(Node n, Meth m, int k) {
            if (n.op == 'p' && operand(m, n.value) == k) {
                return true;
            }
            for (Node kid : n.kids) {
                if (reads(kid, m, k)) {
                    return true;
                }
            }
            return false;
        }

        static String key(Node n) {
            Node last = n.kids.length == 0 ? null : n.kids[n.kids.length - 1];
            return n.op + "|" + n.value + "|" + (n.ref == null ? "" : n.ref.name()) + "|" + (last == null || last.ref == null ? "" : last.ref.name());
        }

        boolean match(Node xn, Node yn, Ctx c) {
            if (yn.op == 'p') {
                int j = operand(c.y, yn.value);
                if (j == 0 && xn.op == 'p' && operand(c.x, xn.value) == 0) {
                    return true; // the receiver, or a constructor's own this
                }
                R r = j < 0 ? null : c.ofX(xn);
                if (r == null) {
                    return false;
                }
                c.read(j, r);
                return true;
            }
            if (xn.op == 'k' && yn.op == 'x' && yn.kids.length == 1) {
                return match(xn, yn.kids[0], c); // a widened value where the old body had the constant
            }
            if (xn.op == 'k' && yn.op == 'o' && ("IADD".equals(yn.value) || "ISUB".equals(yn.value)) && yn.kids.length == 2 && xn.value instanceof Number k0
                && k0.doubleValue() == Math.rint(k0.doubleValue())) {
                Node a = yn.kids[0], b = yn.kids[1];
                boolean add = "IADD".equals(yn.value);
                Node p = a.op == 'p' && b.op == 'k' ? a : add && b.op == 'p' && a.op == 'k' ? b : null;
                Node k = p == a ? b : a;
                int j = p == null ? -1 : operand(c.y, p.value);
                if (j < 0 || !(k.value instanceof Integer kv)) {
                    return false;
                }
                long v = add ? k0.longValue() - kv : k0.longValue() + kv;
                if (v != (int) v) {
                    return false;
                }
                c.read(j, new R(Long.toString(v), "I", true));
                return true;
            }
            if (xn.op != yn.op || !Objects.equals(xn.value, yn.value)) {
                return false;
            }
            int same = xn.op == 'y' && xn.ref != null && yn.ref != null && xn.ref.name().equals(yn.ref.name())
                && h.clsBA(yn.ref.owner()).equals(xn.ref.owner()) ? 0 : same(xn.ref, yn.ref);
            if (same < 0) {
                return false;
            }
            // A lambda is the new code's own: only what it captures is compared, each value as its new type wants.
            if (same == 1 && xn.op != 'y') {
                return callee(xn, yn, c);
            }
            if (xn.kids.length != yn.kids.length) {
                return false;
            }
            for (int i = 0; i < xn.kids.length; i++) {
                if (!match(xn.kids[i], yn.kids[i], c)) {
                    return false;
                }
            }
            return true;
        }

        /** A call to a method that changed the same way: its parameters solved first, then read through its arguments. */
        boolean callee(Node xn, Node yn, Ctx c) {
            Meth cx = h.a.method(xn.ref.owner(), xn.ref.name(), xn.ref.desc()), cy = h.b.method(yn.ref.owner(), yn.ref.name(), yn.ref.desc());
            List<String> sub = cx == null || cy == null ? null : solve(cx, cy, c.depth + 1);
            if (sub == null) {
                return false;
            }
            // Kids are the callee's operands: a receiver first for an instance call or a constructor's own init.
            int shift = xn.op == 's' || xn.op == 'w' ? 1 : 0;
            int yShift = yn.op == 's' || yn.op == 'w' ? 1 : 0;
            for (int q = 0; q < yn.kids.length; q++) {
                String s = sub.get(q + yShift);
                Matcher one = Pattern.compile("\\$(\\d+)").matcher(s);
                if (s.matches("\\$\\d+")) {
                    int i = Integer.parseInt(s.substring(1)) - shift;
                    if (i < 0 || i >= xn.kids.length || !match(xn.kids[i], yn.kids[q], c)) {
                        return false;
                    }
                    continue;
                }
                Node yk = yn.kids[q];
                if (yk.op != 'p') {
                    return false;
                }
                StringBuilder text = new StringBuilder();
                while (one.find()) {
                    int i = Integer.parseInt(one.group(1)) - shift;
                    R r = i < 0 || i >= xn.kids.length ? null : c.ofX(xn.kids[i]);
                    if (r == null) {
                        return false;
                    }
                    one.appendReplacement(text, Matcher.quoteReplacement(r.text()));
                }
                one.appendTail(text);
                int j = operand(c.y, yk.value);
                if (j < 0) {
                    return false;
                }
                c.read(j, new R(text.toString(), operands(cy)[q + yShift], true));
            }
            return true;
        }

        /** 0 the same member, 1 a method the hop linked whose parameters changed, -1 another. */
        int same(Ref xr, Ref yr) {
            if (xr == null || yr == null) {
                return xr == yr ? 0 : -1;
            }
            if (xr.kind() != yr.kind()) {
                return -1;
            }
            if (xr.kind() == 'm') {
                Meth mx = h.a.method(xr.owner(), xr.name(), xr.desc()), my = h.b.method(yr.owner(), yr.name(), yr.desc());
                if (mx != null && my != null && h.mAB.get(mx) == my) {
                    return h.descBA(my.desc).equals(mx.desc) ? 0 : 1;
                }
            } else if (xr.kind() == 'f') {
                // The same field, retyped with the parameter it holds (TextureStateShard.blur, a boolean, is a TriState):
                // what is written there is compared, each value as its new type wants.
                Fld fx = h.a.field(xr.owner(), xr.name()), fy = h.b.field(yr.owner(), yr.name());
                if (fx != null && fy != null && h.fAB.get(fx) == fy) {
                    return 0;
                }
            }
            boolean named = h.clsBA(yr.owner()).equals(xr.owner()) && Objects.equals(xr.name(), yr.name());
            return named && (xr.desc() == null || h.descBA(yr.desc()).equals(xr.desc())) ? 0 : -1;
        }

        /** r as a value of type want (b's names): as it is, or made with want's constructor or one static factory. */
        String fit(R r, String want) {
            String have = r.type() == null ? null : r.inB() ? r.type() : Carry.descAB(h, r.type());
            if (have == null) {
                return want.startsWith("L") || want.startsWith("[") ? r.text() : null;
            }
            if (have.equals(want) || want.startsWith("L") && have.startsWith("L") && Exposure.assignable(h.b, dot(have), dot(want))) {
                return r.text();
            }
            if (want.startsWith("L") && have.startsWith("L") && Exposure.assignable(h.b, dot(want), dot(have))) {
                // The new method takes a narrower type of the same value (Level -> ServerLevel in 1.21.2's server-only
                // methods): every call the new game makes passes one, so the old value is that type where it is called.
                return "(" + dot(want) + ") " + r.text();
            }
            if (have.length() == 1 && want.length() == 1) {
                String order = "BSIJFD";
                return order.indexOf(have.charAt(0)) >= 0 && order.indexOf(want.charAt(0)) > order.indexOf(have.charAt(0)) ? r.text() : null;
            }
            Cls into = want.startsWith("L") ? h.b.classes.get(want.substring(1, want.length() - 1)) : null;
            if (into == null) {
                return null;
            }
            List<String> ways = new ArrayList<>();
            for (Meth m : into.methods.values()) {
                if ((m.flags & ACC_PUBLIC) == 0 || (m.flags & ACC_SYNTHETIC) != 0) {
                    continue;
                }
                List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
                if (ps.size() != 1 || !(ps.get(0).descriptorString().equals(have)
                    || have.startsWith("L") && ps.get(0).descriptorString().startsWith("L") && Exposure.assignable(h.b, dot(have), dot(ps.get(0).descriptorString())))) {
                    continue;
                }
                if (m.name.equals("<init>") && (into.flags & (ACC_INTERFACE | 0x0400)) == 0) {
                    ways.add("new " + Learner.dotted(into.name) + "(" + r.text() + ")");
                } else if (m.isStatic() && MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString().equals(want) && !m.name.startsWith("lambda$")) {
                    ways.add(Learner.dotted(into.name) + "." + m.name + "(" + r.text() + ")");
                }
            }
            return ways.size() == 1 ? ways.get(0) : null;
        }

        static String dot(String desc) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }

        static String zero(String type) {
            return switch (type) {
                case "Z" -> "false";
                case "J" -> "0L";
                case "F" -> "0.0F";
                case "D" -> "0.0D";
                case "B" -> "(byte) 0";
                case "S" -> "(short) 0";
                case "C" -> "(char) 0";
                case "I" -> "0";
                default -> "null";
            };
        }
    }

    /**
     * A replacement learned at one update of a chain, carried through each later update as a mod's own call is: every
     * class, method and field it names is followed to its counterpart, and a member a later update changed takes what
     * that update learned for it (its reading, or the call the game's code switched to). 1.21.5 learned
     * PoseStack.mulPose(Quaternionf) -> $0.mulPose($1), a Quaternionfc; 26.3 renamed that mulPose to rotate, so across both
     * it is $0.rotate($1). JarPort compiles a learned expression against the release it ports to, so one left in its own
     * update's names is refused on every longer jump. A name that cannot be followed (gone with nothing learned, or a call
     * it cannot tell from another) leaves the expression where it got to.
     */
    static final class Carry {

        /** Why an expression stops where it is. */
        static final class Stuck extends RuntimeException {

            Stuck(String why) {
                super(why, null, false, false);
            }
        }

        /** An expression in b's names and its type, a descriptor in a's names ("?" a method reference, null the null literal). */
        record Typed(String text, String type) {}

        final Hop h;
        final Learner learner;
        final Map<String, FixExpr.Node> readings = new HashMap<>(); // Meth.sig(), or owner.name of a field, in a -> what h learned
        final Set<String> unclear = new HashSet<>(); // members h learned two readings for
        final Map<String, Meth> calls = new HashMap<>(); // Meth.sig() in a -> the call h's code switched to

        Carry(Hop h, Learner learner, List<Learner.Learned> learned, List<Hop.Rewrite> rewrites) {
            this.h = h;
            this.learner = learner;
            for (Learner.Learned l : learned) {
                if (l.votes() * 2 <= l.of() || l.expr().isEmpty() || l.name().endsWith("=")) {
                    continue;
                }
                String key = l.owner().replace('.', '/') + "." + l.name() + (l.field() ? "" : l.desc());
                if (unclear.contains(key) || readings.remove(key) != null) {
                    unclear.add(key);
                    continue;
                }
                try {
                    readings.put(key, FixExpr.parse(l.expr()));
                } catch (IllegalArgumentException unreadable) {
                    unclear.add(key);
                }
            }
            for (Hop.Rewrite r : rewrites) {
                if (r.to() != null && r.votes() * 2 > r.total()) {
                    calls.putIfAbsent(r.from().sig(), r.to());
                }
            }
        }

        /** expr, over operands of the types given ($0 the receiver; a's names), in b's names; null where it cannot be followed. */
        String carry(String expr, String[] types) {
            try {
                return e(FixExpr.parse(expr), types).text();
            } catch (Stuck | IllegalArgumentException s) {
                if (verbose) {
                    System.err.println("  carry " + h.a.version + " -> " + h.b.version + ": " + expr + " stops: " + s.getMessage());
                }
                return null;
            }
        }

        /** The operands' types of what l replaces ($0 its receiver, null where there is none), in b's names; null if unknown. */
        static String[] operands(Hop h, Learner.Learned l) {
            String owner = l.owner().replace('.', '/');
            boolean write = l.name().endsWith("=");
            String name = write ? l.name().substring(0, l.name().length() - 1) : l.name();
            List<String> out = new ArrayList<>();
            if (l.field()) {
                Fld f = h.a.field(owner, name);
                if (f == null) {
                    return null;
                }
                out.add((f.flags & ACC_STATIC) != 0 ? null : "L" + owner + ";");
                if (write) {
                    out.add(f.desc);
                }
            } else {
                Meth m = h.a.method(owner, name, l.desc());
                if (m == null) {
                    return null;
                }
                out.add(m.isStatic() || m.special() ? null : "L" + owner + ";");
                for (ClassDesc p : MethodTypeDesc.ofDescriptor(l.desc()).parameterList()) {
                    out.add(p.descriptorString());
                }
            }
            String[] types = out.toArray(String[]::new);
            for (int i = 0; i < types.length; i++) {
                types[i] = types[i] == null ? null : descAB(h, types[i]);
            }
            return types;
        }

        /** A descriptor of a in b's names; a class b dropped keeps its name. */
        static String descAB(Hop h, String d) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length();) {
                char ch = d.charAt(i);
                if (ch == 'L') {
                    int semi = d.indexOf(';', i);
                    String n = d.substring(i + 1, semi);
                    Cls c = h.a.classes.get(n);
                    Cls y = c == null ? null : h.cAB.get(c);
                    sb.append('L').append(y == null ? n : y.name).append(';');
                    i = semi + 1;
                } else {
                    sb.append(ch);
                    i++;
                }
            }
            return sb.toString();
        }

        Typed e(FixExpr.Node n, String[] types) {
            return switch (n) {
                case FixExpr.Arg x -> {
                    if (x.index() >= types.length || types[x.index()] == null) {
                        throw new Stuck("$" + x.index() + " is no operand");
                    }
                    yield new Typed("$" + x.index(), types[x.index()]);
                }
                case FixExpr.Lit l -> new Typed(literal(l), l.type());
                case FixExpr.Cast c -> {
                    Typed t = e(c.expr(), types);
                    String p = primitiveDesc(c.type());
                    if (p != null) {
                        yield new Typed("(" + c.type() + ") " + t.text(), p);
                    }
                    String owner = c.type().replace('.', '/');
                    yield new Typed("(" + Learner.dotted(mapped(owner)) + ") " + t.text(), "L" + owner + ";");
                }
                case FixExpr.Path p -> path(p.dotted());
                case FixExpr.Get g -> {
                    Typed t = e(g.target(), types);
                    yield field(objectType(t), g.name(), false, t.text());
                }
                case FixExpr.Invoke i -> {
                    boolean isStatic = i.target() instanceof FixExpr.Path p && known(p.dotted().replace('.', '/'));
                    String owner, recv = null;
                    if (isStatic) {
                        owner = ((FixExpr.Path) i.target()).dotted().replace('.', '/');
                    } else {
                        Typed t = e(i.target(), types);
                        owner = objectType(t);
                        recv = t.text();
                    }
                    List<Typed> args = new ArrayList<>();
                    for (FixExpr.Node a : i.args()) {
                        args.add(e(a, types));
                    }
                    yield invoke(owner, i.name(), isStatic, recv, args);
                }
                case FixExpr.New w -> {
                    String owner = w.type().replace('.', '/');
                    List<Typed> args = new ArrayList<>();
                    for (FixExpr.Node a : w.args()) {
                        args.add(e(a, types));
                    }
                    if (cls(owner) == null) {
                        if (jdk(owner) == null) {
                            throw new Stuck("no class " + owner);
                        }
                        yield new Typed("new " + w.type() + "(" + texts(args) + ")", "L" + owner + ";");
                    }
                    Meth m = choose(methods(owner, "<init>"), args, false, owner + ".<init>");
                    if (learner.broken(new Ref('m', m.owner.name, m.name, m.desc))) {
                        yield new Typed(instead(m, null, args), "L" + owner + ";");
                    }
                    yield new Typed("new " + Learner.dotted(mapped(owner)) + "(" + texts(args) + ")", "L" + owner + ";");
                }
                case FixExpr.Ref r -> {
                    String owner = r.type().replace('.', '/');
                    if (cls(owner) == null) {
                        if (jdk(owner) == null) {
                            throw new Stuck("no class " + owner);
                        }
                        yield new Typed(r.type() + "::" + r.name(), "?");
                    }
                    Set<String> now = new HashSet<>();
                    for (Meth m : methods(owner, r.name())) {
                        if (!m.isStatic()) {
                            continue;
                        }
                        if (learner.broken(new Ref('m', m.owner.name, m.name, m.desc))) {
                            throw new Stuck("changed " + m.sig());
                        }
                        Meth y = h.mAB.get(m);
                        now.add(y == null ? Learner.dotted(mapped(m.owner.name)) + "::" + m.name : Learner.dotted(y.owner.name) + "::" + y.name);
                    }
                    if (now.size() != 1) {
                        throw new Stuck("no one static " + owner + "::" + r.name());
                    }
                    yield new Typed(now.iterator().next(), "?");
                }
            };
        }

        /** a.b.C.F.G: the longest start that is a class, its static field F, and G of that. */
        Typed path(String dotted) {
            String[] parts = dotted.split("\\.");
            for (int i = parts.length - 1; i >= 1; i--) {
                String owner = String.join("/", Arrays.copyOf(parts, i));
                if (!known(owner)) {
                    continue;
                }
                Typed t = field(owner, parts[i], true, null);
                for (int k = i + 1; k < parts.length; k++) {
                    t = field(objectType(t), parts[k], false, t.text());
                }
                return t;
            }
            throw new Stuck("no field " + dotted);
        }

        Typed field(String owner, String name, boolean isStatic, String recv) {
            if (cls(owner) == null) {
                Class<?> k = jdk(owner);
                try {
                    java.lang.reflect.Field f = k == null ? null : k.getField(name);
                    if (f == null || java.lang.reflect.Modifier.isStatic(f.getModifiers()) != isStatic) {
                        throw new Stuck("no field " + owner + "." + name);
                    }
                    return new Typed((isStatic ? Learner.dotted(owner) : Learner.receiver(recv)) + "." + name, f.getType().descriptorString());
                } catch (NoSuchFieldException absent) {
                    throw new Stuck("no field " + owner + "." + name);
                }
            }
            Fld f = null;
            for (String c : supers(owner)) {
                Cls k = cls(c);
                f = k == null ? null : k.fields.get(name);
                if (f != null) {
                    break;
                }
            }
            if (f == null || ((f.flags & ACC_STATIC) != 0) != isStatic) {
                throw new Stuck("no field " + owner + "." + name);
            }
            if (learner.broken(new Ref('f', f.owner.name, f.name, f.desc))) {
                FixExpr.Node read = readings.get(f.owner.name + "." + f.name);
                if (read == null) {
                    throw new Stuck("changed " + f.owner.name + "." + f.name);
                }
                return new Typed(print(read, Collections.singletonList(recv)), f.desc);
            }
            Fld y = h.fAB.get(f);
            String head = isStatic ? Learner.dotted(y == null ? mapped(f.owner.name) : y.owner.name) : Learner.receiver(recv);
            return new Typed(head + "." + (y == null ? f.name : y.name), f.desc);
        }

        Typed invoke(String owner, String name, boolean isStatic, String recv, List<Typed> args) {
            List<Meth> found = methods(owner, name);
            if (found.isEmpty()) {
                // A method of the JDK: the class itself, or one a class of a extends or implements (AutoCloseable.close).
                Stuck first = null;
                for (String c : supers(owner)) {
                    Class<?> k = cls(c) == null ? jdk(c) : null;
                    if (k != null) {
                        try {
                            return jdkCall(k, c, name, isStatic, recv, args);
                        } catch (Stuck s) {
                            first = first == null ? s : first;
                        }
                    }
                }
                throw first != null ? first : new Stuck("no " + owner + "." + name);
            }
            Meth m = choose(found, args, isStatic, owner + "." + name);
            String ret = MethodTypeDesc.ofDescriptor(m.desc).returnType().descriptorString();
            if (learner.broken(new Ref('m', m.owner.name, m.name, m.desc))) {
                return new Typed(instead(m, recv, args), ret);
            }
            Meth y = h.mAB.get(m);
            String head = isStatic ? Learner.dotted(y == null ? mapped(m.owner.name) : y.owner.name) : Learner.receiver(recv);
            return new Typed(head + "." + (y == null ? m.name : y.name) + "(" + texts(args) + ")", ret);
        }

        /** What h's code computes in place of a member it changed: its learned reading, or the call it switched to. */
        String instead(Meth m, String recv, List<Typed> args) {
            FixExpr.Node read = readings.get(m.sig());
            if (read != null) {
                List<String> ops = new ArrayList<>();
                ops.add(recv);
                args.forEach(t -> ops.add(t.text()));
                return print(read, ops);
            }
            Meth to = calls.get(m.sig());
            if (to != null && !m.special() && to.isStatic() == m.isStatic()
                && MethodTypeDesc.ofDescriptor(to.desc).parameterCount() == args.size()) {
                return (to.isStatic() ? Learner.dotted(to.owner.name) : Learner.receiver(recv)) + "." + to.name + "(" + texts(args) + ")";
            }
            throw new Stuck("changed " + m.sig());
        }

        Typed jdkCall(Class<?> k, String owner, String name, boolean isStatic, String recv, List<Typed> args) {
            Set<String> returns = new HashSet<>();
            for (java.lang.reflect.Method m : k.getMethods()) {
                if (m.getName().equals(name) && !m.isBridge() && m.getParameterCount() == args.size()
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers()) == isStatic) {
                    returns.add(m.getReturnType().descriptorString());
                }
            }
            if (returns.size() != 1) {
                throw new Stuck("no one " + owner + "." + name);
            }
            String head = isStatic ? Learner.dotted(owner) : Learner.receiver(recv);
            return new Typed(head + "." + name + "(" + texts(args) + ")", returns.iterator().next());
        }

        /** The one method a call with these arguments reaches, preferring one whose parameters are exactly theirs. */
        Meth choose(List<Meth> found, List<Typed> args, boolean isStatic, String what) {
            List<Meth> fit = new ArrayList<>(), exact = new ArrayList<>();
            for (Meth m : found) {
                if ((m.flags & ACC_SYNTHETIC) != 0 || !m.special() && m.isStatic() != isStatic) {
                    continue;
                }
                List<ClassDesc> ps = MethodTypeDesc.ofDescriptor(m.desc).parameterList();
                if (ps.size() != args.size()) {
                    continue;
                }
                boolean fits = true, same = true;
                for (int i = 0; i < ps.size() && fits; i++) {
                    String p = ps.get(i).descriptorString(), t = args.get(i).type();
                    fits = fits(t, p);
                    same &= p.equals(t);
                }
                if (fits) {
                    fit.add(m);
                }
                if (fits && same) {
                    exact.add(m);
                }
            }
            if (fit.size() == 1) {
                return fit.get(0);
            }
            if (exact.size() == 1) {
                return exact.get(0);
            }
            throw new Stuck((fit.isEmpty() ? "no " : "more than one ") + what + " for " + args.stream().map(Typed::type).toList());
        }

        boolean fits(String arg, String param) {
            if (arg == null) {
                return param.startsWith("L") || param.startsWith("[");
            }
            if (arg.equals(param) || "?".equals(arg) && param.startsWith("L")) {
                return true;
            }
            if (arg.length() == 1 || param.length() == 1) {
                String order = "BSIJFD";
                int x = order.indexOf(arg.charAt(0)), y = order.indexOf(param.charAt(0));
                return arg.length() == 1 && param.length() == 1 && x >= 0 && y >= x || "C".equals(arg) && "IJFD".contains(param);
            }
            if (param.equals("Ljava/lang/Object;")) {
                return true;
            }
            return arg.startsWith("L") && param.startsWith("L") && isA(arg.substring(1, arg.length() - 1), param.substring(1, param.length() - 1));
        }

        boolean isA(String sub, String sup) {
            for (String c : supers(sub)) {
                if (c.equals(sup)) {
                    return true;
                }
                Class<?> k = cls(c) == null ? jdk(c) : null;
                Class<?> s = jdk(sup);
                if (k != null && s != null && s.isAssignableFrom(k)) {
                    return true;
                }
            }
            return false;
        }

        /** owner and every class and interface above it that a or its libraries declare, then the first one they do not. */
        List<String> supers(String owner) {
            List<String> out = new ArrayList<>();
            Deque<String> todo = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (!seen.add(c)) {
                    continue;
                }
                out.add(c);
                Cls k = cls(c);
                if (k != null) {
                    if (k.sup != null) {
                        todo.add(k.sup);
                    }
                    todo.addAll(k.ifaces);
                }
            }
            return out;
        }

        /** Every method named name a call on owner reaches in a or its libraries, most derived first. */
        List<Meth> methods(String owner, String name) {
            List<Meth> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String c : name.equals("<init>") ? List.of(owner) : supers(owner)) {
                Cls k = cls(c);
                if (k == null) {
                    continue;
                }
                for (Meth m : k.methods.values()) {
                    if (m.name.equals(name) && seen.add(m.desc)) {
                        out.add(m);
                    }
                }
            }
            return out;
        }

        Cls cls(String internal) {
            Cls c = h.a.classes.get(internal);
            return c != null ? c : h.a.libraries.get(internal);
        }

        boolean known(String internal) {
            return cls(internal) != null || jdk(internal) != null;
        }

        /** A class in b's names: its counterpart, or the same library or JDK class. */
        String mapped(String internal) {
            Cls c = h.a.classes.get(internal);
            if (c == null || c.library) {
                if (h.a.libraries.containsKey(internal) && h.b.libraries.containsKey(internal) || c != null && h.b.classes.containsKey(internal) || jdk(internal) != null) {
                    return internal;
                }
                throw new Stuck("no class " + internal + " in " + h.b.version);
            }
            Cls y = h.cAB.get(c);
            if (y == null) {
                throw new Stuck("class " + internal + " gone");
            }
            return y.name;
        }

        static String objectType(Typed t) {
            if (t.type() == null || !t.type().startsWith("L")) {
                throw new Stuck("no members on " + t.type());
            }
            return t.type().substring(1, t.type().length() - 1);
        }

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

        static String primitiveDesc(String keyword) {
            return switch (keyword) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> null;
            };
        }

        static String texts(List<Typed> args) {
            StringJoiner out = new StringJoiner(", ");
            args.forEach(t -> out.add(t.text()));
            return out.toString();
        }

        /** A reading of b (FixExpr) written out with its operands ($0 the receiver) as the texts given. */
        static String print(FixExpr.Node n, List<String> ops) {
            return switch (n) {
                case FixExpr.Arg x -> {
                    if (x.index() >= ops.size() || ops.get(x.index()) == null) {
                        throw new Stuck("$" + x.index() + " has no operand");
                    }
                    yield ops.get(x.index());
                }
                case FixExpr.Lit l -> literal(l);
                case FixExpr.Path p -> p.dotted();
                case FixExpr.Get g -> Learner.receiver(print(g.target(), ops)) + "." + g.name();
                case FixExpr.Invoke i -> {
                    StringJoiner args = new StringJoiner(", ");
                    i.args().forEach(a -> args.add(print(a, ops)));
                    yield Learner.receiver(print(i.target(), ops)) + "." + i.name() + "(" + args + ")";
                }
                case FixExpr.New w -> {
                    StringJoiner args = new StringJoiner(", ");
                    w.args().forEach(a -> args.add(print(a, ops)));
                    yield "new " + w.type() + "(" + args + ")";
                }
                case FixExpr.Cast c -> "(" + c.type() + ") " + print(c.expr(), ops);
                case FixExpr.Ref r -> r.type() + "::" + r.name();
            };
        }

        static String literal(FixExpr.Lit l) {
            Object v = l.value();
            if (l.type() == null) {
                return "null";
            }
            return switch (l.type()) {
                case "Ljava/lang/String;" -> "\"" + ((String) v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                case "J" -> v + "L";
                case "F" -> v + "F";
                case "D" -> v + "D";
                default -> String.valueOf(v);
            };
        }
    }

    static final class Driver implements VersionMap.Chain.Loader {

        final List<String> versions;
        final String loader;
        final List<String> pinned;
        final Map<String, String> loaderUsed = new LinkedHashMap<>();
        final List<String> loaderNotes = new ArrayList<>();
        final List<String> hops = new ArrayList<>();
        /** Call-site rewrites of every hop (Hop.rewrites), each target carried to the newest release. */
        final List<Switched> rewrites = new ArrayList<>();
        /** Removed classes and the class the game's code uses in their place (Hop.classRewrites). */
        final List<String> classRewrites = new ArrayList<>();
        /** Members the game replaced with a chain on the same receiver (Hop.chains). */
        final List<String> chains = new ArrayList<>();
        /** Methods moved to a class one holder keeps beside the old one (Hop.siblings). */
        final List<String> siblings = new ArrayList<>();
        /** Parameters the game added with one value its own code always passes (Hop.argDefaults). */
        final List<String> argDefaults = new ArrayList<>();
        /** Replacements written as expressions over the old member's operands (Learner), applied like a rule's inClass. */
        final List<Carried> learned = new ArrayList<>();
        /** The chain's first release: each member's {owner, name, desc} by id, and each class's id by name. */
        final Map<String, String[]> firstMembers = new HashMap<>();
        final Map<String, String> firstClasses = new HashMap<>();

        /**
         * The member l replaces as the chain's first release names it, where that release had it under another name or
         * owner with the same operands (each class in the descriptor the same class, by id); null otherwise.
         */
        String[] first(Hop h, Learner.Learned l) {
            String owner = l.owner().replace('.', '/');
            String name = l.name().endsWith("=") ? l.name().substring(0, l.name().length() - 1) : l.name();
            String id, desc;
            if (l.field()) {
                Fld f = h.a.field(owner, name);
                id = f == null ? null : f.id;
                desc = f == null ? null : f.desc;
            } else {
                Meth m = h.a.method(owner, name, l.desc());
                id = m == null ? null : m.id;
                desc = l.desc();
            }
            String[] was = id == null ? null : firstMembers.get(id);
            if (was == null || was[0].equals(owner) && was[1].equals(name) && was[2].equals(desc)) {
                return null;
            }
            Pattern part = Pattern.compile("\\[*(?:L([^;]+);|[ZBCSIJFDV])");
            Matcher x = part.matcher(was[2]), y = part.matcher(desc);
            while (x.find()) {
                if (!y.find() || (x.group(1) == null) != (y.group(1) == null)) {
                    return null;
                }
                if (x.group(1) == null ? !x.group().equals(y.group()) : !sameClass(h, x.group(1), y.group(1))) {
                    return null;
                }
            }
            return y.find() ? null : was;
        }

        boolean sameClass(Hop h, String inFirst, String now) {
            Cls c = h.a.classes.get(now);
            String id = firstClasses.get(inFirst);
            return c == null || id == null ? inFirst.equals(now) : id.equals(c.id);
        }

        /** A line of the route map's "learned" list, its expression carried to the newest release as the chain goes on (Carry). */
        static final class Carried {

            final String step;
            final Learner.Learned learned; // null: a line that is not carried (a chain of two members)
            final String fixed;
            String expr;
            String[] types; // its operands' types ($0 the receiver) in the newest release's names; null once it stops
            String[] first; // {owner, name, desc} of the member in the chain's first release, where that names it otherwise

            Carried(String step, Learner.Learned learned, String fixed, String[] types) {
                this.step = step;
                this.learned = learned;
                this.fixed = fixed;
                this.types = types;
                expr = learned == null ? null : learned.expr();
            }

            void carry(Carry c) {
                if (learned == null || types == null || expr.isEmpty()) {
                    return;
                }
                String next = c.carry(expr, types);
                if (next == null) {
                    types = null;
                    return;
                }
                expr = next;
                for (int i = 0; i < types.length; i++) {
                    types[i] = types[i] == null ? null : Carry.descAB(c.h, types[i]);
                }
            }

            String json() {
                if (learned == null) {
                    return fixed;
                }
                Learner.Learned l = learned;
                // Filed under the name a mod of the chain's first release calls (1.21.9 learned packVersion(type) ->
                // $0.packVersion($1).major(); a 1.21.1 mod calls it getPackVersion, renamed at 1.21.5).
                String owner = first == null ? l.owner() : first[0].replace('/', '.');
                String name = first == null ? l.name() : first[1] + (l.name().endsWith("=") ? "=" : "");
                String desc = first == null || l.field() ? l.desc() : first[2];
                String line = new Learner.Learned(owner, name, desc, l.field(), l.linked(), expr, l.votes(), l.of()).json(step);
                if (first != null) {
                    line = line.substring(0, line.length() - 2) + ", \"learnedFor\": " + VersionMap.Diff.q(l.owner() + "." + l.name() + l.desc()) + " }";
                }
                // What the step itself learned, where later updates renamed what it names.
                return expr.equals(l.expr()) ? line : line.substring(0, line.length() - 2) + ", \"learnedAs\": " + VersionMap.Diff.q(l.expr()) + " }";
            }
        }

        /** A call the game's code switched to at one update (Hop.Rewrite), its target followed to the newest release. */
        static final class Switched {

            final String step, owner, from, desc; // strings, not the Meth: an old release is not kept alive
            final boolean isStatic;
            final int votes, total;
            String toOwner, to, newDesc;
            boolean newStatic, stopped;

            Switched(String step, Hop.Rewrite r) {
                this.step = step;
                owner = r.from().owner.name;
                from = r.from().name;
                desc = r.from().desc;
                isStatic = r.from().isStatic();
                votes = r.votes();
                total = r.total();
                if (r.to() != null) {
                    toOwner = r.to().owner.name;
                    to = r.to().name;
                    newDesc = r.to().desc;
                    newStatic = r.to().isStatic();
                }
            }

            /** The target in b's names: its counterpart, or the call h's code switched it to; it stays where it is if neither. */
            void carry(Carry c) {
                if (to == null || stopped) {
                    return;
                }
                Meth m = c.h.a.method(toOwner, to, newDesc);
                m = m != null ? m : c.h.a.libraryMethod(toOwner, to, newDesc);
                if (m == null) {
                    stopped = true;
                    return;
                }
                Meth y = c.learner.broken(new Ref('m', m.owner.name, m.name, m.desc)) ? c.calls.get(m.sig()) : c.h.mAB.getOrDefault(m, m);
                if (y == null || y.isStatic() != m.isStatic()) {
                    stopped = true;
                    return;
                }
                toOwner = y.owner.name;
                to = y.name;
                newDesc = y.owner.library || y == m ? newDesc : y.desc;
            }

            String json() {
                return "{ \"step\": " + VersionMap.Diff.q(step) +
                    ", \"owner\": " + VersionMap.Diff.q(owner.replace('/', '.')) +
                    ", \"from\": " + VersionMap.Diff.q(from) +
                    ", \"desc\": " + VersionMap.Diff.q(desc) +
                    ", \"static\": " + isStatic +
                    // A call the game deleted has no replacement: an empty "to".
                    ", \"toOwner\": " + VersionMap.Diff.q(to == null ? "" : toOwner.replace('/', '.')) +
                    ", \"to\": " + VersionMap.Diff.q(to == null ? "" : to) +
                    ", \"newDesc\": " + VersionMap.Diff.q(to == null ? "" : newDesc) +
                    ", \"newStatic\": " + (to != null && newStatic) +
                    ", \"votes\": " + votes + ", \"of\": " + total + " }";
            }
        }
        /** Calls on a parameter the game retyped, written over the new type (Retyped). */
        final List<String> retyped = new ArrayList<>();
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

        List<byte[]> prevPlatform;

        @Override
        public VersionMap.Table load(String version) throws Exception {
            Path jar = null;
            String lv = null;
            if (loader != null && (pinned == null || "fabric".equals(loader))) {
                // The loader as a mod runs on it, the classes the link check reads (Exposure.snap): NeoForge's universal jar
                // with FML and its other net.neoforged libraries, or Fabric Loader with Fabric API's modules, in Mojang
                // names. Its own code uses its own API, so a change to it is learned like the game's.
                Exposure.Platform p = "fabric".equals(loader) ? Exposure.fabric(version) : Exposure.neoforge(version);
                List<byte[]> classes = p.classes();
                if (!classes.isEmpty()) {
                    prevPlatform = classes;
                    prevLoaderVersion = p.version();
                    loaderUsed.put(version, p.version() == null ? loader : p.version());
                } else if (prevPlatform != null) {
                    classes = prevPlatform;
                    loaderNotes.add(version + ": no " + loader + " release; carried " + prevLoaderVersion);
                    loaderUsed.put(version, null);
                } else {
                    loaderNotes.add(version + ": no " + loader + " release; loader classes start at a later release");
                    loaderUsed.put(version, null);
                }
                return next(GameDiff.load(version, classes, p.version()), null, p.version());
            }
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
                    firstClasses.put(c.name, c.id);
                    for (Meth m : c.methods.values()) {
                        firstMembers.put(m.id, new String[] { c.name, m.name, m.desc });
                    }
                    for (Fld f : c.fields.values()) {
                        firstMembers.put(f.id, new String[] { c.name, f.name, f.desc });
                    }
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
                String step = prev.version + " -> " + s.version;
                List<Hop.Rewrite> found = h.rewrites();
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
                Learner learner = new Learner(h);
                List<Learner.Learned> fresh = learner.learn();
                fresh.addAll(new SameCall(h, learner).learn(fresh));
                // What earlier updates learned, carried through this one before this one's own is added.
                Carry carry = new Carry(h, learner, fresh, found);
                for (Carried c : learned) {
                    c.carry(carry);
                }
                for (Switched w : rewrites) {
                    w.carry(carry);
                }
                for (Hop.Rewrite r : found) {
                    rewrites.add(new Switched(step, r));
                }
                for (Learner.Learned l : fresh) {
                    Carried c = new Carried(step, l, null, Carry.operands(h, l));
                    c.first = first(h, l);
                    learned.add(c);
                }
                for (Learner.Chained c : learner.chains) {
                    learned.add(new Carried(step, null, c.json(step), null));
                }
                for (Retyped.Reading r : new Retyped(h, learner).learn()) {
                    retyped.add(r.json(prev.version + " -> " + s.version));
                }
                List<Hop.ClassRewrite> rewrites = new ArrayList<>(h.classRewrites());
                rewrites.addAll(learner.typeRewrites);
                for (Hop.ClassRewrite r : rewrites) {
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
                head.append(i == 0 ? "\n    " : ",\n    ").append(rewrites.get(i).json());
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
            head.append("  \"learned\": [");
            for (int i = 0; i < learned.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(learned.get(i).json());
            }
            head.append(learned.isEmpty() ? "],\n" : "\n  ],\n");
            head.append("  \"retyped\": [");
            for (int i = 0; i < retyped.size(); i++) {
                head.append(i == 0 ? "\n    " : ",\n    ").append(retyped.get(i));
            }
            head.append(retyped.isEmpty() ? "],\n" : "\n  ],\n");
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
            Snap a = load(va, (Path) null, null);
            Snap b = load(vb, (Path) null, null);
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
