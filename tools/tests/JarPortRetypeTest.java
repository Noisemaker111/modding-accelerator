import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Overrides whose game method's parameters changed type, and injection points a method no longer has, run on this JVM
 * against real classes of the new release. The game's Screen.render(Pose, int) became render(Graphics, int) and
 * key(int, int, int) became key(Key); its own Title screen shows graphics.pose() and key.code() where the old code used
 * the old parameters. A mod screen's render and key move to the new signatures with those conversions, and the calls in
 * them get what the game passes now: back(pose) -> back(graphics), the static Gui.fill(pose, x) -> graphics.fill(x),
 * super.key(k, s, m) -> super.key(event), super.render(pose, x) of the mod's own parent -> its moved render. A call with a
 * pose the game did not pass, or outside a moved body, stays reported, and so does a mixin injection point the new
 * method no longer has.
 */
final class JarPortRetypeTest {

    static final String POSE = "Lg6/Pose;", GFX = "Lg6/Graphics;", KEY = "Lg6/Key;";
    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        JarPortTest.snap(a, "g6/Pose", 0, null, "push()V");
        JarPortTest.snap(b, "g6/Pose", 0, null, "push()V");
        JarPortTest.snap(b, "g6/Graphics", 0, null, "pose()" + POSE, "fill(I)V");
        JarPortTest.snap(b, "g6/Key", 0, null, "code()I", "scan()I");
        JarPortTest.snap(a, "g6/Gui", 0, null);
        a.classes.get("g6/Gui").methods.put("fill(" + POSE + "I)V", new GameDiff.Meth(a.classes.get("g6/Gui"), "fill", "(" + POSE + "I)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        JarPortTest.snap(a, "g6/Screen", 0, null, "render(" + POSE + "I)V", "back(" + POSE + ")V", "key(III)Z", "tick()V", "old()V", "kept()V");
        JarPortTest.snap(b, "g6/Screen", 0, null, "render(" + GFX + "I)V", "back(" + GFX + ")V", "key(" + KEY + ")Z", "tick()V", "old()V", "kept()V");
        JarPortTest.snap(a, "g6/Title", 0, "g6/Screen", "render(" + POSE + "I)V", "key(III)Z");
        JarPortTest.snap(b, "g6/Title", 0, "g6/Screen", "render(" + GFX + "I)V", "key(" + KEY + ")Z");
        Path map = scratch.resolve("route-retype.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "g6.Screen", "from": "render", "desc": "(Lg6/Pose;I)V", "to": "render", "newDesc": "(Lg6/Graphics;I)V"},
                            {"owner": "g6.Screen", "from": "back", "desc": "(Lg6/Pose;)V", "to": "back", "newDesc": "(Lg6/Graphics;)V"},
                            {"owner": "g6.Screen", "from": "key", "desc": "(III)Z", "to": "key", "newDesc": "(Lg6/Key;)Z"}],
             "rewrites": [{"owner": "g6.Gui", "from": "fill", "desc": "(Lg6/Pose;I)V", "static": true, "toOwner": "g6.Graphics", "to": "fill",
                           "newDesc": "(I)V", "newStatic": false, "votes": 3, "of": 3}]}
            """);
        boolean learn = !Boolean.getBoolean("retype.nolearn");
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(learn ? Map.of("g6/Title", title(true), "g6/Screen", screen(true))::get : Map.of("g6/Screen", screen(true))::get, null);
        port.newCode = new JarPort.GameCode(learn ? Map.of("g6/Title", title(false), "g6/Screen", screen(false))::get : Map.of("g6/Screen", screen(false))::get, null);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/MyScreen.class", myScreen(), "m6/MyChild.class", myChild())), "retype.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a screen whose render and key moved to the new parameter types links: " + unlinked);
        Map<String, String> members = JarPortTest.members(ported.get("m6/MyScreen.class"));
        JarPortTest.expect(members.containsKey("method render(" + GFX + "I)V") && !members.containsKey("method render(" + POSE + "I)V")
            && members.containsKey("method key(" + KEY + ")Z"), "the old methods are replaced by the new signatures: " + members.keySet());

        List<Map<String, Object>> others = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/Other.class", other(), "m6/ScreenMixin.class", mixin())), "other.jar", others);
        List<String> stays = Json.array(others.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(stays.contains("call: g6.Gui.fill(Lg6/Pose;I)V"),
            "a pose the game did not pass, and one outside a moved body, still get no graphics: " + stays);
        JarPortTest.expect(stays.contains("mixin injection point gone: g6.Screen.old()V in g6.Screen.tick()V (in m6.ScreenMixin)")
            && stays.stream().filter(u -> u.startsWith("mixin injection point gone")).count() == 1,
            "an @Inject at a call the new method no longer makes is reported, one at a call it still makes is not: " + stays);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.put("g6/Pose", JarPortTest.real("g6/Pose", "java/lang/Object", 0, new String[0], w -> JarPortTest.traced(w, "push", "()V", "push;", m -> { })));
        classes.put("g6/Graphics", graphics());
        classes.put("g6/Key", key());
        classes.put("g6/Screen", screen(false));
        classes.put("m6/MyScreen", ported.get("m6/MyScreen.class"));
        classes.put("m6/MyChild", ported.get("m6/MyChild.class"));
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
            Class<?> screen = loader.loadClass("g6.Screen");
            screen.getMethod("run", screen).invoke(null, loader.loadClass("m6.MyScreen").getConstructor().newInstance());
            screen.getMethod("run", screen).invoke(null, loader.loadClass("m6.MyChild").getConstructor().newInstance());
            trace = String.valueOf(loader.loadClass("g2.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("back;fill7;push;fill8;r;A;basekey;true;back;fill7;push;fill8;r;child;A;basekey;true;"),
            "the game's new render and key reach the mod's code with graphics.pose() and event.code(), and its calls get the graphics and the event: " + trace);
    }

    static ClassWriter writer(String name, String sup) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, sup, null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return w;
    }

    /**
     * m6.MyScreen, written for the old release: render(pose, x) calls back(pose) and Gui.fill(pose, x), pushes the pose
     * when x > 5, then fills with a local x + 1; key(k, s, m) logs A for 65 and returns super.key(k, s, m).
     */
    static byte[] myScreen() {
        ClassWriter w = writer("m6/MyScreen", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m6/MyScreen", "back", "(" + POSE + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        Label skip = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitJumpInsn(Opcodes.IF_ICMPLE, skip);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        m.visitLabel(skip);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitVarInsn(Opcodes.ISTORE, 3);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 3);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        JarPortTest.append(m, "r;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "key", "(III)Z", null, null);
        k.visitCode();
        Label notA = new Label();
        k.visitVarInsn(Opcodes.ILOAD, 1);
        k.visitIntInsn(Opcodes.BIPUSH, 65);
        k.visitJumpInsn(Opcodes.IF_ICMPNE, notA);
        JarPortTest.append(k, "A;");
        k.visitLabel(notA);
        k.visitVarInsn(Opcodes.ALOAD, 0);
        k.visitVarInsn(Opcodes.ILOAD, 1);
        k.visitVarInsn(Opcodes.ILOAD, 2);
        k.visitVarInsn(Opcodes.ILOAD, 3);
        k.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Screen", "key", "(III)Z", false);
        k.visitInsn(Opcodes.IRETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.MyChild extends MyScreen: render(pose, x) calls super.render(pose, x), then logs child. */
    static byte[] myChild() {
        ClassWriter w = writer("m6/MyChild", "m6/MyScreen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "m6/MyScreen", "render", "(" + POSE + "I)V", false);
        JarPortTest.append(m, "child;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Other: render fills with a pose it made itself, and a static helper fills with a pose it was handed. */
    static byte[] other() {
        ClassWriter w = writer("m6/Other", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "g6/Pose");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Pose", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor h = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "side", "(" + POSE + ")V", null, null);
        h.visitCode();
        h.visitVarInsn(Opcodes.ALOAD, 0);
        h.visitInsn(Opcodes.ICONST_1);
        h.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        h.visitInsn(Opcodes.RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.ScreenMixin: @Inject into tick() at its call of old(), which the new tick no longer makes, and at kept(), which it does. */
    static byte[] mixin() {
        ClassWriter w = writer("m6/ScreenMixin", "java/lang/Object");
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g6/Screen"));
        value.visitEnd();
        target.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onOld", "(" + CI + ")V", "tick()V", "INVOKE", "Lg6/Screen;old()V", null);
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onKept", "(" + CI + ")V", "tick()V", "INVOKE", "Lg6/Screen;kept()V", null);
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Title, the game's own screen, in one release: it pushes its pose and answers key 256. */
    static byte[] title(boolean old) {
        ClassWriter w = writer("g6/Title", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", old ? "(" + POSE + "I)V" : "(" + GFX + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (!old) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Graphics", "pose", "()" + POSE, false);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "key", old ? "(III)Z" : "(" + KEY + ")Z", null, null);
        k.visitCode();
        Label no = new Label();
        if (old) {
            k.visitVarInsn(Opcodes.ILOAD, 1);
        } else {
            k.visitVarInsn(Opcodes.ALOAD, 1);
            k.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Key", "code", "()I", false);
        }
        k.visitIntInsn(Opcodes.SIPUSH, 256);
        k.visitJumpInsn(Opcodes.IF_ICMPNE, no);
        k.visitInsn(Opcodes.ICONST_1);
        k.visitInsn(Opcodes.IRETURN);
        k.visitLabel(no);
        k.visitInsn(Opcodes.ICONST_0);
        k.visitInsn(Opcodes.IRETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * g6.Screen in one release. The new one is the real class the test runs: render logs base, back logs back, key logs
     * basekey and returns true, and run(screen) renders with a new Graphics and 7 and logs what key(new Key(65)) says.
     * tick() calls old() and kept() in the old release and only kept() in the new one.
     */
    static byte[] screen(boolean old) {
        ClassWriter w = writer("g6/Screen", "java/lang/Object");
        String p = old ? POSE : GFX;
        JarPortTest.traced(w, "render", "(" + p + "I)V", "base;", m -> { });
        JarPortTest.traced(w, "back", "(" + p + ")V", "back;", m -> { });
        JarPortTest.traced(w, "key", old ? "(III)Z" : "(" + KEY + ")Z", "basekey;", m -> { });
        JarPortTest.traced(w, "old", "()V", "old;", m -> { });
        JarPortTest.traced(w, "kept", "()V", "kept;", m -> { });
        JarPortTest.traced(w, "tick", "()V", "tick;", m -> {
            if (old) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "old", "()V", false);
            }
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "kept", "()V", false);
        });
        if (!old) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lg6/Screen;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitTypeInsn(Opcodes.NEW, "g6/Graphics");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Graphics", "<init>", "()V", false);
            m.visitIntInsn(Opcodes.BIPUSH, 7);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "render", "(" + GFX + "I)V", false);
            m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitTypeInsn(Opcodes.NEW, "g6/Key");
            m.visitInsn(Opcodes.DUP);
            m.visitIntInsn(Opcodes.BIPUSH, 65);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Key", "<init>", "(I)V", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "key", "(" + KEY + ")Z", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            JarPortTest.append(m, ";");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Graphics: its own Pose from pose(), and fill(x) logging fill and x. */
    static byte[] graphics() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g6/Graphics", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_FINAL, "pose", POSE, null, null).visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "g6/Pose");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Pose", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, "g6/Graphics", "pose", POSE);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor pose = w.visitMethod(Opcodes.ACC_PUBLIC, "pose", "()" + POSE, null, null);
        pose.visitCode();
        pose.visitVarInsn(Opcodes.ALOAD, 0);
        pose.visitFieldInsn(Opcodes.GETFIELD, "g6/Graphics", "pose", POSE);
        pose.visitInsn(Opcodes.ARETURN);
        pose.visitMaxs(0, 0);
        pose.visitEnd();
        MethodVisitor fill = w.visitMethod(Opcodes.ACC_PUBLIC, "fill", "(I)V", null, null);
        fill.visitCode();
        JarPortTest.append(fill, "fill");
        fill.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        fill.visitVarInsn(Opcodes.ILOAD, 1);
        fill.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        fill.visitInsn(Opcodes.POP);
        JarPortTest.append(fill, ";");
        fill.visitInsn(Opcodes.RETURN);
        fill.visitMaxs(0, 0);
        fill.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Key(code): code() gives it back, scan() gives 0. */
    static byte[] key() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g6/Key", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_FINAL, "code", "I", null, null).visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, "g6/Key", "code", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor code = w.visitMethod(Opcodes.ACC_PUBLIC, "code", "()I", null, null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, "g6/Key", "code", "I");
        code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        MethodVisitor scan = w.visitMethod(Opcodes.ACC_PUBLIC, "scan", "()I", null, null);
        scan.visitCode();
        scan.visitInsn(Opcodes.ICONST_0);
        scan.visitInsn(Opcodes.IRETURN);
        scan.visitMaxs(0, 0);
        scan.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}
