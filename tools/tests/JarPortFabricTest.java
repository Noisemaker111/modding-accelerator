import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * A Fabric jar through the porter, each part run on this JVM: an extendable (protected) constructor a mod's subclass
 * calls, a generic method the game implements with narrower parameters, a port rule written for Fabric only, and the
 * ported jar handed back in the names Fabric runs (intermediary), where the game calls the mod's override, its code reads
 * the game through intermediary names, and its mixin, accessor, access widener and refmap are written for it.
 */
final class JarPortFabricTest {

    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls base = JarPortTest.snap(s, "g5/Base", 0, null);
            base.methods.put("<init>()V", new GameDiff.Meth(base, "<init>", "()V", Opcodes.ACC_PRIVATE));
            JarPortTest.snap(s, "g5/Input", 0, null);
            JarPortTest.snap(s, "g5/CraftInput", 0, "g5/Input");
            JarPortTest.snap(s, "g5/Recipe", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "matches(Lg5/Input;)Z");
            JarPortTest.snap(s, "g5/Shaped", 0, null, "matches(Lg5/CraftInput;)Z").ifaces.add("g5/Recipe");
            JarPortTest.snap(s, "g5/Level", 0, null);
            GameDiff.Cls entity = JarPortTest.snap(s, "g5/Entity", 0, null, "tick()V", "getLevel()Lg5/Level;");
            entity.fields.put("level", new GameDiff.Fld(entity, "level", "Lg5/Level;", Opcodes.ACC_PRIVATE));
            JarPortTest.snap(s, "g5/Ticker", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "tick()V");
        }
        statics(a, "g5/Clock", "now()J");
        statics(b, "g5/Clock", "time()J");
        Path map = scratch.resolve("route-fabric.json");
        Files.writeString(map, "{}");
        Path book = scratch.resolve("fabric-rules.json");
        Files.writeString(book, """
            {"rules": [{"id": "clock", "jump": "1.0 -> 2.0", "loader": "fabric", "inClass": {"g5.Clock.now()": "g5.Clock.time()"}},
                       {"id": "clock-neoforge", "jump": "1.0 -> 2.0", "loader": "neoforge", "inClass": {"g5.Clock.now()": "g5.Clock.missing()"}}]}
            """);

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", """
            {"schemaVersion": 1, "id": "m5", "version": "1", "accessWidener": "m5.accesswidener", "mixins": ["m5.mixins.json"],
             "depends": {"minecraft": "~1.0", "fabricloader": ">=0.15"}, "jars": [{"file": "META-INF/jars/inner.jar"}]}
            """.getBytes(StandardCharsets.UTF_8));
        files.put("m5.mixins.json", "{\"package\": \"m5\", \"mixins\": [\"EntityMixin\", \"EntityAccessor\"], \"refmap\": \"m5-refmap.json\"}".getBytes(StandardCharsets.UTF_8));
        files.put("m5-refmap.json", "{\"mappings\": {\"m5/EntityMixin\": {\"tick\": \"method_5773()V\"}}}".getBytes(StandardCharsets.UTF_8));
        files.put("m5.accesswidener", "accessWidener v2 named\naccessible field g5/Entity level Lg5/Level;\n".getBytes(StandardCharsets.UTF_8));
        // What ModNames writes for the port to read an extendable constructor (Fabric API's TagEntry, BlockBehaviour.Properties).
        files.put("META-INF/accesstransformer.cfg", "protected-f g5.Base <init>()V\npublic g5.Entity level\n".getBytes(StandardCharsets.UTF_8));
        files.put("m5/Sub.class", sub());
        files.put("m5/MyShaped.class", JarPortTest.real("m5/MyShaped", "g5/Shaped", 0, new String[0], w -> { }));
        files.put("m5/Timer.class", timer());
        files.put("m5/MyEntity.class", myEntity());
        files.put("m5/EntityMixin.class", entityMixin());
        files.put("m5/EntityAccessor.class", accessor());
        files.put("m5/Visitor.class", JarPortTest.empty("m5/Visitor", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", "g5/Ticker"));
        files.put("m5/Lambdas.class", lambdas());
        // A helper mod nested only through fabric.mod.json's "jars": it is ported too, its metadata included.
        files.put("META-INF/jars/inner.jar", JarPortTest.jar(Map.of(
            "fabric.mod.json", "{\"schemaVersion\": 1, \"id\": \"inner\", \"version\": \"1\", \"depends\": {\"minecraft\": \"1.0\"}}".getBytes(StandardCharsets.UTF_8),
            "m5/Timer.class", timer())));
        List<Map<String, Object>> results = new ArrayList<>();
        JarPort port = new JarPort(a, b, map);
        port.fixes(book);
        port.runtimeNames = true;
        port.out = new ModNames.Back(
            Map.of("g5/Entity", "net/minecraft/class_1297", "g5/Level", "net/minecraft/class_1937", "g5/Ticker", "net/minecraft/class_9999"),
            Map.of("g5/Entity.tick()V", "method_5773", "g5/Entity.getLevel()Lg5/Level;", "method_37908", "g5/Ticker.tick()V", "method_9999"),
            Map.of("g5/Entity.level", "field_6002"), b);
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(files), "m5.jar", results));
        // A nested jar's result comes before its parent's.
        List<String> unlinked = results.stream().filter(r -> "m5.jar".equals(r.get("jar"))).flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();

        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("g5.Base.<init>")) && !new String(ported.get("m5.accesswidener"), StandardCharsets.UTF_8).contains("<init>"),
            "a constructor the mod's access widener makes extendable (protected) links from its subclass, with no line added: " + unlinked);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("Recipe.matches")),
            "a generic method the game implements with narrower parameters counts as written: " + unlinked);
        JarPortTest.expect(unlinked.stream().noneMatch(u -> u.contains("Clock")) && calls(ported.get("m5/Timer.class")).stream().anyMatch(c -> c.startsWith("m5/Timer.mmt$fix")),
            "a port rule written for Fabric applies to a Fabric port, and one for NeoForge does not: " + unlinked);

        Map<String, String> entity = JarPortFixesTest.strings(ported.get("m5/MyEntity.class"));
        JarPortTest.expect(entity.containsKey("method_5773()V") && !entity.containsKey("tick()V"),
            "an override of a game method takes its intermediary name: " + entity.keySet());
        List<String> calls = calls(ported.get("m5/MyEntity.class"));
        JarPortTest.expect(calls.contains("m5/MyEntity.method_37908()Lnet/minecraft/class_1937;"),
            "a call to a game method inherited through the mod's class is written in intermediary: " + calls);
        Map<String, String> mixin = JarPortFixesTest.strings(ported.get("m5/EntityMixin.class"));
        JarPortTest.expect(mixin.get("onTick(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                .equals("method_5773 INVOKE Lnet/minecraft/class_1297;method_37908()Lnet/minecraft/class_1937;"),
            "a mixin's target and injection point are written out in intermediary: " + mixin);
        JarPortTest.expect("Lnet/minecraft/class_1297;hashCode()I".equals(mixin.get("onHash(I)I")),
            "a library method named through a game class keeps its name, with the class in intermediary: " + mixin.get("onHash(I)I"));
        JarPortTest.expect("<init> NEW net/minecraft/class_1297".equals(mixin.get("onNew(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")),
            "an @At(\"NEW\") class target is written in intermediary: " + mixin.get("onNew(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"));
        Map<String, byte[]> inner = JarPortTest.entries(ported.get("META-INF/jars/inner.jar"));
        JarPortTest.expect(new String(inner.get("fabric.mod.json"), StandardCharsets.UTF_8).contains("\"minecraft\":\"2.0\"")
            && calls(inner.get("m5/Timer.class")).stream().anyMatch(c -> c.contains("mmt$fix")) && results.stream().anyMatch(r -> String.valueOf(r.get("jar")).endsWith("inner.jar")),
            "a mod nested only through fabric.mod.json is ported and reported, its metadata asking for the new release: " + results.stream().map(r -> r.get("jar")).toList());
        Map<String, byte[]> read = JarPortTest.entries(new ModNames(new Exposure.Intermediary(Map.of(), Map.of())).jar(JarPortTest.jar(files), new ModNames.Counts()));
        JarPortTest.expect(new String(read.get("m5-refmap.json"), StandardCharsets.UTF_8).contains("\"mappings\": {}"),
            "reading a jar writes its refmap's strings into the mixins and empties it, so no release's output keeps a stale one");
        JarPortTest.expect(fields(ported.get("m5/EntityMixin.class")).contains("field_6002"), "a mixin's @Shadow field takes its intermediary name");
        Map<String, String> accessor = JarPortFixesTest.strings(ported.get("m5/EntityAccessor.class"));
        JarPortTest.expect("field_6002".equals(accessor.get("getLevel()Lnet/minecraft/class_1937;")),
            "an @Accessor with no value names its target in intermediary, as Mixin cannot infer it: " + accessor);
        String widener = new String(ported.get("m5.accesswidener"), StandardCharsets.UTF_8);
        JarPortTest.expect(widener.startsWith("accessWidener v2 intermediary\naccessible field net/minecraft/class_1297 field_6002 Lnet/minecraft/class_1937;"),
            "the access widener is written for the intermediary namespace: " + widener);
        JarPortTest.expect(new String(ported.get("m5-refmap.json"), StandardCharsets.UTF_8).contains("\"mappings\": {}") && !ported.containsKey("META-INF/accesstransformer.cfg")
            && new String(ported.get("fabric.mod.json"), StandardCharsets.UTF_8).contains("\"minecraft\":\"2.0\""),
            "the refmap is emptied, the access transformer Fabric never reads is dropped, and the metadata asks for the new release: " + ported.keySet());

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.put("g5/Base", base());
        classes.put("g5/Input", JarPortTest.real("g5/Input", "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("g5/CraftInput", JarPortTest.real("g5/CraftInput", "g5/Input", 0, new String[0], w -> { }));
        classes.put("g5/Recipe", recipe());
        classes.put("g5/Shaped", shaped());
        classes.put("g5/Clock", clock());
        classes.put("net/minecraft/class_1937", JarPortTest.real("net/minecraft/class_1937", "java/lang/Object", 0, new String[0], w -> { }));
        classes.put("net/minecraft/class_1297", runtimeEntity());
        classes.put("net/minecraft/class_9999", JarPortTest.real("net/minecraft/class_9999", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0],
            w -> w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "method_9999", "()V", null, null).visitEnd()));
        for (String c : List.of("m5/Sub", "m5/MyShaped", "m5/Timer", "m5/MyEntity", "m5/Visitor", "m5/Lambdas")) {
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
            loader.loadClass("m5.Sub").getConstructor().newInstance();
            Object shaped = loader.loadClass("m5.MyShaped").getConstructor().newInstance();
            Object matched = loader.loadClass("g5.Recipe").getMethod("matches", loader.loadClass("g5.Input")).invoke(shaped, loader.loadClass("g5.CraftInput").getConstructor().newInstance());
            Object time = loader.loadClass("m5.Timer").getMethod("run").invoke(null);
            Object e = loader.loadClass("m5.MyEntity").getConstructor().newInstance();
            loader.loadClass("net.minecraft.class_1297").getMethod("run").invoke(e);
            Object visitor = loader.loadClass("m5.Lambdas").getMethod("make").invoke(null);
            loader.loadClass("net.minecraft.class_9999").getMethod("method_9999").invoke(visitor);
            trace =loader.loadClass("g2.Log").getField("out").get(null) + "matched:" + matched + ";time:" + time;
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(trace.equals("base;mod-tick;level;lambda;matched:true;time:7"),
            "on the JVM: the subclass calls the extendable constructor, the game's generic method answers, the Fabric rule's "
                + "replacement runs, the game calls the mod's override through its intermediary name, and a lambda for the mod's "
                + "interface answers the game interface's: " + trace);
    }

    static void statics(GameDiff.Snap s, String name, String method) {
        GameDiff.Cls c = JarPortTest.snap(s, name, 0, null);
        c.methods.put(method, new GameDiff.Meth(c, method.substring(0, method.indexOf('(')), method.substring(method.indexOf('(')), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
    }

    static byte[] sub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "m5/Sub", null, "g5/Base", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g5/Base", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The new g5.Base: its constructor protected, as Fabric applies the mod's extendable line. */
    static byte[] base() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g5/Base", null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        JarPortTest.append(m, "base;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static byte[] recipe() {
        return JarPortTest.real("g5/Recipe", "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, new String[0],
            w -> w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "matches", "(Lg5/Input;)Z", null, null).visitEnd());
    }

    /** g5.Shaped: matches(CraftInput), and the bridge javac writes for Recipe.matches(Input). */
    static byte[] shaped() {
        return JarPortTest.real("g5/Shaped", "java/lang/Object", 0, new String[] { "g5/Recipe" }, w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(Lg5/CraftInput;)Z", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            MethodVisitor bridge = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, "matches", "(Lg5/Input;)Z", null, null);
            bridge.visitCode();
            bridge.visitVarInsn(Opcodes.ALOAD, 0);
            bridge.visitVarInsn(Opcodes.ALOAD, 1);
            bridge.visitTypeInsn(Opcodes.CHECKCAST, "g5/CraftInput");
            bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g5/Shaped", "matches", "(Lg5/CraftInput;)Z", false);
            bridge.visitInsn(Opcodes.IRETURN);
            bridge.visitMaxs(0, 0);
            bridge.visitEnd();
        });
    }

    static byte[] clock() {
        return JarPortTest.real("g5/Clock", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "time", "()J", null, null);
            m.visitCode();
            m.visitLdcInsn(7L);
            m.visitInsn(Opcodes.LRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** m5.Timer.run() returns Clock.now(), which only the Fabric rule replaces. */
    static byte[] timer() {
        return JarPortTest.real("m5/Timer", "java/lang/Object", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()J", null, null);
            m.visitCode();
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "g5/Clock", "now", "()J", false);
            m.visitInsn(Opcodes.LRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** m5.MyEntity overrides Entity.tick() and calls the inherited getLevel() from it. */
    static byte[] myEntity() {
        return JarPortTest.real("m5/MyEntity", "g5/Entity", 0, new String[0], w -> {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
            m.visitCode();
            JarPortTest.append(m, "mod-tick;");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m5/MyEntity", "getLevel", "()Lg5/Level;", false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    /** The game's Entity as Fabric runs it: class_1297, whose run() calls method_5773 (tick); method_37908 (getLevel) logs. */
    static byte[] runtimeEntity() {
        return JarPortTest.real("net/minecraft/class_1297", "java/lang/Object", 0, new String[0], w -> {
            JarPortTest.traced(w, "method_5773", "()V", "game-tick;", m -> { });
            MethodVisitor level = w.visitMethod(Opcodes.ACC_PUBLIC, "method_37908", "()Lnet/minecraft/class_1937;", null, null);
            level.visitCode();
            JarPortTest.append(level, "level;");
            level.visitInsn(Opcodes.ACONST_NULL);
            level.visitInsn(Opcodes.ARETURN);
            level.visitMaxs(0, 0);
            level.visitEnd();
            MethodVisitor run = w.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
            run.visitCode();
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1297", "method_5773", "()V", false);
            run.visitInsn(Opcodes.RETURN);
            run.visitMaxs(0, 0);
            run.visitEnd();
        });
    }

    static byte[] entityMixin() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m5/EntityMixin", null, "java/lang/Object", null);
        AnnotationVisitor target = w.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g5/Entity"));
        value.visitEnd();
        target.visitEnd();
        FieldVisitor shadow = w.visitField(Opcodes.ACC_PRIVATE, "level", "Lg5/Level;", null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        shadow.visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "onTick", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor av = m.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = av.visitArray("method");
        methods.visit(null, "tick");
        methods.visitEnd();
        AnnotationVisitor ats = av.visitArray("at");
        AnnotationVisitor point = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", "INVOKE");
        point.visit("target", "Lg5/Entity;getLevel()Lg5/Level;");
        point.visitEnd();
        ats.visitEnd();
        av.visitEnd();
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor made = w.visitMethod(Opcodes.ACC_PRIVATE, "onNew", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = made.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor ctor = inject.visitArray("method");
        ctor.visit(null, "<init>");
        ctor.visitEnd();
        AnnotationVisitor news = inject.visitArray("at");
        AnnotationVisitor at = news.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "NEW");
        at.visit("target", "g5/Entity");
        at.visitEnd();
        news.visitEnd();
        inject.visitEnd();
        made.visitCode();
        made.visitInsn(Opcodes.RETURN);
        made.visitMaxs(0, 0);
        made.visitEnd();
        // A method the game class only inherits from a library (here the JDK): its owner is still the game's class.
        MethodVisitor lib = w.visitMethod(Opcodes.ACC_PRIVATE, "onHash", "(I)I", null, null);
        AnnotationVisitor constant = lib.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", false);
        AnnotationVisitor in = constant.visitArray("method");
        in.visit(null, "Lg5/Entity;hashCode()I");
        in.visitEnd();
        constant.visitEnd();
        lib.visitCode();
        lib.visitVarInsn(Opcodes.ILOAD, 1);
        lib.visitInsn(Opcodes.IRETURN);
        lib.visitMaxs(0, 0);
        lib.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m5.Lambdas.make() returns a lambda for the mod's Visitor, which extends the game's Ticker. */
    static byte[] lambdas() {
        return JarPortTest.real("m5/Lambdas", "java/lang/Object", 0, new String[0], w -> {
            Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Lm5/Visitor;", null, null);
            m.visitCode();
            m.visitInvokeDynamicInsn("tick", "()Lm5/Visitor;", factory,
                Type.getType("()V"), new Handle(Opcodes.H_INVOKESTATIC, "m5/Lambdas", "lambda$make$0", "()V", false), Type.getType("()V"));
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            MethodVisitor body = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$make$0", "()V", null, null);
            body.visitCode();
            JarPortTest.append(body, "lambda;");
            body.visitInsn(Opcodes.RETURN);
            body.visitMaxs(0, 0);
            body.visitEnd();
        });
    }

    static byte[] accessor() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "m5/EntityAccessor", null, "java/lang/Object", null);
        AnnotationVisitor target = w.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g5/Entity"));
        value.visitEnd();
        target.visitEnd();
        JarPortTest.abstractMethod(w, "getLevel", "()Lg5/Level;", "Lorg/spongepowered/asm/mixin/gen/Accessor;", null);
        w.visitEnd();
        return w.toByteArray();
    }

    /** owner.name+desc of every call in a class's code. */
    static List<String> calls(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        out.add(owner + "." + n + d);
                    }
                };
            }
        }, 0);
        return out;
    }

    static List<String> fields(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                out.add(name);
                return null;
            }
        }, 0);
        return out;
    }
}
