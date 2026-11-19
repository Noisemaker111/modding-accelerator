import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * The porter's fixes that need nothing learned, each run on this JVM against real classes of the new release:
 * a mixin @Inject moved to its target's new signature (and its handler re-declared with the new parameters), a mod
 * override bridged to a game method that took other parameters, a member the new release closed opened through the
 * mod's access transformer, and access transformer lines for members that are gone commented out. Injectors that take
 * the changed call's own arguments, handlers that capture locals and injection points the new method no longer has
 * stay reported.
 */
final class JarPortFixesTest {

    static final String STR = "Ljava/lang/String;";
    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "g3/Light", 0, null);
            JarPortTest.snap(s, "g3/Alloc", 0, null);
        }
        JarPortTest.snap(a, "g3/Win", 0, null, "go(Z)V", "run()V", "tick()V", "draw()V", "gone()V", "paint(Lg3/Light;" + STR + ")V", "shade(Lg3/Light;)V");
        JarPortTest.snap(b, "g3/Win", 0, null, "go(ZI)V", "run()V", "tick()V", "draw()V", "paint(Lg3/Alloc;" + STR + ")V", "shade(Lg3/Alloc;)V");
        JarPortTest.snap(a, "g3/Hook", Opcodes.ACC_ABSTRACT, null, "handle(" + STR + "I)" + STR, "fire()" + STR);
        GameDiff.Cls hook = JarPortTest.snap(b, "g3/Hook", Opcodes.ACC_ABSTRACT, null, "handle(I" + STR + "Lg3/Alloc;)" + STR, "fire()" + STR);
        hook.methods.get("handle(I" + STR + "Lg3/Alloc;)" + STR).flags |= Opcodes.ACC_ABSTRACT;
        GameDiff.Cls oldBox = JarPortTest.snap(a, "g3/Box", 0, null);
        oldBox.fields.put("size", new GameDiff.Fld(oldBox, "size", "I", Opcodes.ACC_PUBLIC));
        oldBox.fields.put("removed", new GameDiff.Fld(oldBox, "removed", "I", Opcodes.ACC_PUBLIC));
        GameDiff.Cls box = JarPortTest.snap(b, "g3/Box", 0, null);
        box.fields.put("size", new GameDiff.Fld(box, "size", "I", Opcodes.ACC_PRIVATE));
        JarPortTest.snap(a, "g3/Gone", 0, null);
        Path map = scratch.resolve("route-fixes.json");
        Files.writeString(map, """
            {"signatures": [{"owner": "g3.Win", "from": "go", "desc": "(Z)V", "to": "go", "newDesc": "(ZI)V"},
                            {"owner": "g3.Win", "from": "paint", "desc": "(Lg3/Light;Ljava/lang/String;)V", "to": "paint", "newDesc": "(Lg3/Alloc;Ljava/lang/String;)V"},
                            {"owner": "g3.Hook", "from": "handle", "desc": "(Ljava/lang/String;I)Ljava/lang/String;", "to": "handle", "newDesc": "(ILjava/lang/String;Lg3/Alloc;)Ljava/lang/String;"}]}
            """);

        Map<String, byte[]> files = new LinkedHashMap<>();
        // A transformer the metadata names with a single-quoted TOML string: the loader reads that file, not the default one.
        files.put("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[accessTransformers]]\nfile='META-INF/custom.cfg'\n".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/custom.cfg", "public g3.Gone\npublic g3.Box removed\n".getBytes(StandardCharsets.UTF_8));
        files.put("m3/WinMixin.class", winMixin());
        files.put("m3/MyHook.class", myHook());
        files.put("m3/Reader.class", reader());
        List<Map<String, Object>> results = new ArrayList<>();
        JarPort port = new JarPort(a, b, map);
        port.newCode = new JarPort.GameCode(Map.of("g3/Win", win(false))::get, null);
        port.oldCode = new JarPort.GameCode(Map.of("g3/Win", win(true))::get, null);
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "fixes.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();

        Map<String, String> mixin = strings(ported.get("m3/WinMixin.class"));
        JarPortTest.expect(mixin.get("onGo(" + CI + ")V").equals("go(ZI)V HEAD"),
            "an @Inject whose handler takes only its CallbackInfo moves to the target's new signature: " + mixin);
        JarPortTest.expect(mixin.get("onTick(" + CI + ")V").equals("go(ZI)V INVOKE Lg3/Win;tick()V"),
            "and so does one at a call the new method still makes: " + mixin);
        JarPortTest.expect(mixin.get("onRun(" + CI + ")V").equals("run()V INVOKE Lg3/Win;go(ZI)V"),
            "an @Inject at a call that took other parameters moves when the method still makes it: " + mixin);
        JarPortTest.expect(mixin.get("onGone(" + CI + ")V").startsWith("go(Z)V") && mixin.get("onLocals(Z" + CI + "I)V").startsWith("go(Z)V")
            && mixin.get("redirect(Lg3/Win;Lg3/Light;" + STR + ")V").startsWith("paint(Lg3/Light;")
            && mixin.get("onShade(Lg3/Light;" + CI + ")V").startsWith("shade(Lg3/Light;)V"),
            "a call the new method no longer makes, captured locals, a @Redirect and a handler reading an argument that is gone stay as written: " + mixin);
        JarPortTest.expect(mixin.get("onDraw(" + CI + ")V").startsWith("go(Z)V") && mixin.get("onSecondTick(" + CI + ")V").startsWith("go(Z)V"),
            "so do a call the new method makes only inside a lambda, and an @At ordinal whose occurrence is gone: " + mixin);
        JarPortTest.expect(unlinked.contains("mixin target changed signature: g3.Win.go(Z)V")
            && unlinked.contains("mixin target changed signature: g3.Win.paint(Lg3/Light;Ljava/lang/String;)V"),
            "and are reported: " + unlinked);
        JarPortTest.expect(mixin.get("onPaint(Lg3/Alloc;" + STR + CI + ")V").equals("paint(Lg3/Alloc;" + STR + ")V HEAD")
            && mixin.containsKey("onPaint$mmt(Lg3/Light;" + STR + CI + ")V"),
            "a handler that takes the target's arguments is re-declared with the new ones: " + mixin.keySet());

        String at = new String(ported.get("META-INF/custom.cfg"), StandardCharsets.UTF_8);
        JarPortTest.expect(at.startsWith("# public g3.Gone  # mmt port: gone in 2.0\n# public g3.Box removed  # mmt port: gone in 2.0\n")
            && unlinked.stream().noneMatch(u -> u.startsWith("access transformer")),
            "access transformer lines for a class or member that is gone are commented out, not reported: " + at);
        JarPortTest.expect(at.contains("\npublic g3.Box size\n") && unlinked.stream().noneMatch(u -> u.contains("g3.Box.size")),
            "a field the new release made private is opened through the mod's access transformer: " + at);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("g3.Hook")), "an override bridged to the new signature is not reported: " + unlinked);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.put("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", JarPortTest.real(CI.substring(1, CI.length() - 1), "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("g3/Light", JarPortTest.real("g3/Light", "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("g3/Alloc", JarPortTest.real("g3/Alloc", "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("g3/Win", JarPortTest.real("g3/Win", "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("g3/Hook", newHook());
        classes.put("g3/Box", newBox(at));
        for (String c : List.of("m3/WinMixin", "m3/MyHook", "m3/Reader")) {
            classes.put(c, ported.get(c + ".class"));
        }
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
            Object m = loader.loadClass("m3.WinMixin").getConstructor().newInstance();
            Class<?> ci = loader.loadClass("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
            var onPaint = m.getClass().getDeclaredMethod("onPaint", loader.loadClass("g3.Alloc"), String.class, ci);
            onPaint.setAccessible(true);
            onPaint.invoke(m, null, "p", ci.getConstructor().newInstance());
            Object myHook = loader.loadClass("m3.MyHook").getConstructor().newInstance();
            Object fired = myHook.getClass().getMethod("fire").invoke(myHook);
            Class<?> boxType = loader.loadClass("g3.Box");
            Object size = loader.loadClass("m3.Reader").getMethod("read", boxType).invoke(null, boxType.getConstructor().newInstance());
            trace = loader.loadClass("g2.Log").getField("out").get(null) + String.valueOf(fired) + ";size:" + size;
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("paint:p;a7;size:5"),
            "the re-declared handler calls the original with the arguments it had, the game calls the bridged override, "
                + "and the opened field links with the access transformer applied: " + trace);
    }

    /** m3.WinMixin, a @Mixin(g3.Win) with one handler for each case. */
    static byte[] winMixin() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m3/WinMixin", null, "java/lang/Object", null);
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g3/Win"));
        value.visitEnd();
        target.visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        handler(w, INJECT, "onGo", "(" + CI + ")V", "go(Z)V", "HEAD", null, null);
        handler(w, INJECT, "onTick", "(" + CI + ")V", "go(Z)V", "INVOKE", "Lg3/Win;tick()V", null);
        handler(w, INJECT, "onRun", "(" + CI + ")V", "run()V", "INVOKE", "Lg3/Win;go(Z)V", null);
        handler(w, INJECT, "onGone", "(" + CI + ")V", "go(Z)V", "INVOKE", "Lg3/Win;gone()V", null);
        handler(w, INJECT, "onDraw", "(" + CI + ")V", "go(Z)V", "INVOKE", "Lg3/Win;draw()V", null);
        handler(w, INJECT, "onSecondTick", "(" + CI + ")V", "go(Z)V", "INVOKE", "Lg3/Win;tick()V", null);
        handler(w, INJECT, "onLocals", "(Z" + CI + "I)V", "go(Z)V", "HEAD", null, null);
        handler(w, "Lorg/spongepowered/asm/mixin/injection/Redirect;", "redirect", "(Lg3/Win;Lg3/Light;" + STR + ")V", "paint(Lg3/Light;" + STR + ")V", "HEAD", null, null);
        // Reads the Light the new target no longer passes: nothing can stand in for it.
        handler(w, INJECT, "onShade", "(Lg3/Light;" + CI + ")V", "shade(Lg3/Light;)V", "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitInsn(Opcodes.POP);
        });
        // Reads only its String: the Light the new target no longer passes is never read.
        handler(w, INJECT, "onPaint", "(Lg3/Light;" + STR + CI + ")V", "paint(Lg3/Light;" + STR + ")V", "HEAD", null, m -> {
            JarPortTest.append(m, "paint:");
            m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + STR + ")Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            JarPortTest.append(m, ";");
        });
        w.visitEnd();
        return w.toByteArray();
    }

    static void handler(ClassWriter w, String kind, String name, String desc, String method, String at, String atTarget, java.util.function.Consumer<MethodVisitor> body) {
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, name, desc, null, null);
        AnnotationVisitor av = m.visitAnnotation(kind, false);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, method);
        methods.visitEnd();
        AnnotationVisitor ats = av.visitArray("at");
        AnnotationVisitor point = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", at);
        if (atTarget != null) {
            point.visit("target", atTarget);
        }
        if (name.equals("onSecondTick")) {
            point.visit("ordinal", 1);
        }
        point.visitEnd();
        ats.visitEnd();
        av.visitEnd();
        m.visitCode();
        if (body != null) {
            body.accept(m);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * g3.Win's code in one release. The old go(boolean) calls tick() twice and draw(); the new go(boolean, int) calls
     * tick() once and draw() only inside a lambda. run() calls go either way.
     */
    static byte[] win(boolean old) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g3/Win", null, "java/lang/Object", null);
        String go = old ? "(Z)V" : "(ZI)V";
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "go", go, null, null);
        m.visitCode();
        for (int i = 0; i < (old ? 2 : 1); i++) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Win", "tick", "()V", false);
        }
        m.visitVarInsn(Opcodes.ALOAD, 0);
        if (old) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Win", "draw", "()V", false);
        } else {
            Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
            m.visitInvokeDynamicInsn("run", "(Lg3/Win;)Ljava/lang/Runnable;", factory,
                Type.getType("()V"), new Handle(Opcodes.H_INVOKESPECIAL, "g3/Win", "lambda$go$0", "()V", false), Type.getType("()V"));
            m.visitInsn(Opcodes.POP);
            MethodVisitor lambda = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$go$0", "()V", null, null);
            lambda.visitCode();
            lambda.visitVarInsn(Opcodes.ALOAD, 0);
            lambda.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Win", "draw", "()V", false);
            lambda.visitInsn(Opcodes.RETURN);
            lambda.visitMaxs(0, 0);
            lambda.visitEnd();
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor run = w.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitInsn(Opcodes.ICONST_0);
        if (!old) {
            run.visitInsn(Opcodes.ICONST_0);
        }
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Win", "go", go, false);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        String light = old ? "Lg3/Light;" : "Lg3/Alloc;";
        for (var e : Map.of("paint", "(" + light + STR + ")V", "shade", "(" + light + ")V").entrySet()) {
            MethodVisitor p = w.visitMethod(Opcodes.ACC_PUBLIC, e.getKey(), e.getValue(), null, null);
            p.visitCode();
            p.visitInsn(Opcodes.RETURN);
            p.visitMaxs(0, 0);
            p.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** m3.MyHook overrides the old g3.Hook.handle(String, int) with s + i. */
    static byte[] myHook() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m3/MyHook", null, "g3/Hook", null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "g3/Hook", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "handle", "(" + STR + "I)" + STR, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)" + STR, false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(" + STR + ")" + STR, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new g3.Hook: an abstract handle(int, String, Alloc), which fire() calls with 7 and "a". */
    static byte[] newHook() {
        return JarPortTest.real("g3/Hook", "java/lang/Object", Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "handle", "(I" + STR + "Lg3/Alloc;)" + STR, null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "fire", "()" + STR, null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitIntInsn(Opcodes.BIPUSH, 7);
            m.visitLdcInsn("a");
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Hook", "handle", "(I" + STR + "Lg3/Alloc;)" + STR, false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** m3.Reader.read(Box) returns box.size. */
    static byte[] reader() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m3/Reader", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Lg3/Box;)I", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "g3/Box", "size", "I");
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new g3.Box: size is 5 and private, unless the access transformer opens it, as the loader would. */
    static byte[] newBox(String at) {
        int access = at.lines().anyMatch(l -> l.trim().equals("public g3.Box size")) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g3/Box", null, "java/lang/Object", null);
        w.visitField(access, "size", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitFieldInsn(Opcodes.PUTFIELD, "g3/Box", "size", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Each method (name+desc) of a class with its annotations' string values, space-separated. */
    static Map<String, String> strings(byte[] bytes) {
        Map<String, String> out = new TreeMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                List<String> found = new ArrayList<>();
                out.put(name + desc, "");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return collect(found);
                    }

                    @Override
                    public void visitEnd() {
                        out.put(name + desc, String.join(" ", found));
                    }
                };
            }
        }, 0);
        return out;
    }

    static AnnotationVisitor collect(List<String> found) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String n, Object v) {
                if (v instanceof String s) {
                    found.add(s);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String n) {
                return collect(found);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String n, String d) {
                return collect(found);
            }
        };
    }
}
