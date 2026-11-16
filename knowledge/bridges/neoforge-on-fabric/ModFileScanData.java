package ${package};

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * NeoForge's net.neoforged.neoforgespi.language.ModFileScanData: the annotations on a mod's classes, which NeoForge reads
 * from the mod's file with ASM while it loads mods. Here ASM reads them from the class files under the mod's root paths
 * when first asked: annotations on classes (memberName is the class name), fields (the field's name) and methods (name
 * and descriptor), visible or not, as NeoForge's scan. Values are as NeoForge gives them (constants, strings, classes as
 * ASM Types, arrays of primitives as arrays, other arrays as lists, nested annotations as maps), except an enum constant,
 * which is its name here (NeoForge's ModAnnotation.EnumHolder is not an API class).
 */
public class ModFileScanData {

    private final List<Path> roots;
    private final Map<String, Set<AnnotationData>> byType = new LinkedHashMap<>();
    private Set<AnnotationData> all;

    public ModFileScanData(List<Path> roots) {
        this.roots = roots;
    }

    /** Every annotation on the mod's classes and their members. */
    public synchronized Set<AnnotationData> getAnnotations() {
        if (all == null) all = scan(roots, null);
        return all;
    }

    /** The annotations of one type on one kind of element. */
    public Stream<AnnotationData> getAnnotatedBy(Class<? extends Annotation> type, ElementType target) {
        String descriptor = Type.getDescriptor(type);
        Set<AnnotationData> found;
        synchronized (this) {
            found = all != null ? all : byType.computeIfAbsent(descriptor, d -> scan(roots, d));
        }
        return found.stream().filter(a -> a.targetType() == target && a.annotationType().getDescriptor().equals(descriptor));
    }

    public record AnnotationData(Type annotationType, ElementType targetType, Type clazz, String memberName, Map<String, Object> annotationData) {}

    /** The annotations in the class files under these folders; with a descriptor, only class files that name it are read. */
    static Set<AnnotationData> scan(List<Path> roots, String descriptor) {
        Set<AnnotationData> out = new LinkedHashSet<>();
        byte[] wanted = descriptor == null ? null : descriptor.getBytes(StandardCharsets.UTF_8);
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    byte[] bytes = Files.readAllBytes(file);
                    if (wanted == null || contains(bytes, wanted)) read(bytes, out::add);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the classes under " + root, e);
            }
        }
        return out;
    }

    static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i <= bytes.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) if (bytes[i + j] != part[j]) continue outer;
            return true;
        }
        return false;
    }

    /** The annotations of one class file. */
    static void read(byte[] bytes, Consumer<AnnotationData> out) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            Type clazz;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                clazz = Type.getObjectType(name);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return values(desc, ElementType.TYPE, clazz.getClassName());
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return values(annotation, ElementType.FIELD, name);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return values(annotation, ElementType.METHOD, name + desc);
                    }
                };
            }

            AnnotationVisitor values(String desc, ElementType target, String member) {
                Map<String, Object> data = new LinkedHashMap<>();
                return new Values(data, () -> out.accept(new AnnotationData(Type.getType(desc), target, clazz, member, data)));
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** An annotation's values into a map (an array's into a list under the name null), then done runs. */
    static final class Values extends AnnotationVisitor {
        final Map<String, Object> map;
        final List<Object> list;
        final Runnable done;

        Values(Map<String, Object> map, Runnable done) {
            super(Opcodes.ASM9);
            this.map = map;
            this.list = null;
            this.done = done;
        }

        Values(List<Object> list) {
            super(Opcodes.ASM9);
            this.map = null;
            this.list = list;
            this.done = () -> {};
        }

        void put(String name, Object value) {
            if (list != null) list.add(value);
            else map.put(name, value);
        }

        @Override
        public void visit(String name, Object value) {
            put(name, value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            put(name, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Map<String, Object> nested = new LinkedHashMap<>();
            put(name, nested);
            return new Values(nested, () -> {});
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> items = new ArrayList<>();
            put(name, items);
            return new Values(items);
        }

        @Override
        public void visitEnd() {
            done.run();
        }
    }
}
