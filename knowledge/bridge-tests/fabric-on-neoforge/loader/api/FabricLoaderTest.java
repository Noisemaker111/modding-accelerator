package ${package}.loader.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import ${package}.DualRoleEntrypoint;
import org.junit.jupiter.api.Test;

/** A custom entrypoint is made as Fabric makes it, from the class's no-argument constructor, one instance per key. */
class FabricLoaderTest {

    @Test
    void mainClassIsAlsoACustomEntrypoint() {
        Object first = FabricLoader.make(DualRoleEntrypoint.class.getName());
        Object second = FabricLoader.make(DualRoleEntrypoint.class.getName());
        assertInstanceOf(DualRoleEntrypoint.class, first);
        assertNotSame(first, second);
    }
}
