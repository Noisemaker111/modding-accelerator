import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Port rules' "inClass" replacements (FixExpr), applied by JarPort and then run on this JVM against real classes of the
 * new release: an instance call that became a static one, int literals widened to the float parameters they fill, a
 * method reference whose name matches a rule's regex ({^1}), a call taking a supplier that now takes its value, a
 * static field read that became a call, `new`, and a class the new release dropped whose values become another (a
 * builder). Replacements that would drop an argument the rule did not name, or that the caller may not use, are left
 * unlinked. A class that became an interface under its own name is called as one. JarPortTest runs this with its own
 * checks.
 */
final class JarPortRulesTest {

    static void check(Path scratch) throws Exception {
        String str = "Ljava/lang/String;";
        String supplier = "Ljava/util/function/Supplier;";
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        GameDiff.Cls oldGui = JarPortTest.snap(a, "g3/Gui", 0, null, "setColor(FFFF)V", "alpha(F)V", "blit(I)V", "blit(II)V", "flush()V");
        oldGui.fields.put("MAX", new GameDiff.Fld(oldGui, "MAX", "I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        statics(JarPortTest.snap(a, "g3/Rend", 0, null), Opcodes.ACC_PUBLIC, "getPosTexShader()Lg3/Shader;");
        statics(JarPortTest.snap(a, "g3/Sys", 0, null), Opcodes.ACC_PUBLIC, "setShader(" + supplier + ")V");
        JarPortTest.snap(a, "g3/Shader", 0, null);
        JarPortTest.snap(a, "g3/Box", 0, null, "<init>(II)V");
        statics(JarPortTest.snap(a, "g3/Builder", 0, null, "done()Lg3/Box;"), Opcodes.ACC_PUBLIC, "make(I)Lg3/Builder;");
        JarPortTest.snap(a, "g3/Res", 0, null, "ok()Z");

        JarPortTest.snap(b, "g3/Gui", 0, null, "draw(I)V");
        JarPortTest.snap(b, "g3/Rend", 0, null);
        GameDiff.Cls sys = JarPortTest.snap(b, "g3/Sys", 0, null);
        statics(sys, Opcodes.ACC_PUBLIC, "color(FFFF)V", "use(Lg3/Prog;)V", "max()I");
        statics(sys, Opcodes.ACC_PRIVATE, "hidden()V");
        JarPortTest.snap(b, "g3/Prog", 0, null);
        JarPortTest.snap(b, "g3/Key", 0, null);
        GameDiff.Cls shaders = JarPortTest.snap(b, "g3/Shaders", 0, null);
        shaders.fields.put("POS_TEX", new GameDiff.Fld(shaders, "POS_TEX", "Lg3/Key;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        statics(JarPortTest.snap(b, "g3/Mgr", 0, null), Opcodes.ACC_PUBLIC, "get(Lg3/Key;)Lg3/Prog;");
        JarPortTest.snap(b, "g3/Box", 0, null, "<init>(I)V");
        JarPortTest.snap(b, "g3/Res", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "ok()Z");

        Path map = scratch.resolve("route-rules.json");
        Files.writeString(map, """
            {"classes": [{"from": "g3.Shader", "to": "g3.Prog"}]}
            """);
        Path book = scratch.resolve("fixes-rules.json");
        Files.writeString(book, """
            {"rules": [
              {"id": "color", "inClass": {"g3.Gui.setColor": "g3.Sys.color($1, $2, $3, $4)", "g3.Gui.alpha(F)": "g3.Sys.color(1, 1, 1, $1)"}},
              {"id": "shaders", "inClass": {"g3.Rend.get(\\\\w+)Shader": "g3.Mgr.get(g3.Shaders.{^1})",
                                            "g3.Sys.setShader(Ljava/util/function/Supplier;)": "g3.Sys.use((g3.Prog) $1.get())"}},
              {"id": "max", "inClass": {"g3.Gui.MAX": "g3.Sys.max()"}},
              {"id": "blit", "inClass": {"g3.Gui.blit": "$0.draw($1)"}},
              {"id": "builder", "inClass": {"g3.Builder": "g3.Box", "g3.Builder.make": "new g3.Box($1)", "g3.Builder.done": "$0"}},
              {"id": "box", "inClass": {"g3.Box.<init>(II)": "new g3.Box($1)"}},
              {"id": "hidden", "inClass": {"g3.Gui.flush": "g3.Sys.hidden()"}}
            ]}
            """);

        ClassWriter run = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        run.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m3/Run", null, "java/lang/Object", null);
        MethodVisitor go = run.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "(Lg3/Gui;Lg3/Res;)" + str, null, null);
        go.visitCode();
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitLdcInsn(0.5F);
        go.visitInsn(Opcodes.FCONST_1);
        go.visitInsn(Opcodes.FCONST_1);
        go.visitInsn(Opcodes.FCONST_0);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Gui", "setColor", "(FFFF)V", false);
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitLdcInsn(0.25F);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Gui", "alpha", "(F)V", false);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        go.visitInvokeDynamicInsn("get", "()" + supplier, factory, Type.getType("()Ljava/lang/Object;"),
            new Handle(Opcodes.H_INVOKESTATIC, "g3/Rend", "getPosTexShader", "()Lg3/Shader;", false), Type.getType("()Lg3/Shader;"));
        go.visitMethodInsn(Opcodes.INVOKESTATIC, "g3/Sys", "setShader", "(" + supplier + ")V", false);
        go.visitVarInsn(Opcodes.ALOAD, 0);
        go.visitFieldInsn(Opcodes.GETSTATIC, "g3/Gui", "MAX", "I");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Gui", "blit", "(I)V", false);
        go.visitInsn(Opcodes.ICONST_3);
        go.visitMethodInsn(Opcodes.INVOKESTATIC, "g3/Builder", "make", "(I)Lg3/Builder;", false);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Builder", "done", "()Lg3/Box;", false);
        go.visitInsn(Opcodes.POP);
        go.visitVarInsn(Opcodes.ALOAD, 1);
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Res", "ok", "()Z", false);
        go.visitInsn(Opcodes.POP);
        go.visitTypeInsn(Opcodes.NEW, "g3/Box");
        go.visitInsn(Opcodes.DUP);
        go.visitInsn(Opcodes.ICONST_5);
        go.visitIntInsn(Opcodes.BIPUSH, 6);
        go.visitMethodInsn(Opcodes.INVOKESPECIAL, "g3/Box", "<init>", "(II)V", false);
        go.visitInsn(Opcodes.POP);
        go.visitTypeInsn(Opcodes.NEW, "m3/MyBox");
        go.visitInsn(Opcodes.DUP);
        go.visitMethodInsn(Opcodes.INVOKESPECIAL, "m3/MyBox", "<init>", "()V", false);
        go.visitInsn(Opcodes.POP);
        go.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        go.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()" + str, false);
        go.visitInsn(Opcodes.ARETURN);
        go.visitMaxs(0, 2);
        go.visitEnd();
        MethodVisitor never = run.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "never", "(Lg3/Gui;)V", null, null);
        never.visitCode();
        never.visitVarInsn(Opcodes.ALOAD, 0);
        never.visitInsn(Opcodes.ICONST_1);
        never.visitInsn(Opcodes.ICONST_2);
        never.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Gui", "blit", "(II)V", false);
        never.visitVarInsn(Opcodes.ALOAD, 0);
        never.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g3/Gui", "flush", "()V", false);
        never.visitInsn(Opcodes.RETURN);
        never.visitMaxs(0, 1);
        never.visitEnd();
        run.visitEnd();
        ClassWriter mine = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        mine.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m3/MyBox", null, "g3/Box", null);
        MethodVisitor init = mine.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitIntInsn(Opcodes.BIPUSH, 7);
        init.visitIntInsn(Opcodes.BIPUSH, 8);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "g3/Box", "<init>", "(II)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 1);
        init.visitEnd();
        mine.visitEnd();

        JarPort port = new JarPort(a, b, map);
        port.fixes(book);
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m3/Run.class", run.toByteArray(), "m3/MyBox.class", mine.toByteArray())), "rules.jar", results));
        List<Object> unlinked = Json.array(results.get(0).get("unlinked"));
        JarPortTest.expect(unlinked.equals(List.of("call: g3.Gui.blit(II)V", "call: g3.Gui.flush()V")),
            "a rule that would drop an argument it does not name, or call what the mod may not, is not applied: " + unlinked);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.putAll(newGame());
        classes.put("m3/Run", ported.get("m3/Run.class"));
        classes.put("m3/MyBox", ported.get("m3/MyBox.class"));
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
            Object gui = loader.loadClass("g3.Gui").getConstructor().newInstance();
            Object res = loader.loadClass("g3.ResImpl").getConstructor().newInstance();
            trace = String.valueOf(loader.loadClass("m3.Run").getMethod("go", loader.loadClass("g3.Gui"), loader.loadClass("g3.Res")).invoke(null, gui, res));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("color:0.5/0.0;color:1.0/0.25;get;use;max;draw:7;box:3;ok;box:5;box:7;"),
            "port rules' replacements (a static call, widened literals, a regex-named method reference, a supplier's value, a field read, "
                + "new, a dropped class, a changed constructor and super constructor) and a call on a class that became an interface run on the JVM: " + trace);

        GameDiff.Snap opened = new GameDiff.Snap("2.0");
        GameDiff.Cls tile = new GameDiff.Cls("g4/Tile", Opcodes.ACC_FINAL, "java/lang/Object", new ArrayList<>(), false);
        opened.classes.put("g4/Tile", tile);
        tile.methods.put("<init>(I)V", new GameDiff.Meth(tile, "<init>", "(I)V", Opcodes.ACC_PRIVATE));
        tile.methods.put("draw()V", new GameDiff.Meth(tile, "draw", "()V", Opcodes.ACC_PRIVATE));
        tile.fields.put("size", new GameDiff.Fld(tile, "size", "I", Opcodes.ACC_PROTECTED));
        Exposure.loaderAccess(opened, "public g4.Tile\npublic g4.Tile <init>(I)V # made public\nprotected g4.Tile <init>(I)V\n"
            + "protected g4.Tile draw()V\npublic g4.Tile size\nprivate g4.Tile size\n");
        JarPortTest.expect(tile.flags == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL) && tile.methods.get("<init>(I)V").flags == Opcodes.ACC_PUBLIC
                && tile.methods.get("draw()V").flags == Opcodes.ACC_PROTECTED && tile.fields.get("size").flags == Opcodes.ACC_PUBLIC,
            "the loader's access transformer opens the game as mods see it, and never narrows it");
    }

    /**
     * An @EventBusSubscriber class that names no bus: newer loaders send each listener to its event's bus, 1.21.2's puts
     * the class on the game bus and refuses a mod-bus event there. All mod-bus listeners get bus = MOD; a mix is reported.
     */
    static void buses(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "net/neoforged/neoforge/registries/RegisterEvent", 0, null).ifaces.add("net/neoforged/fml/event/IModBusEvent");
            JarPortTest.snap(s, "net/neoforged/neoforge/event/TickEvent", 0, null);
        }
        Path map = scratch.resolve("route-buses.json");
        Files.writeString(map, "{}");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("m5/Reg.class", subscriber("m5/Reg", "net/neoforged/neoforge/registries/RegisterEvent"));
        files.put("m5/Both.class", subscriber("m5/Both", "net/neoforged/neoforge/registries/RegisterEvent", "net/neoforged/neoforge/event/TickEvent"));
        files.put("m5/Game.class", subscriber("m5/Game", "net/neoforged/neoforge/event/TickEvent"));
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(new JarPort(a, b, map).port(JarPortTest.jar(files), "buses.jar", results));
        JarPortTest.expect(bus(ported.get("m5/Reg.class")).equals("MOD") && bus(ported.get("m5/Game.class")).isEmpty(),
            "a subscriber whose listeners are all mod-bus events is put on the mod bus, a game-bus one is left alone: "
                + bus(ported.get("m5/Reg.class")) + " / " + bus(ported.get("m5/Game.class")));
        JarPortTest.expect(Json.array(results.get(0).get("unlinked")).equals(List.of("listeners for both event buses in one @EventBusSubscriber class: m5.Both")),
            "a subscriber with listeners on both buses is reported: " + results.get(0).get("unlinked"));
    }

    /** A mixin into another mod's class extends that class's game superclass, so it may use the game class's protected members. */
    static void foreignMixin(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls base = JarPortTest.snap(s, "g6/Base", 0, null);
            base.methods.put("draw()V", new GameDiff.Meth(base, "draw", "()V", Opcodes.ACC_PROTECTED));
        }
        Path map = scratch.resolve("route-foreign.json");
        Files.writeString(map, "{}");
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m6/Mix", null, "g6/Base", null);
        AnnotationVisitor mixin = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, "other.mod.Screen");
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "redraw", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Base", "draw", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        List<Map<String, Object>> results = new ArrayList<>();
        new JarPort(a, b, map).port(JarPortTest.jar(Map.of("m6/Mix.class", w.toByteArray())), "foreign.jar", results);
        JarPortTest.expect(Json.array(results.get(0).get("unlinked")).isEmpty(),
            "a mixin into another mod's class may call the protected members of the game class it extends: " + results.get(0).get("unlinked"));
    }

    static byte[] subscriber(String name, String... events) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor av = w.visitAnnotation("Lnet/neoforged/fml/common/EventBusSubscriber;", true);
        av.visit("modid", "m5");
        av.visitEnd();
        for (int i = 0; i < events.length; i++) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "on" + i, "(L" + events[i] + ";)V", null, null);
            m.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** The bus an @EventBusSubscriber names, or "" for none. */
    static String bus(byte[] bytes) {
        String[] found = { "" };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String n, String desc, String value) {
                        if (n.equals("bus")) {
                            found[0] = value;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        return found[0];
    }

    static void statics(GameDiff.Cls c, int access, String... methods) {
        for (String m : methods) {
            int paren = m.indexOf('(');
            c.methods.put(m, new GameDiff.Meth(c, m.substring(0, paren), m.substring(paren), access | Opcodes.ACC_STATIC));
        }
    }

    /** The new release's g3 classes as real bytecode, tracing into g2.Log.out like JarPortTest's. */
    static Map<String, byte[]> newGame() {
        Map<String, byte[]> out = new HashMap<>();
        out.put("g3/Gui", JarPortTest.real("g3/Gui", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(I)V", null, null);
            m.visitCode();
            JarPortTest.append(m, "draw:");
            log(m, Opcodes.ILOAD, 1, "(I)");
            JarPortTest.append(m, ";");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g3/Prog", JarPortTest.real("g3/Prog", "java/lang/Object", 0, new String[0], w -> { }));
        out.put("g3/Res", JarPortTest.real("g3/Res", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0], w -> {
            w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "ok", "()Z", null, null).visitEnd();
        }));
        out.put("g3/ResImpl", JarPortTest.real("g3/ResImpl", "java/lang/Object", 0, new String[] { "g3/Res" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "ok", "()Z", null, null);
            m.visitCode();
            JarPortTest.append(m, "ok;");
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g3/Key", JarPortTest.real("g3/Key", "java/lang/Object", 0, new String[0], w -> { }));
        out.put("g3/Rend", JarPortTest.real("g3/Rend", "java/lang/Object", 0, new String[0], w -> { }));
        out.put("g3/Box", JarPortTest.real("g3/Box", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            JarPortTest.append(m, "box:");
            log(m, Opcodes.ILOAD, 1, "(I)");
            JarPortTest.append(m, ";");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g3/Shaders", JarPortTest.real("g3/Shaders", "java/lang/Object", 0, new String[0], w -> {
            w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "POS_TEX", "Lg3/Key;", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            m.visitTypeInsn(Opcodes.NEW, "g3/Key");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g3/Key", "<init>", "()V", false);
            m.visitFieldInsn(Opcodes.PUTSTATIC, "g3/Shaders", "POS_TEX", "Lg3/Key;");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g3/Mgr", JarPortTest.real("g3/Mgr", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "(Lg3/Key;)Lg3/Prog;", null, null);
            m.visitCode();
            JarPortTest.append(m, "get;");
            m.visitTypeInsn(Opcodes.NEW, "g3/Prog");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g3/Prog", "<init>", "()V", false);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }));
        out.put("g3/Sys", JarPortTest.real("g3/Sys", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor color = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "color", "(FFFF)V", null, null);
            color.visitCode();
            JarPortTest.append(color, "color:");
            log(color, Opcodes.FLOAD, 0, "(F)");
            JarPortTest.append(color, "/");
            log(color, Opcodes.FLOAD, 3, "(F)");
            JarPortTest.append(color, ";");
            color.visitInsn(Opcodes.RETURN);
            color.visitMaxs(0, 0);
            color.visitEnd();
            MethodVisitor use = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(Lg3/Prog;)V", null, null);
            use.visitCode();
            JarPortTest.append(use, "use;");
            use.visitInsn(Opcodes.RETURN);
            use.visitMaxs(0, 0);
            use.visitEnd();
            MethodVisitor max = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "max", "()I", null, null);
            max.visitCode();
            JarPortTest.append(max, "max;");
            max.visitIntInsn(Opcodes.BIPUSH, 7);
            max.visitInsn(Opcodes.IRETURN);
            max.visitMaxs(0, 0);
            max.visitEnd();
        }));
        return out;
    }

    /** Appends a local (int or float) to g2.Log.out. */
    static void log(MethodVisitor m, int load, int slot, String type) {
        m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        m.visitVarInsn(load, slot);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", type + "Ljava/lang/StringBuilder;", false);
        m.visitInsn(Opcodes.POP);
    }
}
