package ${package};

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/** NeoForge 21.1's game events on the server (Fabric API for Minecraft 1.21.1), each posted from the Fabric API callback that fires at the same moment. */
final class GameEvents {

    private GameEvents() {}

    static void hook() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Bus.INSTANCE.post(new PlayerEvent.PlayerLoggedInEvent(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Bus.INSTANCE.post(new PlayerEvent.PlayerLoggedOutEvent(handler.player)));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Bus.INSTANCE.post(new PlayerEvent.PlayerRespawnEvent(newPlayer, alive)));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> Bus.INSTANCE.post(new PlayerEvent.Clone(newPlayer, oldPlayer, !alive)));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> Bus.INSTANCE.post(new PlayerEvent.PlayerChangedDimensionEvent(player, origin.dimension(), destination.dimension())));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> !Bus.INSTANCE.post(new LivingDeathEvent(entity, source)).canceled);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !Bus.INSTANCE.post(new LivingIncomingDamageEvent(entity, source, amount)).canceled);
        // Fabric API for 1.21.1 has no event before an entity joins a level, only ENTITY_LOAD once it has.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Bus.INSTANCE.post(new EntityJoinLevelEvent(entity, level)));
        ServerWorldEvents.LOAD.register((server, level) -> Bus.INSTANCE.post(new LevelEvent.Load(level)));
        ServerWorldEvents.UNLOAD.register((server, level) -> Bus.INSTANCE.post(new LevelEvent.Unload(level)));
        ServerTickEvents.START_WORLD_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Pre(() -> true, level)));
        ServerTickEvents.END_WORLD_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Post(() -> true, level)));
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> Bus.INSTANCE.post(new OnDatapackSyncEvent(player.level().getServer().getPlayerList(), player)));
        interactionHooks();
    }

    // ---- player interaction, block and lifecycle hooks ----

    /**
     * ServerAboutToStartEvent (on a dedicated server after the load-complete lifecycle events), LivingEquipmentChangeEvent,
     * EntityLeaveLevelEvent, TagsUpdatedEvent, the username cache and fake players, IEntityWithComplexSpawn's payload,
     * and the client's share of these when the game is a client.
     */
    static void interactionHooks() {
        UsernameCache.load();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Bridge.loadComplete();
            Bus.INSTANCE.post(new ServerAboutToStartEvent(server));
        });
        ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, from, to) -> Bus.INSTANCE.post(new LivingEquipmentChangeEvent(entity, slot, from, to)));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Bus.INSTANCE.post(new EntityLeaveLevelEvent(entity, level)));
        ServerWorldEvents.UNLOAD.register((server, level) -> FakePlayerFactory.unloadLevel(level));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> UsernameCache.setUsername(handler.player.getUUID(), handler.player.getScoreboardName()));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> Bus.INSTANCE.post(new TagsUpdatedEvent(registries, client, client && ClientAccess.isIntegratedServer())));
        AdvancedAddEntityPayload.register();
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) ClientAccess.hook();
    }
}
