import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Calls on a parameter the game retyped, end to end on a two-release game built here as real bytecode. In 1.0 the game's
 * Title screen renders with a Pose and calls font.draw(pose, text, (float) x, 2.0F, color); in 2.0 it renders with a
 * Graphics and calls graphics.drawString(font, text, x, 2, color, false), and Font.draw is gone. GameDiff.Retyped learns
 * that rewrite from the two bodies; JarPort applies it in a mod screen whose render moved to the Graphics, and in a static
 * helper that render passes its pose to; the ported mod runs on this JVM against 2.0's classes.
 *
 *   java -cp asm.jar:CLASSES GameRetypedTest SCRATCH   (run by bench/run_tests.py's jar-port check)
 */
final class GameRetypedTest {

    static final String R = "net/minecraft/r/", STR = "Ljava/lang/String;";
    static final String POSE = "L" + R + "Pose;", GFX = "L" + R + "Graphics;", FONT = "L" + R + "Font;";

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Map<String, byte[]> oldGame = game(false), newGame = game(true);
        GameDiff.Snap a = GameDiff.read("1.0", List.copyOf(oldGame.values()), null, List.of());
        GameDiff.Snap b = GameDiff.read("2.0", List.copyOf(newGame.values()), null, List.of());
        GameDiff.Hop hop = new GameDiff.Hop(a, b);
        hop.run();
        List<String> lines = new ArrayList<>();
        String draw = "nothing learned";
        GameDiff.Retyped retyped = new GameDiff.Retyped(hop, new GameDiff.Learner(hop));
        for (GameDiff.Retyped.Reading r : retyped.learn()) {
            lines.add(r.json("1.0 -> 2.0"));
            if (r.name().equals("draw")) {
                draw = r.at() + " " + r.as() + " " + r.expr();
            }
        }
        JarPortTest.expect(draw.equals("1 " + GFX + " $1.drawString($0, $2, (int) $3, (int) $4, $5, false)"),
            "font.draw(pose, ...) is learned as the graphics that took the pose's place drawing with the font, its float positions narrowed back: " + draw + " " + retyped.misses);

        Path map = scratch.resolve("route-retyped.json");
        Files.writeString(map, "{\"signatures\": [{\"owner\": \"net.minecraft.r.Screen\", \"from\": \"render\", \"desc\": \"(" + POSE + "I)V\", \"to\": \"render\", \"newDesc\": \"(" + GFX + "I)V\"}],"
            + " \"retyped\": [" + String.join(",\n", lines) + "]}");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(oldGame::get, null);
        port.newCode = new JarPort.GameCode(newGame::get, null);
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m8/Panel.class", panel())), "retyped.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "the mod's render and the helper it passes its pose to link, their font.draw calls rewritten: " + unlinked);
        Map<String, String> members = JarPortTest.members(ported.get("m8/Panel.class"));
        JarPortTest.expect(members.containsKey("method label(" + GFX + "I)V") && !members.containsKey("method label(" + POSE + "I)V")
            && members.containsKey("method sign(" + GFX + ")V") && !members.containsKey("method sign(" + POSE + ")V"),
            "the helpers moved to the Graphics too, the static one and an instance one nothing overrides: " + members.keySet());

        Map<String, byte[]> classes = new HashMap<>(newGame);
        classes.put("m8/Panel", ported.get("m8/Panel.class"));
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
            Class<?> screen = loader.loadClass("net.minecraft.r.Screen");
            screen.getMethod("show", screen).invoke(null, loader.loadClass("m8.Panel").getConstructor().newInstance());
            trace = String.valueOf(loader.loadClass("net.minecraft.r.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("draw:hi,7,3,9,false;draw:lo,8,4,1,false;draw:si,5,6,2,false;"),
            "the game's render(graphics, 7) reaches the mod, whose calls draw on that graphics with the mod's font, text, positions and color: " + trace);
        if (JarPortTest.failures > 0) {
            System.exit(1);
        }
        System.out.println("game-retyped: 4 checks passed");
    }

    /**
     * m8.Panel, a Screen written for 1.0: render(pose, x) calls font.draw(pose, "hi", (float) x, 3.0F, 9) and passes pose and
     * x + 1 to its static helper label, which calls font.draw(pose, "lo", (float) i, 4.0F, 1).
     */
    static byte[] panel() {
        ClassWriter w = JarPortRetypeTest.writer("m8/Panel", R + "Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        draw(m, 0, 1, "hi", 2, 3.0F, 9, false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "m8/Panel", "label", "(" + POSE + "I)V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m8/Panel", "sign", "(" + POSE + ")V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC, "sign", "(" + POSE + ")V", null, null);
        s.visitCode();
        s.visitInsn(Opcodes.ICONST_5);
        s.visitVarInsn(Opcodes.ISTORE, 2);
        draw(s, 0, 1, "si", 2, 6.0F, 2, false);
        s.visitInsn(Opcodes.RETURN);
        s.visitMaxs(0, 0);
        s.visitEnd();
        MethodVisitor l = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "label", "(" + POSE + "I)V", null, null);
        l.visitCode();
        l.visitTypeInsn(Opcodes.NEW, R + "Font");
        l.visitInsn(Opcodes.DUP);
        l.visitMethodInsn(Opcodes.INVOKESPECIAL, R + "Font", "<init>", "()V", false);
        l.visitVarInsn(Opcodes.ALOAD, 0);
        l.visitLdcInsn("lo");
        l.visitVarInsn(Opcodes.ILOAD, 1);
        l.visitInsn(Opcodes.I2F);
        l.visitLdcInsn(4.0F);
        l.visitInsn(Opcodes.ICONST_1);
        l.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Font", "draw", "(" + POSE + STR + "FFI)I", false);
        l.visitInsn(Opcodes.POP);
        l.visitInsn(Opcodes.RETURN);
        l.visitMaxs(0, 0);
        l.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * this.font.draw(pose, text, (float) x, y, color) in 1.0's names, or graphics.drawString(this.font, text, x, (int) y,
     * color, false) in 2.0's (pose: the slot of the pose or graphics, x: the slot of an int).
     */
    static void draw(MethodVisitor m, int self, int pose, String text, int x, float y, int color, boolean two) {
        draw(m, self, pose, text, x, y, color, two, null);
    }

    /** The same with the font read through a synthetic accessor of the game's own Title (accessor: its name), as javac writes one. */
    static void draw(MethodVisitor m, int self, int pose, String text, int x, float y, int color, boolean two, String accessor) {
        if (two) {
            m.visitVarInsn(Opcodes.ALOAD, pose);
        }
        m.visitVarInsn(Opcodes.ALOAD, self);
        if (accessor == null) {
            m.visitFieldInsn(Opcodes.GETFIELD, R + "Screen", "font", FONT);
        } else {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, R + "Title", accessor, "(L" + R + "Title;)" + FONT, false);
        }
        if (!two) {
            m.visitVarInsn(Opcodes.ALOAD, pose);
        }
        m.visitLdcInsn(text);
        m.visitVarInsn(Opcodes.ILOAD, x);
        if (two) {
            m.visitLdcInsn((int) y);
        } else {
            m.visitInsn(Opcodes.I2F);
            m.visitLdcInsn(y);
        }
        m.visitLdcInsn(color);
        if (two) {
            m.visitInsn(Opcodes.ICONST_0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Graphics", "drawString", "(" + FONT + STR + "IIIZ)I", false);
        } else {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Font", "draw", "(" + POSE + STR + "FFI)I", false);
        }
        m.visitInsn(Opcodes.POP);
    }

    /**
     * One release: Log, Pose, Font (1.0: draw), Graphics (2.0: pose() and drawString, logging its arguments), Screen (font, stamp, render, and in
     * 2.0 show(screen), which renders it with a new Graphics and 7), and the game's own Title, whose render stamps its pose and draws three times.
     */
    static Map<String, byte[]> game(boolean two) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        ClassWriter log = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        log.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, R + "Log", null, "java/lang/Object", null);
        log.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "out", "Ljava/lang/StringBuilder;", null, null).visitEnd();
        MethodVisitor init = log.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, R + "Log", "out", "Ljava/lang/StringBuilder;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        log.visitEnd();
        out.put(R + "Log", log.toByteArray());
        out.put(R + "Pose", JarPortTest.real(R + "Pose", "java/lang/Object", 0, new String[0], w -> { }));
        out.put(R + "Font", JarPortTest.real(R + "Font", "java/lang/Object", 0, new String[0], w -> {
            if (!two) {
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(" + POSE + STR + "FFI)I", null, null);
                m.visitCode();
                m.visitInsn(Opcodes.ICONST_0);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }));
        if (two) {
            out.put(R + "Graphics", JarPortTest.real(R + "Graphics", "java/lang/Object", 0, new String[0], w -> {
                MethodVisitor pose = w.visitMethod(Opcodes.ACC_PUBLIC, "pose", "()" + POSE, null, null);
                pose.visitCode();
                pose.visitTypeInsn(Opcodes.NEW, R + "Pose");
                pose.visitInsn(Opcodes.DUP);
                pose.visitMethodInsn(Opcodes.INVOKESPECIAL, R + "Pose", "<init>", "()V", false);
                pose.visitInsn(Opcodes.ARETURN);
                pose.visitMaxs(0, 0);
                pose.visitEnd();
                MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "drawString", "(" + FONT + STR + "IIIZ)I", null, null);
                m.visitCode();
                append(m, "draw:");
                for (Object[] arg : new Object[][] { { 2, STR, "," }, { 3, "I", "," }, { 4, "I", "," }, { 5, "I", "," }, { 6, "Z", ";" } }) {
                    m.visitFieldInsn(Opcodes.GETSTATIC, R + "Log", "out", "Ljava/lang/StringBuilder;");
                    String t = (String) arg[1];
                    m.visitVarInsn(t.equals(STR) ? Opcodes.ALOAD : Opcodes.ILOAD, (Integer) arg[0]);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + (t.equals(STR) ? STR : t) + ")Ljava/lang/StringBuilder;", false);
                    m.visitInsn(Opcodes.POP);
                    append(m, (String) arg[2]);
                }
                m.visitInsn(Opcodes.ICONST_0);
                m.visitInsn(Opcodes.IRETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }));
        }
        String p = two ? GFX : POSE;
        out.put(R + "Screen", JarPortTest.real(R + "Screen", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PROTECTED, "font", FONT, null, null).visitEnd();
            MethodVisitor stamp = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stamp", "(" + POSE + ")V", null, null);
            stamp.visitCode();
            stamp.visitInsn(Opcodes.RETURN);
            stamp.visitMaxs(0, 0);
            stamp.visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + p + "I)V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            if (two) {
                MethodVisitor s = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "show", "(L" + R + "Screen;)V", null, null);
                s.visitCode();
                s.visitVarInsn(Opcodes.ALOAD, 0);
                s.visitTypeInsn(Opcodes.NEW, R + "Font");
                s.visitInsn(Opcodes.DUP);
                s.visitMethodInsn(Opcodes.INVOKESPECIAL, R + "Font", "<init>", "()V", false);
                s.visitFieldInsn(Opcodes.PUTFIELD, R + "Screen", "font", FONT);
                s.visitVarInsn(Opcodes.ALOAD, 0);
                s.visitTypeInsn(Opcodes.NEW, R + "Graphics");
                s.visitInsn(Opcodes.DUP);
                s.visitMethodInsn(Opcodes.INVOKESPECIAL, R + "Graphics", "<init>", "()V", false);
                s.visitIntInsn(Opcodes.BIPUSH, 7);
                s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Screen", "render", "(" + GFX + "I)V", false);
                s.visitInsn(Opcodes.RETURN);
                s.visitMaxs(0, 0);
                s.visitEnd();
            }
        }));
        out.put(R + "Title", JarPortTest.real(R + "Title", R + "Screen", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + p + "I)V", null, null);
            m.visitCode();
            // The pose the old render had is graphics.pose() in the new one.
            m.visitVarInsn(Opcodes.ALOAD, 1);
            if (two) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Graphics", "pose", "()" + POSE, false);
            }
            m.visitMethodInsn(Opcodes.INVOKESTATIC, R + "Screen", "stamp", "(" + POSE + ")V", false);
            draw(m, 0, 1, "title", 2, 2.0F, 5, two);
            draw(m, 0, 1, "sub", 2, 12.0F, 6, two);
            // javac numbers its synthetic accessors anew in each release: access$100 in 1.0 is access$200 in 2.0.
            draw(m, 0, 1, "foot", 2, 30.0F, 7, two, two ? "access$200" : "access$100");
            // At x 0, written (float) 0 in 1.0: the constant false that 2.0 passes after it is not that operand narrowed back.
            m.visitInsn(Opcodes.ICONST_0);
            m.visitVarInsn(Opcodes.ISTORE, 3);
            draw(m, 0, 1, "zero", 3, 3.0F, 4, two);
            // Twice where 2.0 computes the height another way (x * 2 where 1.0 passed 50.0F): no reading, and not another one.
            for (String text : List.of("odd1", "odd2")) {
                if (two) {
                    m.visitVarInsn(Opcodes.ALOAD, 1);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, R + "Screen", "font", FONT);
                    m.visitLdcInsn(text);
                    m.visitVarInsn(Opcodes.ILOAD, 2);
                    m.visitVarInsn(Opcodes.ILOAD, 2);
                    m.visitInsn(Opcodes.ICONST_2);
                    m.visitInsn(Opcodes.IMUL);
                    m.visitLdcInsn(9);
                    m.visitInsn(Opcodes.ICONST_0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, R + "Graphics", "drawString", "(" + FONT + STR + "IIIZ)I", false);
                    m.visitInsn(Opcodes.POP);
                } else {
                    draw(m, 0, 1, text, 2, 50.0F, 9, false);
                }
            }
            // Twice at a position that depends on the path taken (x > 0 ? 5 : 6): such a use abstains.
            for (int round = 0; round < 2; round++) {
                Label other = new Label(), join = new Label();
                m.visitVarInsn(Opcodes.ILOAD, 2);
                m.visitJumpInsn(Opcodes.IFLE, other);
                m.visitInsn(Opcodes.ICONST_5);
                m.visitJumpInsn(Opcodes.GOTO, join);
                m.visitLabel(other);
                m.visitIntInsn(Opcodes.BIPUSH, 6);
                m.visitLabel(join);
                m.visitVarInsn(Opcodes.ISTORE, 3);
                draw(m, 0, 1, "row", 3, 40.0F, 8, two);
            }
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            MethodVisitor access = w.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, two ? "access$200" : "access$100", "(L" + R + "Title;)" + FONT, null, null);
            access.visitCode();
            access.visitVarInsn(Opcodes.ALOAD, 0);
            access.visitFieldInsn(Opcodes.GETFIELD, R + "Screen", "font", FONT);
            access.visitInsn(Opcodes.ARETURN);
            access.visitMaxs(0, 0);
            access.visitEnd();
        }));
        return out;
    }

    static void append(MethodVisitor m, String text) {
        m.visitFieldInsn(Opcodes.GETSTATIC, R + "Log", "out", "Ljava/lang/StringBuilder;");
        m.visitLdcInsn(text);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")Ljava/lang/StringBuilder;", false);
        m.visitInsn(Opcodes.POP);
    }
}
