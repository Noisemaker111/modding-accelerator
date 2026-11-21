import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A value the new release takes where an object is made, which old code set later on the same object, on a two-release
 * game built here. 1.0: Btn.builder(label) gives a B; B.values(vals) and B.initial(v) give the B back; B.make() gives
 * "label:initial", or the first value when none was set. 2.0: Btn.builder(label, v) takes it, and B.initial is gone. The
 * game's own screens build buttons both ways, so GameDiff learns builder(label) ... initial(v) as builder(label, v), and
 * JarPort moves a mod's setter argument into the factory call (PortChains, "late") where it can be read there: a parameter,
 * a static field, a getter's result. Where the builder is used before the setter, the call stays and is reported.
 *
 *   java -cp asm.jar:CLASSES GameLateTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameLateTest {

    static final String U = "net/minecraft/l7/", B = "L" + U + "B;", STR = "Ljava/lang/String;", OBJ = "Ljava/lang/Object;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        GameDiff.Learner learner = new GameDiff.Learner(hop);
        List<String> lines = new ArrayList<>();
        Map<String, String> learned = new TreeMap<>();
        for (GameDiff.Learner.Learned l : learner.learn()) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        String late = null;
        for (GameDiff.Learner.Chained c : learner.chains) {
            lines.add(c.json("1.0 -> 2.0"));
            if (c.at() == -2) {
                late = c.expr();
            }
        }
        JarPortTest.expect("net.minecraft.l7.Btn.builder($1, $2)".equals(late), "the game's own builders show the later setter's value moving into the factory: " + late);
        JarPortTest.expect(!learned.containsKey("net.minecraft.l7.B.initial(" + OBJ + ")" + B), "the setter alone is not dropped: " + learned);

        Path map = scratch.resolve("route-late.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(mod()), "late.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
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
            Class<?> run = loader.loadClass("m7.Run");
            trace = run.getMethod("plain", String.class).invoke(null, "mine") + "|" + run.getField("CHOICE").get(null) + "|" + run.getMethod("called").invoke(null);
            for (String kept : List.of("early")) {
                try {
                    trace += "|" + kept + " ran: " + run.getMethod(kept).invoke(null);
                } catch (java.lang.reflect.InvocationTargetException left) {
                    trace += left.getCause() instanceof NoSuchMethodError ? "" : "|" + kept + ": " + left.getCause();
                }
            }
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + unlinked;
        }
        JarPortTest.expect(trace.equals("label:mine|label:b|label:c"), "a setter's argument moved into the factory runs on the new game as the old code behaved, and the refused ones keep the old call: " + trace);
        JarPortTest.expect(unlinked.contains("call: net.minecraft.l7.Btn.builder(" + STR + ")" + B) && unlinked.contains("call: net.minecraft.l7.B.initial(" + OBJ + ")" + B),
            "where the builder is used before the setter, the call stays and is reported: " + unlinked);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-late: 4 checks passed");
    }

    /**
     * m7.Run: plain(v) is Btn.builder(LABEL).values(VALS).initial(v).make(); CHOICE (a static field) is the same with the
     * static field PICK as the value; called() passes pick() (a call's result) and early() hands the builder to keep() first.
     */
    static Map<String, byte[]> mod() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m7/Run", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "PICK", OBJ, null, null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CHOICE", STR, null, null).visitEnd();
        for (String[] m : new String[][] { { "plain", "(" + STR + ")" + STR }, { "called", "()" + STR }, { "early", "()" + STR } }) {
            MethodVisitor v = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, m[0], m[1], null, null);
            v.visitCode();
            v.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "LABEL", STR);
            v.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Btn", "builder", "(" + STR + ")" + B, false);
            if (m[0].equals("early")) {
                v.visitInsn(Opcodes.DUP);
                v.visitMethodInsn(Opcodes.INVOKESTATIC, "m7/Run", "keep", "(" + B + ")V", false);
            }
            v.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "VALS", "[" + OBJ);
            v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "values", "([" + OBJ + ")" + B, false);
            switch (m[0]) {
                case "plain" -> v.visitVarInsn(Opcodes.ALOAD, 0);
                case "called" -> v.visitMethodInsn(Opcodes.INVOKESTATIC, "m7/Run", "pick", "()" + OBJ, false);
                default -> v.visitFieldInsn(Opcodes.GETSTATIC, "m7/Run", "PICK", OBJ);
            }
            v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "initial", "(" + OBJ + ")" + B, false);
            v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "make", "()" + STR, false);
            v.visitInsn(Opcodes.ARETURN);
            v.visitMaxs(0, 0);
            v.visitEnd();
        }
        MethodVisitor keep = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keep", "(" + B + ")V", null, null);
        keep.visitCode();
        keep.visitInsn(Opcodes.RETURN);
        keep.visitMaxs(0, 1);
        keep.visitEnd();
        MethodVisitor pick = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "()" + OBJ, null, null);
        pick.visitCode();
        pick.visitLdcInsn("c");
        pick.visitInsn(Opcodes.ARETURN);
        pick.visitMaxs(0, 0);
        pick.visitEnd();
        // CHOICE = Btn.builder(LABEL).values(VALS).initial(PICK).make(), with PICK = "b" first.
        MethodVisitor clinit = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("b");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "m7/Run", "PICK", OBJ);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "LABEL", STR);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Btn", "builder", "(" + STR + ")" + B, false);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "VALS", "[" + OBJ);
        clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "values", "([" + OBJ + ")" + B, false);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "m7/Run", "PICK", OBJ);
        clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "initial", "(" + OBJ + ")" + B, false);
        clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "make", "()" + STR, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "m7/Run", "CHOICE", STR);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        w.visitEnd();
        return Map.of("m7/Run.class", w.toByteArray());
    }

    /**
     * Btn.builder (see the class comment); B keeps label, vals and initial in fields. Ui.LABEL = "label", Ui.VALS = {"a"},
     * Ui.FIRST = "x"; Ui.one() and Ui.two() build buttons with FIRST as their starting value, the 1.0 way or the 2.0 way.
     */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "B", cls("B", w -> {
            for (String[] f : new String[][] { { "label", STR }, { "vals", "[" + OBJ }, { "init", OBJ } }) {
                w.visitField(Opcodes.ACC_PUBLIC, f[0], f[1], null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + STR + (two ? OBJ : "") + ")V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ALOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "B", "label", STR);
            if (two) {
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ALOAD, 2);
                c.visitFieldInsn(Opcodes.PUTFIELD, U + "B", "init", OBJ);
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            setter(w, "values", "vals", "[" + OBJ);
            if (!two) {
                setter(w, "initial", "init", OBJ);
            }
            // make(): label + ":" + (init != null ? init : vals[0])
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "make", "()" + STR, null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, U + "B", "init", OBJ);
            Label set = new Label(), join = new Label();
            m.visitJumpInsn(Opcodes.IFNONNULL, set);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, U + "B", "vals", "[" + OBJ);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitInsn(Opcodes.AALOAD);
            m.visitJumpInsn(Opcodes.GOTO, join);
            m.visitLabel(set);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, U + "B", "init", OBJ);
            m.visitLabel(join);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(" + OBJ + ")" + STR, false);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, U + "B", "label", STR);
            m.visitInsn(Opcodes.SWAP);
            m.visitLdcInsn(":");
            m.visitInsn(Opcodes.SWAP);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR.substring(1, STR.length() - 1), "concat", "(" + STR + ")" + STR, false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR.substring(1, STR.length() - 1), "concat", "(" + STR + ")" + STR, false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put(U + "Btn", cls("Btn", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builder", "(" + STR + (two ? OBJ : "") + ")" + B, null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, U + "B");
            m.visitInsn(Opcodes.DUP);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            if (two) {
                m.visitVarInsn(Opcodes.ALOAD, 1);
            }
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "B", "<init>", "(" + STR + (two ? OBJ : "") + ")V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put(U + "Ui", cls("Ui", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "LABEL", STR, null, null).visitEnd();
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALS", "[" + OBJ, null, null).visitEnd();
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FIRST", OBJ, null, null).visitEnd();
            MethodVisitor ci = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            ci.visitCode();
            ci.visitLdcInsn("label");
            ci.visitFieldInsn(Opcodes.PUTSTATIC, U + "Ui", "LABEL", STR);
            ci.visitInsn(Opcodes.ICONST_1);
            ci.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            ci.visitInsn(Opcodes.DUP);
            ci.visitInsn(Opcodes.ICONST_0);
            ci.visitLdcInsn("a");
            ci.visitInsn(Opcodes.AASTORE);
            ci.visitFieldInsn(Opcodes.PUTSTATIC, U + "Ui", "VALS", "[" + OBJ);
            ci.visitLdcInsn("x");
            ci.visitFieldInsn(Opcodes.PUTSTATIC, U + "Ui", "FIRST", OBJ);
            ci.visitInsn(Opcodes.RETURN);
            ci.visitMaxs(0, 0);
            ci.visitEnd();
            for (String name : List.of("one", "two")) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()" + STR, null, null);
                m.visitCode();
                m.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "LABEL", STR);
                if (two) {
                    m.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "FIRST", OBJ);
                }
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Btn", "builder", "(" + STR + (two ? OBJ : "") + ")" + B, false);
                m.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "VALS", "[" + OBJ);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "values", "([" + OBJ + ")" + B, false);
                if (!two) {
                    m.visitFieldInsn(Opcodes.GETSTATIC, U + "Ui", "FIRST", OBJ);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "initial", "(" + OBJ + ")" + B, false);
                }
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "B", "make", "()" + STR, false);
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        return out;
    }

    static void setter(ClassWriter w, String name, String field, String desc) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "(" + desc + ")" + B, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.PUTFIELD, U + "B", field, desc);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static byte[] cls(String simple, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, U + simple, null, "java/lang/Object", null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
