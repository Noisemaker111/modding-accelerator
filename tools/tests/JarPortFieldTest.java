import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Game fields still there with another type, run on this JVM against real classes of the new release. Level.random was
 * a java.util.Random and is an f9.Rand (as 1.19's RandomSource): a mod calling nextInt on it, or passing it to
 * Block.tick, whose parameter changed with it, reads it as the new type. Sounds.CLICK was a Sound and is a Holder of one
 * (as 1.19.3's sounds): a mod passing it to Player.play(Sound) gets holder.value(), as the game's own code reads it.
 * A read compared with == or passed where any object goes stays reported.
 */
final class JarPortFieldTest {

    static final String RANDOM = "Ljava/util/Random;", RAND = "Lf9/Rand;", SOUND = "Lf9/Sound;", HOLDER = "Lf9/Holder;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        field(JarPortTest.snap(a, "f9/Level", 0, null), "random", RANDOM, 0);
        field(JarPortTest.snap(b, "f9/Level", 0, null), "random", RAND, 0);
        field(a.classes.get("f9/Level"), "name", "Ljava/lang/String;", 0);
        field(b.classes.get("f9/Level"), "name", "Ljava/lang/String;", 0);
        JarPortTest.snap(b, "f9/Rand", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "nextInt(I)I");
        JarPortTest.snap(a, "f9/Block", 0, null, "tick(" + RANDOM + ")V");
        JarPortTest.snap(b, "f9/Block", 0, null, "tick(" + RAND + ")V");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "f9/Sound", 0, null);
            GameDiff.Cls player = JarPortTest.snap(s, "f9/Player", 0, null);
            player.methods.put("play(" + SOUND + ")V", new GameDiff.Meth(player, "play", "(" + SOUND + ")V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        }
        field(JarPortTest.snap(a, "f9/Sounds", 0, null), "CLICK", SOUND, Opcodes.ACC_STATIC);
        field(JarPortTest.snap(b, "f9/Sounds", 0, null), "CLICK", HOLDER, Opcodes.ACC_STATIC);
        JarPortTest.snap(b, "f9/Holder", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "value()Ljava/lang/Object;");
        field(a.classes.get("f9/Sounds"), "LAZY", SOUND, Opcodes.ACC_STATIC);
        field(b.classes.get("f9/Sounds"), "LAZY", "Ljava/util/function/Supplier;", Opcodes.ACC_STATIC);
        field(a.classes.get("f9/Level"), "reg", "Lf9/OldReg;", 0);
        field(b.classes.get("f9/Level"), "reg", "Lf9/Reg;", 0);
        JarPortTest.snap(a, "f9/OldReg", 0, null, "pick(" + RANDOM + ")I");
        JarPortTest.snap(b, "f9/Reg", 0, null, "pick(" + RAND + ")I");
        Path map = scratch.resolve("route-field.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "f9.Block", "from": "tick", "desc": "(Ljava/util/Random;)V", "to": "tick", "newDesc": "(Lf9/Rand;)V"},
                            {"owner": "f9.OldReg", "from": "pick", "desc": "(Ljava/util/Random;)I", "to": "pick", "newDesc": "(Lf9/Rand;)I"}]}
            """);
        JarPort port = new JarPort(a, b, map);
        // The new game's own code: twice a holder's value cast to the sound it holds, passed to play.
        Map<String, byte[]> newCode = Map.of("f9/Ui", ui(), "f9/Lazy", lazyUi());
        port.newCode = new JarPort.GameCode(newCode::get, null, newCode);
        port.oldCode = new JarPort.GameCode(Map.<String, byte[]>of()::get, null, Map.of());

        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("n9/Mod.class", mod())), "field.jar", runs));
        List<String> unlinked = Json.array(runs.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "reads of fields whose type changed link: as the new type where every use fits it, else through the holder's value: " + unlinked);

        List<Map<String, Object>> badRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n9/Bad.class", bad())), "bad.jar", badRuns);
        List<String> badUnlinked = Json.array(badRuns.get(0).get("unlinked")).stream().map(String::valueOf).sorted().toList();
        JarPortTest.expect(badUnlinked.equals(List.of("field: f9.Level.random")),
            "a read compared with == stays reported; one of a type the old release never gave it never linked and is left as written: " + badUnlinked);
        for (String[] alone : List.of(new String[] { "n9/Safe", "a read a null check carries to its branch (a Kotlin safe call)" },
            new String[] { "n9/Picks", "a call on a read whose new type's method takes other arguments (pick(Random) -> pick(Rand))" })) {
            List<Map<String, Object>> aloneRuns = new ArrayList<>();
            port.port(JarPortTest.jar(Map.of(alone[0] + ".class", alone[0].equals("n9/Safe") ? safe() : picks())), "alone.jar", aloneRuns);
            List<String> aloneUnlinked = Json.array(aloneRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
            JarPortTest.expect(aloneUnlinked.contains(alone[0].equals("n9/Safe") ? "field: f9.Level.random" : "field: f9.Level.reg"), alone[1] + " stays reported: " + aloneUnlinked);
        }

        // What the platforms answer to: the old one gave "oldname" for its module "api", which the new one no longer provides.
        JarPort names = new JarPort(a, b, map);
        Path was = scratch.resolve("api-old.jar"), now = scratch.resolve("api-new.jar");
        Files.write(was, JarPortTest.jar(Map.of("fabric.mod.json", "{\"id\": \"api\", \"provides\": [\"oldname\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "META-INF/jars/part.jar", JarPortTest.jar(Map.of("fabric.mod.json", "{\"id\": \"api-part\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        Files.write(now, JarPortTest.jar(Map.of("fabric.mod.json", "{\"id\": \"api\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "META-INF/jars/part.jar", JarPortTest.jar(Map.of("fabric.mod.json", "{\"id\": \"api-part\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
        names.platform(was, now);
        JarPortTest.expect(names.platformIds.equals(Set.of("api", "api-part")) && names.platformAliases.equals(Map.of("oldname", "api")),
            "the platform's modules and the old names it dropped are read from its two jars: " + names.platformIds + " " + names.platformAliases);
        List<Map<String, Object>> keyRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("n9/Keyed.class", keyed())), "keyed.jar", keyRuns);
        List<String> keyUnlinked = Json.array(keyRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(keyUnlinked.equals(List.of("field: f9.Level.random")), "a read passed where any object goes (a map key) stays reported: " + keyUnlinked);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(game());
        classes.put("n9/Mod", ported.get("n9/Mod.class"));
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
            Class<?> level = loader.loadClass("f9.Level"), block = loader.loadClass("f9.Block"), mod = loader.loadClass("n9.Mod");
            Object l = level.getConstructor().newInstance();
            Object roll = mod.getMethod("roll", level).invoke(null, l);
            mod.getMethod("tick", level, block).invoke(null, l, block.getConstructor().newInstance());
            mod.getMethod("click").invoke(null);
            mod.getMethod("lazy").invoke(null);
            trace = roll + ";" + loader.loadClass("g2.Log").getField("out").get(null);
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("42;next;tick;value;play;get;play;"), "the ported reads run against the new release: " + trace);
    }

    static void field(GameDiff.Cls c, String name, String desc, int flags) {
        c.fields.put(name, new GameDiff.Fld(c, name, desc, Opcodes.ACC_PUBLIC | flags));
    }

    /** f9.Ui.click(holder) and again(holder): play((Sound) holder.value()). */
    static byte[] ui() {
        ClassWriter w = JarPortRetypeTest.writer("f9/Ui", "java/lang/Object");
        for (String name : List.of("click", "again")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + HOLDER + ")V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "f9/Holder", "value", "()Ljava/lang/Object;", true);
            m.visitTypeInsn(Opcodes.CHECKCAST, "f9/Sound");
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "f9/Player", "play", "(" + SOUND + ")V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** f9.Lazy.a(supplier) and b(supplier): play((Sound) supplier.get()). */
    static byte[] lazyUi() {
        ClassWriter w = JarPortRetypeTest.writer("f9/Lazy", "java/lang/Object");
        for (String name : List.of("a", "b")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(Ljava/util/function/Supplier;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            m.visitTypeInsn(Opcodes.CHECKCAST, "f9/Sound");
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "f9/Player", "play", "(" + SOUND + ")V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** n9.Safe: safe(level) = level.random?.nextInt(5) ?: 0, as Kotlin writes it (the read duplicated, the copy to a null check). */
    static byte[] safe() {
        ClassWriter w = JarPortRetypeTest.writer("n9/Safe", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "safe", "(Lf9/Level;)I", null, null);
        m.visitCode();
        Label none = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "random", RANDOM);
        m.visitInsn(Opcodes.DUP);
        m.visitJumpInsn(Opcodes.IFNULL, none);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextInt", "(I)I", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(none);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n9.Picks: pick(level) = level.reg.pick(new Random()). */
    static byte[] picks() {
        ClassWriter w = JarPortRetypeTest.writer("n9/Picks", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Lf9/Level;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "reg", "Lf9/OldReg;");
        m.visitTypeInsn(Opcodes.NEW, "java/util/Random");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Random", "<init>", "()V", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "f9/OldReg", "pick", "(" + RANDOM + ")I", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n9.Mod: roll(level) = level.random.nextInt(5); tick(level, block): block.tick(level.random); click(): play(Sounds.CLICK); lazy(): play(Sounds.LAZY). */
    static byte[] mod() {
        ClassWriter w = JarPortRetypeTest.writer("n9/Mod", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "roll", "(Lf9/Level;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "random", RANDOM);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextInt", "(I)I", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(2, 1);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "(Lf9/Level;Lf9/Block;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "random", RANDOM);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "f9/Block", "tick", "(" + RANDOM + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "click", "()V", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "f9/Sounds", "CLICK", SOUND);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "f9/Player", "play", "(" + SOUND + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lazy", "()V", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "f9/Sounds", "LAZY", SOUND);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "f9/Player", "play", "(" + SOUND + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n9.Bad: same(level, r) = level.random == r; odd(level) reads name as an f9.Text. */
    static byte[] bad() {
        ClassWriter w = JarPortRetypeTest.writer("n9/Bad", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "same", "(Lf9/Level;" + RANDOM + ")Z", null, null);
        m.visitCode();
        Label no = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "random", RANDOM);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitJumpInsn(Opcodes.IF_ACMPNE, no);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(no);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "odd", "(Lf9/Level;)Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "name", "Lf9/Text;");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** n9.Keyed: key(level, map) = map.get(level.random). */
    static byte[] keyed() {
        ClassWriter w = JarPortRetypeTest.writer("n9/Keyed", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "key", "(Lf9/Level;Ljava/util/Map;)Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "f9/Level", "random", RANDOM);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new release as real classes: each call appends its name to g2.Log.out. */
    static Map<String, byte[]> game() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("f9/Rand", JarPortTest.real("f9/Rand", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w ->
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "nextInt", "(I)I", null, null).visitEnd()));
        out.put("f9/Dice", JarPortTest.real("f9/Dice", "java/lang/Object", 0, new String[] { "f9/Rand" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "nextInt", "(I)I", null, null);
            m.visitCode();
            JarPortTest.append(m, "next;");
            m.visitIntInsn(Opcodes.BIPUSH, 42);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("f9/Level", level());
        out.put("f9/Block", JarPortTest.real("f9/Block", "java/lang/Object", 0, new String[0], w -> JarPortTest.traced(w, "tick", "(" + RAND + ")V", "tick;", m -> { })));
        out.put("f9/Sound", JarPortTest.real("f9/Sound", "java/lang/Object", 0, new String[0], w -> { }));
        out.put("f9/Player", JarPortTest.real("f9/Player", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "play", "(" + SOUND + ")V", null, null);
            m.visitCode();
            JarPortTest.append(m, "play;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("f9/Holder", JarPortTest.real("f9/Holder", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w ->
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()Ljava/lang/Object;", null, null).visitEnd()));
        out.put("f9/Ref", JarPortTest.real("f9/Ref", "java/lang/Object", 0, new String[] { "f9/Holder" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/Object;", null, null);
            m.visitCode();
            JarPortTest.append(m, "value;");
            m.visitTypeInsn(Opcodes.NEW, "f9/Sound");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f9/Sound", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("f9/Later", JarPortTest.real("f9/Later", "java/lang/Object", 0, new String[] { "java/util/function/Supplier" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
            m.visitCode();
            JarPortTest.append(m, "get;");
            m.visitTypeInsn(Opcodes.NEW, "f9/Sound");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f9/Sound", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("f9/Sounds", JarPortTest.real("f9/Sounds", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CLICK", HOLDER, null, null).visitEnd();
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "LAZY", "Ljava/util/function/Supplier;", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "f9/Ref");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f9/Ref", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, "f9/Sounds", "CLICK", HOLDER);
            m.visitTypeInsn(Opcodes.NEW, "f9/Later");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f9/Later", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, "f9/Sounds", "LAZY", "Ljava/util/function/Supplier;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        return out;
    }

    /** f9.Level with random a new f9.Dice. */
    static byte[] level() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "f9/Level", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC, "random", RAND, null, null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitTypeInsn(Opcodes.NEW, "f9/Dice");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "f9/Dice", "<init>", "()V", false);
        m.visitFieldInsn(Opcodes.PUTFIELD, "f9/Level", "random", RAND);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}
