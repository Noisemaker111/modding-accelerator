import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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
        String version = null, out = null, report = null;
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> version = args[++i];
                case "--out" -> out = args[++i];
                case "--report" -> report = args[++i];
                case "--cache" -> VersionMap.cache = Path.of(args[++i]).toAbsolutePath();
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (version == null || out == null || jars.isEmpty()) {
            System.err.println("usage: ModNames --version V --out DIR [--report R.json] IN.jar ...");
            System.exit(2);
        }
        ModNames names = new ModNames(new Exposure.Intermediary(version));
        Path dir = Files.createDirectories(Path.of(out));
        List<Object> rows = new ArrayList<>();
        for (Path jar : jars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", jar.getFileName().toString());
            try {
                Counts n = new Counts();
                Files.write(dir.resolve(jar.getFileName().toString()), names.jar(Files.readAllBytes(jar), n));
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
        Set<String> nested = new LinkedHashSet<>();
        for (Object j : meta.get("jars") instanceof List<?> list ? list : List.of()) {
            if (j instanceof Map<?, ?> m && m.get("file") instanceof String file && entries.containsKey(file)) {
                nested.add(file);
            }
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
        Map<String, Map<String, String>> out = new HashMap<>();
        for (String f : files) {
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

    final Remapper remapper = new Remapper(Opcodes.ASM9) {
        @Override
        public String map(String internalName) {
            return classes.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return members.getOrDefault(name, name);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bootstrap, Object... arguments) {
            return members.getOrDefault(name, name); // a lambda implementing a game interface names its method_N
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
