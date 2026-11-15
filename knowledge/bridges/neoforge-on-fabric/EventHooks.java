package ${package};

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's net.neoforged.neoforge.event.EventHooks: the static methods NeoForge's patches call to post its events,
 * and that mods call directly. Each one here posts the same event as NeoForge's and returns the same result; the bridge
 * mixins call them from the points NeoForge's patches do.
 */
public final class EventHooks {

    private EventHooks() {}

    /** Posts EntityTickEvent.Pre; from the entity tick mixins, before the entity ticks. */
    public static EntityTickEvent.Pre fireEntityTickPre(Entity entity) {
        return Bus.INSTANCE.post(new EntityTickEvent.Pre(entity));
    }

    /** Posts EntityTickEvent.Post; from the entity tick mixins, after the entity ticked. */
    public static void fireEntityTickPost(Entity entity) {
        Bus.INSTANCE.post(new EntityTickEvent.Post(entity));
    }

    /** Posts PlayerTickEvent.Pre; from PlayerTickMixin, first in Player.tick(). */
    public static void firePlayerTickPre(Player player) {
        Bus.INSTANCE.post(new PlayerTickEvent.Pre(player));
    }

    /** Posts PlayerTickEvent.Post; from PlayerTickMixin, last in Player.tick(). */
    public static void firePlayerTickPost(Player player) {
        Bus.INSTANCE.post(new PlayerTickEvent.Post(player));
    }
}
