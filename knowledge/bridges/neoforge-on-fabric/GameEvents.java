package ${package};

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** NeoForge's game events on the server, each posted from the Fabric API callback that fires at the same moment. */
final class GameEvents {

    private GameEvents() {}

    static void hook() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Bus.INSTANCE.post(new PlayerEvent.PlayerLoggedInEvent(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Bus.INSTANCE.post(new PlayerEvent.PlayerLoggedOutEvent(handler.player)));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Bus.INSTANCE.post(new PlayerEvent.PlayerRespawnEvent(newPlayer, alive)));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> Bus.INSTANCE.post(new PlayerEvent.Clone(newPlayer, oldPlayer, !alive)));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> Bus.INSTANCE.post(new PlayerEvent.PlayerChangedDimensionEvent(player, origin.dimension(), destination.dimension())));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> !Bus.INSTANCE.post(new LivingDeathEvent(entity, source)).canceled);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !Bus.INSTANCE.post(new LivingIncomingDamageEvent(entity, source, amount)).canceled);
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, fromDisk) -> !Bus.INSTANCE.post(new EntityJoinLevelEvent(entity, level)).canceled);
        ServerLevelEvents.LOAD.register((server, level) -> Bus.INSTANCE.post(new LevelEvent.Load(level)));
        ServerLevelEvents.UNLOAD.register((server, level) -> Bus.INSTANCE.post(new LevelEvent.Unload(level)));
        ServerTickEvents.START_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Pre(() -> true, level)));
        ServerTickEvents.END_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Post(() -> true, level)));
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> Bus.INSTANCE.post(new OnDatapackSyncEvent(player.level().getServer().getPlayerList(), player)));
    }
}
