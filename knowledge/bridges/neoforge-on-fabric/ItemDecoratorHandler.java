package ${package};

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.client.ItemDecoratorHandler: the decorations mods register for an item with
 * NeoForge's RegisterItemDecorationsEvent, drawn over its slot. Fabric has no such registry, so every item has none and
 * render draws nothing, as NeoForge draws for an item no mod decorates.
 */
public final class ItemDecoratorHandler {

    private static final ItemDecoratorHandler EMPTY = new ItemDecoratorHandler();

    private ItemDecoratorHandler() {}

    public static ItemDecoratorHandler of(ItemStack stack) {
        return EMPTY;
    }

    public void render(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int xOffset, int yOffset) {}
}
