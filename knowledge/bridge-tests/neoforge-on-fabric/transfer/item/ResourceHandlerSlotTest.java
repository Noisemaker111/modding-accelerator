package ${package}.transfer.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A menu slot over an index of an item resource handler shows what that index holds, writes a stack set in it to that
 * index (and no other), takes items out of it, and refuses an empty stack or a pickup from an empty index.
 */
class ResourceHandlerSlotTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A stack needs its item's components, which the game binds when it loads data; the test binds the apple's.
        if (!Items.APPLE.builtInRegistryHolder().areComponentsBound()) {
            Items.APPLE.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }

    @Test
    void theSlotReadsAndWritesItsIndex() {
        ItemStacksResourceHandler handler = new ItemStacksResourceHandler(2);
        ResourceHandlerSlot slot = new ResourceHandlerSlot(handler, handler::set, 1, 0, 0);
        assertFalse(slot.mayPickup(null));
        assertFalse(slot.mayPlace(ItemStack.EMPTY));
        assertTrue(slot.mayPlace(new ItemStack(Items.APPLE)));
        slot.set(new ItemStack(Items.APPLE, 5));
        assertEquals(5, handler.getAmountAsInt(1));
        assertEquals(0, handler.getAmountAsInt(0));
        assertTrue(slot.getItem().is(Items.APPLE));
        assertTrue(slot.mayPickup(null));
        ItemStack taken = slot.remove(2);
        assertEquals(2, taken.getCount());
        assertEquals(3, handler.getAmountAsInt(1));
        assertEquals(64, slot.getMaxStackSize(new ItemStack(Items.APPLE)));
    }
}
