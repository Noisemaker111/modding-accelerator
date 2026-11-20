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
             "methods": [{"owner": "g.Block", "from": "foo", "desc": "()V", "to": "bar", "newDesc": "()V"},
                         {"owner": "g.Block", "from": "sig", "desc": "()V", "to": "sig", "newDesc": "(I)V"},
                         {"owner": "g.Vault", "from": "hid", "desc": "()V", "to": "secret", "newDesc": "()V"},
                         {"owner": "g.Root", "from": "foo2", "desc": "()V", "to": "bar2", "newDesc": "()V"},
                         {"owner": "g.Api", "from": "run", "desc": "()V", "to": "go", "newDesc": "()V"}],
             "fields": [{"owner": "g.Block", "from": "old", "desc": "I", "to": "renamed", "newDesc": "I"},
                        {"owner": "g.Vault", "from": "inner", "desc": "I", "to": "hidden", "newDesc": "I"}],
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
        List<String> unlinked = Json.array(mine.get("unlinked")).stream().map(String::valueOf).toList();
        expect(unlinked.equals(List.of(
            "abstract method not written: g.Shape.area()D (in m.Square)",
            "call: g.Block.missing()V",
            "call: g.Block.sig()V",
            "class gone: g.GoneIface (in m.Impl)"
        )), "only what does not link in the new release is reported: " + unlinked);
        expect(run.contains("call g/Block.sig()V"), "a call whose parameters changed is left as written, not given a descriptor its stack does not fit: " + run);
        expect(run.contains("call g/Block.prot()V"), "a protected method stays callable from a subclass (not reported above)");
        String at = new String(entries.get("META-INF/accesstransformer.cfg"), StandardCharsets.UTF_8);
        expect(at.contains("\npublic g.Block closed()V\n") && run.contains("call g/Block.closed()V"),
            "a method the new release made private is opened through the mod's access transformer, not reported: " + at);
        expect(run.contains("call g/Vault.secret()V") && run.contains("get g/Vault.hidden I"),
            "a rename to a private member the mod's access transformer opens with a wildcard is taken: " + run);
        expect(members(entries.get("m/ModMid.class")).containsKey("method foo2()V"),
            "an override stays as written when the game class it extends keeps the old name, though an ancestor renamed it");
        Map<String, String> child = members(entries.get("m/MyChild.class"));
        expect(child.containsKey("method bar()V") && !child.containsKey("method foo()V"), "a second-level override follows the rename too: " + child.keySet());
        Map<String, String> byName = members(entries.get("m/OldMixin.class"));
        expect("g.New".equals(byName.get("annotation targets")), "@Mixin(targets = ...) names the renamed class: " + byName);
        Map<String, String> accessor = members(entries.get("m/BlockAccessor.class"));
        expect(accessor.get("method value()I").contains("value=renamed"), "an @Accessor with a value names the field's new name: " + accessor.get("method value()I"));
        expect(accessor.get("method getOld()I").contains("value=renamed"), "an @Accessor with no value gets the new name written in: " + accessor.get("method getOld()I"));
        expect(accessor.get("method callFoo()V").contains("value=bar"), "an @Invoker names the method's new name: " + accessor.get("method callFoo()V"));
        try {
            port.port(jar(Map.of("../escape.txt", new byte[] { 1 })), "bad.jar", new ArrayList<>());
            expect(false, "an entry that points outside the jar is refused");
        } catch (IOException refused) {
            expect(refused.getMessage().contains("../escape.txt"), "an entry that points outside the jar is refused: " + refused.getMessage());
        }

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
        String use = members(inner.get("l/Lib.class")).get("method use()V");
        expect(use.contains("call g/Block.bar()V"), "and its classes are rewritten: " + use);
        expect(use.contains("call g/Vault.secret()V"), "with the access its parent's access transformer gives the game: " + use);
        runs(scratch);
        JarPortRulesTest.check(scratch);
        JarPortRulesTest.buses(scratch);
        JarPortRulesTest.foreignMixin(scratch);
        JarPortFixesTest.check(scratch);
        JarPortFabricTest.check(scratch);
        JarPortRetypeTest.check(scratch);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port: 65 checks passed");
    }

    /**
     * The game's own replacements, applied in a class and then run on this JVM against real classes of the new release:
     * an added argument filled with the value the game passes (a method reference), a call switched to an interface
     * method, a field read that now holds a subtype, a field read through a chain, and an argument the new method no
     * longer takes. The JVM verifies the rewritten code when it loads it, and the calls leave their trace in g2.Log.out.
     */
    static void runs(Path scratch) throws Exception {
        String str = "Ljava/lang/String;";
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        snap(a, "g2/Gui", 0, null, "draw(" + str + ")V", "clear(Z)V");
        snap(b, "g2/Gui", 0, null, "draw(Ljava/util/function/Function;" + str + ")V", "clear()V");
        snap(a, "g2/Holder", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null);
        snap(b, "g2/Holder", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "get(" + str + ")" + str);
        snap(a, "g2/Reg", 0, null, "lookup(" + str + ")" + str).ifaces.add("g2/Holder");
        snap(b, "g2/Reg", 0, null, "get(" + str + ")" + str).ifaces.add("g2/Holder");
        snap(a, "g2/Res", 0, null).fields.put("OK", new GameDiff.Fld(a.classes.get("g2/Res"), "OK", "Lg2/Res;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        snap(b, "g2/Res", 0, null).fields.put("OK", new GameDiff.Fld(b.classes.get("g2/Res"), "OK", "Lg2/Res$Ok;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        snap(b, "g2/Res$Ok", 0, "g2/Res");
        snap(a, "g2/In", 0, null).fields.put("jumping", new GameDiff.Fld(a.classes.get("g2/In"), "jumping", "Z", Opcodes.ACC_PUBLIC));
        snap(b, "g2/In", 0, null).fields.put("keys", new GameDiff.Fld(b.classes.get("g2/In"), "keys", "Lg2/Keys;", Opcodes.ACC_PUBLIC));
        snap(b, "g2/Keys", 0, null, "jump()Z");
        snap(a, "g2/Type", 0, null);
        GameDiff.Cls type = snap(b, "g2/Type", 0, null);
        type.methods.put("make(" + str + ")Lg2/Type;", new GameDiff.Meth(type, "make", "(" + str + ")Lg2/Type;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        Path map = scratch.resolve("route-runs.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "g2.Gui", "from": "clear", "desc": "(Z)V", "to": "clear", "newDesc": "()V"}],
             "rewrites": [{"owner": "g2.Reg", "from": "lookup", "desc": "(Ljava/lang/String;)Ljava/lang/String;", "static": false,
                           "toOwner": "g2.Holder", "to": "get", "newDesc": "(Ljava/lang/String;)Ljava/lang/String;", "newStatic": false, "votes": 3, "of": 3}],
             "argDefaults": [{"owner": "g2.Gui", "from": "draw", "desc": "(Ljava/lang/String;)V", "toOwner": "g2.Gui", "to": "draw",
                              "newDesc": "(Ljava/util/function/Function;Ljava/lang/String;)V", "index": 0, "value": "g2.Type::make", "votes": 3, "of": 3}],
             "chains": [{"owner": "g2.In", "newOwner": "g2.In", "from": "jumping", "desc": "", "field": true, "steps": "f:keys,m:jump", "votes": 2, "of": 2}]}
            """);

        ClassWriter run = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        run.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m2/Run", null, "java/lang/Object", null);
        MethodVisitor go = run.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(Lg2/Gui;Lg2/Reg;Lg2/In;)" + str, null, null);
        go.visitCode();
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitLdcInsn("x");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g2/Gui", "draw", "(" + str + ")V", false);
        go.visitVarInsn(Opcodes.ALOAD, 1);
        go.visitLdcInsn("y");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g2/Reg", "lookup", "(" + str + ")" + str, false);
        go.visitInsn(Opcodes.POP);
        go.visitFieldInsn(Opcodes.GETSTATIC, "g2/Res", "OK", "Lg2/Res;");
        go.visitInsn(Opcodes.POP);
        go.visitVarInsn(Opcodes.ALOAD, 2);
        go.visitFieldInsn(Opcodes.GETFIELD, "g2/In", "jumping", "Z");
        go.visitInsn(Opcodes.POP);
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitInsn(Opcodes.ICONST_1);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g2/Gui", "clear", "(Z)V", false);
        go.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + str, false);
        go.visitInsn(Opcodes.ARETURN);
        go.visitMaxs(0, 3);
        go.visitEnd();
        run.visitEnd();

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = entries(new JarPort(a, b, map).port(jar(Map.of("m2/Run.class", run.toByteArray())), "runs.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame());
        classes.put("m2/Run", ported.get("m2/Run.class"));
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        String trace;
        try {
            Object gui = loader.loadClass("g2.Gui").getConstructor().newInstance();
            Object reg = loader.loadClass("g2.Reg").getConstructor().newInstance();
            Object in = loader.loadClass("g2.In").getConstructor().newInstance();
            trace = String.valueOf(loader.loadClass("m2.Run").getMethods()[0].invoke(null, gui, reg, in));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        expect(trace.equals("type:x;draw;get;jump;clear;"),
            "the game's replacements (an added argument, an interface call, a narrower field, a chain, a dropped argument) run on the JVM: " + trace
                + " " + Json.array(results.get(0).get("unlinked")));
    }

    static GameDiff.Cls snap(GameDiff.Snap s, String name, int flags, String sup, String... methods) {
        GameDiff.Cls c = new GameDiff.Cls(name, Opcodes.ACC_PUBLIC | flags, sup == null ? "java/lang/Object" : sup, new ArrayList<>(), false);
        for (String m : methods) {
            int paren = m.indexOf('(');
            int access = Opcodes.ACC_PUBLIC | ((flags & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_ABSTRACT : 0);
            c.methods.put(m, new GameDiff.Meth(c, m.substring(0, paren), m.substring(paren), access));
        }
        s.classes.put(name, c);
        return c;
    }

    /** The new release's classes as real bytecode; each method appends its name to g2.Log.out. */
    static Map<String, byte[]> newGame() {
        Map<String, byte[]> out = new HashMap<>();
        ClassWriter log = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        log.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g2/Log", null, "java/lang/Object", null);
        log.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "out", "Ljava/lang/StringBuilder;", null, null).visitEnd();
        MethodVisitor init = log.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        log.visitEnd();
        out.put("g2/Log", log.toByteArray());
        out.put("g2/Holder", real("g2/Holder", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "get", "(Ljava/lang/String;)Ljava/lang/String;", null, null).visitEnd();
        }));
        out.put("g2/Gui", real("g2/Gui", "java/lang/Object", 0, new String[0], w -> {
            traced(w, "draw", "(Ljava/util/function/Function;Ljava/lang/String;)V", "draw;", m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitVarInsn(Opcodes.ALOAD, 2);
                m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                m.visitInsn(Opcodes.POP);
            });
            traced(w, "clear", "()V", "clear;", m -> { });
        }));
        out.put("g2/Reg", real("g2/Reg", "java/lang/Object", 0, new String[] { "g2/Holder" }, w -> {
            traced(w, "get", "(Ljava/lang/String;)Ljava/lang/String;", "get;", m -> { });
        }));
        out.put("g2/Keys", real("g2/Keys", "java/lang/Object", 0, new String[0], w -> traced(w, "jump", "()Z", "jump;", m -> { })));
        out.put("g2/Res$Ok", real("g2/Res$Ok", "g2/Res", 0, new String[0], w -> { }));
        out.put("g2/Res", real("g2/Res", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OK", "Lg2/Res$Ok;", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "g2/Res$Ok");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g2/Res$Ok", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, "g2/Res", "OK", "Lg2/Res$Ok;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g2/In", real("g2/In", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "keys", "Lg2/Keys;", null, null).visitEnd();
        }));
        out.put("g2/Type", real("g2/Type", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(Ljava/lang/String;)Lg2/Type;", null, null);
            m.visitCode();
            append(m, "type:");
            m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            append(m, ";");
            m.visitTypeInsn(Opcodes.NEW, "g2/Type");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g2/Type", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        return out;
    }

    /** A public class with a public no-argument constructor (In's sets keys) and whatever body adds. */
    static byte[] real(String name, String sup, int flags, String[] ifaces, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | flags, name, null, sup, ifaces);
        if ((flags & Opcodes.ACC_INTERFACE) == 0) {
            MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
            if (name.equals("g2/In")) {
                init.visitVarInsn(Opcodes.ALOAD, 0);
                init.visitTypeInsn(Opcodes.NEW, "g2/Keys");
                init.visitInsn(Opcodes.DUP);
                init.visitMethodInsn(Opcodes.INVOKESPECIAL, "g2/Keys", "<init>", "()V", false);
                init.visitFieldInsn(Opcodes.PUTFIELD, "g2/In", "keys", "Lg2/Keys;");
            }
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }

    /** A public method that runs before, appends text to g2.Log.out and returns its default value. */
    static void traced(ClassWriter w, String name, String desc, String text, java.util.function.Consumer<MethodVisitor> before) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        before.accept(m);
        append(m, text);
        Type ret = Type.getReturnType(desc);
        if (ret.getSort() == Type.VOID) {
            m.visitInsn(Opcodes.RETURN);
        } else if (ret.getSort() == Type.BOOLEAN) {
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IRETURN);
        } else {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitInsn(Opcodes.ARETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void append(MethodVisitor m, String text) {
        m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn(text);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        m.visitInsn(Opcodes.POP);
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    /**
     * One release: g/Block with a method and a field (renamed between the two), gone() and missing() and the interface
     * GoneIface only in the old one, sig() taking an int in the new one, closed() made private, protected prot() in both,
     * the interface Shape with a new abstract area(), and the class Old or New.
     */
    static GameDiff.Snap game(String version, String method, String field, String cls) {
        boolean old = version.equals("1.0");
        GameDiff.Snap s = new GameDiff.Snap(version);
        GameDiff.Cls block = new GameDiff.Cls("g/Block", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
        block.methods.put(method + "()V", new GameDiff.Meth(block, method, "()V", Opcodes.ACC_PUBLIC));
        block.fields.put(field, new GameDiff.Fld(block, field, "I", Opcodes.ACC_PUBLIC));
        block.methods.put("prot()V", new GameDiff.Meth(block, "prot", "()V", Opcodes.ACC_PROTECTED));
        String hid = old ? "hid" : "secret";
        GameDiff.Cls vault = new GameDiff.Cls("g/Vault", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
        vault.methods.put(hid + "()V", new GameDiff.Meth(vault, hid, "()V", old ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE));
        String inner = old ? "inner" : "hidden";
        vault.fields.put(inner, new GameDiff.Fld(vault, inner, "I", old ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE));
        GameDiff.Cls root = new GameDiff.Cls("g/Root", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
        root.methods.put((old ? "foo2" : "bar2") + "()V", new GameDiff.Meth(root, old ? "foo2" : "bar2", "()V", Opcodes.ACC_PUBLIC));
        GameDiff.Cls mid = new GameDiff.Cls("g/Mid", Opcodes.ACC_PUBLIC, "g/Root", List.of(), false);
        if (!old) {
            mid.methods.put("foo2()V", new GameDiff.Meth(mid, "foo2", "()V", Opcodes.ACC_PUBLIC));
        }
        GameDiff.Cls api = new GameDiff.Cls("g/Api", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", List.of(), false);
        api.methods.put((old ? "run" : "go") + "()V", new GameDiff.Meth(api, old ? "run" : "go", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
        for (GameDiff.Cls c : List.of(root, mid, api, vault)) {
            s.classes.put(c.name, c);
        }
        block.methods.put("closed()V", new GameDiff.Meth(block, "closed", "()V", old ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE));
        String sig = old ? "()V" : "(I)V";
        block.methods.put("sig" + sig, new GameDiff.Meth(block, "sig", sig, Opcodes.ACC_PUBLIC));
        int iface = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        GameDiff.Cls shape = new GameDiff.Cls("g/Shape", iface, "java/lang/Object", List.of(), false);
        if (old) {
            block.methods.put("gone()V", new GameDiff.Meth(block, "gone", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            block.methods.put("missing()V", new GameDiff.Meth(block, "missing", "()V", Opcodes.ACC_PUBLIC));
            s.classes.put("g/GoneIface", new GameDiff.Cls("g/GoneIface", iface, "java/lang/Object", List.of(), false));
        } else {
            shape.methods.put("area()D", new GameDiff.Meth(shape, "area", "()D", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
        }
        s.classes.put(block.name, block);
        s.classes.put(shape.name, shape);
        s.classes.put(cls, new GameDiff.Cls(cls, Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false));
        return s;
    }

    /** A class with no members beyond what its header says. */
    static byte[] empty(String name, int access, String sup, String... ifaces) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, access, name, null, sup, ifaces.length == 0 ? null : ifaces);
        w.visitEnd();
        return w.toByteArray();
    }

    /** A class (or interface, as a default method) with one empty method that overrides a game one. */
    static byte[] overriding(String name, int access, String sup, String iface, String method) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, access, name, null, sup, iface == null ? null : new String[] { iface });
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static void abstractMethod(ClassWriter w, String name, String desc, String annotation, String value) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null);
        AnnotationVisitor av = m.visitAnnotation(annotation, false);
        if (value != null) {
            av.visit("value", value);
        }
        av.visitEnd();
        m.visitEnd();
    }

    static byte[] mixin(String name, int access, String key, Object target, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, access, name, null, "java/lang/Object", null);
        AnnotationVisitor at = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor list = at.visitArray(key);
        list.visit(null, target);
        list.visitEnd();
        at.visitEnd();
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
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
        for (String call : List.of("sig", "closed", "prot")) {
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g/Block", call, "()V", false);
        }
        run.visitInsn(Opcodes.ACONST_NULL);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g/Vault", "hid", "()V", false);
        run.visitInsn(Opcodes.ACONST_NULL);
        run.visitFieldInsn(Opcodes.GETFIELD, "g/Vault", "inner", "I");
        run.visitInsn(Opcodes.POP);
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
        use.visitInsn(Opcodes.ACONST_NULL);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g/Vault", "hid", "()V", false);
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
        ClassWriter child = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        child.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m/MyChild", null, "m/MyBlock", null);
        MethodVisitor childFoo = child.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null);
        childFoo.visitCode();
        childFoo.visitInsn(Opcodes.RETURN);
        childFoo.visitMaxs(0, 1);
        childFoo.visitEnd();
        child.visitEnd();
        files.put("m/MyChild.class", child.toByteArray());
        files.put("m/Impl.class", empty("m/Impl", Opcodes.ACC_PUBLIC, "java/lang/Object", "g/GoneIface"));
        files.put("META-INF/accesstransformer.cfg", "public g.Vault *()\npublic g.Vault *\n".getBytes(StandardCharsets.UTF_8));
        files.put("m/ModMid.class", overriding("m/ModMid", Opcodes.ACC_PUBLIC, "g/Mid", null, "foo2"));
        files.put("m/ApiDefault.class", overriding("m/ApiDefault", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", "g/Api", "run"));
        files.put("m/Impl2.class", empty("m/Impl2", Opcodes.ACC_PUBLIC, "java/lang/Object", "m/ApiDefault"));
        files.put("m/Square.class", empty("m/Square", Opcodes.ACC_PUBLIC, "java/lang/Object", "g/Shape"));
        int abstractClass = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        files.put("m/OldMixin.class", mixin("m/OldMixin", abstractClass, "targets", "g.Old", w2 -> { }));
        files.put("m/BlockAccessor.class", mixin("m/BlockAccessor", abstractClass | Opcodes.ACC_INTERFACE, "value", Type.getObjectType("g/Block"), w2 -> {
            abstractMethod(w2, "value", "()I", "Lorg/spongepowered/asm/mixin/gen/Accessor;", "old");
            abstractMethod(w2, "getOld", "()I", "Lorg/spongepowered/asm/mixin/gen/Accessor;", null);
            abstractMethod(w2, "callFoo", "()V", "Lorg/spongepowered/asm/mixin/gen/Invoker;", "foo");
        }));
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
            public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String n) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String x, Object v) {
                                out.put("annotation " + n, String.valueOf(v));
                            }
                        };
                    }
                };
            }

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
                            public void visit(String n, Object v) {
                                body.append(n).append('=').append(v).append('\n');
                            }

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
