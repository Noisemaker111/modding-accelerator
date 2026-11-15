package ${package}.capabilities;

import ${package}.Event;
import ${package}.IModBusEvent;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent, posted on the mod's bus at the end of the main
 * entrypoint, after registration (Bridge.common), as NeoForge posts it once its registries are frozen. Each provider is
 * registered with the Fabric API lookup the capability is (see BlockCapability), so Fabric mods querying that lookup get it.
 */
public class RegisterCapabilitiesEvent extends Event implements IModBusEvent {

    public RegisterCapabilitiesEvent() {}

    public <T, C> void registerBlock(BlockCapability<T, C> capability, IBlockCapabilityProvider<T, C> provider, Block... blocks) {
        Objects.requireNonNull(provider);
        if (blocks.length == 0) throw new IllegalArgumentException("Must register at least one block");
        for (Block block : blocks) capability.registerBlock(Objects.requireNonNull(block), provider);
    }

    @SuppressWarnings("unchecked")
    public <T, C, BE extends BlockEntity> void registerBlockEntity(BlockCapability<T, C> capability, BlockEntityType<BE> blockEntityType, ICapabilityProvider<? super BE, C, T> provider) {
        Objects.requireNonNull(provider);
        ICapabilityProvider<BE, C, T> typed = (ICapabilityProvider<BE, C, T>) provider;
        capability.registerBlockEntity(blockEntityType, (blockEntity, context) -> blockEntity.getType() == blockEntityType ? typed.getCapability((BE) blockEntity, context) : null);
    }

    public boolean isBlockRegistered(BlockCapability<?, ?> capability, Block block) {
        return capability.isRegistered(Objects.requireNonNull(block));
    }

    @SuppressWarnings("unchecked")
    public <T, C, E extends Entity> void registerEntity(EntityCapability<T, C> capability, EntityType<E> entityType, ICapabilityProvider<? super E, C, T> provider) {
        Objects.requireNonNull(provider);
        ICapabilityProvider<E, C, T> typed = (ICapabilityProvider<E, C, T>) provider;
        capability.register(entityType, (entity, context) -> typed.getCapability((E) entity, context));
    }

    public boolean isEntityRegistered(EntityCapability<?, ?> capability, EntityType<?> entityType) {
        return capability.isRegistered(Objects.requireNonNull(entityType));
    }

    public <T, C> void registerItem(ItemCapability<T, C> capability, ICapabilityProvider<ItemStack, C, T> provider, ItemLike... items) {
        Objects.requireNonNull(provider);
        if (items.length == 0) throw new IllegalArgumentException("Must register at least one item");
        for (ItemLike item : items) capability.register(Objects.requireNonNull(item.asItem()), provider);
    }

    public boolean isItemRegistered(ItemCapability<?, ?> capability, Item item) {
        return capability.isRegistered(Objects.requireNonNull(item));
    }
}
