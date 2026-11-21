import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A method the game took away with nothing of its own code to read, answered from the new release's own types, on a
 * two-release game built here. 1.0: Src.allowed(int level) is level <= 2. 2.0: Src has no allowed; Src.perms() gives a
 * Set whose allowed(Perm) answers it, a Perm is made only by HasLevel(Level), and a Level from an int only by Level.byId.
 * So Src.allowed(n) is $0.perms().allowed(new HasLevel(Level.byId($1))), and a mod calling it runs on the new game as
 * before. Shelf.find(String) became find(Key), and Key's only maker takes a CharSequence (Key.wrap): a factory from another
 * object may mean something else of the same type, so nothing is learned for it. Shelf.put(String) became put(Box), and
 * a Box is made by Box(Object), which holds anything it is given: nothing is learned for that either. Src.tag(String)
 * became tag(Object), as an erased type variable reads: it wants some one type, not any, so it is not taken as the answer.
 * Src.paint(int) became paint(Kind), and Kind has two factories, plain() and glow(int): glow's int is not paint's. Pair.allowed(int) went too, and a Pair
 * hands out two Sets: either could be the answer, so nothing is learned for it either.
 *
 * Pos(Vec) went in 2.0 and the game's own uses of it, all on a Pos or a Mut (a Pos), became p, p, p.immutable() and new
 * Pos(m.getX(), m.getY()). A mod's argument is only known to be a Vec, and of those only the last compiles for a Vec, so
 * that is what Pos(Vec) is; the mod's new Pos(new Vec(3, 4)) gives a Pos at 3, 4.
 *
 *   java -cp asm.jar:CLASSES GameTypesTest SCRATCH   (in the jar-port check of bench/run_tests.py)
 */
final class GameTypesTest {

    static final String U = "net/minecraft/y8/", OBJ = "Ljava/lang/Object;", STR = "Ljava/lang/String;";

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
        String y = "net.minecraft.y8.";
        JarPortTest.expect(("$0.perms().allowed(new " + y + "HasLevel(" + y + "Level.byId($1)))").equals(learned.get(y + "Src.allowed(I)Z")),
            "a removed method is asked of the one object the class hands out that answers it, its argument made by the new types' only makers: " + learned);
        JarPortTest.expect(!learned.containsKey(y + "Shelf.find(" + STR + ")" + OBJ), "a maker that reads another object is not used: " + learned.get(y + "Shelf.find(" + STR + ")" + OBJ));
        JarPortTest.expect(!learned.containsKey(y + "Shelf.put(" + STR + ")V"), "a maker that holds any object is not used: " + learned.get(y + "Shelf.put(" + STR + ")V"));
        JarPortTest.expect(!learned.containsKey(y + "Src.tag(" + STR + ")V"), "a method taking any object is not the answer: " + learned.get(y + "Src.tag(" + STR + ")V"));
        JarPortTest.expect(!learned.containsKey(y + "Src.paint(I)V"), "one of a class's several factories is not the plain value's meaning: " + learned.get(y + "Src.paint(I)V"));
        JarPortTest.expect(!learned.containsKey(y + "Pair.allowed(I)Z"), "two objects that could answer and nothing is learned: " + learned.get(y + "Pair.allowed(I)Z"));
        JarPortTest.expect(("new " + y + "Pos($1.getX(), $1.getY())").equals(learned.get(y + "Pos.<init>(L" + U + "Vec;)V")),
            "of the game's rewrites of a narrower value, the one that compiles for the declared type: " + learned.get(y + "Pos.<init>(L" + U + "Vec;)V"));

        Path map = scratch.resolve("route-types.json");
        Files.writeString(map, "{\"learned\": [" + String.join(",\n", lines) + "]}");
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m8/Run", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(L" + U + "Src;)" + STR, null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        for (int level : new int[] { 1, 4 }) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitIntInsn(Opcodes.BIPUSH, level);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Src", "allowed", "(I)Z", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        }
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "m8/Run", "spot", "()I", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor spot = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "spot", "()I", null, null);
        spot.visitCode();
        spot.visitTypeInsn(Opcodes.NEW, U + "Pos");
        spot.visitInsn(Opcodes.DUP);
        spot.visitTypeInsn(Opcodes.NEW, U + "Vec");
        spot.visitInsn(Opcodes.DUP);
        spot.visitInsn(Opcodes.ICONST_3);
        spot.visitInsn(Opcodes.ICONST_4);
        spot.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Vec", "<init>", "(II)V", false);
        spot.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Pos", "<init>", "(L" + U + "Vec;)V", false);
        spot.visitVarInsn(Opcodes.ASTORE, 0);
        spot.visitVarInsn(Opcodes.ALOAD, 0);
        spot.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Pos", "getX", "()I", false);
        spot.visitIntInsn(Opcodes.BIPUSH, 10);
        spot.visitInsn(Opcodes.IMUL);
        spot.visitVarInsn(Opcodes.ALOAD, 0);
        spot.visitMethodInsn(Opcodes.INVOKEVIRTUAL, U + "Pos", "getY", "()I", false);
        spot.visitInsn(Opcodes.IADD);
        spot.visitInsn(Opcodes.IRETURN);
        spot.visitMaxs(0, 0);
        spot.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m8/Run.class", w.toByteArray())), "types.jar", results));
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
        String trace;
        try {
            Object src = loader.loadClass("net.minecraft.y8.Src").getConstructor().newInstance();
            trace = String.valueOf(loader.loadClass("m8.Run").getMethod("go", loader.loadClass("net.minecraft.y8.Src")).invoke(null, src));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause()) + " " + Json.array(results.get(0).get("unlinked"));
        }
        JarPortTest.expect(trace.equals("truefalse34"), "the mod's level checks and its Pos run on the new game as before: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-types: 8 checks passed");
    }

    /**
     * 1.0: Src.allowed(I)Z (level <= 2); Shelf.find(String). 2.0: Perm (an interface), Level (id(), byId(int)), HasLevel
     * implements Perm (HasLevel(Level), level()), Set.allowed(Perm) (a HasLevel's level id <= 2), Src.perms(), Key with only
     * Key.wrap(CharSequence), Shelf.find(Key); Shelf.put(String) becomes put(Box), Box(Object). Pair.allowed(I)Z in 1.0; Pair.left() and Pair.right(), both Sets, in 2.0. Both releases have Src.name(), which gives a String, as a decoy accessor.
     */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(U + "Src", cls("Src", 0, null, null, w -> {
            constructor(w, "java/lang/Object");
            MethodVisitor n = w.visitMethod(Opcodes.ACC_PUBLIC, "name", "()" + STR, null, null);
            n.visitCode();
            n.visitLdcInsn("src");
            n.visitInsn(Opcodes.ARETURN);
            n.visitMaxs(0, 0);
            n.visitEnd();
            MethodVisitor paint = w.visitMethod(Opcodes.ACC_PUBLIC, "paint", two ? "(L" + U + "Kind;)V" : "(I)V", null, null);
            paint.visitCode();
            paint.visitInsn(Opcodes.RETURN);
            paint.visitMaxs(0, 0);
            paint.visitEnd();
            MethodVisitor tag = w.visitMethod(Opcodes.ACC_PUBLIC, "tag", "(" + (two ? OBJ : STR) + ")V", null, null);
            tag.visitCode();
            tag.visitInsn(Opcodes.RETURN);
            tag.visitMaxs(0, 0);
            tag.visitEnd();
            if (!two) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "allowed", "(I)Z", null, null);
                m.visitCode();
                Label no = new Label();
                m.visitVarInsn(Opcodes.ILOAD, 1);
                m.visitInsn(Opcodes.ICONST_2);
                m.visitJumpInsn(Opcodes.IF_ICMPGT, no);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitInsn(Opcodes.IRETURN);
                m.visitLabel(no);
                m.visitInsn(Opcodes.ICONST_0);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            } else {
                MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC, "perms", "()L" + U + "Set;", null, null);
                p.visitCode();
                p.visitTypeInsn(Opcodes.NEW, U + "Set");
                p.visitInsn(Opcodes.DUP);
                p.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Set", "<init>", "()V", false);
                p.visitInsn(Opcodes.ARETURN);
                p.visitMaxs(0, 0);
                p.visitEnd();
            }
        }));
        positions(out, two);
        out.put(U + "Pair", cls("Pair", 0, null, null, w -> {
            constructor(w, "java/lang/Object");
            for (String side : two ? new String[] { "left", "right" } : new String[] { "allowed" }) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, side, two ? "()L" + U + "Set;" : "(I)Z", null, null);
                m.visitCode();
                if (two) {
                    m.visitTypeInsn(Opcodes.NEW, U + "Set");
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Set", "<init>", "()V", false);
                    m.visitInsn(Opcodes.ARETURN);
                } else {
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitInsn(Opcodes.IRETURN);
                }
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        out.put(U + "Shelf", cls("Shelf", 0, null, null, w -> {
            constructor(w, "java/lang/Object");
            MethodVisitor f = w.visitMethod(Opcodes.ACC_PUBLIC, "find", "(" + (two ? "L" + U + "Key;" : STR) + ")" + OBJ, null, null);
            f.visitCode();
            f.visitVarInsn(Opcodes.ALOAD, 1);
            f.visitInsn(Opcodes.ARETURN);
            f.visitMaxs(0, 0);
            f.visitEnd();
            MethodVisitor put = w.visitMethod(Opcodes.ACC_PUBLIC, "put", "(" + (two ? "L" + U + "Box;" : STR) + ")V", null, null);
            put.visitCode();
            put.visitInsn(Opcodes.RETURN);
            put.visitMaxs(0, 0);
            put.visitEnd();
        }));
        if (two) {
            out.put(U + "Perm", cls("Perm", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, null, w -> {}));
            out.put(U + "Level", cls("Level", 0, null, null, w -> {
                w.visitField(Opcodes.ACC_PUBLIC, "id", "I", null, null).visitEnd();
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(I)V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ILOAD, 1);
                c.visitFieldInsn(Opcodes.PUTFIELD, U + "Level", "id", "I");
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
                MethodVisitor by = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "byId", "(I)L" + U + "Level;", null, null);
                by.visitCode();
                by.visitTypeInsn(Opcodes.NEW, U + "Level");
                by.visitInsn(Opcodes.DUP);
                by.visitVarInsn(Opcodes.ILOAD, 0);
                by.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Level", "<init>", "(I)V", false);
                by.visitInsn(Opcodes.ARETURN);
                by.visitMaxs(0, 0);
                by.visitEnd();
            }));
            out.put(U + "HasLevel", cls("HasLevel", 0, null, U + "Perm", w -> {
                w.visitField(Opcodes.ACC_PUBLIC, "level", "L" + U + "Level;", null, null).visitEnd();
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + U + "Level;)V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ALOAD, 1);
                c.visitFieldInsn(Opcodes.PUTFIELD, U + "HasLevel", "level", "L" + U + "Level;");
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }));
            out.put(U + "Set", cls("Set", 0, null, null, w -> {
                constructor(w, "java/lang/Object");
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "allowed", "(L" + U + "Perm;)Z", null, null);
                m.visitCode();
                Label no = new Label();
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitTypeInsn(Opcodes.CHECKCAST, U + "HasLevel");
                m.visitFieldInsn(Opcodes.GETFIELD, U + "HasLevel", "level", "L" + U + "Level;");
                m.visitFieldInsn(Opcodes.GETFIELD, U + "Level", "id", "I");
                m.visitInsn(Opcodes.ICONST_2);
                m.visitJumpInsn(Opcodes.IF_ICMPGT, no);
                m.visitInsn(Opcodes.ICONST_1);
                m.visitInsn(Opcodes.IRETURN);
                m.visitLabel(no);
                m.visitInsn(Opcodes.ICONST_0);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }));
            out.put(U + "Key", cls("Key", 0, null, null, w -> {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
                MethodVisitor wr = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wrap", "(Ljava/lang/CharSequence;)L" + U + "Key;", null, null);
                wr.visitCode();
                wr.visitTypeInsn(Opcodes.NEW, U + "Key");
                wr.visitInsn(Opcodes.DUP);
                wr.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Key", "<init>", "()V", false);
                wr.visitInsn(Opcodes.ARETURN);
                wr.visitMaxs(0, 0);
                wr.visitEnd();
            }));
            out.put(U + "Kind", cls("Kind", 0, null, null, w -> {
                constructor(w, "java/lang/Object");
                for (String f : new String[] { "()", "(I)" }) {
                    MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, f.equals("()") ? "plain" : "glow", f + "L" + U + "Kind;", null, null);
                    m.visitCode();
                    m.visitTypeInsn(Opcodes.NEW, U + "Kind");
                    m.visitInsn(Opcodes.DUP);
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, U + "Kind", "<init>", "()V", false);
                    m.visitInsn(Opcodes.ARETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            }));
            out.put(U + "Box", cls("Box", 0, null, null, w -> {
                MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + OBJ + ")V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
            }));
        }
        return out;
    }

    /**
     * Vec (getX(), getY()); Pos, a Vec, with Pos(int, int) and immutable(), and in 1.0 Pos(Vec); Mut, a Pos. User's a(Pos),
     * b(Pos), c(Pos) and d(Mut) each make a Pos from their argument: new Pos(v) in 1.0; v, v, v.immutable() and new
     * Pos(v.getX(), v.getY()) in 2.0.
     */
    static void positions(Map<String, byte[]> out, boolean two) {
        String vec = U + "Vec", pos = U + "Pos", mut = U + "Mut";
        out.put(vec, cls("Vec", 0, null, null, w -> {
            w.visitField(Opcodes.ACC_PUBLIC, "x", "I", null, null).visitEnd();
            w.visitField(Opcodes.ACC_PUBLIC, "y", "I", null, null).visitEnd();
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            for (int i = 0; i < 2; i++) {
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ILOAD, i + 1);
                c.visitFieldInsn(Opcodes.PUTFIELD, vec, i == 0 ? "x" : "y", "I");
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            for (String f : new String[] { "x", "y" }) {
                MethodVisitor g = w.visitMethod(Opcodes.ACC_PUBLIC, f.equals("x") ? "getX" : "getY", "()I", null, null);
                g.visitCode();
                g.visitVarInsn(Opcodes.ALOAD, 0);
                g.visitFieldInsn(Opcodes.GETFIELD, vec, f, "I");
                g.visitInsn(Opcodes.IRETURN);
                g.visitMaxs(0, 0);
                g.visitEnd();
            }
        }));
        out.put(pos, cls("Pos", 0, vec, null, w -> {
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            c.visitVarInsn(Opcodes.ILOAD, 2);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, vec, "<init>", "(II)V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
            if (!two) {
                MethodVisitor v = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + vec + ";)V", null, null);
                v.visitCode();
                v.visitVarInsn(Opcodes.ALOAD, 0);
                v.visitVarInsn(Opcodes.ALOAD, 1);
                v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, vec, "getX", "()I", false);
                v.visitVarInsn(Opcodes.ALOAD, 1);
                v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, vec, "getY", "()I", false);
                v.visitMethodInsn(Opcodes.INVOKESPECIAL, vec, "<init>", "(II)V", false);
                v.visitInsn(Opcodes.RETURN);
                v.visitMaxs(0, 0);
                v.visitEnd();
            }
            MethodVisitor i = w.visitMethod(Opcodes.ACC_PUBLIC, "immutable", "()L" + pos + ";", null, null);
            i.visitCode();
            i.visitVarInsn(Opcodes.ALOAD, 0);
            i.visitInsn(Opcodes.ARETURN);
            i.visitMaxs(0, 0);
            i.visitEnd();
        }));
        out.put(mut, cls("Mut", 0, pos, null, w -> {
            MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitVarInsn(Opcodes.ILOAD, 1);
            c.visitVarInsn(Opcodes.ILOAD, 2);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, pos, "<init>", "(II)V", false);
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }));
        out.put(U + "User", cls("User", 0, null, null, w -> {
            for (String name : new String[] { "a", "b", "c", "d" }) {
                String arg = name.equals("d") ? mut : pos;
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(L" + arg + ";)L" + pos + ";", null, null);
                m.visitCode();
                if (!two || name.equals("d")) {
                    m.visitTypeInsn(Opcodes.NEW, pos);
                    m.visitInsn(Opcodes.DUP);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    if (two) {
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, vec, "getX", "()I", false);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, vec, "getY", "()I", false);
                    }
                    m.visitMethodInsn(Opcodes.INVOKESPECIAL, pos, "<init>", two ? "(II)V" : "(L" + vec + ";)V", false);
                } else {
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    if (name.equals("c")) {
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pos, "immutable", "()L" + pos + ";", false);
                    }
                }
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
    }

    static void constructor(ClassWriter w, String sup) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    static byte[] cls(String simple, int flags, String sup, String iface, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | flags, U + simple, null, sup == null ? "java/lang/Object" : sup, iface == null ? null : new String[] { iface });
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }
}
