package ${package}.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An item stack takes a supplied component type (a DeferredHolder) as NeoForge's ItemStack does: set, get, has, update
 * and remove act on the type the supplier gives, and isComponentsPatchEmpty tells whether the stack has components of
 * its own. Runs inside Fabric Loader, so ItemStackComponentsMixin has given ItemStack these methods.
 */
class ItemStackComponentsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void suppliedTypesActOnTheStack() {
        Supplier<DataComponentType<Component>> name = () -> DataComponents.CUSTOM_NAME;
        ItemStack stack = new ItemStack(Items.STICK);
        assertTrue(stack.isComponentsPatchEmpty());
        assertFalse(stack.has(name));
        stack.set(name, Component.literal("Wand"));
        assertEquals("Wand", stack.get(name).getString());
        assertEquals("Wand", stack.get(DataComponents.CUSTOM_NAME).getString());
        assertFalse(stack.isComponentsPatchEmpty());
        stack.update(name, Component.empty(), old -> Component.literal(old.getString() + "!"));
        assertEquals("Wand!", stack.getOrDefault(name, Component.empty()).getString());
        assertEquals("Wand!", stack.remove(name).getString());
        assertNull(stack.get(name));
        assertTrue(stack.isComponentsPatchEmpty());
    }
}
