package ${package};

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent and its Start, Tick, Stop and Finish,
 * posted by EventHooks.onItemUseStart, onItemUseTick, onUseItemStop and onItemUseFinish from LivingEntityUseItemMixin
 * where NeoForge's patches post them, on both sides: Start in LivingEntity.startUsingItem (cancel: the item is not used;
 * the duration it leaves is the use time), Tick first in updateUsingItem (the duration it leaves is the time left;
 * cancelling it, or a duration of 0 or less, ends the use), Stop before ItemStack.releaseUsing in releaseUsingItem
 * (cancel: releaseUsing is skipped), Finish after ItemStack.finishUsingItem in completeUsingItem (the result stack it
 * leaves goes in the hand). The same members as NeoForge's.
 */
public class LivingEntityUseItemEvent extends LivingEvent {

    private final ItemStack item;
    private final InteractionHand hand;
    private int duration;

    private LivingEntityUseItemEvent(LivingEntity entity, ItemStack item, int duration) {
        this(entity, item, entity.getUsedItemHand(), duration);
    }

    private LivingEntityUseItemEvent(LivingEntity entity, ItemStack item, InteractionHand hand, int duration) {
        super(entity);
        this.item = item;
        this.hand = hand;
        this.setDuration(duration);
    }

    public ItemStack getItem() {
        return item;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public InteractionHand getHand() {
        return hand;
    }

    public static class Start extends LivingEntityUseItemEvent implements ICancellableEvent {

        public Start(LivingEntity entity, ItemStack item, InteractionHand hand, int duration) {
            super(entity, item, hand, duration);
        }
    }

    public static class Tick extends LivingEntityUseItemEvent implements ICancellableEvent {

        public Tick(LivingEntity entity, ItemStack item, int duration) {
            super(entity, item, duration);
        }
    }

    public static class Stop extends LivingEntityUseItemEvent implements ICancellableEvent {

        public Stop(LivingEntity entity, ItemStack item, int duration) {
            super(entity, item, duration);
        }
    }

    public static class Finish extends LivingEntityUseItemEvent {

        private ItemStack result;

        public Finish(LivingEntity entity, ItemStack item, int duration, ItemStack result) {
            super(entity, item, duration);
            this.setResultStack(result);
        }

        public ItemStack getResultStack() {
            return result;
        }

        public void setResultStack(ItemStack result) {
            this.result = result;
        }
    }
}
