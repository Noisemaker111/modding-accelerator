package ${package};

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

/**
 * A mod's annotations read from its class files as NeoForge's scan gives them: the annotated class by its dotted name,
 * its values (an enum constant by its name, an array of primitives as itself), a method by name and descriptor, and only the type asked.
 */
class ModFileScanDataTest {

    @Retention(RetentionPolicy.CLASS)
    @interface Marker {
        String value();

        ElementType kind() default ElementType.TYPE;

        int[] sizes() default {};
    }

    @Marker(value = "plugin", kind = ElementType.FIELD, sizes = { 1, 2 })
    static final class Marked {
        @Marker("method")
        void run() {}
    }

    @Test
    void annotationsAreReadFromTheClassFiles() throws Exception {
        Path classes = Path.of(ModFileScanDataTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ModFileScanData scan = new ModFileScanData(List.of(classes));
        List<ModFileScanData.AnnotationData> types = scan.getAnnotatedBy(Marker.class, ElementType.TYPE).toList();
        assertEquals(1, types.size());
        ModFileScanData.AnnotationData type = types.get(0);
        assertEquals(Marked.class.getName(), type.memberName());
        assertEquals(Type.getType(Marked.class), type.clazz());
        // An enum constant by its name; an array of primitives as ASM and NeoForge give it, the array itself.
        assertEquals("plugin", type.annotationData().get("value"));
        assertEquals("FIELD", type.annotationData().get("kind"));
        assertArrayEquals(new int[] { 1, 2 }, (int[]) type.annotationData().get("sizes"));
        List<ModFileScanData.AnnotationData> methods = scan.getAnnotatedBy(Marker.class, ElementType.METHOD).toList();
        assertEquals(List.of("run()V"), methods.stream().map(ModFileScanData.AnnotationData::memberName).toList());
        assertEquals("method", methods.get(0).annotationData().get("value"));
        assertTrue(scan.getAnnotatedBy(Test.class, ElementType.TYPE).toList().isEmpty(), "@Test is on methods, not types");
        List<String> all = scan.getAnnotations().stream().map(ModFileScanData.AnnotationData::memberName).toList();
        assertTrue(all.contains(Marked.class.getName()) && all.contains("run()V"), "the whole scan holds what a typed scan finds: " + all);
    }
}
