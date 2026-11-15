package ${package};

import java.util.stream.Stream;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * NeoForge's net.neoforged.neoforge.event.OnDatapackSyncEvent, posted from Fabric's
 * ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS, which Fabric fires once for each player it syncs: getPlayer() is that
 * player, and getRelevantPlayers() is that one player.
 */
public class OnDatapackSyncEvent extends Event {

    private final PlayerList playerList;
    private final ServerPlayer player;

    public OnDatapackSyncEvent(PlayerList playerList, ServerPlayer player) {
        this.playerList = playerList;
        this.player = player;
    }

    public PlayerList getPlayerList() {
        return playerList;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public Stream<ServerPlayer> getRelevantPlayers() {
        return player == null ? playerList.getPlayers().stream() : Stream.of(player);
    }
}
