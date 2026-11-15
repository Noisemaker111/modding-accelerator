package ${package};

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * NeoForge's net.neoforged.neoforge.network.IContainerFactory: makes the client's copy of a menu from the extra data
 * the server wrote when it opened it. NeoForge's extends the game's MenuType.MenuSupplier, which the game keeps private
 * (Fabric API widens it for itself only), so this one does not; IMenuTypeExtension.create takes it.
 */
@FunctionalInterface
public interface IContainerFactory<T extends AbstractContainerMenu> {

    T create(int windowId, Inventory inv, RegistryFriendlyByteBuf data);

    default T create(int windowId, Inventory inv) {
        return create(windowId, inv, null);
    }
}
