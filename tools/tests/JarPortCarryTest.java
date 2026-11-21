import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A port across several updates, on a three-release game built here, run on this JVM against the last one:
 * - 2.0 learned Pose.mul(s) -> $0.turn($1), and 3.0 renamed turn to rotate: the learned line, carried through 3.0
 *   (GameDiff.Carry), is $0.rotate($1); a call the game switched to (a rewrite) is carried the same way.
 * - G.draw(String) returned an int and returns nothing in 3.0: where the mod pops the value the new call is written,
 *   where it uses the value the call stays and is reported (JarPort.Dropped).
 * - L.list() was static and is an instance method of L.current() in 3.0: the old call is not linked (the JVM would
 *   refuse it), and what the game's code writes instead takes its place.
 * - Uses that pass their own fixed default where most pass one abstain from the vote (1.21.5's getInt(key) is
 *   getIntOr(key, 0) in most places, getIntOr(key, 200) in one).
 * - Ops.allow(String) takes an Id in 3.0, which Id(String) makes: the argument goes through that constructor.
 * - A @ModifyVariable at STORE of its fourth boolean (ordinal 3, after three boolean parameters) in W.keep, whose new
 *   body stores none, and one whose method string names a signature the old jar lacks (a loader patch) on a method
 *   3.0 dropped, are both reported.
 *
 *   java -cp asm.jar:CLASSES JarPortCarryTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class JarPortCarryTest {

    static final String P = "net/minecraft/c4/", STR = "Ljava/lang/String;", LOG = "Ljava/lang/StringBuilder;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> one = game(1), two = game(2), three = game(3);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(one.values()), null, List.of());
        GameDiff.Snap s2 = GameDiff.read("2.0", List.copyOf(two.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("3.0", List.copyOf(three.values()), null, List.of());

        GameDiff.Hop hop = new GameDiff.Hop(s2, b);
        hop.run();
        GameDiff.Learner learner = new GameDiff.Learner(hop);
        GameDiff.Carry carry = new GameDiff.Carry(hop, learner, learner.learn(), hop.rewrites());
        GameDiff.Learner.Learned mul = new GameDiff.Learner.Learned("net.minecraft.c4.Pose", "mul", "(" + STR + ")V", false, false, "$0.turn($1)", 3, 3);
        GameDiff.Driver.Carried line = new GameDiff.Driver.Carried("1.0 -> 2.0", mul, null, new String[] { "L" + P + "Pose;", STR });
        line.carry(carry);
        String learned = line.json();
        JarPortTest.expect(learned.contains("\"expr\": \"$0.rotate($1)\"") && learned.contains("\"learnedAs\": \"$0.turn($1)\""),
            "a replacement learned at 2.0 names what 3.0 renamed its call to: " + learned);
        GameDiff.Meth turn = s2.method(P + "Pose", "turn", "(" + STR + ")V");
        GameDiff.Driver.Switched switched = new GameDiff.Driver.Switched("1.0 -> 2.0", new GameDiff.Hop.Rewrite(turn, turn, 3, 3));
        switched.carry(carry);
        JarPortTest.expect(switched.json().contains("\"to\": \"rotate\""), "a call the game switched to at 2.0 is followed to its 3.0 name: " + switched.json());

        String signature = "{\"owner\": \"net.minecraft.c4.G\", \"from\": \"draw\", \"desc\": \"(" + STR + ")I\", \"to\": \"draw\", \"newDesc\": \"(" + STR + ")V\"}, "
            + "{\"owner\": \"net.minecraft.c4.Ops\", \"from\": \"allow\", \"desc\": \"(" + STR + ")Z\", \"to\": \"allow\", \"newDesc\": \"(L" + P + "Id;)Z\"}";
        String current = "{ \"step\": \"2.0 -> 3.0\", \"owner\": \"net.minecraft.c4.L\", \"from\": \"list\", \"desc\": \"()" + STR + "\", \"field\": false, \"linked\": true, "
            + "\"expr\": \"net.minecraft.c4.L.current().list()\", \"votes\": 1, \"of\": 1 }";
        Path map = scratch.resolve("route-carry.json");
        Files.writeString(map, "{\"signatures\": [" + signature + "], \"learned\": [" + learned + ",\n" + current + "]}");
        List<Map<String, Object>> results = new ArrayList<>();
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(one::get, null);
        port.newCode = new JarPort.GameCode(three::get, null);
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(mod()), "carry.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        String trace = run(three, ported);
        JarPortTest.expect(trace.equals("turn:xalisttrue"),
            "the carried replacement, the void call where the value was dropped, the instance call the game writes now and an argument made with the new type's constructor run on the JVM: " + trace + " " + unlinked);
        JarPortTest.expect(unlinked.contains("call: net.minecraft.c4.G.draw(" + STR + ")I") && unlinked.stream().filter(u -> u.contains("G.draw")).count() == 1,
            "a call whose value the mod uses stays reported, once: " + unlinked);
        JarPortTest.expect(unlinked.contains("mixin injection point gone: STORE #3 of a boolean local in net.minecraft.c4.W.keep(ZZZ)V (in m4.WMixin)"),
            "a @ModifyVariable's own ordinal counts the target's parameters, and a new body with too few is reported: " + unlinked);
        JarPortTest.expect(unlinked.contains("mixin target gone: net.minecraft.c4.W.load(ZZZZ)V"),
            "a method string with a signature the old jar lacks, on a method the new release has none of by that name, is reported: " + unlinked);

        Path bare = scratch.resolve("route-carry-bare.json");
        Files.writeString(bare, "{\"signatures\": [" + signature + "]}");
        List<Map<String, Object>> plain = new ArrayList<>();
        new JarPort(a, b, bare).port(JarPortTest.jar(mod()), "carry.jar", plain);
        List<String> left = Json.array(plain.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(left.contains("call: net.minecraft.c4.L.list()" + STR), "a static call to what is now an instance method is not linked: " + left);
        // The chain's first release called turn mul: what 2.0 -> 3.0 learned for turn is filed under mul.
        GameDiff.Driver driver = new GameDiff.Driver(List.of("1.0", "2.0", "3.0"), null, null);
        GameDiff.Snap r1 = GameDiff.read("1.0", List.copyOf(one.values()), null, List.of()), r2 = GameDiff.read("2.0", List.copyOf(two.values()), null, List.of());
        driver.next(r1, null, null);
        driver.next(r2, null, null);
        GameDiff.Hop later = new GameDiff.Hop(r2, GameDiff.read("3.0", List.copyOf(three.values()), null, List.of()));
        later.run();
        String[] first = driver.first(later, new GameDiff.Learner.Learned("net.minecraft.c4.Pose", "turn", "(" + STR + ")V", false, true, "$0.rotate($1)", 2, 2));
        JarPortTest.expect(first != null && first[1].equals("mul") && first[2].equals("(" + STR + ")V"),
            "a fix learned for a member the first release named otherwise is filed under that name: " + (first == null ? null : Arrays.toString(first)));
        // Four uses read getInt(key) as getIntOr(key, 0); two chose their own default there (200, 5).
        char o = GameDiff.Learner.OPEN, or = GameDiff.Learner.OR, c = GameDiff.Learner.CLOSE;
        List<Set<String>> uses = new ArrayList<>();
        for (String k : List.of("0", "0", "0", "0", "200", "5")) {
            uses.add(Set.of(o + "$0" + c + ".getIntOr(" + o + "$1" + or + "\"k\"" + c + ", " + o + k + c + ")"));
        }
        int[] tally = new int[2];
        String best = GameDiff.Learner.vote(uses, tally);
        JarPortTest.expect("$0.getIntOr($1, 0)".equals(best) && tally[0] == 4 && tally[1] == 4,
            "a use that passes its own fixed default where most pass one abstains: " + best + " " + Arrays.toString(tally));
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("jar-port carry: 9 checks passed");
    }

    static String run(Map<String, byte[]> game, Map<String, byte[]> ported) {
        Map<String, byte[]> classes = new HashMap<>(game);
        classes.put("m4/Run", ported.get("m4/Run.class"));
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
            Object pose = loader.loadClass("net.minecraft.c4.Pose").getConstructor().newInstance();
            return String.valueOf(loader.loadClass("m4.Run").getMethod("go", loader.loadClass("net.minecraft.c4.Pose")).invoke(null, pose));
        } catch (ReflectiveOperationException | LinkageError failed) {
            return "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
    }

    /**
     * m4.Run.go(pose): pose.mul("x"); G.draw("a") (its value popped); Log.out.append(L.list()); return Log.out. m4.Run.keep()
     * returns G.draw("b"). m4.WMixin: two @ModifyVariable at STORE (see the class comment).
     */
    static Map<String, byte[]> mod() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m4/Run", null, "java/lang/Object", null);
        MethodVisitor go = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + P + "Pose;)" + STR, null, null);
        go.visitCode();
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitLdcInsn("x");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, P + "Pose", "mul", "(" + STR + ")V", false);
        go.visitLdcInsn("a");
        go.visitMethodInsn(Opcodes.INVOKESTATIC, P + "G", "draw", "(" + STR + ")I", false);
        go.visitInsn(Opcodes.POP);
        go.visitFieldInsn(Opcodes.GETSTATIC, P + "Log", "out", LOG);
        go.visitMethodInsn(Opcodes.INVOKESTATIC, P + "L", "list", "()" + STR, false);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")" + LOG, false);
        go.visitLdcInsn("me");
        go.visitMethodInsn(Opcodes.INVOKESTATIC, P + "Ops", "allow", "(" + STR + ")Z", false);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)" + LOG, false);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        go.visitInsn(Opcodes.ARETURN);
        go.visitMaxs(0, 0);
        go.visitEnd();
        MethodVisitor keep = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keep", "()I", null, null);
        keep.visitCode();
        keep.visitLdcInsn("b");
        keep.visitMethodInsn(Opcodes.INVOKESTATIC, P + "G", "draw", "(" + STR + ")I", false);
        keep.visitInsn(Opcodes.IRETURN);
        keep.visitMaxs(0, 0);
        keep.visitEnd();
        w.visitEnd();

        byte[] mixin = JarPortTest.mixin("m4/WMixin", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", Type.getObjectType(P + "W"), c -> {
            modifyVariable(c, "k", "keep(ZZZ)V", 3);
            modifyVariable(c, "l", "load(ZZZZ)V", 0);
        });
        return Map.of("m4/Run.class", w.toByteArray(), "m4/WMixin.class", mixin);
    }

    static void modifyVariable(ClassWriter c, String name, String target, int ordinal) {
        MethodVisitor m = c.visitMethod(Opcodes.ACC_PRIVATE, name, "(Z)Z", null, null);
        AnnotationVisitor av = m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", true);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, target);
        methods.visitEnd();
        AnnotationVisitor at = av.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "STORE");
        at.visitEnd();
        av.visit("ordinal", ordinal);
        av.visit("require", 1);
        av.visitEnd();
        m.visitCode();
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(1, 2);
        m.visitEnd();
    }

    /**
     * Log.out, a StringBuilder. Pose: mul(s) in 1.0, turn(s) in 2.0, the same body as rotate(s) in 3.0, with Scene.show(pose)
     * calling it. G.draw(s): returns 1 before 3.0, nothing in 3.0. L.list(): static before 3.0, an instance method of
     * L.current() in 3.0. W.keep(ZZZ)V: stores its fourth boolean before 3.0 and none in 3.0; W.load(ZZZ)V before 3.0 only.
     */
    static Map<String, byte[]> game(int release) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(P + "Log", cls("Log", w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "out", LOG, null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, P + "Log", "out", LOG);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        String turn = release == 1 ? "mul" : release == 2 ? "turn" : "rotate";
        out.put(P + "Pose", cls("Pose", w -> {
            constructor(w);
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, turn, "(" + STR + ")V", null, null);
            m.visitCode();
            m.visitFieldInsn(Opcodes.GETSTATIC, P + "Log", "out", LOG);
            m.visitLdcInsn(release == 1 ? "mul:" : "turn:");
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")" + LOG, false);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")" + LOG, false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put(P + "Scene", cls("Scene", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "show", "(L" + P + "Pose;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitLdcInsn("scene");
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, P + "Pose", turn, "(" + STR + ")V", false);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put(P + "G", cls("G", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "(" + STR + ")" + (release < 3 ? "I" : "V"), null, null);
            m.visitCode();
            m.visitFieldInsn(Opcodes.GETSTATIC, P + "Log", "out", LOG);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")" + LOG, false);
            m.visitInsn(Opcodes.POP);
            if (release < 3) {
                m.visitInsn(Opcodes.ICONST_1);
                m.visitInsn(Opcodes.IRETURN);
            } else {
                m.visitInsn(Opcodes.RETURN);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put(P + "L", cls("L", w -> {
            constructor(w);
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | (release < 3 ? Opcodes.ACC_STATIC : 0), "list", "()" + STR, null, null);
            m.visitCode();
            m.visitLdcInsn("list");
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            if (release == 3) {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "()L" + P + "L;", null, null);
                c.visitCode();
                c.visitTypeInsn(Opcodes.NEW, P + "L");
                c.visitInsn(Opcodes.DUP);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, P + "L", "<init>", "()V", false);
                c.visitInsn(Opcodes.ARETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }
        }));
        out.put(P + "Ops", cls("Ops", w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "allow", "(" + (release < 3 ? STR : "L" + P + "Id;") + ")Z", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        if (release == 3) {
            out.put(P + "Id", cls("Id", w -> {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + STR + ")V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }));
        }
        out.put(P + "W", cls("W", w -> {
            constructor(w);
            MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "keep", "(ZZZ)V", null, null);
            k.visitCode();
            if (release < 3) {
                k.visitVarInsn(Opcodes.ILOAD, 1);
                k.visitVarInsn(Opcodes.ISTORE, 4);
            }
            k.visitInsn(Opcodes.RETURN);
            k.visitMaxs(0, 0);
            k.visitEnd();
            if (release < 3) {
                MethodVisitor l = w.visitMethod(Opcodes.ACC_PUBLIC, "load", "(ZZZ)V", null, null);
                l.visitCode();
                l.visitVarInsn(Opcodes.ILOAD, 2);
                l.visitVarInsn(Opcodes.ISTORE, 4);
                l.visitInsn(Opcodes.RETURN);
                l.visitMaxs(0, 0);
                l.visitEnd();
            }
        }));
        return out;
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
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, P + simple, null, "java/lang/Object", null);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
