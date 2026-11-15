package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.player.PlayerEvent and the player events this bridge posts from
 * Fabric's: logged in and out (ServerPlayConnectionEvents JOIN and DISCONNECT), respawn and clone (ServerPlayerEvents
 * AFTER_RESPAWN and COPY_FROM) and dimension change (the server's player level change event).
 */
public abstract class PlayerEvent extends LivingEvent {

    public PlayerEvent(Player player) {
        super(player);
    }

    @Override
    public Player getEntity() {
        return (Player) super.getEntity();
    }

    public static class PlayerLoggedInEvent extends PlayerEvent {

        public PlayerLoggedInEvent(Player player) {
            super(player);
        }
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {

        public PlayerLoggedOutEvent(Player player) {
            super(player);
        }
    }

    public static class PlayerRespawnEvent extends PlayerEvent {

        private final boolean endConquered;

        public PlayerRespawnEvent(Player player, boolean endConquered) {
            super(player);
            this.endConquered = endConquered;
        }

        public boolean isEndConquered() {
            return endConquered;
        }
    }

    public static class Clone extends PlayerEvent {

        private final Player original;
        private final boolean wasDeath;

        public Clone(Player player, Player original, boolean wasDeath) {
            super(player);
            this.original = original;
            this.wasDeath = wasDeath;
        }

        public Player getOriginal() {
            return original;
        }

        public boolean isWasDeath() {
            return wasDeath;
        }
    }

    public static class PlayerChangedDimensionEvent extends PlayerEvent {

        private final ResourceKey<Level> from, to;

        public PlayerChangedDimensionEvent(Player player, ResourceKey<Level> from, ResourceKey<Level> to) {
            super(player);
            this.from = from;
            this.to = to;
        }

        public ResourceKey<Level> getFrom() {
            return from;
        }

        public ResourceKey<Level> getTo() {
            return to;
        }
    }

    /**
     * Whether the player can harvest a block (get its drops), posted by EventHooks.doPlayerHarvestCheck, which mods call;
     * the game's own harvest checks do not post it here.
     */
    public static class HarvestCheck extends PlayerEvent {

        private final BlockState state;
        private final BlockGetter level;
        private final BlockPos pos;
        private boolean success;

        public HarvestCheck(Player player, BlockState state, BlockGetter level, BlockPos pos, boolean success) {
            super(player);
            this.state = state;
            this.level = level;
            this.pos = pos;
            this.success = success;
        }

        public BlockState getTargetBlock() {
            return this.state;
        }

        public BlockGetter getLevel() {
            return level;
        }

        public BlockPos getPos() {
            return pos;
        }

        public boolean canHarvest() {
            return this.success;
        }

        public void setCanHarvest(boolean success) {
            this.success = success;
        }
    }
}
