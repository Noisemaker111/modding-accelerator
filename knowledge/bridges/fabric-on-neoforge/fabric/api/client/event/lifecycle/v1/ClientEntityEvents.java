package ${package}.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;

/**
 * Fabric API's net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents, posted from NeoForge's
 * EntityJoinLevelEvent and EntityLeaveLevelEvent on a client level. ENTITY_LOAD is posted when ClientLevel.addEntity
 * starts (Fabric: once the entity is tracked, just after), after every other listener, and not for a join another
 * listener cancelled, as the entity then never loads; ENTITY_UNLOAD when the level stops tracking the entity (Fabric:
 * at the start of that, here at its end).
 */
public final class ClientEntityEvents {

    public static final Event<Load> ENTITY_LOAD = EventFactory.createArrayBacked(Load.class, callbacks -> (entity, level) -> {
        for (Load callback : callbacks) callback.onLoad(entity, level);
    });

    public static final Event<Unload> ENTITY_UNLOAD = EventFactory.createArrayBacked(Unload.class, callbacks -> (entity, level) -> {
        for (Unload callback : callbacks) callback.onUnload(entity, level);
    });

    static {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EntityJoinLevelEvent.class, event -> {
            if (event.getLevel() instanceof ClientLevel level) ENTITY_LOAD.invoker().onLoad(event.getEntity(), level);
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (event.getLevel() instanceof ClientLevel level) ENTITY_UNLOAD.invoker().onUnload(event.getEntity(), level);
        });
    }

    private ClientEntityEvents() {}

    @FunctionalInterface
    public interface Load {
        void onLoad(Entity entity, ClientLevel level);
    }

    @FunctionalInterface
    public interface Unload {
        void onUnload(Entity entity, ClientLevel level);
    }
}
