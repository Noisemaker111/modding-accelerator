package ${package};

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** NeoForge's net.neoforged.neoforge.registries.DeferredItem: a DeferredHolder for an item, usable as an ItemLike. */
public class DeferredItem<T extends Item> extends DeferredHolder<Item, T> implements ItemLike {

    public static <T extends Item> DeferredItem<T> createItem(Identifier id) {
        return createItem(ResourceKey.create(Registries.ITEM, id));
    }

    public static <T extends Item> DeferredItem<T> createItem(ResourceKey<Item> key) {
        return new DeferredItem<>(key);
    }

    protected DeferredItem(ResourceKey<Item> key) {
        super(key);
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public ItemStack toStack(int count) {
        ItemStack stack = get().getDefaultInstance();
        stack.setCount(count);
        return stack;
    }

    @Override
    public Item asItem() {
        return get();
    }
}
