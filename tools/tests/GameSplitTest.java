import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on calls the game replaced with the same call on different receivers, on a two-release game built
 * here. Key.shift() is gone in 2.0: two handlers that now take an Ev ask ev.shift(), and one other method asks
 * Mc.get().shift(). A mod's call has no Ev, but those two uses say what is called, so they count for Mc.get().shift().
 * Key.ctrl() is the same, except one place lined up with an unrelated constant: the reading the handlers back wins.
 * Pal.find(o) is refused: the handlers call find(o, this) with a value only they have as an argument, not only as the
 * receiver, so they disagree with the one place that passes Pal.none(). The learned lines are applied by JarPort to a
 * mod that then runs on this JVM.
 *
 *   java -cp asm.jar:CLASSES GameSplitTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameSplitTest {

    static final String U = "net/minecraft/t6/";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        Map<String, String> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (GameDiff.Learner.Learned l : new GameDiff.Learner(hop).learn()) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        String t = "net.minecraft.t6.";
        check(learned, t + "Key.shift()Z", t + "Mc.get().shift()", "uses that make the same call on a value only their method has count for the reading the other uses show");
        check(learned, t + "Key.ctrl()Z", t + "Mc.get().ctrl()", "the reading those uses back wins over one place that lined up with something else");
        check(learned, t + "Pal.find(Ljava/lang/Object;)I", null, "uses that pass a value only their method has as an argument count against");

        Path map = scratch.resolve("route-split.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        String trace = run(a, b, map, newGame);
        JarPortTest.expect(trace.equals("truefalse"), "the mod's calls run on the new game: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-split: 4 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(Objects.equals(want, learned.get(key)), what + ": " + learned.get(key));
    }

    /** m6.Run.go(): "" + Key.shift() + Key.ctrl(). */
    static String run(GameDiff.Snap a, GameDiff.Snap b, Path map, Map<String, byte[]> newGame) throws Exception {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m6/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        for (String key : List.of("shift", "ctrl")) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Key", key, "()Z", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m6/Run.class", w.toByteArray())), "split.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m6/Run", ported.get("m6/Run.class"));
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
        try {
            return String.valueOf(loader.loadClass("m6.Run").getMethods()[0].invoke(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
    }

    /**
     * 1.0: Key.shift(), Key.ctrl(), Pal.find(o). 2.0: Ev and Mc each have shift() (true) and ctrl() (false); Mc.get();
     * Pal.find(o, owner) and Pal.none(); Cfg.FLAG. Ui's three handlers take an int in 1.0 and an Ev in 2.0; Ui.tick and
     * Ui.idle keep their signature.
     */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!two) {
            out.put(U + "Key", cls("Key", w -> {
                constant(w, "shift", "()Z", true, 1);
                constant(w, "ctrl", "()Z", true, 0);
            }));
        } else {
            for (String c : List.of("Ev", "Mc")) {
                out.put(U + c, cls(c, w -> {
                    constructor(w);
                    constant(w, "shift", "()Z", false, 1);
                    constant(w, "ctrl", "()Z", false, 0);
                    if (c.equals("Mc")) {
                        MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()L" + U + "Mc;", null, null);
                        g.visitCode();
                        g.visitTypeInsn(Opcodes.NEW, U + "Mc");
                        g.visitInsn(Opcodes.DUP);
                        g.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Mc", "<init>", "()V", false);
                        g.visitInsn(Opcodes.ARETURN);
                        g.visitMaxs(0, 0);
                        g.visitEnd();
                    }
                }));
            }
            out.put(U + "Cfg", cls("Cfg", w -> w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FLAG", "Z", null, null).visitEnd()));
        }
        out.put(U + "Pal", cls("Pal", w -> {
            constructor(w);
            MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC, "find", two ? "(Ljava/lang/Object;Ljava/lang/Object;)I" : "(Ljava/lang/Object;)I", null, null);
            f.visitCode();
            f.visitInsn(Opcodes.ICONST_3);
            f.visitInsn(Opcodes.IRETURN);
            f.visitMaxs(0, 0);
            f.visitEnd();
            if (two) {
                MethodVisitor n = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "none", "()Ljava/lang/Object;", null, null);
                n.visitCode();
                n.visitInsn(Opcodes.ACONST_NULL);
                n.visitInsn(Opcodes.ARETURN);
                n.visitMaxs(0, 0);
                n.visitEnd();
            }
        }));
        // Ui: onKey and onClick ask shift, ctrl and find in each release; tick asks them too, the other way in 2.0.
        out.put(U + "Ui", cls("Ui", w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "pal", "L" + U + "Pal;", null, null).visitEnd();
            constructor(w);
            for (String sink : List.of("log", "note")) {
                MethodVisitor log = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, sink, sink.equals("log") ? "(Z)V" : "(ZI)V", null, null);
                log.visitCode();
                log.visitInsn(Opcodes.RETURN);
                log.visitMaxs(0, 2);
                log.visitEnd();
            }
            for (String name : List.of("onKey", "onClick", "onScroll")) {
                handler(w, name, two, true, false);
            }
            handler(w, "tick", two, false, true);
            handler(w, "idle", two, false, false);
        }));
        return out;
    }

    /**
     * name: log(shift); note(ctrl, 9); pal.find("o"). In 2.0, asked of the Ev parameter (event) or Mc.get(), and find passes
     * this (event) or Pal.none(); with flag, ctrl lines up with Cfg.FLAG instead.
     */
    static void handler(ClassWriter w, String name, boolean two, boolean event, boolean flag) {
        String on = event ? "Ev" : "Mc";
        String desc = two && event ? "(L" + U + "Ev;)V" : event ? "(I)V" : "()V";
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        for (String key : List.of("shift", "ctrl")) {
            if (!two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Key", key, "()Z", false);
            } else if (flag && key.equals("ctrl")) {
                m.visitFieldInsn(Opcodes.GETSTATIC, U + "Cfg", "FLAG", "Z");
            } else {
                if (event) {
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                } else {
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Mc", "get", "()L" + U + "Mc;", false);
                }
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + on, key, "()Z", false);
            }
            if (key.equals("shift")) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "log", "(Z)V", false);
            } else {
                m.visitIntInsn(Opcodes.BIPUSH, 9);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "note", "(ZI)V", false);
            }
        }
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, U + "Ui", "pal", "L" + U + "Pal;");
        m.visitLdcInsn("o");
        if (two) {
            if (event) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
            } else {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Pal", "none", "()Ljava/lang/Object;", false);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Pal", "find", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
        } else {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Pal", "find", "(Ljava/lang/Object;)I", false);
        }
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void constant(ClassWriter w, String name, String desc, boolean isStatic, int value) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), name, desc, null, null);
        m.visitCode();
        m.visitInsn(value == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void constructor(ClassWriter w) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    static byte[] cls(String simple, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, U + simple, null, "java/lang/Object", null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
