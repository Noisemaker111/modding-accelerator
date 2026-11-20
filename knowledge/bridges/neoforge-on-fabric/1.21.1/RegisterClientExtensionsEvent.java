package ${package};

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent, posted on the mod bus while
 * the client mod loads (ClientGameEvents.registration). An item's extensions are kept (IClientItemExtensions.of) and its
 * custom renderer is Fabric API's builtin item renderer for it; a block's and a fluid type's are kept for
 * IClientBlockExtensions.of and the like. Fabric has no hooks for the rest of what the extensions describe.
 */
public class RegisterClientExtensionsEvent extends Event implements IModBusEvent {

    static final Map<Item, IClientItemExtensions> ITEMS = new ConcurrentHashMap<>();
    static final Map<Block, Object> BLOCKS = new ConcurrentHashMap<>();
    static final Map<Object, Object> FLUID_TYPES = new ConcurrentHashMap<>();

    public RegisterClientExtensionsEvent() {}

    public void registerItem(IClientItemExtensions extensions, ItemLike... items) {
        for (ItemLike like : items) {
            Item item = like.asItem();
            if (ITEMS.putIfAbsent(item, extensions) != null) throw new IllegalStateException("Duplicate client item extensions for " + item);
            BlockEntityWithoutLevelRenderer renderer = extensions.getCustomRenderer();
            if (renderer != null) BuiltinItemRendererRegistry.INSTANCE.register(item, renderer::renderByItem);
        }
    }

    @SafeVarargs
    public final void registerItem(IClientItemExtensions extensions, Holder<Item>... items) {
        for (Holder<Item> item : items) registerItem(extensions, item.value());
    }

    public boolean isItemRegistered(Item item) {
        return ITEMS.containsKey(item);
    }

    public void registerBlock(Object extensions, Block... blocks) {
        for (Block block : blocks) BLOCKS.putIfAbsent(block, extensions);
    }

    public boolean isBlockRegistered(Block block) {
        return BLOCKS.containsKey(block);
    }

    public void registerFluidType(Object extensions, Object... fluidTypes) {
        for (Object type : fluidTypes) FLUID_TYPES.putIfAbsent(type, extensions);
    }
}
