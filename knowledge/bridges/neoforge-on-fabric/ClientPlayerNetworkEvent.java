package ${package};

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;

/**
 * NeoForge's net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent: LoggingIn is posted from Fabric API's
 * ClientPlayConnectionEvents.JOIN and LoggingOut from its DISCONNECT, the moments NeoForge posts them (the client player
 * made on joining a world; the connection closed). Clone is never posted (Fabric API has no callback when the client
 * player is replaced on respawn).
 */
public abstract class ClientPlayerNetworkEvent extends Event {

    private final MultiPlayerGameMode multiPlayerGameMode;
    private final LocalPlayer player;
    private final Connection connection;

    protected ClientPlayerNetworkEvent(final MultiPlayerGameMode multiPlayerGameMode, final LocalPlayer player, final Connection connection) {
        this.multiPlayerGameMode = multiPlayerGameMode;
        this.player = player;
        this.connection = connection;
    }

    public MultiPlayerGameMode getMultiPlayerGameMode() {
        return multiPlayerGameMode;
    }

    public LocalPlayer getPlayer() {
        return player;
    }

    public Connection getConnection() {
        return connection;
    }

    public static class LoggingIn extends ClientPlayerNetworkEvent {
        public LoggingIn(final MultiPlayerGameMode controller, final LocalPlayer player, final Connection networkManager) {
            super(controller, player, networkManager);
        }
    }

    public static class LoggingOut extends ClientPlayerNetworkEvent {
        public LoggingOut(final MultiPlayerGameMode controller, final LocalPlayer player, final Connection networkManager) {
            super(controller, player, networkManager);
        }
    }

    public static class Clone extends ClientPlayerNetworkEvent {
        private final LocalPlayer oldPlayer;

        public Clone(final MultiPlayerGameMode pc, final LocalPlayer oldPlayer, final LocalPlayer newPlayer, final Connection networkManager) {
            super(pc, newPlayer, networkManager);
            this.oldPlayer = oldPlayer;
        }

        public LocalPlayer getOldPlayer() {
            return oldPlayer;
        }

        public LocalPlayer getNewPlayer() {
            return super.getPlayer();
        }

        @Override
        public LocalPlayer getPlayer() {
            return super.getPlayer();
        }
    }
}
