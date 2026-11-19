import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on a two-release game built here as real bytecode, end to end: the game's own code shows how it
 * ported its uses of changed members, the learner writes each replacement as an expression over the old operands, JarPort
 * applies it to a mod, and the ported mod runs on this JVM against the new release.
 *
 * Release 1.0 has Gui.blit(String, int, int) and the field In.jumping; 2.0 has Gui.blit(Function, String, float, float,
 * int) and In.keys.jump(). The game's Screen calls blit with constants (0, 0 makes u and v ambiguous) and with a
 * parameter, and reads jumping once. Spawner.create(String) became create(String, Reason) and the game passes another
 * Reason at each of its two uses, so nothing may be learned for it.
 *
 *   java -cp asm.jar:CLASSES GameLearnTest SCRATCH   (run by JarPortTest's check in bench/run_tests.py)
 */
final class GameLearnTest {

    static final String T = "net/minecraft/t/", STR = "Ljava/lang/String;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        Map<String, GameDiff.Learner.Learned> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (GameDiff.Learner.Learned l : new GameDiff.Learner(hop).learn()) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l);
            lines.add(l.json("1.0 -> 2.0"));
        }
        String blit = expr(learned, "net.minecraft.t.Gui.blit(" + STR + "II)V");
        JarPortTest.expect("$0.blit(net.minecraft.t.Type::make, $1, (float) $2, (float) $3, 256)".equals(blit),
            "an added method reference, int arguments made float and a constant the game always passes are learned, with each "
                + "argument from its own operand although two game uses passed 0 for both: " + blit);
        String jumping = expr(learned, "net.minecraft.t.In.jumping");
        JarPortTest.expect("$0.keys.jump()".equals(jumping), "a field read the game replaced with a chain is learned: " + jumping);
        JarPortTest.expect(!learned.containsKey("net.minecraft.t.Spawner.create(" + STR + ")V"),
            "a member whose game uses pass different values is not learned: " + learned.keySet());
        String fill = expr(learned, "net.minecraft.t.Gui.fill(II)V");
        JarPortTest.expect("$0.fill($1, $2, -1)".equals(fill), "where every game use passes the same constant twice, each argument keeps its own operand: " + fill);
        JarPortTest.expect(!learned.containsKey("net.minecraft.t.Gui.tint(L" + T + "In;)V"),
            "a parameter in the same place but of another type in the new code is not the old operand: " + learned.keySet());

        Path map = scratch.resolve("route-learned.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        ClassWriter run = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        run.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/Run", null, "java/lang/Object", null);
        MethodVisitor go = run.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + T + "Gui;L" + T + "In;)" + STR, null, null);
        go.visitCode();
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitLdcInsn("m");
        go.visitIntInsn(Opcodes.BIPUSH, 7);
        go.visitIntInsn(Opcodes.BIPUSH, 9);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Gui", "blit", "(" + STR + "II)V", false);
        go.visitVarInsn(Opcodes.ALOAD, 1);
        go.visitFieldInsn(Opcodes.GETFIELD, T + "In", "jumping", "Z");
        go.visitInsn(Opcodes.POP);
        go.visitFieldInsn(Opcodes.GETSTATIC, T + "Log", "out", "Ljava/lang/StringBuilder;");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        go.visitInsn(Opcodes.ARETURN);
        go.visitMaxs(0, 2);
        go.visitEnd();
        run.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m7/Run.class", run.toByteArray())), "learned.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m7/Run", ported.get("m7/Run.class"));
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
            Object gui = loader.loadClass("net.minecraft.t.Gui").getConstructor().newInstance();
            Object in = loader.loadClass("net.minecraft.t.In").getConstructor().newInstance();
            trace = String.valueOf(loader.loadClass("m7.Run").getMethods()[0].invoke(null, gui, in));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("make:m;blit:m,7.0,9.0,256;jump;"),
            "the learned replacements run in the ported mod on the JVM: " + trace + " " + Json.array(results.get(0).get("unlinked")));
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-learn: 6 checks passed");
    }

    static String expr(Map<String, GameDiff.Learner.Learned> learned, String key) {
        GameDiff.Learner.Learned l = learned.get(key);
        return l == null ? "nothing learned" : l.expr();
    }

    /** One release of the little game, every class as real bytecode; each 2.0 method leaves a trace in Log.out. */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(T + "Log", cls("Log", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "out", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, T + "Log", "out", "Ljava/lang/StringBuilder;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }, null));
        out.put(T + "Type", cls("Type", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(" + STR + ")L" + T + "Type;", null, null);
            m.visitCode();
            trace(m, "make:", 0, "Ljava/lang/Object;", ";");
            m.visitTypeInsn(Opcodes.NEW, T + "Type");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, T + "Type", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }, null));
        out.put(T + "Gui", cls("Gui", w -> {
            String desc = two ? "(Ljava/util/function/Function;" + STR + "FFI)V" : "(" + STR + "II)V";
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "blit", desc, null, null);
            m.visitCode();
            if (two) {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitVarInsn(Opcodes.ALOAD, 2);
                m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                m.visitInsn(Opcodes.POP);
                trace(m, "blit:", 2, "Ljava/lang/Object;", ",");
                trace(m, null, 3, "F", ",");
                trace(m, null, 4, "F", ",");
                trace(m, null, 5, "I", ";");
            }
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            for (String[] other : new String[][] { { "fill", two ? "(III)V" : "(II)V" }, { "tint", two ? "(L" + T + "Keys;)V" : "(L" + T + "In;)V" } }) {
                MethodVisitor o = w.visitMethod(Opcodes.ACC_PUBLIC, other[0], other[1], null, null);
                o.visitCode();
                o.visitInsn(Opcodes.RETURN);
                o.visitMaxs(0, 0);
                o.visitEnd();
            }
        }, null));
        if (two) {
            out.put(T + "Keys", cls("Keys", w -> {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "jump", "()Z", null, null);
                m.visitCode();
                trace(m, "jump;", -1, null, null);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }, null));
            out.put(T + "Reason", cls("Reason", w -> {
                w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "NATURAL", "L" + T + "Reason;", null, null).visitEnd();
                w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SPAWNER", "L" + T + "Reason;", null, null).visitEnd();
            }, null));
        }
        out.put(T + "In", cls("In", w -> w.visitField(Opcodes.ACC_PUBLIC, two ? "keys" : "jumping", two ? "L" + T + "Keys;" : "Z", null, null).visitEnd(),
            two ? init -> {
                init.visitVarInsn(Opcodes.ALOAD, 0);
                init.visitTypeInsn(Opcodes.NEW, T + "Keys");
                init.visitInsn(Opcodes.DUP);
                init.visitMethodInsn(Opcodes.INVOKESPECIAL, T + "Keys", "<init>", "()V", false);
                init.visitFieldInsn(Opcodes.PUTFIELD, T + "In", "keys", "L" + T + "Keys;");
            } : null));
        out.put(T + "Spawner", cls("Spawner", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "create", two ? "(" + STR + "L" + T + "Reason;)V" : "(" + STR + ")V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }, null));
        out.put(T + "Screen", cls("Screen", w -> {
            // render(gui, in, spawner): blit("a", 0, 0); blit("b", 3, 4); if (in.jumping) blit("j", 1, 2); spawner.create("x")
            MethodVisitor r = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(L" + T + "Gui;L" + T + "In;L" + T + "Spawner;)V", null, null);
            r.visitCode();
            blit(r, two, "a", 0, 0, -1);
            blit(r, two, "b", 3, 4, -1);
            Label skip = new Label();
            r.visitVarInsn(Opcodes.ALOAD, 2);
            if (two) {
                r.visitFieldInsn(Opcodes.GETFIELD, T + "In", "keys", "L" + T + "Keys;");
                r.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Keys", "jump", "()Z", false);
            } else {
                r.visitFieldInsn(Opcodes.GETFIELD, T + "In", "jumping", "Z");
            }
            r.visitJumpInsn(Opcodes.IFEQ, skip);
            blit(r, two, "j", 1, 2, -1);
            r.visitLabel(skip);
            create(r, two, 3, "x", "NATURAL");
            fill(r, two);
            r.visitInsn(Opcodes.RETURN);
            r.visitMaxs(0, 0);
            r.visitEnd();
            // paint(gui, x, spawner): blit("c", x, 5); spawner.create("y"); gui.fill(0, 0)
            MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC, "paint", "(L" + T + "Gui;IL" + T + "Spawner;)V", null, null);
            p.visitCode();
            blit(p, two, "c", 0, 5, 2);
            create(p, two, 3, "y", "SPAWNER");
            fill(p, two);
            p.visitInsn(Opcodes.RETURN);
            p.visitMaxs(0, 0);
            p.visitEnd();
            // draw(gui, in) in 1.0 and draw(gui, keys) in 2.0: gui.tint(the second parameter)
            String param = two ? "L" + T + "Keys;" : "L" + T + "In;";
            MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(L" + T + "Gui;" + param + ")V", null, null);
            d.visitCode();
            d.visitVarInsn(Opcodes.ALOAD, 1);
            d.visitVarInsn(Opcodes.ALOAD, 2);
            d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Gui", "tint", "(" + param + ")V", false);
            d.visitInsn(Opcodes.RETURN);
            d.visitMaxs(0, 0);
            d.visitEnd();
        }, null));
        return out;
    }

    /** gui (local 1).fill(0, 0) in 1.0, fill(0, 0, -1) in 2.0. */
    static void fill(MethodVisitor m, boolean two) {
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.ICONST_0);
        if (two) {
            m.visitInsn(Opcodes.ICONST_M1);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Gui", "fill", two ? "(III)V" : "(II)V", false);
    }

    /** gui (local 1).blit(tex, u, v) in 1.0, gui.blit(Type::make, tex, u, v, 256) in 2.0; u from local fromLocal when it is not -1. */
    static void blit(MethodVisitor m, boolean two, String tex, int u, int v, int fromLocal) {
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (two) {
            m.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESTATIC, T + "Type", "make", "(" + STR + ")L" + T + "Type;", false),
                Type.getType("(" + STR + ")L" + T + "Type;"));
        }
        m.visitLdcInsn(tex);
        if (fromLocal >= 0) {
            m.visitVarInsn(Opcodes.ILOAD, fromLocal);
            if (two) {
                m.visitInsn(Opcodes.I2F);
            }
        } else {
            m.visitLdcInsn(two ? (Object) (float) u : (Object) u);
        }
        m.visitLdcInsn(two ? (Object) (float) v : (Object) v);
        if (two) {
            m.visitIntInsn(Opcodes.SIPUSH, 256);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Gui", "blit", two ? "(Ljava/util/function/Function;" + STR + "FFI)V" : "(" + STR + "II)V", false);
    }

    static void create(MethodVisitor m, boolean two, int local, String where, String reason) {
        m.visitVarInsn(Opcodes.ALOAD, local);
        m.visitLdcInsn(where);
        if (two) {
            m.visitFieldInsn(Opcodes.GETSTATIC, T + "Reason", reason, "L" + T + "Reason;");
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, T + "Spawner", "create", two ? "(" + STR + "L" + T + "Reason;)V" : "(" + STR + ")V", false);
    }

    /** Log.out gets before, then local (a value of type desc) unless it is -1, then after. */
    static void trace(MethodVisitor m, String before, int local, String desc, String after) {
        m.visitFieldInsn(Opcodes.GETSTATIC, T + "Log", "out", "Ljava/lang/StringBuilder;");
        if (before != null) {
            m.visitLdcInsn(before);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")Ljava/lang/StringBuilder;", false);
        }
        if (local >= 0) {
            m.visitVarInsn(Type.getType(desc).getOpcode(Opcodes.ILOAD), local);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
        }
        if (after != null) {
            m.visitLdcInsn(after);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")Ljava/lang/StringBuilder;", false);
        }
        m.visitInsn(Opcodes.POP);
    }

    /** A public class of the game with a public no-argument constructor (init adds to it) and whatever body adds. */
    static byte[] cls(String simple, java.util.function.Consumer<ClassWriter> body, java.util.function.Consumer<MethodVisitor> init) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, T + simple, null, "java/lang/Object", null);
        MethodVisitor ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (init != null) {
            init.accept(ctor);
        }
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
