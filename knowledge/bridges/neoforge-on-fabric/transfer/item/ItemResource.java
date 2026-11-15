package ${package}.transfer.item;

import ${package}.transfer.resource.DataComponentHolderResource;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.transfer.item.ItemResource: an item with its data components, without a count.
 * It is the same thing as Fabric API's ItemVariant (item and component patch); TransferBridge turns one into the other.
 */
public final class ItemResource implements DataComponentHolderResource<Item> {

    public static final ItemResource EMPTY = new ItemResource(ItemStack.EMPTY);

    public static final Codec<ItemResource> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(i -> i.group(
        Item.CODEC.fieldOf("id").forGetter(ItemResource::typeHolder),
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(ItemResource::getComponentsPatch)
    ).apply(i, ItemResource::of)));

    public static final Codec<ItemResource> OPTIONAL_CODEC = ExtraCodecs.optionalEmptyMap(CODEC).xmap(
        optional -> optional.orElse(EMPTY),
        resource -> resource.isEmpty() ? Optional.empty() : Optional.of(resource)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemResource> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.holderRegistry(Registries.ITEM),
        ItemResource::typeHolder,
        DataComponentPatch.STREAM_CODEC,
        ItemResource::getComponentsPatch,
        ItemResource::of
    );

    /** A stack of one, never changed. */
    private final ItemStack innerStack;

    private ItemResource(ItemStack stack) {
        this.innerStack = stack;
    }

    public static ItemResource of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new ItemResource(stack.copyWithCount(1));
    }

    public static ItemResource of(ItemLike item) {
        return of(new ItemStack(item));
    }

    public static ItemResource of(ItemLike item, DataComponentPatch patch) {
        return of(item.asItem().builtInRegistryHolder(), patch);
    }

    public static ItemResource of(Holder<Item> holder) {
        return of(new ItemStack(holder));
    }

    public static ItemResource of(Holder<Item> holder, DataComponentPatch patch) {
        return of(new ItemStack(holder, 1, patch));
    }

    @Override
    public Item value() {
        return innerStack.getItem();
    }

    public Item getItem() {
        return innerStack.getItem();
    }

    @Override
    public Holder<Item> typeHolder() {
        return innerStack.typeHolder();
    }

    @Override
    public boolean isEmpty() {
        return innerStack.isEmpty();
    }

    public boolean matches(ItemStack stack) {
        return ItemStack.isSameItemSameComponents(stack, innerStack);
    }

    public boolean is(ItemLike item) {
        return innerStack.is(item.asItem());
    }

    public boolean test(Predicate<ItemStack> predicate) {
        return predicate.test(innerStack);
    }

    @Override
    public boolean isComponentsPatchEmpty() {
        return innerStack.getComponentsPatch().isEmpty();
    }

    @Override
    public ItemResource withMergedPatch(DataComponentPatch patch) {
        if (isEmpty() || patch.isEmpty()) return this;
        ItemStack stack = innerStack.copy();
        stack.applyComponents(patch);
        return new ItemResource(stack);
    }

    @Override
    public <D> ItemResource with(DataComponentType<D> type, @Nullable D data) {
        if (isEmpty()) return this;
        ItemStack stack = innerStack.copy();
        stack.set(type, data);
        return new ItemResource(stack);
    }

    @Override
    public <D> ItemResource with(Supplier<? extends DataComponentType<D>> type, @Nullable D data) {
        return with(type.get(), data);
    }

    @Override
    public ItemResource without(DataComponentType<?> type) {
        if (isEmpty()) return this;
        ItemStack stack = innerStack.copy();
        stack.remove(type);
        return new ItemResource(stack);
    }

    @Override
    public ItemResource without(Supplier<? extends DataComponentType<?>> type) {
        return without(type.get());
    }

    @Override
    public DataComponentMap getComponents() {
        return innerStack.getComponents();
    }

    @Override
    public DataComponentPatch getComponentsPatch() {
        return innerStack.getComponentsPatch();
    }

    public ItemStack toStack(int count) {
        return innerStack.copyWithCount(count);
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public int getMaxStackSize() {
        return innerStack.getMaxStackSize();
    }

    public Component getHoverName() {
        return innerStack.getHoverName();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj == this || obj instanceof ItemResource other && ItemStack.isSameItemSameComponents(innerStack, other.innerStack);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(innerStack);
    }

    @Override
    public String toString() {
        return isEmpty() ? "ItemResource[EMPTY]" : "ItemResource[" + innerStack.getItem() + " " + innerStack.getComponentsPatch() + "]";
    }
}
