package ${package}.transfer.item;

import ${package}.transfer.ResourceHandler;
import ${package}.transfer.TransferBridge;
import ${package}.transfer.transaction.Transaction;
import ${package}.transfer.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper, on Fabric API's PlayerInventoryStorage: insert
 * offers to the hand and then the inventory as a pickup does, placeItemBackInInventory is offerOrDrop, and drop throws
 * items into the world once the transaction commits. The armor and grouped slot views are not mirrored.
 */
public final class PlayerInventoryWrapper extends VanillaContainerWrapper {

    private final PlayerInventoryStorage inventory;

    private PlayerInventoryWrapper(PlayerInventoryStorage inventory) {
        super(inventory);
        this.inventory = inventory;
    }

    public static PlayerInventoryWrapper of(Player player) {
        return new PlayerInventoryWrapper(PlayerInventoryStorage.of(player));
    }

    public static PlayerInventoryWrapper of(Inventory inventory) {
        return new PlayerInventoryWrapper(PlayerInventoryStorage.of(inventory));
    }

    public ResourceHandler<ItemResource> getSlot(int slot) {
        return TransferBridge.itemHandler(inventory.getSlot(slot));
    }

    public ResourceHandler<ItemResource> getMainHandSlot() {
        return getHandSlot(InteractionHand.MAIN_HAND);
    }

    public ResourceHandler<ItemResource> getHandSlot(InteractionHand hand) {
        return TransferBridge.itemHandler(inventory.getHandSlot(hand));
    }

    @Override
    public int insert(ItemResource resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        return TransferBridge.saturated(inventory.insert(TransferBridge.variant(resource), amount, Transaction.fabric(transaction)));
    }

    public void placeItemBackInInventory(ItemResource resource, int amount, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        inventory.offerOrDrop(TransferBridge.variant(resource), amount, Transaction.fabric(transaction));
    }

    public void drop(ItemResource resource, int amount, boolean dropAround, boolean includeThrowerName, TransactionContext transaction) {
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        inventory.drop(TransferBridge.variant(resource), amount, dropAround, includeThrowerName, Transaction.fabric(transaction));
    }

    @Override
    public String toString() {
        return "PlayerInventoryWrapper[" + inventory + "]";
    }
}
