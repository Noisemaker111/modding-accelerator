import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * JarPort on a jar built here: a mod and its mixin against a two-release game where one method, one field and one class
 * were renamed and one call was taken out. The ported jar must use the new names wherever the JVM and Mixin link them,
 * say what does not link instead of guessing, and keep a loader able to read it: metadata for the new release, nested
 * mods ported too, no stale signature, a manifest that still parses.
 *
 *   java -cp asm.jar:asm-commons.jar:CLASSES JarPortTest SCRATCH
 */
public final class JarPortTest {

    static int failures;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        GameDiff.Snap a = game("1.0", "foo", "old", "g/Old"), b = game("2.0", "bar", "renamed", "g/New");
        b.loaderVersion = "2.0.3-beta";
        Path map = scratch.resolve("route.json");
        Files.writeString(map, """
            {"classes": [{"from": "g.Old", "to": "g.New"}],
             "methods": [{"owner": "g.Block", "from": "foo", "desc": "()V", "to": "bar", "newDesc": "()V"}],
             "fields": [{"owner": "g.Block", "from": "old", "desc": "I", "to": "renamed", "newDesc": "I"}],
             "rewrites": [{"owner": "g.Block", "from": "gone", "desc": "()V", "toOwner": "", "to": "", "newDesc": ""}]}
            """);
        JarPort port = new JarPort(a, b, map);
        List<Map<String, Object>> results = new ArrayList<>();
        byte[] out = port.port(modJar(), "mod.jar", results);
        Map<String, byte[]> entries = entries(out);

        Map<String, String> block = members(entries.get("m/MyBlock.class"));
        expect(block.containsKey("method bar()V") && !block.containsKey("method foo()V"), "an override of a renamed game method takes the new name: " + block.keySet());
        String run = block.get("method run()V");
        expect(run.contains("call m/MyBlock.bar()V"), "the mod's own call to its override follows the rename: " + run);
        expect(run.contains("get g/Block.renamed I"), "a renamed game field is read by its new name: " + run);
        expect(!run.contains("gone"), "a call the new release took out is removed: " + run);
        expect(run.contains("call g/Block.missing()V"), "a call nothing links to is left as written, not guessed: " + run);
        expect(block.get("field holder").equals("Lg/New;"), "a renamed game class is renamed in the mod's types: " + block.get("field holder"));

        Map<String, String> mixin = members(entries.get("m/BlockMixin.class"));
        expect(mixin.containsKey("field renamed"), "a mixin's @Shadow field takes its target's new name: " + mixin.keySet());
        expect(mixin.get("method onFoo()V").contains("method=bar"), "an injection's target method string is moved to the new name: " + mixin.get("method onFoo()V"));

        Map<String, Object> mine = results.get(results.size() - 1);
        List<Object> unlinked = Json.array(mine.get("unlinked"));
        expect(unlinked.size() == 1 && unlinked.get(0).toString().contains("g.Block.missing()V"), "only what does not link in the new release is reported: " + unlinked);

        String toml = new String(entries.get("META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        expect(toml.contains("versionRange=\"[2.0]\"") && toml.contains("versionRange=\"[2.0,)\"") && toml.contains("[1,)"),
            "the game's and the loader's ranges ask for the new release; other ranges stay: " + toml);
        expect(!entries.containsKey("META-INF/SIGNER.SF"), "a signature of the old classes is removed");
        java.util.jar.Manifest manifest = new java.util.jar.Manifest(new ByteArrayInputStream(entries.get("META-INF/MANIFEST.MF")));
        expect(manifest.getMainAttributes().getValue("MixinConfigs").isEmpty() && manifest.getEntries().isEmpty(),
            "the manifest still parses, keeps its main section (an empty value too) and drops the digests: " + manifest.getEntries());
        Map<String, byte[]> inner = entries(entries.get("META-INF/jars/lib.jar"));
        String innerToml = new String(inner.get("META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        expect(innerToml.contains("[2.0]"), "a nested mod listed in jarjar/metadata.json is ported too: " + innerToml);
        expect(members(inner.get("l/Lib.class")).get("method use()V").contains("call g/Block.bar()V"), "and its classes are rewritten");
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: 15 checks passed");
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    /** One release: g/Block with a method and a field, gone() and missing() only in the old one, and the class Old or New. */
    static GameDiff.Snap game(String version, String method, String field, String cls) {
        GameDiff.Snap s = new GameDiff.Snap(version);
        GameDiff.Cls block = new GameDiff.Cls("g/Block", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
        block.methods.put(method + "()V", new GameDiff.Meth(block, method, "()V", Opcodes.ACC_PUBLIC));
        block.fields.put(field, new GameDiff.Fld(block, field, "I", Opcodes.ACC_PUBLIC));
        if (version.equals("1.0")) {
            block.methods.put("gone()V", new GameDiff.Meth(block, "gone", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            block.methods.put("missing()V", new GameDiff.Meth(block, "missing", "()V", Opcodes.ACC_PUBLIC));
        }
        s.classes.put(block.name, block);
        s.classes.put(cls, new GameDiff.Cls(cls, Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false));
        return s;
    }

    static byte[] modJar() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m/MyBlock", null, "g/Block", null);
        w.visitField(Opcodes.ACC_PUBLIC, "holder", "Lg/Old;", null, null).visitEnd();
        MethodVisitor foo = w.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null);
        foo.visitCode();
        foo.visitInsn(Opcodes.RETURN);
        foo.visitMaxs(0, 1);
        foo.visitEnd();
        MethodVisitor run = w.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m/MyBlock", "foo", "()V", false);
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitFieldInsn(Opcodes.GETFIELD, "g/Block", "old", "I");
        run.visitInsn(Opcodes.POP);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "g/Block", "gone", "()V", false);
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g/Block", "missing", "()V", false);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 1);
        run.visitEnd();
        w.visitEnd();

        ClassWriter mx = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        mx.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m/BlockMixin", null, "java/lang/Object", null);
        AnnotationVisitor target = mx.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g/Block"));
        value.visitEnd();
        target.visitEnd();
        FieldVisitor shadow = mx.visitField(Opcodes.ACC_PRIVATE, "old", "I", null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        shadow.visitEnd();
        MethodVisitor inject = mx.visitMethod(Opcodes.ACC_PRIVATE, "onFoo", "()V", null, null);
        AnnotationVisitor at = inject.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = at.visitArray("method");
        methods.visit(null, "foo");
        methods.visitEnd();
        at.visitEnd();
        inject.visitCode();
        inject.visitInsn(Opcodes.RETURN);
        inject.visitMaxs(0, 1);
        inject.visitEnd();
        mx.visitEnd();

        ClassWriter lib = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        lib.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "l/Lib", null, "java/lang/Object", null);
        MethodVisitor use = lib.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
        use.visitCode();
        use.visitInsn(Opcodes.ACONST_NULL);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g/Block", "foo", "()V", false);
        use.visitInsn(Opcodes.RETURN);
        use.visitMaxs(0, 1);
        use.visitEnd();
        lib.visitEnd();
        String libToml = "modLoader=\"javafml\"\n[[dependencies.lib]]\n    modId=\"minecraft\"\n    versionRange=\"[1.0]\"\n";
        byte[] nested = jar(Map.of("l/Lib.class", lib.toByteArray(), "META-INF/neoforge.mods.toml", libToml.getBytes(StandardCharsets.UTF_8)));

        // versionRange before modId, and a range on another mod that must stay as it is.
        String toml = String.join("\n",
            "modLoader=\"javafml\"",
            "loaderVersion=\"[1,)\"",
            "[[mods]]",
            "modId=\"mod\"",
            "[[dependencies.mod]]",
            "    versionRange=\"[1.0]\"",
            "    modId=\"minecraft\"",
            "[[dependencies.mod]]",
            "    modId=\"neoforge\"",
            "    versionRange=\"[1.0.5,1.1)\"",
            "[[dependencies.mod]]",
            "    modId=\"other\"",
            "    versionRange=\"[1,)\"",
            "");
        String manifest = "Manifest-Version: 1.0\r\nMixinConfigs: \r\n\r\nName: m/MyBlock.class\r\nSHA-256-Digest: abc=\r\n\r\n";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/MANIFEST.MF", manifest.getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/SIGNER.SF", new byte[] { 1 });
        files.put("META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/jarjar/metadata.json", "{\"jars\": [{\"path\": \"META-INF/jars/lib.jar\"}]}".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/jars/lib.jar", nested);
        files.put("m/MyBlock.class", w.toByteArray());
        files.put("m/BlockMixin.class", mx.toByteArray());
        return jar(files);
    }

    static byte[] jar(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    static Map<String, byte[]> entries(byte[] jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                out.put(e.getName(), zip.readAllBytes());
            }
        }
        return out;
    }

    /** "field NAME" -> its type, "method NAME DESC" -> what its body calls and reads and its annotations' strings. */
    static Map<String, String> members(byte[] bytes) {
        Map<String, String> out = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                out.put("field " + name, desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                StringBuilder body = new StringBuilder();
                out.put("method " + name + desc, "");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        body.append("call ").append(owner).append('.').append(n).append(d).append('\n');
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        body.append("get ").append(owner).append('.').append(n).append(' ').append(d).append('\n');
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String n) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String x, Object v) {
                                        body.append(n).append('=').append(v).append('\n');
                                    }
                                };
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        out.put("method " + name + desc, body.toString());
                    }
                };
            }
        }, 0);
        return out;
    }
}
