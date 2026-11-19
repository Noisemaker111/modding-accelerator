import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on members the game still has but closed, moved or made generic, on a two-release game built here:
 * a constructor made private behind a public factory (and a decoy factory that drops its argument), a field made private
 * behind a getter and a setter, a field moved to another class, a method replaced by a generic one that gives back its
 * argument, a replacement shown only with literal arguments (and refused), and uses inside a loop. The learned
 * replacements are applied by JarPort to a mod that then runs on this JVM against the new release.
 *
 *   java -cp asm.jar:CLASSES GameMeaningTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameMeaningTest {

    static final String U = "net/minecraft/u/", STR = "Ljava/lang/String;", OBJ = "Ljava/lang/Object;";

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
        String u = "net.minecraft.u.";
        check(learned, u + "Key.<init>(" + STR + ")V", u + "Key.of($1)", "a constructor made private is the public factory that wraps it, through a check that returns its argument, not a decoy that converts or drops it");
        check(learned, u + "Widget.y", "$0.getY()", "a field made private is read through its getter");
        check(learned, u + "Widget.y=", "$0.setY($1)", "and written through its setter");
        check(learned, u + "Reg.BLOCKS", u + "Built.BLOCKS", "a field moved to another class is read there");
        check(learned, u + "Holder.add(L" + U + "Widget;)L" + U + "Widget;", "(" + u + "Widget) $0.addThing($1)", "a generic replacement that gives back its argument is cast to the argument's type");
        check(learned, u + "Holder.put(I)V", "$0.put($1, 0)", "uses inside a loop abstain instead of counting against");
        JarPortTest.expect(!learned.containsKey(u + "Res.make(" + STR + ")L" + U + "Res;"),
            "what the game wrote only for literal arguments is not taken when its computed uses wrote something else: " + learned.get(u + "Res.make(" + STR + ")L" + U + "Res;"));

        Path map = scratch.resolve("route-meaning.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        String trace = run(a, b, map, newGame);
        JarPortTest.expect(trace.equals("k:5:true"), "new Key(...) becomes Key.of(...), a write and a read of y go through setY and getY, add through addThing, on the JVM: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-meaning: 8 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(want.equals(learned.get(key)), what + ": " + learned.get(key));
    }

    /** m8.Run.go(widget, holder): key = new Key("k"); widget.y = 5; return key.id + ":" + widget.y + ":" + (holder.add(widget) == widget). */
    static String run(GameDiff.Snap a, GameDiff.Snap b, Path map, Map<String, byte[]> newGame) throws Exception {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m8/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + U + "Widget;L" + U + "Holder;)" + STR, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, U + "Key");
        m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn("k");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Key", "<init>", "(" + STR + ")V", false);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitFieldInsn(Opcodes.PUTFIELD, U + "Widget", "y", "I");
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitFieldInsn(Opcodes.GETFIELD, U + "Key", "id", STR);
        append(m, STR);
        m.visitLdcInsn(":");
        append(m, STR);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, U + "Widget", "y", "I");
        append(m, "I");
        m.visitLdcInsn(":");
        append(m, STR);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Holder", "add", "(L" + U + "Widget;)L" + U + "Widget;", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        Label same = new Label(), done = new Label();
        m.visitJumpInsn(Opcodes.IF_ACMPEQ, same);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitJumpInsn(Opcodes.GOTO, done);
        m.visitLabel(same);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitLabel(done);
        append(m, "Z");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m8/Run.class", w.toByteArray())), "meaning.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m8/Run", ported.get("m8/Run.class"));
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
            Object widget = loader.loadClass("net.minecraft.u.Widget").getConstructor().newInstance();
            Object holder = loader.loadClass("net.minecraft.u.Holder").getConstructor().newInstance();
            return String.valueOf(loader.loadClass("m8.Run").getMethods()[0].invoke(null, widget, holder));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
    }

    static String resDesc(String name) {
        return "(" + STR + switch (name) {
            case "plain" -> "Z";
            case "parse" -> "C";
            default -> "";
        } + ")L" + U + "Res;";
    }

    /** holder (local 1).put(the int on the stack), with 0 added in 2.0. */
    static void put(MethodVisitor g, boolean two) {
        if (two) {
            g.visitInsn(Opcodes.ICONST_0);
        }
        g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Holder", "put", two ? "(II)V" : "(I)V", false);
    }

    static void append(MethodVisitor m, String desc) {
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
    }

    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "Key", cls("Key", null, w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "id", STR, null, null).visitEnd();
            MethodVisitor c = w.visitMethod(two ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, "<init>", "(" + STR + ")V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ALOAD, 1);
            c.visitFieldInsn(Opcodes.PUTFIELD, U + "Key", "id", STR);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            if (two) {
                // shout(id) = new Key(trimmed(id)); of(id) = new Key(check(check(id))); blank(id) = new Key(""); check(s) throws
                // on null and returns s, trimmed(s) returns s.trim().
                for (String name : List.of("shout", "of", "blank")) {
                    MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(" + STR + ")L" + U + "Key;", null, null);
                    f.visitCode();
                    f.visitTypeInsn(Opcodes.NEW, U + "Key");
                    f.visitInsn(Opcodes.DUP);
                    if (name.equals("blank")) {
                        f.visitLdcInsn("");
                    } else {
                        f.visitVarInsn(Opcodes.ALOAD, 0);
                        f.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Key", name.equals("of") ? "check" : "trimmed", "(" + STR + ")" + STR, false);
                        if (name.equals("of")) {
                            f.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Key", "check", "(" + STR + ")" + STR, false);
                        }
                    }
                    f.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Key", "<init>", "(" + STR + ")V", false);
                    f.visitInsn(Opcodes.ARETURN);
                    f.visitMaxs(0, 0);
                    f.visitEnd();
                }
                MethodVisitor k = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "check", "(" + STR + ")" + STR, null, null);
                k.visitCode();
                Label ok = new Label();
                k.visitVarInsn(Opcodes.ALOAD, 0);
                k.visitJumpInsn(Opcodes.IFNONNULL, ok);
                k.visitTypeInsn(Opcodes.NEW, "java/lang/NullPointerException");
                k.visitInsn(Opcodes.DUP);
                k.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/NullPointerException", "<init>", "()V", false);
                k.visitInsn(Opcodes.ATHROW);
                k.visitLabel(ok);
                k.visitVarInsn(Opcodes.ALOAD, 0);
                k.visitInsn(Opcodes.ARETURN);
                k.visitMaxs(0, 0);
                k.visitEnd();
                MethodVisitor t = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "trimmed", "(" + STR + ")" + STR, null, null);
                t.visitCode();
                t.visitVarInsn(Opcodes.ALOAD, 0);
                t.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()" + STR, false);
                t.visitInsn(Opcodes.ARETURN);
                t.visitMaxs(0, 0);
                t.visitEnd();
            }
        }, false));
        out.put(U + "Widget", cls("Widget", null, w -> {
            w.visitField(two ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, "y", "I", null, null).visitEnd();
            if (two) {
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "getY", "()I", null, null);
                g.visitCode();
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, U + "Widget", "y", "I");
                g.visitInsn(Opcodes.IRETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
                MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "setY", "(I)V", null, null);
                s.visitCode();
                s.visitVarInsn(Opcodes.ALOAD, 0);
                s.visitVarInsn(Opcodes.ILOAD, 1);
                s.visitFieldInsn(Opcodes.PUTFIELD, U + "Widget", "y", "I");
                s.visitInsn(Opcodes.RETURN);
                s.visitMaxs(0, 0);
                s.visitEnd();
            }
        }, true));
        // Holder: add(Widget) returning it, in 2.0 <T> T addThing(T); put(int) in 1.0, put(int, int) in 2.0.
        out.put(U + "Holder", cls("Holder", null, w -> {
            MethodVisitor add = two ? w.visitMethod(Opcodes.ACC_PUBLIC, "addThing", "(" + OBJ + ")" + OBJ, "<T:Ljava/lang/Object;>(TT;)TT;", null)
                : w.visitMethod(Opcodes.ACC_PUBLIC, "add", "(L" + U + "Widget;)L" + U + "Widget;", null, null);
            add.visitCode();
            add.visitVarInsn(Opcodes.ALOAD, 1);
            add.visitInsn(Opcodes.ARETURN);
            add.visitMaxs(0, 0);
            add.visitEnd();
            MethodVisitor put = w.visitMethod(Opcodes.ACC_PUBLIC, "put", two ? "(II)V" : "(I)V", null, null);
            put.visitCode();
            put.visitInsn(Opcodes.RETURN);
            put.visitMaxs(0, 0);
            put.visitEnd();
        }, true));
        // Res.make(s) in 1.0; plain(s, flag) and parse(s, separator) in 2.0.
        out.put(U + "Res", cls("Res", null, w -> {
            for (String name : two ? List.of("plain", "parse") : List.of("make")) {
                MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, resDesc(name), null, null);
                f.visitCode();
                f.visitTypeInsn(Opcodes.NEW, U + "Res");
                f.visitInsn(Opcodes.DUP);
                f.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Res", "<init>", "()V", false);
                f.visitInsn(Opcodes.ARETURN);
                f.visitMaxs(0, 0);
                f.visitEnd();
            }
        }, true));
        // Reg.BLOCKS in 1.0, Built.BLOCKS in 2.0 (Reg keeps another field).
        out.put(U + "Reg", cls("Reg", null, w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ITEMS", OBJ, null, null).visitEnd();
            if (!two) {
                w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BLOCKS", OBJ, null, null).visitEnd();
            }
        }, true));
        if (two) {
            out.put(U + "Built", cls("Built", null, w -> w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BLOCKS", OBJ, null, null).visitEnd(), true));
        }
        out.put(U + "Use", cls("Use", null, w -> {
            // go(holder, s): Reg.BLOCKS.hashCode() twice; holder.add(new Widget()) twice; holder.put(1), put(2) and put(i) for i in 0..2;
            // Res.make("lit0".."lit7") and Res.make(s) twice.
            MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "go", "(L" + U + "Holder;" + STR + ")V", null, null);
            g.visitCode();
            for (int i = 0; i < 2; i++) {
                g.visitFieldInsn(Opcodes.GETSTATIC, U + (two ? "Built" : "Reg"), "BLOCKS", OBJ);
                g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
                g.visitInsn(Opcodes.POP);
            }
            for (int i = 0; i < 2; i++) {
                g.visitVarInsn(Opcodes.ALOAD, 1);
                g.visitTypeInsn(Opcodes.NEW, U + "Widget");
                g.visitInsn(Opcodes.DUP);
                g.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Widget", "<init>", "()V", false);
                g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Holder", two ? "addThing" : "add", two ? "(" + OBJ + ")" + OBJ : "(L" + U + "Widget;)L" + U + "Widget;", false);
                g.visitInsn(Opcodes.POP);
            }
            for (int i = 1; i <= 2; i++) {
                g.visitVarInsn(Opcodes.ALOAD, 1);
                g.visitInsn(Opcodes.ICONST_0 + i);
                put(g, two);
            }
            Label head = new Label(), end = new Label();
            g.visitInsn(Opcodes.ICONST_0);
            g.visitVarInsn(Opcodes.ISTORE, 3);
            g.visitLabel(head);
            g.visitVarInsn(Opcodes.ILOAD, 3);
            g.visitInsn(Opcodes.ICONST_3);
            g.visitJumpInsn(Opcodes.IF_ICMPGE, end);
            g.visitVarInsn(Opcodes.ALOAD, 1);
            g.visitVarInsn(Opcodes.ILOAD, 3);
            put(g, two);
            g.visitIincInsn(3, 1);
            g.visitJumpInsn(Opcodes.GOTO, head);
            g.visitLabel(end);
            for (int i = 0; i < 10; i++) {
                if (i < 8) {
                    g.visitLdcInsn("lit" + i);
                } else {
                    g.visitVarInsn(Opcodes.ALOAD, 2);
                }
                String name = two ? (i < 8 ? "plain" : "parse") : "make";
                if (two) {
                    g.visitIntInsn(Opcodes.BIPUSH, i < 8 ? 1 : 58);
                }
                g.visitMethodInsn(Opcodes.INVOKESTATIC, U + "Res", name, resDesc(name), false);
                g.visitInsn(Opcodes.POP);
            }
            g.visitInsn(Opcodes.RETURN);
            g.visitMaxs(0, 0);
            g.visitEnd();
        }, true));
        return out;
    }

    /** A public class of the game; with a public no-argument constructor unless it writes its own. */
    static byte[] cls(String simple, String sup, java.util.function.Consumer<ClassWriter> body, boolean constructor) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, U + simple, null, sup == null ? "java/lang/Object" : sup, null);
        if (constructor) {
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
