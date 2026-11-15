package ${package};

import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's net.neoforged.neoforge.event.tick.PlayerTickEvent, posted where NeoForge's Player patch posts it: first
 * and last in Player.tick(), on both sides (PlayerTickMixin).
 */
public abstract class PlayerTickEvent extends PlayerEvent {

    protected PlayerTickEvent(Player player) {
        super(player);
    }

    /** Fired once per game tick, per player, before the player ticks. */
    public static class Pre extends PlayerTickEvent {

        public Pre(Player player) {
            super(player);
        }
    }

    /** Fired once per game tick, per player, after the player ticked. */
    public static class Post extends PlayerTickEvent {

        public Post(Player player) {
            super(player);
        }
    }
}
