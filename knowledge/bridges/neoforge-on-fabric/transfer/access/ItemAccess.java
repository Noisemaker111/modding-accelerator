package ${package}.transfer.access;

import ${package}.capabilities.ItemCapability;
import ${package}.transfer.ResourceHandler;
import ${package}.transfer.TransferBridge;
import ${package}.transfer.item.ItemResource;
import ${package}.transfer.transaction.Transaction;
import ${package}.transfer.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.transfer.access.ItemAccess: where an item that has a capability is (a hand, a slot,
 * a cursor, a handler index), so the capability can change it. On Fabric it is Fabric API's ContainerItemContext:
 * forPlayerInteraction is ContainerItemContext.forPlayerInteraction, forInfiniteMaterials is forCreativeInteraction,
 * forPlayerCursor is ofPlayerCursor, forPlayerSlot and forHandlerIndex are ofPlayerSlot and ofSingleSlot. oneByOne is
 * not mirrored; forHandlerIndexStrict is forHandlerIndex.
 */
public interface ItemAccess {

    static ItemAccess forPlayerInteraction(Player player, InteractionHand hand) {
        return TransferBridge.access(ContainerItemContext.forPlayerInteraction(player, hand));
    }

    static ItemAccess forInfiniteMaterials(Player player, ItemStack contents) {
        return TransferBridge.access(ContainerItemContext.forCreativeInteraction(player, contents));
    }

    static ItemAccess forPlayerCursor(Player player, AbstractContainerMenu menu) {
        return TransferBridge.access(ContainerItemContext.ofPlayerCursor(player, menu));
    }

    static ItemAccess forPlayerSlot(Player player, int slot) {
        return TransferBridge.access(ContainerItemContext.ofPlayerSlot(player, PlayerInventoryStorage.of(player).getSlot(slot)));
    }

    static ItemAccess forHandlerIndex(ResourceHandler<ItemResource> handler, int index) {
        return TransferBridge.access(ContainerItemContext.ofSingleSlot(TransferBridge.itemSlot(handler, index)));
    }

    static ItemAccess forHandlerIndexStrict(ResourceHandler<ItemResource> handler, int index) {
        return forHandlerIndex(handler, index);
    }

    /** A stack held by nothing else, in a one-slot container of the access's own. */
    static ItemAccess forStack(ItemStack stack) {
        return TransferBridge.access(ContainerItemContext.ofSingleSlot(ContainerStorage.of(new SimpleContainer(stack), null).getSlot(0)));
    }

    default <T> @Nullable T getCapability(ItemCapability<T, ItemAccess> capability) {
        return capability.getCapability(getResource().toStack(), this);
    }

    ItemResource getResource();

    int getAmount();

    int insert(ItemResource resource, int amount, TransactionContext transaction);

    int extract(ItemResource resource, int amount, TransactionContext transaction);

    /** Extracts amount of the current item and inserts as much of newResource, all or nothing. */
    default int exchange(ItemResource newResource, int amount, @Nullable TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(newResource, amount);
        ItemResource current = getResource();
        TransferBridge.checkNonEmpty(current);
        try (Transaction nested = Transaction.open(transaction)) {
            int extracted = extract(current, amount, nested);
            if (extracted > 0 && insert(newResource, extracted, nested) == extracted) {
                nested.commit();
                return extracted;
            }
        }
        return 0;
    }
}
