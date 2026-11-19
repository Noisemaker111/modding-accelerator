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
 *   java JarPort --from 1.21.1 --to 1.21.2 --map route.json [--loader neoforge] --out OUT.jar|DIR [--report R.json] IN.jar ...
 *
 * The report lists per jar what was rewritten and every reference that does not link in the new release ("unlinked");
 * a jar with no unlinked reference is expected to load. Exit 0 when every jar was written, whatever it reports.
 */
public final class JarPort {

    final GameDiff.Snap a, b;
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

        void change(String kind) {
            changed.merge(kind, 1, Integer::sum);
        }
    }

    /** a and b are the old and the new release (with their loader); map is GameDiff's route map between them. */
    JarPort(GameDiff.Snap a, GameDiff.Snap b, Path map) throws IOException {
        this.a = a;
        this.b = b;
        to = b.version;
        loaderTo = b.loaderVersion;
        route = Exposure.Route.read(map);
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
            if (Json.text(w, "to", "").isEmpty() && Json.text(w, "toOwner", "").isEmpty()) {
                String key = Exposure.Route.internal(Json.text(w, "owner", "")) + "." + Json.text(w, "from", "") + Json.text(w, "desc", "");
                removals.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String from = null, to = null, map = null, out = null, loader = null, report = null;
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                case "--map" -> map = args[++i];
                case "--out" -> out = args[++i];
                case "--loader" -> loader = args[++i];
                case "--report" -> report = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (from == null || to == null || map == null || out == null || jars.isEmpty()) {
            System.err.println("usage: JarPort --from V --to V --map route.json [--loader neoforge] --out OUT.jar|DIR [--report R.json] IN.jar ...");
            System.exit(2);
        }
        JarPort port = new JarPort(Exposure.snap(from, loader), Exposure.snap(to, loader), Path.of(map));
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
        return port(jar, name, results, null);
    }

    /**
     * grants: what every access transformer in the outermost jar and its nested jars opens (null: read it here). The loader
     * applies an access transformer to the game for every mod, so a nested helper may rely on its parent's.
     */
    byte[] port(byte[] jar, String name, List<Map<String, Object>> results, Set<String> grants) throws IOException {
        Map<String, Long> times = new HashMap<>();
        Map<String, byte[]> entries = entries(jar, name, times);
        Set<String> nested = nested(entries);
        if (grants == null) {
            grants = new HashSet<>();
            for (String text : accessTransformers(entries, name)) {
                widen(grants, text);
            }
        }
        Mod mod = new Mod();
        mod.widened.addAll(grants);
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/versions/")) {
                read(mod, e.getValue());
            }
        }
        plan(mod);
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
                } else if (nested.contains(n) || n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    data = port(data, name + "!" + n.substring(n.lastIndexOf('/') + 1), results, grants);
                } else if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                    data = rewrite(mod, data);
                } else if (n.equals("META-INF/accesstransformer.cfg")) {
                    data = accessTransformer(mod, new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
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

    /** Jar-in-jar: nested jars are where META-INF/jarjar/metadata.json says ("path", often META-INF/jars/), or in META-INF/jarjar/. */
    static Set<String> nested(Map<String, byte[]> entries) {
        Set<String> nested = new HashSet<>();
        byte[] list = entries.get("META-INF/jarjar/metadata.json");
        if (list != null) {
            for (Object j : Json.array(Json.object(Json.parse(new String(list, StandardCharsets.UTF_8))).getOrDefault("jars", List.of()))) {
                nested.add(Json.text(Json.object(j), "path", ""));
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
        byte[] at = entries.get("META-INF/accesstransformer.cfg");
        if (at != null) {
            out.add(new String(at, StandardCharsets.UTF_8));
        }
        for (String n : nested(entries)) {
            out.addAll(accessTransformers(entries(entries.get(n), name + "!" + n, new HashMap<>()), name + "!" + n));
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
     * renames, or parameters that became supertypes with the same return. Anything else (an added, removed or narrowed
     * parameter, another return type) does not link here; dropped arguments are handled at the call site.
     */
    String[] target(Mod mod, List<String> callers, String siteOwner, char kind, String name, String desc, Decl decl) {
        String newSite = cls(siteOwner);
        if (kind == 'f') {
            String[] moved = route.field(decl.owner(), name);
            String fieldDesc = desc(desc);
            for (String[] t : List.of(new String[] { newSite, name }, new String[] { newSite, moved[1] }, new String[] { moved[0], moved[1] })) {
                FieldHit found = fieldNew(mod, callers, t[0], t[1]);
                if (found != null && found.desc().equals(fieldDesc) && found.open()) {
                    return new String[] { t[0], t[1], fieldDesc };
                }
                if (found != null) {
                    return null; // there with another type, or closed to the mod: a getfield of it fails to link
                }
            }
            return null;
        }
        String[] moved = route.method(decl.owner(), name, desc);
        String mapped = desc(desc);
        if (linksNew(mod, callers, newSite, 'm', name, mapped)) {
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

    boolean widens(String from, String to) {
        Type[] before = Type.getArgumentTypes(from), after = Type.getArgumentTypes(to);
        if (before.length != after.length || !Type.getReturnType(from).equals(Type.getReturnType(to))) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (!before[i].equals(after[i]) && !Exposure.assignable(b, before[i].getClassName(), after[i].getClassName())) {
                return false;
            }
        }
        return true;
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
        // The game no longer calls it: the class still loads, the hook is silent.
        mod.silent.add("override no longer called: " + Exposure.dotted(decl.owner()) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
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
                if ((m.flags & Opcodes.ACC_ABSTRACT) != 0 && !written.contains(m.name + m.desc) && !OBJECT.contains(m.name + m.desc)) {
                    mod.unlinked.add("abstract method not written: " + Exposure.dotted(k.name) + "." + m.name + m.desc + " (in " + Exposure.dotted(rc.name) + ")");
                }
            }
        }
    }

    static final Set<String> OBJECT = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;");

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
                targets = mod.mixinTargets.getOrDefault(name, List.of());
                // A mixin's code runs inside each target class, so access is checked from there.
                callers = targets.isEmpty() ? List.of(name) : targets.stream().map(JarPort.this::cls).toList();
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
                        return mixinStrings(mod, targets, super.visitAnnotation(d, visible));
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
                MethodVisitor mv = super.visitMethod(access, newName, newDesc, signature, exceptions);
                String key = self[0] + "." + name + desc;
                int locals = mod.maxLocals.getOrDefault(key, 0);
                return mv == null ? null : new Sites(mod, mv, locals, targets, callers, key, where);
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
    AnnotationVisitor mixinStrings(Mod mod, List<String> targets, AnnotationVisitor av) {
        if (av == null || targets.isEmpty()) {
            return av;
        }
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override
            public void visit(String name, Object value) {
                super.visit(name, value instanceof String s && name != null && MEMBER_KEYS.contains(name) ? member(mod, targets, name, s) : value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor inner = super.visitArray(name);
                if (name == null || !MEMBER_KEYS.contains(name)) {
                    return mixinStrings(mod, targets, inner);
                }
                return new AnnotationVisitor(Opcodes.ASM9, inner) {
                    @Override
                    public void visit(String n, Object value) {
                        super.visit(n, value instanceof String s ? member(mod, targets, name, s) : value);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        return mixinStrings(mod, targets, super.visitAnnotation(n, d));
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return mixinStrings(mod, targets, super.visitAnnotation(name, desc));
            }
        };
    }

    static final Pattern MEMBER = Pattern.compile("(?:L([^;]+);)?([\\w$<>]+)(?:(\\(.*)|:(.+))?");

    /** One member string of a mixin annotation in the new release's names, or as written when it cannot be read or does not link. */
    String member(Mod mod, List<String> targets, String key, String text) {
        if (text.contains("*") || text.contains(" ") || text.isEmpty() || key.equals("value") && !text.contains("(") && !text.contains(";")) {
            return text; // wildcards, @At("HEAD") and friends
        }
        Matcher m = MEMBER.matcher(text);
        if (!m.matches()) {
            return text;
        }
        String owner = m.group(1), name = m.group(2), mdesc = m.group(3), fdesc = m.group(4);
        if (name.equals("<init>") || name.equals("<clinit>")) {
            return mdesc == null ? text : (owner == null ? "" : "L" + cls(owner) + ";") + name + desc(mdesc);
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
                    mod.unlinked.add("mixin target gone: " + Exposure.dotted(o) + "." + name);
                    return text;
                }
                if (!moved[1].equals(name)) {
                    mod.change("mixin target renamed");
                }
                return (owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (fdesc == null ? "" : ":" + desc(fdesc));
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
                return (owner == null ? "" : "L" + cls(o) + ";") + name + (mdesc == null ? "" : mapped);
            }
            if (Exposure.declaring(b, cls(o), 'm', moved[1], moved[2]) != null && moved[2].equals(mapped)) {
                mod.change("mixin target renamed");
                return (owner == null ? "" : "L" + cls(o) + ";") + moved[1] + (mdesc == null ? "" : moved[2]);
            }
            boolean sameName = false;
            for (GameDiff.Cls c = b.classes.get(cls(o)); c != null && !sameName; c = c.sup == null ? null : b.classes.get(c.sup)) {
                for (GameDiff.Meth gm : c.methods.values()) {
                    sameName |= gm.name.equals(moved[1]);
                }
            }
            mod.unlinked.add("mixin target " + (sameName ? "changed signature" : "gone") + ": " + Exposure.dotted(o) + "." + name + d);
            return text;
        }
        return text;
    }

    /** Call sites, field access, method references and the types named inside one method body. */
    final class Sites extends MethodVisitor {
        final Mod mod;
        final List<String> targets;
        final List<String> callers;
        final String key;
        final String where;
        int nextLocal;

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
            return mixinStrings(mod, targets, av);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 4, Math.max(maxLocals, nextLocal));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
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
            String[] t = target(mod, callers, owner, 'f', name, desc, decl);
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
            String[] own = mod.renamedDecl.get(owner + "." + name + desc);
            if (own != null) {
                super.visitMethodInsn(opcode, owner, own[0], oldDesc(own[1]), itf);
                return;
            }
            Decl decl = owner.startsWith("[") ? null : declaringOld(mod, owner, 'm', name, desc);
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
                mod.unlinked.add("call: " + Exposure.dotted(decl.owner()) + "." + name + desc);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            if (!t[1].equals(name) || !t[2].equals(desc(desc)) || !t[0].equals(cls(owner))) {
                mod.change("call renamed");
            }
            boolean nowInterface = itf;
            GameDiff.Cls holder = b.classes.get(t[0]);
            if (holder != null && !t[0].equals(cls(owner))) {
                nowInterface = (holder.flags & Opcodes.ACC_INTERFACE) != 0;
                if (opcode == Opcodes.INVOKEVIRTUAL && nowInterface) {
                    opcode = Opcodes.INVOKEINTERFACE;
                } else if (opcode == Opcodes.INVOKEINTERFACE && !nowInterface) {
                    opcode = Opcodes.INVOKEVIRTUAL;
                }
            }
            super.visitMethodInsn(opcode, inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), nowInterface);
        }

        /** Takes the arguments not in keep off the stack: all are stored in fresh locals and the kept ones loaded back. */
        void drop(String desc, int[] keep) {
            Type[] args = Type.getArgumentTypes(desc);
            int[] slots = new int[args.length];
            for (int i = args.length - 1; i >= 0; i--) {
                slots[i] = nextLocal;
                nextLocal += args[i].getSize();
                super.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slots[i]);
            }
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
                } else if (mapped[i] instanceof Type t) {
                    types(mod, t.getDescriptor(), where);
                }
            }
            super.visitInvokeDynamicInsn(name, desc, bsm, mapped);
        }

        @Override
        public void visitLdcInsn(Object value) {
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
                    mod.unlinked.add("method reference: " + Exposure.dotted(decl.owner()) + "." + h.getName() + h.getDesc());
                    return h;
                }
                if (!t[1].equals(h.getName()) || !t[0].equals(cls(h.getOwner()))) {
                    mod.change("method reference renamed");
                }
                return new Handle(h.getTag(), inverse.getOrDefault(t[0], t[0]), t[1], oldDesc(t[2]), h.isInterface());
            }
            return h;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            types(mod, type.startsWith("[") ? type : "L" + type + ";", where);
            super.visitTypeInsn(opcode, type);
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
            if (a.classes.containsKey(t) && !b.classes.containsKey(cls(t))) {
                mod.unlinked.add("class gone: " + Exposure.dotted(t) + " (in " + where + ")");
            }
        }
    }

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

    /**
     * What an access transformer makes public, in the new release's names, so linking does not count it as closed: a class
     * ("owner"), a member ("owner.name" or "owner.name+desc"), every field ("owner.*") or every method but constructors ("owner.*()").
     */
    void widen(Set<String> grants, String text) {
        for (String line : text.split("\n")) {
            String[] p = line.replaceAll("#.*", "").trim().split("\\s+");
            if (p.length < 2 || !p[0].startsWith("public")) {
                continue;
            }
            String owner = p[1].replace('.', '/');
            String newOwner = cls(owner);
            if (p.length == 2) {
                grants.add(newOwner);
                continue;
            }
            int paren = p[2].indexOf('(');
            if (p[2].equals("*") || p[2].equals("*()")) {
                grants.add(newOwner + "." + p[2]);
            } else if (paren < 0) {
                grants.add(newOwner + "." + route.field(owner, p[2])[1]);
            } else {
                String[] moved = route.method(owner, p[2].substring(0, paren), p[2].substring(paren));
                grants.add(newOwner + "." + moved[1] + moved[2]);
            }
        }
    }

    /** Access transformer lines name game members in the old release; each one is moved to its new name or reported. */
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
            if (rest != null && !rest.startsWith("*") && a.classes.containsKey(owner)) {
                int paren = rest.indexOf('(');
                if (paren < 0) {
                    String[] moved = route.field(owner, rest);
                    if (Exposure.declaring(b, newOwner, 'f', moved[1], null) == null) {
                        mod.unlinked.add("access transformer: " + Exposure.dotted(owner) + "." + rest);
                    } else {
                        newRest = moved[1];
                    }
                } else {
                    String name = rest.substring(0, paren), d = rest.substring(paren);
                    String[] moved = route.method(owner, name, d);
                    if (Exposure.declaring(b, newOwner, 'm', moved[1], moved[2]) == null) {
                        mod.unlinked.add("access transformer: " + Exposure.dotted(owner) + "." + rest);
                    } else {
                        newRest = moved[1] + moved[2];
                    }
                }
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
