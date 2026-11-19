import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * A Fabric mod jar read in Mojang's names (tools/ModNames.java), as the jar scoreboard and the port read it: classes,
 * members, lambda targets, record components, mixin strings through the refmap (an accessor with no value too), the
 * access widener and its access transformer form, a nested jar, and a NeoForge access transformer Fabric never reads.
 * Each check fails if that part is left in intermediary names or given the developer's name instead of Mojang's.
 */
public final class ModNamesTest {

    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        failures += ok ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        Exposure.Intermediary names = new Exposure.Intermediary(
            Map.of("net/minecraft/class_1", "net/minecraft/world/entity/Entity", "net/minecraft/class_2", "net/minecraft/world/level/Level",
                "net/minecraft/class_3", "net/minecraft/resources/Key", "net/minecraft/class_1$class_4", "net/minecraft/world/entity/Entity$RemovalReason"),
            Map.of("method_10", "tick", "method_11", "baseTick", "field_20", "tickCount", "comp_5", "id", "method_12", "get")
        );
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("mod/Thing.class", thing());
        files.put("mod/ThingMixin.class", mixin());
        files.put("m.mixins.json", "{\"package\": \"mod\", \"refmap\": \"m-refmap.json\", \"mixins\": [\"ThingMixin\"]}".getBytes(StandardCharsets.UTF_8));
        files.put("m-refmap.json", ("{\"mappings\": {\"mod/ThingMixin\": {\"update\": \"Lnet/minecraft/class_1;method_10()V\", \"age\": \"field_20:I\"}}}")
            .getBytes(StandardCharsets.UTF_8));
        files.put("m.accesswidener", "accessWidener v2 intermediary\ntransitive-accessible method net/minecraft/class_1 method_11 ()V\nextendable class net/minecraft/class_2\n"
            .getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/accesstransformer.cfg", "public net.minecraft.world.level.Level f_46443_\n".getBytes(StandardCharsets.UTF_8));
        files.put("META-INF/jars/inner.jar", zip(Map.of("lib/Inner.class", inner())));
        files.put("fabric.mod.json", "{\"id\": \"m\", \"accessWidener\": \"m.accesswidener\", \"jars\": [{\"file\": \"META-INF/jars/inner.jar\"}]}"
            .getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> out = unzip(new ModNames(names).jar(zip(files), new ModNames.Counts()));

        Set<String> thing = refs(out.get("mod/Thing.class"));
        check(thing.contains("super net/minecraft/world/entity/Entity"), "classes in Mojang names, the superclass too");
        check(thing.contains("call net/minecraft/world/entity/Entity.baseTick") && thing.contains("field net/minecraft/world/entity/Entity.tickCount"), "calls and fields renamed");
        check(thing.contains("declares tick"), "an override takes the overridden method's name");
        check(thing.contains("lambda get()Lnet/minecraft/world/level/Level;"), "a lambda implementing a game interface names its method");
        check(thing.contains("field net/minecraft/resources/Key.id"), "a record component renamed");
        check(thing.contains("field net/minecraft/world/entity/Entity$RemovalReason.KILLED"), "a nested game class renamed");
        check(thing.stream().noneMatch(r -> r.matches(".*(class|method|field|comp)_\\d.*")), "no intermediary name left: " + thing);

        Map<String, String> strings = annotationStrings(out.get("mod/ThingMixin.class"));
        check("Lnet/minecraft/world/entity/Entity;tick()V".equals(strings.get("onUpdate")), "an injection's method string resolved through the refmap: " + strings.get("onUpdate"));
        check("tickCount".equals(strings.get("getAge")), "an accessor with no value gets the target's Mojang name, not the inferred developer name: " + strings.get("getAge"));
        check("HEAD".equals(strings.get("at")), "strings that name no member stay as written");
        check(strings.get("targets").equals("net.minecraft.world.entity.Entity"), "a @Mixin target written as text renamed in its own spelling");

        String widener = new String(out.get("m.accesswidener"), StandardCharsets.UTF_8);
        check(widener.startsWith("accessWidener v2 named") && widener.contains("net/minecraft/world/entity/Entity baseTick"), "the access widener in Mojang names");
        String at = new String(out.get("META-INF/accesstransformer.cfg"), StandardCharsets.UTF_8);
        check(at.contains("public net.minecraft.world.entity.Entity baseTick()V") && at.contains("public-f net.minecraft.world.level.Level"),
            "the widener as access transformer lines the port reads");
        check(!at.contains("f_46443_"), "a NeoForge access transformer in the same jar is dropped: Fabric never reads it");

        String nested = new String(out.get("META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8);
        check(nested.contains("META-INF/jars/inner.jar"), "the nested jar listed where the port looks for nested jars");
        check(refs(unzip(out.get("META-INF/jars/inner.jar")).get("lib/Inner.class")).contains("super net/minecraft/world/level/Level"), "the nested jar renamed too");

        if (failures > 0) {
            System.out.println(failures + " ModNames checks failed");
            System.exit(1);
        }
        System.out.println("ModNames: every check passed");
    }

    /** A mod class overriding a game method, calling one, reading a field, a record component and a lambda. */
    static byte[] thing() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Thing", null, "net/minecraft/class_1", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_10", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1", "method_11", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_1", "field_20", "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_3", "comp_5", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_1$class_4", "KILLED", "Lnet/minecraft/class_1$class_4;");
        mv.visitInsn(Opcodes.POP);
        Handle meta = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("method_12", "()Lnet/minecraft/class_2;", meta, Type.getType("()Ljava/lang/Object;"),
            new Handle(Opcodes.H_INVOKESTATIC, "mod/Thing", "lambda$0", "()Ljava/lang/Object;", false), Type.getType("()Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mixin whose strings are the developer's names, resolved by the refmap. */
    static byte[] mixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/ThingMixin", null, "java/lang/Object", null);
        AnnotationVisitor m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = m.visitArray("targets");
        targets.visit(null, "net.minecraft.class_1");
        targets.visitEnd();
        m.visitEnd();
        MethodVisitor inject = cw.visitMethod(Opcodes.ACC_PRIVATE, "onUpdate", "()V", null, null);
        AnnotationVisitor a = inject.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor method = a.visitArray("method");
        method.visit(null, "update");
        method.visitEnd();
        AnnotationVisitor at = a.visitArray("at");
        AnnotationVisitor head = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        head.visit("value", "HEAD");
        head.visitEnd();
        at.visitEnd();
        a.visitEnd();
        inject.visitEnd();
        MethodVisitor accessor = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getAge", "()I", null, null);
        accessor.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visitEnd();
        accessor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] inner() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "lib/Inner", null, "net/minecraft/class_2", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every annotation string of a class, keyed by the method it is on, or "targets" and "at". */
    static Map<String, String> annotationStrings(byte[] bytes) {
        Map<String, String> out = new HashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return collect("targets", out);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return collect(name, out);
                    }
                };
            }
        }, 0);
        return out;
    }

    static AnnotationVisitor collect(String key, Map<String, String> out) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String s) {
                    out.put("HEAD".equals(s) ? "at" : key, s);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return collect(key, out);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return collect(key, out);
            }
        };
    }

    /** What a class names: its superclass, the methods it declares, and the calls, fields and lambdas in its code. */
    static Set<String> refs(byte[] bytes) {
        Set<String> out = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                out.add("super " + superName);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                out.add("declares " + name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        out.add("call " + owner + "." + n);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        out.add("field " + owner + "." + n);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        out.add("lambda " + n + d);
                    }
                };
            }
        }, 0);
        return out;
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buffer)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                out.put(e.getName(), z.readAllBytes());
            }
        }
        return out;
    }
}
