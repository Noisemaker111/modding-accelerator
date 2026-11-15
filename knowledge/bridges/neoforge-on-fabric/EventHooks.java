package ${package};

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

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

    // ---- player interaction, block and lifecycle hooks ----

    /** Posts LivingEntityUseItemEvent.Start; the use duration it leaves, or -1 when it was cancelled. */
    public static int onItemUseStart(LivingEntity entity, ItemStack item, InteractionHand hand, int duration) {
        LivingEntityUseItemEvent.Start event = new LivingEntityUseItemEvent.Start(entity, item, hand, duration);
        return Bus.INSTANCE.post(event).isCanceled() ? -1 : event.getDuration();
    }

    /** Posts LivingEntityUseItemEvent.Tick; the duration left it leaves, or -1 when it was cancelled. */
    public static int onItemUseTick(LivingEntity entity, ItemStack item, int duration) {
        LivingEntityUseItemEvent.Tick event = new LivingEntityUseItemEvent.Tick(entity, item, duration);
        return Bus.INSTANCE.post(event).isCanceled() ? -1 : event.getDuration();
    }

    /** Posts LivingEntityUseItemEvent.Stop; true when it was cancelled (the item is not released). */
    public static boolean onUseItemStop(LivingEntity entity, ItemStack item, int duration) {
        return Bus.INSTANCE.post(new LivingEntityUseItemEvent.Stop(entity, item, duration)).isCanceled();
    }

    /** Posts LivingEntityUseItemEvent.Finish; the result stack it leaves. */
    public static ItemStack onItemUseFinish(LivingEntity entity, ItemStack item, int duration, ItemStack result) {
        LivingEntityUseItemEvent.Finish event = new LivingEntityUseItemEvent.Finish(entity, item, duration, result);
        Bus.INSTANCE.post(event);
        return event.getResultStack();
    }

    /**
     * Posts BlockEvent.EntityMultiPlaceEvent; true when it was cancelled. (The same as the coordinator's; one of the two
     * copies goes when the branches merge.)
     */
    public static boolean onMultiBlockPlace(Entity entity, List<BlockSnapshot> blockSnapshots, Direction direction) {
        BlockSnapshot snap = blockSnapshots.get(0);
        BlockState placedAgainst = snap.getLevel().getBlockState(snap.getPos().relative(direction.getOpposite()));
        return Bus.INSTANCE.post(new BlockEvent.EntityMultiPlaceEvent(blockSnapshots, placedAgainst, entity)).isCanceled();
    }

    /** Posts BlockEvent.EntityPlaceEvent; true when it was cancelled. (The same as the coordinator's; see above.) */
    public static boolean onBlockPlace(Entity entity, BlockSnapshot blockSnapshot, Direction direction) {
        BlockState placedAgainst = blockSnapshot.getLevel().getBlockState(blockSnapshot.getPos().relative(direction.getOpposite()));
        return Bus.INSTANCE.post(new BlockEvent.EntityPlaceEvent(blockSnapshot, placedAgainst, entity)).isCanceled();
    }
}
