import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on calls the game removed and classes it replaced, on a two-release game built here. 2.0 drops
 * Buf.endVertex() (the vertex chains just end, one also rewritten by the game itself) and GL.disableTexture() (the
 * statement is gone), and replaces Text with Mut (new Text(s) becomes Mut.literal(s); a linked method's parameter
 * switches type). Refused: GL.flush(), which the game replaced in most places, and Buf.copy(), which returns a new Buf, not
 * its receiver. The learned replacements are applied by JarPort to a mod that then runs on this JVM against 2.0.
 *
 *   java -cp asm.jar:CLASSES GameRemovedTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameRemovedTest {

    static final String U = "net/minecraft/r/", STR = "Ljava/lang/String;", OBJ = "Ljava/lang/Object;", BUF = "L" + U + "Buf;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        GameDiff.Learner learner = new GameDiff.Learner(hop);
        Map<String, String> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (GameDiff.Learner.Learned l : learner.learn()) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        List<String> classes = new ArrayList<>();
        for (GameDiff.Hop.ClassRewrite r : learner.typeRewrites) {
            classes.add("{\"step\": \"1.0 -> 2.0\", \"from\": \"" + r.from().name.replace('/', '.') + "\", \"to\": \"" + r.to().name.replace('/', '.') + "\", \"votes\": " + r.votes() + ", \"of\": " + r.total() + "}");
        }
        String r = "net.minecraft.r.";
        check(learned, r + "Buf.endVertex()V", "", "a void call whose vertex chains just end is dropped, also where the game rewrote the chain");
        check(learned, r + "GL.disableTexture()V", "", "a void call whose statement is gone between statements that line up is dropped");
        JarPortTest.expect(!"".equals(learned.get(r + "GL.flush()V")), "a call the game replaced in most places is not dropped: " + learned.get(r + "GL.flush()V"));
        JarPortTest.expect(!learned.containsKey(r + "Buf.copy()" + BUF), "a call that returns a new object, not its receiver, is not read as its receiver: " + learned.get(r + "Buf.copy()" + BUF));
        JarPortTest.expect(classes.size() == 1 && classes.get(0).contains("\"" + r + "Text\", \"to\": \"" + r + "Mut\""),
            "the class the game's own types and values switched to is the class map: " + classes);
        check(learned, r + "Text.<init>(" + STR + ")V", r + "Mut.literal($1)", "a gone class's constructor is learned through the class map");
        check(learned, r + "GL.tell(" + STR + "I)V", r + "GL.say($1)", "an operand lines up with the game's new code also where it holds another changed call");
        check(learned, r + "GL.enableTexture()V", "", "a call with no operands paired only as the one statement left in its gap is no vote against its drop");
        check(learned, r + "Env.dist", r + "Ldr.current().dist()", "a one-line wrapper and its body are one reading");

        Path map = scratch.resolve("route-removed.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "], \"classRewrites\": [" + String.join(",\n", classes) + "]}");
        String trace = run(a, b, map, newGame);
        JarPortTest.expect(trace.equals("v1.0c5|texsay:n4|mut:hi|mut:yo"),
            "endVertex and disableTexture are dropped with their receiver's chain and arguments still run, new Text and Text::new become Mut.literal, on the JVM: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-removed: 10 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(want.equals(learned.get(key)), what + ": " + learned.get(key));
    }

    /**
     * m9.Run.go(buf, sink): buf.vertex(1).color(5).endVertex(); GL.disableTexture() after GL.log("tex"); GL.tell("n4", 9); sink.take(new
     * Text("hi")); sink.take(((Function) Text::new).apply("yo")); return buf + "|" + GL.LOG + "|" + sink.
     */
    static String run(GameDiff.Snap a, GameDiff.Snap b, Path map, Map<String, byte[]> newGame) throws Exception {
        ClassWriter w = writer();
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m9/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(" + BUF + "L" + U + "Sink;)" + STR, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "vertex", "(F)" + BUF, false);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "color", "(I)" + BUF, false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "endVertex", "()V", false);
        m.visitLdcInsn("tex");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        // Object t = sink; if (buf == null) { t = 0; GL.log("none"); } else GL.disableTexture(); the dropped call is all there is
        // between two frames, and the second differs from the first (t is no longer one type after the join).
        Label notNull = new Label(), after = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ASTORE, 3);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitVarInsn(Opcodes.ISTORE, 3);
        m.visitLdcInsn("none");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        m.visitJumpInsn(Opcodes.GOTO, after);
        m.visitLabel(notNull);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "disableTexture", "()V", false);
        m.visitLabel(after);
        m.visitLdcInsn("n4");
        m.visitIntInsn(Opcodes.BIPUSH, 9);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "tell", "(" + STR + "I)V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitTypeInsn(Opcodes.NEW, U + "Text");
        m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn("hi");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Text", "<init>", "(" + STR + ")V", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "take", "(" + OBJ + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        m.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", factory, Type.getType("(" + OBJ + ")" + OBJ),
            new Handle(Opcodes.H_NEWINVOKESPECIAL, U + "Text", "<init>", "(" + STR + ")V", false), Type.getType("(" + STR + ")L" + U + "Text;"));
        m.visitLdcInsn("yo");
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(" + OBJ + ")" + OBJ, true);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "take", "(" + OBJ + ")V", false);
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        append(m, OBJ);
        m.visitLdcInsn("|");
        append(m, STR);
        m.visitFieldInsn(Opcodes.GETSTATIC, U + "GL", "LOG", "Ljava/lang/StringBuilder;");
        append(m, OBJ);
        m.visitLdcInsn("|");
        append(m, STR);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        append(m, OBJ);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m9/Run.class", w.toByteArray())), "removed.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m9/Run", ported.get("m9/Run.class"));
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
            Object buf = loader.loadClass("net.minecraft.r.Buf").getConstructor().newInstance();
            Object sink = loader.loadClass("net.minecraft.r.Sink").getConstructor().newInstance();
            return String.valueOf(loader.loadClass("m9.Run").getMethods()[0].invoke(null, buf, sink));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
    }

    static void append(MethodVisitor m, String desc) {
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
    }

    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        // Buf: vertex(F) and color(I) log and return this; endVertex() in 1.0 only; copy() returns a new Buf, in 1.0 only.
        out.put(U + "Buf", cls("Buf", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "log", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            c.visitInsn(Opcodes.DUP);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Buf", "log", "Ljava/lang/StringBuilder;");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            logStep(w, "vertex", "F", "v");
            logStep(w, "color", "I", "c");
            MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()" + STR, null, null);
            s.visitCode();
            s.visitVarInsn(Opcodes.ALOAD, 0);
            s.visitFieldInsn(Opcodes.GETFIELD, U + "Buf", "log", "Ljava/lang/StringBuilder;");
            s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
            s.visitInsn(Opcodes.ARETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
            if (!two) {
                MethodVisitor e = w.visitMethod(Opcodes.ACC_PUBLIC, "endVertex", "()V", null, null);
                e.visitCode();
                e.visitVarInsn(Opcodes.ALOAD, 0);
                e.visitFieldInsn(Opcodes.GETFIELD, U + "Buf", "log", "Ljava/lang/StringBuilder;");
                e.visitLdcInsn(";");
                append(e, STR);
                e.visitInsn(Opcodes.POP);
                e.visitInsn(Opcodes.RETURN);
                e.visitMaxs(0, 0);
                e.visitEnd();
                MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "copy", "()" + BUF, null, null);
                k.visitCode();
                k.visitTypeInsn(Opcodes.NEW, U + "Buf");
                k.visitInsn(Opcodes.DUP);
                k.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Buf", "<init>", "()V", false);
                k.visitInsn(Opcodes.ARETURN);
                k.visitMaxs(0, 0);
                k.visitEnd();
            }
        }));
        // GL: static LOG; log(s); enableBlend(); disableTexture() and flush() in 1.0 only, commit() in 2.0 only.
        out.put(U + "GL", cls("GL", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "LOG", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            MethodVisitor ci = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            ci.visitCode();
            ci.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            ci.visitInsn(Opcodes.DUP);
            ci.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            ci.visitFieldInsn(Opcodes.PUTSTATIC, U + "GL", "LOG", "Ljava/lang/StringBuilder;");
            ci.visitInsn(Opcodes.RETURN);
            ci.visitMaxs(0, 0);
            ci.visitEnd();
            MethodVisitor l = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "log", "(" + STR + ")V", null, null);
            l.visitCode();
            l.visitFieldInsn(Opcodes.GETSTATIC, U + "GL", "LOG", "Ljava/lang/StringBuilder;");
            l.visitVarInsn(Opcodes.ALOAD, 0);
            append(l, STR);
            l.visitInsn(Opcodes.POP);
            l.visitInsn(Opcodes.RETURN);
            l.visitMaxs(0, 0);
            l.visitEnd();
            // name(int) in 1.0, name(long) in 2.0: "n" + the number; tell(s, n) logs s in 1.0, say(s) logs "say:" + s in 2.0.
            MethodVisitor nm = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "name", two ? "(J)" + STR : "(I)" + STR, null, null);
            nm.visitCode();
            nm.visitLdcInsn("n");
            nm.visitVarInsn(two ? Opcodes.LLOAD : Opcodes.ILOAD, 0);
            nm.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", two ? "(J)" + STR : "(I)" + STR, false);
            nm.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(" + STR + ")" + STR, false);
            nm.visitInsn(Opcodes.ARETURN);
            nm.visitMaxs(0, 0);
            nm.visitEnd();
            MethodVisitor say = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, two ? "say" : "tell", two ? "(" + STR + ")V" : "(" + STR + "I)V", null, null);
            say.visitCode();
            if (two) {
                say.visitLdcInsn("say:");
                say.visitVarInsn(Opcodes.ALOAD, 0);
                say.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(" + STR + ")" + STR, false);
            } else {
                say.visitVarInsn(Opcodes.ALOAD, 0);
            }
            say.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
            say.visitInsn(Opcodes.RETURN);
            say.visitMaxs(0, 0);
            say.visitEnd();
            for (String name : two ? List.of("enableBlend", "commit") : List.of("enableBlend", "disableTexture", "flush", "enableTexture")) {
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
                g.visitCode();
                g.visitLdcInsn(name.substring(0, 2));
                g.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
                g.visitInsn(Opcodes.RETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
            }
        }));
        // Mut in both (literal(s) in 2.0); Text(s) in 1.0 only; Sink.take(Object) logs what it is given.
        out.put(U + "Mut", cls("Mut", w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "text", STR, null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()" + STR, null, null);
            s.visitCode();
            s.visitLdcInsn("mut:");
            s.visitVarInsn(Opcodes.ALOAD, 0);
            s.visitFieldInsn(Opcodes.GETFIELD, U + "Mut", "text", STR);
            s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(" + STR + ")" + STR, false);
            s.visitInsn(Opcodes.ARETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
            if (two) {
                MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "literal", "(" + STR + ")L" + U + "Mut;", null, null);
                f.visitCode();
                f.visitTypeInsn(Opcodes.NEW, U + "Mut");
                f.visitInsn(Opcodes.DUP);
                f.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Mut", "<init>", "()V", false);
                f.visitInsn(Opcodes.DUP);
                f.visitVarInsn(Opcodes.ALOAD, 0);
                f.visitFieldInsn(Opcodes.PUTFIELD, U + "Mut", "text", STR);
                f.visitInsn(Opcodes.ARETURN);
                f.visitMaxs(0, 0);
                f.visitEnd();
            }
        }));
        if (!two) {
            out.put(U + "Text", cls("Text", w -> {
                w.visitField(Opcodes.ACC_PUBLIC, "text", STR, null, null).visitEnd();
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + STR + ")V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ALOAD, 1);
                c.visitFieldInsn(Opcodes.PUTFIELD, U + "Text", "text", STR);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }));
        }
        String text = "L" + U + (two ? "Mut;" : "Text;");
        out.put(U + "Sink", cls("Sink", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "log", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            c.visitInsn(Opcodes.DUP);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Sink", "log", "Ljava/lang/StringBuilder;");
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor t = w.visitMethod(Opcodes.ACC_PUBLIC, "take", "(" + OBJ + ")V", null, null);
            t.visitCode();
            t.visitVarInsn(Opcodes.ALOAD, 0);
            t.visitFieldInsn(Opcodes.GETFIELD, U + "Sink", "log", "Ljava/lang/StringBuilder;");
            t.visitInsn(Opcodes.DUP);
            t.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "length", "()I", false);
            Label first = new Label();
            t.visitJumpInsn(Opcodes.IFEQ, first);
            t.visitLdcInsn("|");
            append(t, STR);
            t.visitLabel(first);
            t.visitVarInsn(Opcodes.ALOAD, 1);
            append(t, OBJ);
            t.visitInsn(Opcodes.POP);
            t.visitInsn(Opcodes.RETURN);
            t.visitMaxs(0, 0);
            t.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()" + STR, null, null);
            s.visitCode();
            s.visitVarInsn(Opcodes.ALOAD, 0);
            s.visitFieldInsn(Opcodes.GETFIELD, U + "Sink", "log", "Ljava/lang/StringBuilder;");
            s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
            s.visitInsn(Opcodes.ARETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
            // title(Text) and title(Mut): the same method, its parameter switched with the class.
            MethodVisitor ti = w.visitMethod(Opcodes.ACC_PUBLIC, "title", "(" + text + ")V", null, null);
            ti.visitCode();
            ti.visitVarInsn(Opcodes.ALOAD, 0);
            ti.visitVarInsn(Opcodes.ALOAD, 1);
            ti.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "take", "(" + OBJ + ")V", false);
            ti.visitInsn(Opcodes.RETURN);
            ti.visitMaxs(0, 0);
            ti.visitEnd();
        }));
        out.put(U + "Use", cls("Use", w -> {
            noArgs(w);
            draw(w, two);
            state(w, two);
            say(w, two);
            report(w, two);
            textures(w, two);
            envs(w, two);
        }));
        // Env.dist (a static field) in 1.0; Env.getDist(), a one-line wrapper of Ldr.current().dist(), in 2.0.
        out.put(U + "Ldr", cls("Ldr", w -> {
            noArgs(w);
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CURRENT", "L" + U + "Ldr;", null, null).visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            s.visitTypeInsn(Opcodes.NEW, U + "Ldr");
            s.visitInsn(Opcodes.DUP);
            s.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Ldr", "<init>", "()V", false);
            s.visitFieldInsn(Opcodes.PUTSTATIC, U + "Ldr", "CURRENT", "L" + U + "Ldr;");
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "()L" + U + "Ldr;", null, null);
            c.visitCode();
            c.visitFieldInsn(Opcodes.GETSTATIC, U + "Ldr", "CURRENT", "L" + U + "Ldr;");
            c.visitInsn(Opcodes.ARETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC, "dist", "()" + OBJ, null, null);
            d.visitCode();
            d.visitLdcInsn("client");
            d.visitInsn(Opcodes.ARETURN);
            d.visitMaxs(0, 0);
            d.visitEnd();
        }));
        out.put(U + "Env", cls("Env", w -> {
            noArgs(w);
            if (two) {
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getDist", "()" + OBJ, null, null);
                g.visitCode();
                g.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ldr", "current", "()L" + U + "Ldr;", false);
                g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ldr", "dist", "()" + OBJ, false);
                g.visitInsn(Opcodes.ARETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
            } else {
                w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dist", OBJ, null, "client").visitEnd();
            }
        }));
        return out;
    }

    /**
     * textures(): log("t" + i); GL.enableTexture() three times, which 2.0 drops; then log("x"); GL.enableTexture(); log("y"),
     * which 2.0 writes log("x"); GL.enableBlend(); log("y"): a neighbour, not a replacement.
     */
    static void textures(ClassWriter w, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "textures", "()V", null, null);
        m.visitCode();
        for (int i = 0; i < 3; i++) {
            m.visitLdcInsn("t" + i);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
            if (!two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "enableTexture", "()V", false);
            }
        }
        m.visitLdcInsn("x");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", two ? "enableBlend" : "enableTexture", "()V", false);
        m.visitLdcInsn("y");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** envA/envB/envC(sink): sink.take(Env.dist); 2.0 writes Env.getDist() in two and Ldr.current().dist() in the third. */
    static void envs(ClassWriter w, boolean two) {
        for (String name : List.of("envA", "envB", "envC")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(L" + U + "Sink;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            if (!two) {
                m.visitFieldInsn(Opcodes.GETSTATIC, U + "Env", "dist", OBJ);
            } else if (name.equals("envC")) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Ldr", "current", "()L" + U + "Ldr;", false);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Ldr", "dist", "()" + OBJ, false);
            } else {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Env", "getDist", "()" + OBJ, false);
            }
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "take", "(" + OBJ + ")V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }

    /** report(i): GL.tell(GL.name(i).trim(), i) twice; 2.0 writes GL.say(GL.name((long) i).trim()), name having changed too. */
    static void report(ClassWriter w, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "report", "(I)V", null, null);
        m.visitCode();
        for (int k = 0; k < 2; k++) {
            m.visitVarInsn(Opcodes.ILOAD, 0);
            if (two) {
                m.visitInsn(Opcodes.I2L);
            }
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "name", two ? "(J)" + STR : "(I)" + STR, false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()" + STR, false);
            if (two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "say", "(" + STR + ")V", false);
            } else {
                m.visitVarInsn(Opcodes.ILOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "tell", "(" + STR + "I)V", false);
            }
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** vertex(F)/color(I): log the step and its value, return this. */
    static void logStep(ClassWriter w, String name, String type, String tag) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "(" + type + ")" + BUF, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, U + "Buf", "log", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn(tag);
        append(m, STR);
        m.visitVarInsn(type.equals("F") ? Opcodes.FLOAD : Opcodes.ILOAD, 1);
        append(m, type);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static void noArgs(ClassWriter w) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    /**
     * draw(buf, x): buf.vertex(x).color(1).endVertex(); buf.vertex(x).color(2).endVertex(); buf.vertex(x).color(255)
     * .endVertex(), which 2.0 writes color(-1); buf.copy() twice, a statement 2.0 drops; then log("end"). 2.0 ends each chain.
     */
    static void draw(ClassWriter w, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(" + BUF + "F)V", null, null);
        m.visitCode();
        for (int c : new int[] { 1, 2, 255 }) {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitVarInsn(Opcodes.FLOAD, 2);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "vertex", "(F)" + BUF, false);
            m.visitIntInsn(Opcodes.SIPUSH, two && c == 255 ? -1 : c);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "color", "(I)" + BUF, false);
            if (two) {
                m.visitInsn(Opcodes.POP);
            } else {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "endVertex", "()V", false);
            }
        }
        for (int i = 0; i < 2 && !two; i++) {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Buf", "copy", "()" + BUF, false);
            m.visitInsn(Opcodes.POP);
            m.visitLdcInsn("c" + i);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        }
        for (int i = 0; i < 2 && two; i++) {
            m.visitLdcInsn("c" + i);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        }
        m.visitLdcInsn("end");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * state(): three times log("a"); disableTexture(); enableBlend() (2.0 drops disableTexture); then flush() three times
     * between logs, which 2.0 drops once and replaces by commit() twice.
     */
    static void state(ClassWriter w, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "state", "()V", null, null);
        m.visitCode();
        for (int i = 0; i < 3; i++) {
            m.visitLdcInsn("a" + i);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
            if (!two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "disableTexture", "()V", false);
            }
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "enableBlend", "()V", false);
        }
        for (int i = 0; i < 3; i++) {
            m.visitLdcInsn("f" + i);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
            if (!two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "flush", "()V", false);
            } else if (i > 0) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "commit", "()V", false);
            }
        }
        m.visitLdcInsn("z");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "GL", "log", "(" + STR + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** say(sink, s): sink.take(new Text(s)); sink.take(new Text(s.trim())); sink.title(new Text(s)), Mut.literal in 2.0. */
    static void say(ClassWriter w, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "say", "(L" + U + "Sink;" + STR + ")V", null, null);
        m.visitCode();
        for (int i = 0; i < 3; i++) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            if (!two) {
                m.visitTypeInsn(Opcodes.NEW, U + "Text");
                m.visitInsn(Opcodes.DUP);
            }
            m.visitVarInsn(Opcodes.ALOAD, 1);
            if (i == 1) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()" + STR, false);
            }
            if (two) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Mut", "literal", "(" + STR + ")L" + U + "Mut;", false);
            } else {
                m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Text", "<init>", "(" + STR + ")V", false);
            }
            if (i == 2) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "title", "(L" + U + (two ? "Mut;" : "Text;") + ")V", false);
            } else {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Sink", "take", "(" + OBJ + ")V", false);
            }
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
    }

    /** A public class of the game, which writes its own constructors. */
    static byte[] cls(String simple, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = writer();
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, U + simple, null, "java/lang/Object", null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
