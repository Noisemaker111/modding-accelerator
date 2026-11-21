import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on calls the game replaced with the same call on different receivers, on a two-release game built
 * here. Key.shift() is gone in 2.0: two handlers that now take an Ev ask ev.shift(), and one other method asks
 * Mc.get().shift(). A mod's call has no Ev, but those two uses say what is called, so they count for Mc.get().shift().
 * Key.ctrl() is the same, except one place lined up with an unrelated constant: the reading the handlers back wins.
 * Pal.find(o) is refused: the handlers call find(o, this) with a value only they have as an argument, not only as the
 * receiver, so they disagree with the one place that passes Pal.none(). Id.of(s) became Id.path(s) where the game passed
 * a literal and Id.parse(s) where it passed a call's result: a mod's argument is a value no one can see in advance, so
 * it is Id.parse. The learned lines are applied by JarPort to a mod that then runs on this JVM.
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
        check(learned, t + "Ldr.bind()Ljava/lang/String;", t + "Ldr.current().bind()", "a static the class made an instance method, which the game never calls, goes through the accessor its code uses for the class's others");
        check(learned, t + "Id.of(Ljava/lang/String;)Ljava/lang/String;", t + "Id.parse($1)", "where the game wrote one call for a literal and another for a call's result, the reading for the call's result is the general one");

        Path map = scratch.resolve("route-split.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        String trace = run(a, b, map, newGame);
        JarPortTest.expect(trace.equals("truefalseXb"), "the mod's calls run on the new game: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-split: 6 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(Objects.equals(want, learned.get(key)), what + ": " + learned.get(key));
    }

    /** m6.Run.go(): "" + Key.shift() + Key.ctrl() + Id.of("x") + Ldr.bind(). */
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
        m.visitLdcInsn("x");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Id", "of", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ldr", "bind", "()Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
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
        // Ldr: dist(), prod() and bind() static in 1.0; instance methods of Ldr.current() in 2.0. Ui.boot asks dist and prod.
        out.put(U + "Ldr", cls("Ldr", w -> {
            constructor(w);
            constant(w, "dist", "()Z", !two, 1);
            constant(w, "prod", "()Z", !two, 0);
            MethodVisitor bd = w.visitMethod(Opcodes.ACC_PUBLIC | (two ? 0 : Opcodes.ACC_STATIC), "bind", "()Ljava/lang/String;", null, null);
            bd.visitCode();
            bd.visitLdcInsn("b");
            bd.visitInsn(Opcodes.ARETURN);
            bd.visitMaxs(0, 0);
            bd.visitEnd();
            if (two) {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "()L" + U + "Ldr;", null, null);
                c.visitCode();
                c.visitTypeInsn(Opcodes.NEW, U + "Ldr");
                c.visitInsn(Opcodes.DUP);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Ldr", "<init>", "()V", false);
                c.visitInsn(Opcodes.ARETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }
        }));
        // Id: of(s) in 1.0; parse(s) (upper case, a one-line wrapper of bySplit(s, ':')) and path(s) (lower case) in 2.0.
        out.put(U + "Id", cls("Id", w -> {
            if (two) {
                MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "parse", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                p.visitCode();
                p.visitVarInsn(Opcodes.ALOAD, 0);
                p.visitIntInsn(Opcodes.BIPUSH, ':');
                p.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Id", "bySplit", "(Ljava/lang/String;C)Ljava/lang/String;", false);
                p.visitInsn(Opcodes.ARETURN);
                p.visitMaxs(0, 0);
                p.visitEnd();
                MethodVisitor sp = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bySplit", "(Ljava/lang/String;C)Ljava/lang/String;", null, null);
                sp.visitCode();
                sp.visitVarInsn(Opcodes.ALOAD, 0);
                sp.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toUpperCase", "()Ljava/lang/String;", false);
                sp.visitInsn(Opcodes.ARETURN);
                sp.visitMaxs(0, 0);
                sp.visitEnd();
            }
            for (String[] f : two ? new String[][] { { "path", "toLowerCase" } } : new String[][] { { "of", "trim" } }) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, f[0], "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                m.visitCode();
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                m.visitInsn(Opcodes.POP);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", f[1], "()Ljava/lang/String;", false);
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
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
            MethodVisitor nm = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "name", "()Ljava/lang/String;", null, null);
            nm.visitCode();
            nm.visitLdcInsn("n");
            nm.visitInsn(Opcodes.ARETURN);
            nm.visitMaxs(0, 0);
            nm.visitEnd();
            MethodVisitor keep = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keep", "(Ljava/lang/String;)V", null, null);
            keep.visitCode();
            keep.visitInsn(Opcodes.RETURN);
            keep.visitMaxs(0, 1);
            keep.visitEnd();
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
            MethodVisitor boot = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "boot", "()V", null, null);
            boot.visitCode();
            for (String q : List.of("dist", "prod")) {
                if (two) {
                    boot.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ldr", "current", "()L" + U + "Ldr;", false);
                    boot.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ldr", q, "()Z", false);
                } else {
                    boot.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ldr", q, "()Z", false);
                }
                if (q.equals("dist")) {
                    boot.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "log", "(Z)V", false);
                } else {
                    boot.visitIntInsn(Opcodes.BIPUSH, 9);
                    boot.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "note", "(ZI)V", false);
                }
            }
            boot.visitInsn(Opcodes.RETURN);
            boot.visitMaxs(0, 0);
            boot.visitEnd();
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
        // keep(Id.of(...)): the handlers pass a call's result (parse in 2.0), the others a literal (path in 2.0).
        if (event) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "name", "()Ljava/lang/String;", false);
        } else {
            m.visitLdcInsn("lit");
        }
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Id", two ? (event ? "parse" : "path") : "of", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ui", "keep", "(Ljava/lang/String;)V", false);
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
