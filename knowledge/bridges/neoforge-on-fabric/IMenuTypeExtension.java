package ${package};

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IMenuTypeExtension. create() makes Fabric API's ExtendedMenuType,
 * whose opening data is the bytes the server wrote (IPlayerExtension.openMenu), handed to the factory as a
 * RegistryFriendlyByteBuf as NeoForge hands them. Fabric API opens such a menu only through an ExtendedMenuProvider,
 * which IPlayerExtension.openMenu makes: a menu of this type opened with the game's one-argument openMenu throws on Fabric,
 * where NeoForge gives the factory an empty buffer.
 */
public interface IMenuTypeExtension<T> {

    /** The opening data as it travels: its length, then its bytes. */
    StreamCodec<RegistryFriendlyByteBuf, RegistryFriendlyByteBuf> DATA = StreamCodec.of(
        (out, data) -> {
            out.writeVarInt(data.readableBytes());
            out.writeBytes(data, data.readerIndex(), data.readableBytes());
        },
        in -> {
            byte[] bytes = new byte[in.readVarInt()];
            in.readBytes(bytes);
            return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), in.registryAccess());
        }
    );

    static <T extends AbstractContainerMenu> MenuType<T> create(IContainerFactory<T> factory) {
        return new ExtendedMenuType<>(factory::create, DATA);
    }

    T create(int windowId, Inventory playerInv, RegistryFriendlyByteBuf extraData);
}
