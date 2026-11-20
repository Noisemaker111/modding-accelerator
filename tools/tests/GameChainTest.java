import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * GameDiff's Learner on replacements that span calls, and on moves into the game's libraries, on a two-release game built
 * here. 2.0 merges tess.getBuilder() then builder.begin(mode) into tess.begin(mode), turns tess.end() into
 * Up.draw(builder.build()), renames Buf.vertex(Mat, FFF) to addVertex and drops vertex(DDD) (now addVertex(FFF)), and moves
 * the game's Vec to a library class org.lib.Vec whose set(FFF) returns the vector. Refused: a pair whose second call's
 * argument the new code leaves out. A mod ported with what was learned runs on this JVM against 2.0 and the library.
 *
 *   java -cp asm.jar:CLASSES GameChainTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameChainTest {

    static final String C = "net/minecraft/c/", LIB = "org/lib/", BUF = "L" + C + "Buf;", MAT = "L" + C + "Mat;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        Map<String, byte[]> library = Map.of(LIB + "Vec", vec(LIB, true));
        Path libJar = scratch.resolve("chain-library.jar");
        Files.write(libJar, JarPortTest.jar(Map.of(LIB + "Vec.class", library.get(LIB + "Vec"))));
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.addLibraries(b.libraries, List.of(libJar));
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        GameDiff.Learner learner = new GameDiff.Learner(hop);
        Map<String, String> learned = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (GameDiff.Learner.Learned l : learner.learn()) {
            learned.put(l.owner() + "." + l.name() + l.desc(), l.expr());
            lines.add(l.json("1.0 -> 2.0"));
        }
        Map<String, String> chains = new TreeMap<>();
        for (GameDiff.Learner.Chained c : learner.chains) {
            chains.put(c.x().name() + "+" + c.y().name() + (c.at() < 0 ? " sibling" : c.first() ? " first" : " last"), c.expr());
            lines.add(c.json("1.0 -> 2.0"));
        }
        List<String> classes = new ArrayList<>();
        for (GameDiff.Hop.ClassRewrite r : learner.typeRewrites) {
            classes.add("{\"step\": \"1.0 -> 2.0\", \"from\": \"" + r.from().name.replace('/', '.') + "\", \"to\": \"" + r.to().name.replace('/', '.') + "\", \"votes\": " + r.votes() + ", \"of\": " + r.total() + "}");
        }
        String c = "net.minecraft.c.";
        check(chains, "getBuilder+begin first", "$1.begin($2)", "two calls on one value the game merged into one are learned together, over both calls' operands");
        check(chains, "getBuilder+end sibling", c + "Up.draw($2.build())", "a call on the same receiver is learned with the earlier call's value among its operands");
        JarPortTest.expect(!chains.containsKey("getBuilder+tint first"), "a pair whose second call's argument the new code leaves out is refused: " + chains.get("getBuilder+tint first"));
        check(learned, c + "Buf.vertex(DDD)" + BUF, "$0.addVertex((float) $1, (float) $2, (float) $3)", "an overload dropped while its family was renamed goes to the renamed one that takes its arguments");
        JarPortTest.expect(classes.toString().contains("\"" + c + "Vec\", \"to\": \"org.lib.Vec\""), "a game class moved into a library is the class map: " + classes);
        check(learned, c + "Vec.set(FFF)V", "$0.set($1, $2, $3)", "a member of the moved class is the library's member javac would pick");

        Path map = scratch.resolve("route-chain.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "], \"classRewrites\": [" + String.join(",\n", classes) + "]}");
        GameDiff.addLibraries(b.classes, List.of(libJar));
        String trace = run(a, b, map, newGame, library);
        JarPortTest.expect(trace.equals("begin7v1.0drawn|4.0"), "the mod's getBuilder/begin/vertex/end and Vec run on 2.0 and the library: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-chain: 7 checks passed");
    }

    static void check(Map<String, String> learned, String key, String want, String what) {
        JarPortTest.expect(want.equals(learned.get(key)), what + ": " + learned.get(key));
    }

    /** m10.Run.go(): t = Tess.getInstance(); b = t.getBuilder(); b.begin(7); b.vertex(1.0, 2.0, 3.0); t.end(); v = new Vec(1, 2, 3); v.set(4, 5, 6); return b.log + "|" + v.x(). */
    static String run(GameDiff.Snap a, GameDiff.Snap b, Path map, Map<String, byte[]> newGame, Map<String, byte[]> library) throws Exception {
        ClassWriter w = writer();
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m10/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, C + "Tess", "getInstance", "()L" + C + "Tess;", false);
        m.visitVarInsn(Opcodes.ASTORE, 0);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "getBuilder", "()" + BUF, false);
        m.visitVarInsn(Opcodes.ASTORE, 1);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitIntInsn(Opcodes.BIPUSH, 7);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", "begin", "(I)V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.DCONST_1);
        m.visitLdcInsn(2.0);
        m.visitLdcInsn(3.0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", "vertex", "(DDD)" + BUF, false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "end", "()V", false);
        m.visitTypeInsn(Opcodes.NEW, C + "Vec");
        m.visitInsn(Opcodes.DUP);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitInsn(Opcodes.FCONST_2);
        m.visitLdcInsn(3.0F);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, C + "Vec", "<init>", "(FFF)V", false);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitLdcInsn(4.0F);
        m.visitLdcInsn(5.0F);
        m.visitLdcInsn(6.0F);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Vec", "set", "(FFF)V", false);
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, C + "Buf", "log", "Ljava/lang/StringBuilder;");
        append(m, "Ljava/lang/Object;");
        m.visitLdcInsn("|");
        append(m, "Ljava/lang/String;");
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Vec", "x", "()F", false);
        append(m, "F");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m10/Run.class", w.toByteArray())), "chain.jar", results));
        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.putAll(library);
        classes.put("m10/Run", ported.get("m10/Run.class"));
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
            return String.valueOf(loader.loadClass("m10.Run").getMethods()[0].invoke(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
    }

    static void append(MethodVisitor m, String desc) {
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
    }

    /** log.append(text) on the Buf in local 0 (or the one on the stack when local is -1). */
    static void log(MethodVisitor m, int local, String text) {
        if (local >= 0) {
            m.visitVarInsn(Opcodes.ALOAD, local);
        }
        m.visitFieldInsn(Opcodes.GETFIELD, C + "Buf", "log", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn(text);
        append(m, "Ljava/lang/String;");
        m.visitInsn(Opcodes.POP);
    }

    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(C + "Mat", cls(C + "Mat", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "ID", MAT, null, null).visitEnd();
            init(w, "java/lang/Object");
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            s.visitTypeInsn(Opcodes.NEW, C + "Mat");
            s.visitInsn(Opcodes.DUP);
            s.visitMethodInsn(Opcodes.INVOKESPECIAL, C + "Mat", "<init>", "()V", false);
            s.visitFieldInsn(Opcodes.PUTSTATIC, C + "Mat", "ID", MAT);
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
        }));
        out.put(C + "Buf", buf(two));
        out.put(C + "Tess", tess(two));
        out.put(C + "Up", cls(C + "Up", w -> {
            init(w, "java/lang/Object");
            MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "(" + BUF + ")V", null, null);
            d.visitCode();
            d.visitVarInsn(Opcodes.ALOAD, 0);
            log(d, -1, "drawn");
            d.visitInsn(Opcodes.RETURN);
            d.visitMaxs(0, 0);
            d.visitEnd();
        }));
        if (!two) {
            out.put(C + "Vec", vec(C, false));
        }
        String vec = two ? "L" + LIB + "Vec;" : "L" + C + "Vec;";
        out.put(C + "Pose", cls(C + "Pose", w -> {
            init(w, "java/lang/Object");
            MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC, "put", "(" + vec + ")V", null, null);
            p.visitCode();
            p.visitVarInsn(Opcodes.ALOAD, 1);
            p.visitInsn(Opcodes.POP);
            p.visitInsn(Opcodes.RETURN);
            p.visitMaxs(0, 0);
            p.visitEnd();
            MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, "vec", "()" + vec, null, null);
            g.visitCode();
            newVec(g, vec.substring(1, vec.length() - 1), 1, 2, 3);
            g.visitInsn(Opcodes.ARETURN);
            g.visitMaxs(0, 0);
            g.visitEnd();
        }));
        out.put(C + "Use", cls(C + "Use", w -> {
            init(w, "java/lang/Object");
            for (String name : List.of("draw", "draw2", "draw3")) {
                draw(w, name, two);
            }
            tinted(w, "tint", two);
            tinted(w, "tint2", two);
            MethodVisitor u = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(L" + C + "Pose;F)V", null, null);
            u.visitCode();
            for (int i = 0; i < 2; i++) {
                u.visitVarInsn(Opcodes.ALOAD, 0);
                u.visitTypeInsn(Opcodes.NEW, vec.substring(1, vec.length() - 1));
                u.visitInsn(Opcodes.DUP);
                u.visitVarInsn(Opcodes.FLOAD, 1);
                u.visitInsn(Opcodes.FCONST_0 + i);
                u.visitInsn(Opcodes.FCONST_2);
                u.visitMethodInsn(Opcodes.INVOKESPECIAL, vec.substring(1, vec.length() - 1), "<init>", "(FFF)V", false);
                u.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Pose", "put", "(" + vec + ")V", false);
            }
            u.visitInsn(Opcodes.RETURN);
            u.visitMaxs(0, 0);
            u.visitEnd();
        }));
        return out;
    }

    static void newVec(MethodVisitor m, String owner, int x, int y, int z) {
        m.visitTypeInsn(Opcodes.NEW, owner);
        m.visitInsn(Opcodes.DUP);
        m.visitInsn(Opcodes.FCONST_0 + Math.min(x, 2));
        m.visitInsn(Opcodes.FCONST_0 + Math.min(y, 2));
        m.visitInsn(Opcodes.FCONST_0 + Math.min(z, 2));
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(FFF)V", false);
    }

    /**
     * draw(mode): t = Tess.getInstance(); b = t.getBuilder(); b.begin(mode); b.vertex(Mat.ID, 1, 2, 3); t.end().
     * 2.0: t = Tess.getInstance(); b = t.begin(mode); b.addVertex(Mat.ID, 1, 2, 3); Up.draw(b.build()).
     */
    static void draw(ClassWriter w, String name, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(I)V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, C + "Tess", "getInstance", "()L" + C + "Tess;", false);
        m.visitVarInsn(Opcodes.ASTORE, 1);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (two) {
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "begin", "(I)" + BUF, false);
            m.visitVarInsn(Opcodes.ASTORE, 2);
        } else {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "getBuilder", "()" + BUF, false);
            m.visitVarInsn(Opcodes.ASTORE, 2);
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", "begin", "(I)V", false);
        }
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitFieldInsn(Opcodes.GETSTATIC, C + "Mat", "ID", MAT);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitInsn(Opcodes.FCONST_2);
        m.visitLdcInsn(3.0F);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", two ? "addVertex" : "vertex", "(" + MAT + "FFF)" + BUF, false);
        m.visitInsn(Opcodes.POP);
        if (two) {
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", "build", "()" + BUF, false);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, C + "Up", "draw", "(" + BUF + ")V", false);
        } else {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "end", "()V", false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** tint(c): b = Tess.getInstance().getBuilder(); b.tint(c); b.vertex(Mat.ID, 1, 2, 3). 2.0: b = Tess.getInstance().begin(0), c is gone. */
    static void tinted(ClassWriter w, String name, boolean two) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(I)V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, C + "Tess", "getInstance", "()L" + C + "Tess;", false);
        if (two) {
            m.visitInsn(Opcodes.ICONST_0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "begin", "(I)" + BUF, false);
            m.visitVarInsn(Opcodes.ASTORE, 1);
        } else {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Tess", "getBuilder", "()" + BUF, false);
            m.visitVarInsn(Opcodes.ASTORE, 1);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", "tint", "(I)V", false);
        }
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETSTATIC, C + "Mat", "ID", MAT);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitInsn(Opcodes.FCONST_2);
        m.visitLdcInsn(3.0F);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, C + "Buf", two ? "addVertex" : "vertex", "(" + MAT + "FFF)" + BUF, false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Buf: log; 1.0: begin(I), tint(I), vertex(DDD), vertex(Mat, FFF); 2.0: Buf(I), addVertex(FFF), addVertex(Mat, FFF), build(). */
    static byte[] buf(boolean two) {
        return cls(C + "Buf", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "log", "Ljava/lang/StringBuilder;", null, null).visitEnd();
            for (String desc : two ? List.of("(I)V") : List.of("()V")) {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                c.visitInsn(Opcodes.DUP);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                c.visitFieldInsn(Opcodes.PUTFIELD, C + "Buf", "log", "Ljava/lang/StringBuilder;");
                if (two) {
                    beginLog(c);
                }
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }
            if (!two) {
                MethodVisitor b = w.visitMethod(Opcodes.ACC_PUBLIC, "begin", "(I)V", null, null);
                b.visitCode();
                beginLog(b);
                b.visitInsn(Opcodes.RETURN);
                b.visitMaxs(0, 0);
                b.visitEnd();
                MethodVisitor t = w.visitMethod(Opcodes.ACC_PUBLIC, "tint", "(I)V", null, null);
                t.visitCode();
                t.visitInsn(Opcodes.RETURN);
                t.visitMaxs(0, 0);
                t.visitEnd();
            }
            vertex(w, two ? "addVertex" : "vertex", two ? "(FFF)" : "(DDD)", two ? "F" : "D");
            vertex(w, two ? "addVertex" : "vertex", "(" + MAT + "FFF)", "M");
            if (two) {
                MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC, "build", "()" + BUF, null, null);
                d.visitCode();
                d.visitVarInsn(Opcodes.ALOAD, 0);
                d.visitInsn(Opcodes.ARETURN);
                d.visitMaxs(0, 0);
                d.visitEnd();
            }
        });
    }

    /** log.append("begin").append(mode), mode in local 1. */
    static void beginLog(MethodVisitor m) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, C + "Buf", "log", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn("begin");
        append(m, "Ljava/lang/String;");
        m.visitVarInsn(Opcodes.ILOAD, 1);
        append(m, "I");
        m.visitInsn(Opcodes.POP);
    }

    /** A vertex step: log "v" and its x (kind D or F; M: a matrix first, x after it), return this. */
    static void vertex(ClassWriter w, String name, String params, String kind) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, params + BUF, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, C + "Buf", "log", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn(kind.equals("M") ? "m" : "v");
        append(m, "Ljava/lang/String;");
        m.visitVarInsn(kind.equals("D") ? Opcodes.DLOAD : Opcodes.FLOAD, kind.equals("M") ? 2 : 1);
        append(m, kind.equals("D") ? "D" : "F");
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Tess: a singleton; 1.0: builder, getBuilder(), end() = Up.draw(builder); 2.0: begin(mode) = new Buf(mode). */
    static byte[] tess(boolean two) {
        return cls(C + "Tess", w -> {
            String tess = "L" + C + "Tess;";
            w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "INSTANCE", tess, null, null).visitEnd();
            if (!two) {
                w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "builder", BUF, null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            if (!two) {
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitTypeInsn(Opcodes.NEW, C + "Buf");
                c.visitInsn(Opcodes.DUP);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, C + "Buf", "<init>", "()V", false);
                c.visitFieldInsn(Opcodes.PUTFIELD, C + "Tess", "builder", BUF);
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            s.visitCode();
            s.visitTypeInsn(Opcodes.NEW, C + "Tess");
            s.visitInsn(Opcodes.DUP);
            s.visitMethodInsn(Opcodes.INVOKESPECIAL, C + "Tess", "<init>", "()V", false);
            s.visitFieldInsn(Opcodes.PUTSTATIC, C + "Tess", "INSTANCE", tess);
            s.visitInsn(Opcodes.RETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();
            MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstance", "()" + tess, null, null);
            g.visitCode();
            g.visitFieldInsn(Opcodes.GETSTATIC, C + "Tess", "INSTANCE", tess);
            g.visitInsn(Opcodes.ARETURN);
            g.visitMaxs(0, 0);
            g.visitEnd();
            if (two) {
                MethodVisitor b = w.visitMethod(Opcodes.ACC_PUBLIC, "begin", "(I)" + BUF, null, null);
                b.visitCode();
                b.visitTypeInsn(Opcodes.NEW, C + "Buf");
                b.visitInsn(Opcodes.DUP);
                b.visitVarInsn(Opcodes.ILOAD, 1);
                b.visitMethodInsn(Opcodes.INVOKESPECIAL, C + "Buf", "<init>", "(I)V", false);
                b.visitInsn(Opcodes.ARETURN);
                b.visitMaxs(0, 0);
                b.visitEnd();
            } else {
                MethodVisitor b = w.visitMethod(Opcodes.ACC_PUBLIC, "getBuilder", "()" + BUF, null, null);
                b.visitCode();
                b.visitVarInsn(Opcodes.ALOAD, 0);
                b.visitFieldInsn(Opcodes.GETFIELD, C + "Tess", "builder", BUF);
                b.visitInsn(Opcodes.ARETURN);
                b.visitMaxs(0, 0);
                b.visitEnd();
                MethodVisitor e = w.visitMethod(Opcodes.ACC_PUBLIC, "end", "()V", null, null);
                e.visitCode();
                e.visitVarInsn(Opcodes.ALOAD, 0);
                e.visitFieldInsn(Opcodes.GETFIELD, C + "Tess", "builder", BUF);
                e.visitMethodInsn(Opcodes.INVOKESTATIC, C + "Up", "draw", "(" + BUF + ")V", false);
                e.visitInsn(Opcodes.RETURN);
                e.visitMaxs(0, 0);
                e.visitEnd();
            }
        });
    }

    /** Vec: x, y, z; Vec(FFF); x(); set(FFF), void in the game's 1.0 class and returning the vector in the library's. */
    static byte[] vec(String pkg, boolean fluent) {
        String self = pkg + "Vec";
        return cls(self, w -> {
            for (String f : List.of("x", "y", "z")) {
                w.visitField(Opcodes.ACC_PUBLIC, f, "F", null, null).visitEnd();
            }
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(FFF)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            setFields(c, self);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "set", "(FFF)" + (fluent ? "L" + self + ";" : "V"), null, null);
            s.visitCode();
            setFields(s, self);
            if (fluent) {
                s.visitVarInsn(Opcodes.ALOAD, 0);
                s.visitInsn(Opcodes.ARETURN);
            } else {
                s.visitInsn(Opcodes.RETURN);
            }
            s.visitMaxs(0, 0);
            s.visitEnd();
            MethodVisitor x = w.visitMethod(Opcodes.ACC_PUBLIC, "x", "()F", null, null);
            x.visitCode();
            x.visitVarInsn(Opcodes.ALOAD, 0);
            x.visitFieldInsn(Opcodes.GETFIELD, self, "x", "F");
            x.visitInsn(Opcodes.FRETURN);
            x.visitMaxs(0, 0);
            x.visitEnd();
        });
    }

    static void setFields(MethodVisitor m, String self) {
        String[] names = { "x", "y", "z" };
        for (int i = 0; i < 3; i++) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.FLOAD, i + 1);
            m.visitFieldInsn(Opcodes.PUTFIELD, self, names[i], "F");
        }
    }

    static void init(ClassWriter w, String sup) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
    }

    static byte[] cls(String name, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = writer();
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
