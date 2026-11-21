import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * ModNames - a mod jar in the game's Mojang names, so the port and the link check read every loader's mods alike.
 *
 * Fabric mods for releases before 26.1 ship in Fabric's intermediary names (net/minecraft/class_1234, method_5678,
 * field_9012), which Fabric's loader maps to the running game. Intermediary and Mojang's own mappings both name the
 * obfuscated game, so joined through it (VersionMap.Table) every intermediary class and member has its Mojang name.
 * A jar is rewritten into those names: classes, members, lambda targets, generic signatures, the strings its mixins
 * name targets with (through the refmap the mixin processor wrote, as Mixin reads them at run time), its access
 * widener (also written as an access transformer, the form the port checks) and the mods nested in it.
 *
 *   java ModNames --version 1.21.1 --out DIR [--report R.json] IN.jar ...
 *
 * A jar is written to DIR under its own file name. The report lists per jar what was renamed, or why it could not be read.
 */
public final class ModNames {

    static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    final Exposure.Intermediary names;
    final Map<String, String> classes;
    final Map<String, String> members;

    ModNames(Exposure.Intermediary names) {
        this.names = names;
        classes = names.classes;
        members = names.members;
    }

    public static void main(String[] args) throws Exception {
        String version = null, out = null, report = null, loader = "fabric";
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> version = args[++i];
                case "--loader" -> loader = args[++i];
                case "--out" -> out = args[++i];
                case "--report" -> report = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (version == null || out == null || jars.isEmpty()) {
            System.err.println("usage: ModNames --version V [--loader fabric|neoforge] --out DIR [--report R.json] IN.jar ...");
            System.exit(2);
        }
        ModNames names = new ModNames(loader.equals("neoforge") ? Exposure.Intermediary.neoform(version) : new Exposure.Intermediary(version));
        Path dir = Files.createDirectories(Path.of(out));
        List<Object> rows = new ArrayList<>();
        for (Path jar : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", jar.getFileName().toString());
            try {
                Counts n = new Counts();
                written(dir.resolve(jar.getFileName().toString()), names.jar(Files.readAllBytes(jar), n));
                row.put("classes", n.classes);
                row.put("mixinStrings", n.strings);
                row.put("widenerLines", n.widener);
                row.put("nested", n.nested);
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(dir.resolve(jar.getFileName().toString()));
                row.put("error", String.valueOf(failed));
            }
            rows.add(row);
        }
        long failed = rows.stream().filter(r -> ((Map<?, ?>) r).containsKey("error")).count();
        System.out.printf("%d of %d jars in Mojang names for %s in %s%n", jars.size() - failed, jars.size(), version, dir);
        if (report != null) {
            Files.writeString(Path.of(report), Json.write(Map.of("version", version, "mods", rows)), StandardCharsets.UTF_8);
        }
    }

    static final class Counts {
        int classes, strings, widener, nested;
    }

    /**
     * Writes a file into a folder other runs share (~/.mmt/exposure), whole or not at all: under a name of its own, then
     * moved into place. A run beside this one that wrote it first wins; its copy came from the same tools and input.
     */
    static void written(Path target, byte[] data) throws IOException {
        Path part = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        Files.write(part, data);
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException another) {
            Files.delete(part);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException another) {
            // Windows refuses a move over a file another run holds open: that run wrote it already.
            Files.deleteIfExists(part);
            if (!Files.isRegularFile(target)) {
                throw another;
            }
        }
    }

    /** One jar, nested jars included, in Mojang names. */
    byte[] jar(byte[] bytes, Counts n) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, Long> times = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), zip.readAllBytes());
                    times.put(e.getName(), e.getTime());
                }
            }
        }
        Map<String, Object> meta = Map.of();
        byte[] fmj = entries.get("fabric.mod.json");
        if (fmj != null) {
            try {
                meta = Json.object(Json.parse(new String(fmj, StandardCharsets.UTF_8)));
            } catch (RuntimeException unreadable) {
                // a loader rejects it too; the classes are still the mod's
            }
        }
        Map<String, Map<String, String>> refmap = refmaps(entries);
        // Every string a refmap resolves is written into its mixin below, so the refmap is emptied: left as it was, Mixin
        // would turn a string that equals one of its keys back into an intermediary name of the old release.
        Set<String> refmapFiles = refmapFiles(entries);
        Set<String> nested = new LinkedHashSet<>();
        for (Object j : meta.get("jars") instanceof List<?> list ? list : List.of()) {
            if (j instanceof Map<?, ?> m && m.get("file") instanceof String file && entries.containsKey(file)) {
                nested.add(file);
            }
        }
        // A NeoForge mod lists its nested jars in META-INF/jarjar/metadata.json, and the loader reads its access
        // transformers, which name members as the mod's classes do (SRG ids before 1.20.5).
        Set<String> transformers = new HashSet<>();
        if (fmj == null) {
            for (String file : JarPort.nested(entries)) {
                if (entries.containsKey(file)) {
                    nested.add(file);
                }
            }
            transformers.addAll(JarPort.atFiles(entries));
        }
        String widener = meta.get("accessWidener") instanceof String w && entries.containsKey(w) ? w : null;
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            String name = e.getKey();
            byte[] data = e.getValue();
            if (name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") || fmj != null && name.equals("META-INF/accesstransformer.cfg")) {
                continue; // a jar for several loaders: Fabric never reads NeoForge's access transformer
            }
            if (name.endsWith(".class")) {
                String[] renamed = new String[1];
                try {
                    data = cls(data, refmap, n, renamed);
                    name = name.replaceFirst("^(META-INF/versions/\\d+/)?.*", "$1") + renamed[0] + ".class";
                    n.classes++;
                } catch (RuntimeException unreadable) {
                    // the port skips a class it cannot parse as well
                }
            } else if (nested.contains(name)) {
                data = jar(data, new Counts());
                n.nested++;
            } else if (refmapFiles.contains(name)) {
                data = EMPTY_REFMAP;
            } else if (transformers.contains(name)) {
                data = rename(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            } else if (name.equals(widener)) {
                String text = new String(data, StandardCharsets.UTF_8);
                data = widener(text, n).getBytes(StandardCharsets.UTF_8);
                out.put("META-INF/accesstransformer.cfg", Exposure.transformer(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            }
            out.put(name, data);
        }
        if (!nested.isEmpty() && !out.containsKey("META-INF/jarjar/metadata.json")) {
            // The port reads nested jars where NeoForge lists them; a Fabric mod lists them in fabric.mod.json.
            List<Object> list = new ArrayList<>();
            for (String file : nested) {
                list.add(Map.of("path", file));
            }
            out.put("META-INF/jarjar/metadata.json", Json.write(Map.of("jars", list)).getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (var e : out.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(times.getOrDefault(e.getKey(), 0L));
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * What each mixin's annotation strings resolve to, by mixin class: the refmaps the jar's mixin configs name ("refmap"),
     * in the default ("mappings") table, which is the one written for intermediary.
     */
    static Map<String, Map<String, String>> refmaps(Map<String, byte[]> entries) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (String f : refmapFiles(entries)) {
            byte[] data = entries.get(f);
            if (data == null) {
                continue;
            }
            try {
                Object mappings = Json.object(Json.parse(new String(data, StandardCharsets.UTF_8))).get("mappings");
                for (var c : (mappings instanceof Map<?, ?> m ? m : Map.of()).entrySet()) {
                    Map<String, String> into = out.computeIfAbsent(String.valueOf(c.getKey()), k -> new HashMap<>());
                    for (var s : ((Map<?, ?>) c.getValue()).entrySet()) {
                        into.putIfAbsent(String.valueOf(s.getKey()), String.valueOf(s.getValue()));
                    }
                }
            } catch (RuntimeException unreadable) {
                // Mixin would fail on it too: the strings stay as written
            }
        }
        return out;
    }

    /** The refmaps a jar's mixin configs (top-level .json files with "refmap") name. */
    static Set<String> refmapFiles(Map<String, byte[]> entries) {
        Set<String> files = new LinkedHashSet<>();
        for (var e : entries.entrySet()) {
            if (!e.getKey().endsWith(".json") || e.getKey().contains("/")) {
                continue;
            }
            try {
                Map<String, Object> config = Json.object(Json.parse(new String(e.getValue(), StandardCharsets.UTF_8)));
                if (config.get("refmap") instanceof String r) {
                    files.add(r);
                }
            } catch (RuntimeException notJson) {
                // not a mixin config
            }
        }
        return files;
    }

    /** What a refmap becomes once its strings are written into the mixins themselves: nothing left to look up. */
    static final byte[] EMPTY_REFMAP = "{\n  \"mappings\": {},\n  \"data\": {}\n}\n".getBytes(StandardCharsets.UTF_8);

    final Remapper remapper = new Remapper(Opcodes.ASM9) {
        @Override
        public String map(String internalName) {
            return classes.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return names.shared.getOrDefault(name + descriptor, members.getOrDefault(name, name));
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bootstrap, Object... arguments) {
            // A lambda implementing a game interface names its method_N; the descriptor here is the factory's, so the
            // interface method's own (the first bootstrap argument) tells a shared id apart.
            String own = arguments.length > 0 && arguments[0] instanceof Type t ? name + t.getDescriptor() : null;
            return own != null && names.shared.containsKey(own) ? names.shared.get(own) : members.getOrDefault(name, name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return members.getOrDefault(name, name);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return members.getOrDefault(name, name);
        }
    };

    /** One class in Mojang names; its new internal name goes in renamed[0]. */
    byte[] cls(byte[] bytes, Map<String, Map<String, String>> refmap, Counts n, String[] renamed) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor remap = new ClassRemapper(writer, remapper) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                renamed[0] = remapper.map(name);
                super.visit(version, access, name, signature, superName, interfaces);
            }
        };
        reader.accept(new Strings(remap, refmap.getOrDefault(reader.getClassName(), Map.of()), n), 0);
        return writer.toByteArray();
    }

    /**
     * A mixin's annotation strings in Mojang names, before the class itself is renamed: each string the refmap lists is
     * replaced by the member it resolves to, and intermediary names written by hand are renamed in place. An @Accessor or
     * @Invoker gets the target's bare name, inferred from the method's name as Mixin does when the annotation has none.
     */
    final class Strings extends ClassVisitor {
        final Map<String, String> refs;
        final Counts n;

        Strings(ClassVisitor next, Map<String, String> refs, Counts n) {
            super(Opcodes.ASM9, next);
            this.refs = refs;
            this.n = n;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return new Values(super.visitAnnotation(desc, visible), false, null);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, desc, signature, value);
            return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return new Values(super.visitAnnotation(d, visible), false, null);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    boolean bare = d.equals(ACCESSOR) || d.equals(INVOKER);
                    return new Values(super.visitAnnotation(d, visible), bare, bare ? inferred(d, name) : null);
                }
            };
        }

        /** Rewrites string values; bare: only the member's name is kept; infer: the name to give when no value is written. */
        final class Values extends AnnotationVisitor {
            final boolean bare;
            final String infer;
            boolean valued;

            Values(AnnotationVisitor av, boolean bare, String infer) {
                super(Opcodes.ASM9, av);
                this.bare = bare;
                this.infer = infer;
            }

            @Override
            public void visit(String name, Object value) {
                valued |= "value".equals(name);
                super.visit(name, value instanceof String s ? string(s, bare) : value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return new Values(super.visitArray(name), bare, null);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return new Values(super.visitAnnotation(name, desc), false, null);
            }

            @Override
            public void visitEnd() {
                if (!valued && infer != null && refs.containsKey(infer)) {
                    super.visit("value", string(infer, true));
                }
                super.visitEnd();
            }
        }

        String string(String s, boolean bare) {
            if (REGEX.matcher(s.trim()).matches()) {
                // A selector written as a pattern ("/^(connectToServer|method_10753)$/") is kept as written: no name in it
                // can be told from text, and handed back it must still match the names the game runs.
                return s;
            }
            String resolved = refs.get(s);
            String out = rename(resolved != null ? resolved : s);
            if (bare && resolved != null) {
                // "Lowner;name(desc)" or "name:desc" -> name
                out = out.replaceFirst("^L[^;]+;", "").replaceFirst("[(:].*$", "");
            }
            if (!out.equals(s)) {
                n.strings++;
            }
            return out;
        }
    }

    /** The target name Mixin infers for an accessor (getFoo, setFoo, isFoo) or invoker (callFoo, invokeFoo) with no value. */
    static String inferred(String annotation, String method) {
        List<String> prefixes = annotation.equals(ACCESSOR) ? List.of("get", "set", "is") : List.of("call", "invoke", "new", "create");
        for (String p : prefixes) {
            if (method.startsWith(p) && method.length() > p.length() && Character.isUpperCase(method.charAt(p.length()))) {
                String rest = method.substring(p.length());
                return rest.equals(rest.toUpperCase(Locale.ROOT)) ? rest : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return method;
    }

    String rename(String s) {
        return names.rename(s);
    }

    /**
     * The other way: a jar in Mojang's names handed back in the names Fabric runs before 26.1, one release's intermediary.
     * A reference names its member through a class (the owner) that may only inherit it, and intermediary names a member
     * where it is declared, so each one is looked up through the release's hierarchy. A mod's own method that overrides a
     * game method takes the game method's name, and so does every mod class above it that declares it; a mixin's members
     * that are its targets' (shadows, overwrites) take theirs. Mixin annotation strings are written out in full in
     * intermediary, accessors get an explicit target, refmaps are emptied (the strings no longer need them), the access
     * widener is written for the "intermediary" namespace, the access transformer the port reads is dropped (Fabric never
     * reads one), and nested jars are handed back the same way.
     */
    static final class Back {
        final Map<String, String> classes = new HashMap<>(); // Mojang -> intermediary
        final Map<String, String> methods = new HashMap<>(); // Mojang owner.name+desc (declaring class) -> intermediary name
        final Map<String, String> fields = new HashMap<>(); // Mojang owner.name -> intermediary name
        final GameDiff.Snap game; // the release in Mojang's names

        Back(String version, GameDiff.Snap game) throws Exception {
            this.game = game;
            VersionMap.Table t = VersionMap.Table.load(version, "mojmap");
            t.classes.forEach((inter, named) -> classes.put(named, inter));
            for (boolean method : new boolean[] { true, false }) {
                for (var e : (method ? t.methods : t.fields).entrySet()) {
                    VersionMap.Member m = e.getValue();
                    String name = e.getKey().substring(e.getKey().indexOf('.') + 1);
                    if (!name.startsWith("method_") && !name.startsWith("field_") && name.endsWith(m.interDesc())) {
                        name = name.substring(0, name.length() - m.interDesc().length());
                    }
                    if (method) {
                        methods.put(m.owner() + "." + m.name() + m.desc(), name);
                    } else {
                        fields.put(m.owner() + "." + m.name(), name);
                    }
                }
            }
        }

        Back(Map<String, String> classes, Map<String, String> methods, Map<String, String> fields, GameDiff.Snap game) {
            this.classes.putAll(classes);
            this.methods.putAll(methods);
            this.fields.putAll(fields);
            this.game = game;
        }

        String cls(String mojang) {
            return classes.getOrDefault(mojang, mojang);
        }

        String desc(String mojang) {
            return mojang == null ? null : VersionMap.Tiny.remapDesc(mojang, classes);
        }

        /** A game member's intermediary name, found where the release declares it (owner or a supertype); null when none. */
        String game(char kind, String owner, String name, String desc) {
            Deque<String> queue = new ArrayDeque<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String c = queue.poll();
                if (!seen.add(c)) {
                    continue;
                }
                String hit = kind == 'f' ? fields.get(c + "." + name) : methods.get(c + "." + name + desc);
                if (hit != null) {
                    return hit;
                }
                GameDiff.Cls k = game.classes.get(c);
                if (k != null) {
                    if (k.sup != null) {
                        queue.add(k.sup);
                    }
                    queue.addAll(k.ifaces);
                }
            }
            return null;
        }

        /** One mod class as the hand-back needs it. */
        static final class Own {
            String sup;
            List<String> ifaces = List.of();
            final Set<String> methods = new HashSet<>(); // name + desc
            final Set<String> fields = new HashSet<>();
            final Set<String> accessors = new HashSet<>(); // @Accessor/@Invoker methods: their own names stay
            final List<String> targets = new ArrayList<>(); // a mixin's target classes
        }

        /** A jar's classes and what each mod method or field is renamed to. */
        final class Jar {
            final Map<String, Own> own = new HashMap<>();
            final Map<String, String> renamed = new HashMap<>(); // class.name+desc or class.name -> intermediary

            Jar(Collection<byte[]> classFiles) {
                for (byte[] bytes : classFiles) {
                    read(bytes);
                }
                // A mixin's members named as its targets' are theirs; a mod method overriding a game method takes its name.
                for (var e : own.entrySet()) {
                    Own o = e.getValue();
                    for (String f : o.fields) {
                        String hit = o.targets.stream().map(t -> game('f', t, f, null)).filter(Objects::nonNull).findFirst().orElse(null);
                        if (hit != null) {
                            renamed.put(e.getKey() + "." + f, hit);
                        }
                    }
                    for (String m : o.methods) {
                        if (o.accessors.contains(m) || m.startsWith("<")) {
                            continue;
                        }
                        int paren = m.indexOf('(');
                        String name = m.substring(0, paren), desc = m.substring(paren);
                        String bare = name.startsWith("shadow$") ? name.substring(7) : name;
                        String hit = o.targets.stream().map(t -> game('m', t, bare, desc)).filter(Objects::nonNull).findFirst().orElse(null);
                        if (hit == null) {
                            hit = inherited(e.getKey(), bare, desc);
                        }
                        if (hit != null) {
                            above(e.getKey(), m, name.substring(0, name.length() - bare.length()) + hit, new HashSet<>());
                        }
                    }
                }
            }

            void read(byte[] bytes) {
                ClassReader r = new ClassReader(bytes);
                Own o = new Own();
                own.put(r.getClassName(), o);
                o.sup = r.getSuperName();
                o.ifaces = List.of(r.getInterfaces());
                r.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String n, Object v) {
                                        if (v instanceof Type t) {
                                            o.targets.add(t.getInternalName());
                                        } else if (v instanceof String s) {
                                            o.targets.add(s.replace('.', '/'));
                                        }
                                    }
                                };
                            }
                        };
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                        o.fields.add(name);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        o.methods.add(name + desc);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                                if (d.equals(ACCESSOR) || d.equals(INVOKER)) {
                                    o.accessors.add(name + desc);
                                }
                                return null;
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE);
            }

            /** The intermediary name of a game method a mod class inherits through its own supertypes, or null. */
            String inherited(String cls, String name, String desc) {
                Deque<String> queue = new ArrayDeque<>();
                Own o = own.get(cls);
                if (o.sup != null) {
                    queue.add(o.sup);
                }
                queue.addAll(o.ifaces);
                Set<String> seen = new HashSet<>();
                while (!queue.isEmpty()) {
                    String c = queue.poll();
                    if (!seen.add(c)) {
                        continue;
                    }
                    Own up = own.get(c);
                    if (up == null) {
                        String hit = game('m', c, name, desc);
                        if (hit != null) {
                            return hit;
                        }
                        continue;
                    }
                    if (up.sup != null) {
                        queue.add(up.sup);
                    }
                    queue.addAll(up.ifaces);
                    queue.addAll(up.targets);
                }
                return null;
            }

            /** Every mod class above cls that declares the method takes the same name, so the override family stays one. */
            void above(String cls, String method, String to, Set<String> seen) {
                Own o = own.get(cls);
                if (o == null || !seen.add(cls)) {
                    return;
                }
                if (o.methods.contains(method)) {
                    renamed.putIfAbsent(cls + "." + method, to);
                }
                if (o.sup != null) {
                    above(o.sup, method, to, seen);
                }
                for (String i : o.ifaces) {
                    above(i, method, to, seen);
                }
            }

            /** A member reached through owner: a mod class's own renaming, or the game's, looked up through the supertypes. */
            String member(char kind, String owner, String name, String desc) {
                Deque<String> queue = new ArrayDeque<>(List.of(owner));
                Set<String> seen = new HashSet<>();
                while (!queue.isEmpty()) {
                    String c = queue.poll();
                    if (!seen.add(c)) {
                        continue;
                    }
                    Own o = own.get(c);
                    if (o == null) {
                        String hit = game(kind, c, name, desc);
                        if (hit != null) {
                            return hit;
                        }
                        continue;
                    }
                    String key = c + "." + name + (kind == 'f' ? "" : desc);
                    if (kind == 'f' ? o.fields.contains(name) : o.methods.contains(name + desc)) {
                        return renamed.getOrDefault(key, name);
                    }
                    if (o.sup != null) {
                        queue.add(o.sup);
                    }
                    queue.addAll(o.ifaces);
                }
                return name;
            }
        }

        /** One jar, nested jars included, in the names Fabric runs. */
        byte[] jar(byte[] bytes) throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            Map<String, Long> times = new HashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (!e.isDirectory()) {
                        entries.put(e.getName(), zip.readAllBytes());
                        times.put(e.getName(), e.getTime());
                    }
                }
            }
            Map<String, Object> meta = Map.of();
            if (entries.get("fabric.mod.json") != null) {
                try {
                    meta = Json.object(Json.parse(new String(entries.get("fabric.mod.json"), StandardCharsets.UTF_8)));
                } catch (RuntimeException unreadable) {
                    // the classes are still the mod's
                }
            }
            Set<String> nested = new HashSet<>();
            for (Object j : meta.get("jars") instanceof List<?> list ? list : List.of()) {
                if (j instanceof Map<?, ?> m && m.get("file") instanceof String file) {
                    nested.add(file);
                }
            }
            String widener = meta.get("accessWidener") instanceof String w ? w : null;
            Set<String> refmaps = refmapFiles(entries);
            List<byte[]> classFiles = entries.entrySet().stream().filter(e -> e.getKey().endsWith(".class")).map(Map.Entry::getValue).toList();
            Jar jar = new Jar(classFiles);
            Map<String, byte[]> out = new LinkedHashMap<>();
            for (var e : entries.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();
                if (name.equals("META-INF/accesstransformer.cfg") && !meta.isEmpty()) {
                    continue;
                }
                if (name.endsWith(".class")) {
                    String[] renamed = new String[1];
                    try {
                        data = cls(jar, data, renamed);
                        name = name.replaceFirst("^(META-INF/versions/\\d+/)?.*", "$1") + renamed[0] + ".class";
                    } catch (RuntimeException unreadable) {
                        // left as it is
                    }
                } else if (nested.contains(name)) {
                    data = jar(data);
                } else if (name.equals(widener)) {
                    data = widener(new String(data, StandardCharsets.UTF_8), jar).getBytes(StandardCharsets.UTF_8);
                } else if (refmaps.contains(name)) {
                    data = EMPTY_REFMAP; // the strings are written out in full; ModNames emptied it already when it read the jar
                }
                out.put(name, data);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                for (var e : out.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTime(times.getOrDefault(e.getKey(), 0L));
                    zip.putNextEntry(entry);
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        }

        byte[] cls(Jar jar, byte[] bytes, String[] renamed) {
            ClassReader reader = new ClassReader(bytes);
            Own self = jar.own.get(reader.getClassName());
            List<String> targets = self == null ? List.of() : self.targets;
            Remapper remapper = new Remapper(Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    return Back.this.cls(internalName);
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return owner.startsWith("[") || name.startsWith("<") ? name : jar.member('m', owner, name, descriptor);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return jar.member('f', owner, name, descriptor);
                }

                @Override
                public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bootstrap, Object... arguments) {
                    // A lambda implements its interface's method under the name a call to it uses, through the mod's own
                    // interfaces too (a mod interface extending a game one).
                    Type made = Type.getReturnType(descriptor);
                    if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") && made.getSort() == Type.OBJECT
                        && arguments.length > 0 && arguments[0] instanceof Type sam) {
                        return jar.member('m', made.getInternalName(), name, sam.getDescriptor());
                    }
                    return name;
                }
            };
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor remap = new ClassRemapper(writer, remapper) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    renamed[0] = remapper.map(name);
                    super.visit(version, access, name, signature, superName, interfaces);
                }
            };
            reader.accept(new Annotations(remap, targets), 0);
            return writer.toByteArray();
        }

        /** A mixin's annotation strings, written out in intermediary before the class itself is renamed. */
        final class Annotations extends ClassVisitor {
            final List<String> targets;

            Annotations(ClassVisitor next, List<String> targets) {
                super(Opcodes.ASM9, next);
                this.targets = targets;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                AnnotationVisitor av = super.visitAnnotation(desc, visible);
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || av == null) {
                    return strings(av);
                }
                return new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        AnnotationVisitor inner = super.visitArray(name);
                        return !"targets".equals(name) || inner == null ? inner : new AnnotationVisitor(Opcodes.ASM9, inner) {
                            @Override
                            public void visit(String n, Object v) {
                                super.visit(n, v instanceof String s ? className(s) : v);
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                FieldVisitor fv = super.visitField(access, name, desc, signature, value);
                return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return strings(super.visitAnnotation(d, visible));
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        AnnotationVisitor av = super.visitAnnotation(d, visible);
                        if (av != null && (d.equals(ACCESSOR) || d.equals(INVOKER))) {
                            return accessor(av, d.equals(INVOKER), name, desc);
                        }
                        return strings(av);
                    }
                };
            }

            String className(String s) {
                boolean dotted = s.indexOf('/') < 0;
                String c = cls(s.replace('.', '/'));
                return dotted ? c.replace('/', '.') : c;
            }

            /** An @Accessor or @Invoker always names its target in intermediary: Mixin cannot infer it from the method's name. */
            AnnotationVisitor accessor(AnnotationVisitor av, boolean invoker, String method, String desc) {
                return new AnnotationVisitor(Opcodes.ASM9, av) {
                    String value;

                    @Override
                    public void visit(String n, Object v) {
                        if ("value".equals(n) && v instanceof String s) {
                            value = s;
                            return;
                        }
                        super.visit(n, v);
                    }

                    @Override
                    public void visitEnd() {
                        String target = value != null ? value : inferred(invoker ? INVOKER : ACCESSOR, method);
                        String hit = null;
                        if (!target.equals("<init>") && !(invoker && value == null && method.matches("^(new|create).*"))) {
                            for (String t : targets) {
                                if (!invoker) {
                                    hit = game('f', t, target, null);
                                    if (hit == null && value == null) {
                                        hit = game('f', t, target.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT), null);
                                    }
                                } else {
                                    hit = game('m', t, target, desc);
                                }
                                if (hit != null) {
                                    break;
                                }
                            }
                        }
                        if (hit != null) {
                            super.visit("value", hit);
                        } else if (value != null) {
                            super.visit("value", value);
                        }
                        super.visitEnd();
                    }
                };
            }

            /** Member strings ("method", "target", a desc'd "value") in intermediary; a name for several overloads becomes one string each. */
            AnnotationVisitor strings(AnnotationVisitor av) {
                if (av == null || targets.isEmpty()) {
                    return av;
                }
                return new AnnotationVisitor(Opcodes.ASM9, av) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof String s && name != null && MEMBER_KEYS.contains(name)) {
                            List<String> out = member(name, s);
                            super.visit(name, out.get(0));
                            return;
                        }
                        super.visit(name, value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        AnnotationVisitor inner = super.visitArray(name);
                        if (inner == null || name == null || !MEMBER_KEYS.contains(name)) {
                            return strings(inner);
                        }
                        return new AnnotationVisitor(Opcodes.ASM9, inner) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof String s) {
                                    for (String one : member(name, s)) {
                                        super.visit(n, one);
                                    }
                                    return;
                                }
                                super.visit(n, value);
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String n, String d) {
                                return strings(super.visitAnnotation(n, d));
                            }
                        };
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String name, String desc) {
                        return strings(super.visitAnnotation(name, desc));
                    }
                };
            }

            List<String> member(String key, String text) {
                if (text.contains("*") || text.contains(" ") || text.isEmpty() || key.equals("value") && !text.contains("(") && !text.contains(";")) {
                    return List.of(text);
                }
                // @At("NEW") names a class ("a/b/C", "La/b/C;") or a constructor's descriptor ("(I)La/b/C;").
                if (key.equals("target") && (text.startsWith("(") || text.matches("L[^;()]+;") || text.matches("[\\w$]+(/[\\w$]+)+"))) {
                    return List.of(text.startsWith("(") || text.startsWith("L") && text.endsWith(";") ? desc(text) : cls(text));
                }
                Matcher m = STRING.matcher(text);
                if (!m.matches()) {
                    return List.of(text);
                }
                String owner = m.group(1), name = m.group(2), mdesc = m.group(3), fdesc = m.group(4);
                String prefix = owner == null ? "" : "L" + cls(owner) + ";";
                if (name.equals("<init>") || name.equals("<clinit>")) {
                    return List.of(prefix + name + (mdesc == null ? "" : desc(mdesc)));
                }
                List<String> owners = owner != null ? List.of(owner) : targets;
                for (String o : owners) {
                    if (fdesc != null || mdesc == null && owner != null && game('f', o, name, null) != null) {
                        String hit = game('f', o, name, null);
                        if (hit != null) {
                            return List.of(prefix + hit + (fdesc == null ? "" : ":" + desc(fdesc)));
                        }
                        continue;
                    }
                    if (mdesc != null) {
                        String hit = game('m', o, name, mdesc);
                        if (hit != null) {
                            return List.of(prefix + hit + desc(mdesc));
                        }
                        continue;
                    }
                    // A bare name: every overload the target declares by that name.
                    GameDiff.Cls k = game.classes.get(o);
                    List<String> descs = k == null ? List.of() : k.methods.values().stream().filter(x -> x.name.equals(name)).map(x -> x.desc).toList();
                    Set<String> names = new LinkedHashSet<>();
                    for (String d : descs) {
                        String hit = game('m', o, name, d);
                        names.add(hit == null ? name : hit);
                    }
                    if (names.size() == 1) {
                        return List.of(prefix + names.iterator().next());
                    }
                    if (!names.isEmpty()) {
                        return descs.stream().map(d -> {
                            String hit = game('m', o, name, d);
                            return prefix + (hit == null ? name : hit) + desc(d);
                        }).toList();
                    }
                }
                // Not the game's member (a library method on a game class, like Netty's initChannel): its owner and types still are.
                return List.of(prefix + name + (mdesc != null ? desc(mdesc) : fdesc != null ? ":" + desc(fdesc) : ""));
            }
        }

        /** The access widener for the "intermediary" namespace. */
        String widener(String text, Jar jar) {
            StringBuilder out = new StringBuilder();
            for (String line : text.split("\r?\n", -1)) {
                String body = line.replaceAll("#.*", "").trim();
                String[] p = body.split("\\s+");
                if (p.length >= 3 && (p[0].equals("accessWidener") || p[0].equals("classTweaker"))) {
                    out.append(p[0]).append(' ').append(p[1]).append(" intermediary\n");
                    continue;
                }
                if (body.isEmpty() || p.length < 3) {
                    out.append(line).append('\n');
                    continue;
                }
                String owner = p[2];
                StringBuilder l = new StringBuilder(p[0]).append(' ').append(p[1]).append(' ').append(cls(owner));
                if (p[1].equals("method") && p.length >= 5) {
                    l.append(' ').append(jar.member('m', owner, p[3], p[4])).append(' ').append(desc(p[4]));
                } else if (p[1].equals("field") && p.length >= 5) {
                    l.append(' ').append(jar.member('f', owner, p[3], p[4])).append(' ').append(desc(p[4]));
                }
                out.append(l).append('\n');
            }
            String s = out.toString();
            return s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s;
        }
    }

    static final Set<String> MEMBER_KEYS = Set.of("method", "target", "value");
    /** A Mixin selector written as a regular expression: "/pattern/", or "name=/pattern/ desc=/pattern/". */
    static final Pattern REGEX = Pattern.compile("^(?:name=)?/.+/(?:\\s+desc=/.+/)?$");

    static final Pattern STRING = Pattern.compile("(?:L([^;]+);)?([\\w$<>]+)(?:(\\(.*)|:(.+))?");

    /** An access widener in Mojang names, under the "named" namespace. */
    String widener(String text, Counts n) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String body = line.replaceAll("#.*", "").trim();
            String[] p = body.split("\\s+");
            if (p.length >= 3 && (p[0].equals("accessWidener") || p[0].equals("classTweaker"))) {
                out.append(p[0]).append(' ').append(p[1]).append(" named\n");
                continue;
            }
            if (p.length >= 3) {
                n.widener++;
            }
            out.append(rename(line)).append('\n');
        }
        return out.toString();
    }
}
