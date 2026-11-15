package ${package}.fabric.api.transfer.v1.item;

import ${package}.fabric.api.lookup.v1.block.BlockApiLookup;
import ${package}.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.item.ItemStorage, on NeoForge: SIDED is NeoForge's
 * Capabilities.Item.BLOCK (each ResourceHandler seen as a Storage of ItemVariant, and back). ITEM (item-held storages,
 * ContainerItemContext) is not mirrored.
 */
public final class ItemStorage {

    public static final BlockApiLookup<Storage<ItemVariant>, @Nullable Direction> SIDED = BlockApiLookup.get(Identifier.fromNamespaceAndPath("fabric", "sided_item_storage"), Storage.asClass(), Direction.class);

    private ItemStorage() {}
}
