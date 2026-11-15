package ${package}.fabric.api.transfer.v1.item;

import ${package}.TransferBridge;
import ${package}.fabric.api.transfer.v1.storage.TransferVariant;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.item.ItemVariant, on NeoForge: an item and its component patch, the
 * same thing as NeoForge's ItemResource (the bridge's TransferBridge turns one into the other). Saved and sent as Fabric
 * does ("item", "components").
 */
public interface ItemVariant extends TransferVariant<Item> {

    Codec<ItemVariant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.holderByNameCodec().fieldOf("item").forGetter(ItemVariant::typeHolder),
        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(ItemVariant::getComponentsPatch)
    ).apply(instance, (item, patch) -> TransferBridge.itemVariant(item.value(), patch)));

    StreamCodec<RegistryFriendlyByteBuf, ItemVariant> PACKET_CODEC = StreamCodec.composite(
        ByteBufCodecs.holderRegistry(Registries.ITEM),
        ItemVariant::typeHolder,
        DataComponentPatch.STREAM_CODEC,
        ItemVariant::getComponentsPatch,
        (item, patch) -> TransferBridge.itemVariant(item.value(), patch)
    );

    static ItemVariant blank() {
        return of(Items.AIR);
    }

    static ItemVariant of(ItemStack stack) {
        return of(stack.getItem(), stack.getComponentsPatch());
    }

    static ItemVariant of(ItemLike item) {
        return of(item, DataComponentPatch.EMPTY);
    }

    static ItemVariant of(ItemLike item, DataComponentPatch components) {
        return TransferBridge.itemVariant(item.asItem(), components);
    }

    default boolean matches(ItemStack stack) {
        return isOf(stack.getItem()) && componentsMatch(stack.getComponentsPatch());
    }

    default Item getItem() {
        return getObject();
    }

    @Override
    @SuppressWarnings("deprecation")
    default Holder<Item> typeHolder() {
        return getItem().builtInRegistryHolder();
    }

    default ItemStack toStack() {
        return toStack(1);
    }

    default ItemStack toStack(int count) {
        if (isBlank()) return ItemStack.EMPTY;
        return new ItemStack(typeHolder(), count, getComponentsPatch());
    }

    @Override
    ItemVariant withComponents(DataComponentPatch patch);
}
