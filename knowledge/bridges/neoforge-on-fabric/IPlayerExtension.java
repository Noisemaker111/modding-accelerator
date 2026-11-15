package ${package};

import io.netty.buffer.Unpooled;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IPlayerExtension, for its openMenu methods: NeoForge adds them to
 * Player, so modaccel convert writes player.openMenu(provider, data) as IPlayerExtension.openMenu(player, provider, data).
 * On a server player the menu opens through Fabric API's ExtendedMenuProvider with the bytes the writer wrote, which
 * IMenuTypeExtension's menu types read back; on the client player nothing happens and the result is empty, as on NeoForge.
 * Unlike NeoForge, Fabric API refuses a menu whose type is not one IMenuTypeExtension.create made when it is opened here.
 */
public interface IPlayerExtension {

    static OptionalInt openMenu(Player player, MenuProvider menuProvider, BlockPos pos) {
        return openMenu(player, menuProvider, buf -> buf.writeBlockPos(pos));
    }

    static OptionalInt openMenu(Player player, MenuProvider menuProvider, Consumer<RegistryFriendlyByteBuf> extraDataWriter) {
        if (!(player instanceof ServerPlayer serverPlayer)) return OptionalInt.empty();
        return serverPlayer.openMenu(new ExtendedMenuProvider<RegistryFriendlyByteBuf>() {
            @Override
            public RegistryFriendlyByteBuf getScreenOpeningData(ServerPlayer opener) {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), opener.registryAccess());
                extraDataWriter.accept(buf);
                return buf;
            }

            @Override
            public Component getDisplayName() {
                return menuProvider.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player opener) {
                return menuProvider.createMenu(containerId, inventory, opener);
            }
        });
    }
}
