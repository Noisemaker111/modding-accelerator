package ${package};

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/** NeoForge's net.neoforged.neoforge.registries.DeferredBlock: a DeferredHolder for a block, usable as an ItemLike. */
public class DeferredBlock<T extends Block> extends DeferredHolder<Block, T> implements ItemLike {

    public static <T extends Block> DeferredBlock<T> createBlock(ResourceLocation id) {
        return createBlock(ResourceKey.create(Registries.BLOCK, id));
    }

    public static <T extends Block> DeferredBlock<T> createBlock(ResourceKey<Block> key) {
        return new DeferredBlock<>(key);
    }

    protected DeferredBlock(ResourceKey<Block> key) {
        super(key);
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public ItemStack toStack(int count) {
        ItemStack stack = asItem().getDefaultInstance();
        stack.setCount(count);
        return stack;
    }

    @Override
    public Item asItem() {
        return get().asItem();
    }
}
