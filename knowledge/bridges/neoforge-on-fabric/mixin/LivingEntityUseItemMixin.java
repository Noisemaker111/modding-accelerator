package ${package}.mixin;

import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's LivingEntity patch, LivingEntityUseItemEvent: startUsingItem posts Start before the item is taken into use
 * (cancelled: not used; its duration is the use time); updateUsingItem posts Tick first and keeps the duration it leaves
 * (the item's tick is skipped and the use ends when that is 0 or less, as NeoForge's patch does);
 * completeUsingItem posts Finish after ItemStack.finishUsingItem (its result stack goes in the hand); releaseUsingItem
 * posts Stop before ItemStack.releaseUsing (cancelled: releaseUsing is skipped).
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityUseItemMixin {

    @Shadow
    protected ItemStack useItem;

    @Shadow
    protected int useItemRemaining;

    @Shadow
    public abstract int getUseItemRemainingTicks();

    @Shadow
    protected abstract void completeUsingItem();

    @Inject(method = "startUsingItem", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;useItem:Lnet/minecraft/world/item/ItemStack;", opcode = Opcodes.PUTFIELD), cancellable = true)
    private void neoforgeBridge$useStart(InteractionHand hand, CallbackInfo ci, @Local ItemStack stack, @Share("duration") LocalIntRef duration) {
        duration.set(EventHooks.onItemUseStart((LivingEntity) (Object) this, stack, hand, stack.getUseDuration((LivingEntity) (Object) this)));
        if (duration.get() < 0) ci.cancel();
    }

    @WrapOperation(method = "startUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getUseDuration(Lnet/minecraft/world/entity/LivingEntity;)I"))
    private int neoforgeBridge$useDuration(ItemStack stack, LivingEntity entity, Operation<Integer> getUseDuration, @Share("duration") LocalIntRef duration) {
        return duration.get();
    }

    @Inject(method = "updateUsingItem", at = @At("HEAD"))
    private void neoforgeBridge$useTick(ItemStack stack, CallbackInfo ci) {
        if (!stack.isEmpty()) this.useItemRemaining = EventHooks.onItemUseTick((LivingEntity) (Object) this, stack, this.getUseItemRemainingTicks());
    }

    @WrapOperation(method = "updateUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;onUseTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void neoforgeBridge$itemTick(ItemStack stack, Level level, LivingEntity entity, int remaining, Operation<Void> onUseTick) {
        if (this.getUseItemRemainingTicks() > 0) onUseTick.call(stack, level, entity, remaining);
    }

    @Inject(method = "updateUsingItem", at = @At("TAIL"))
    private void neoforgeBridge$useEnded(ItemStack stack, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (this.useItemRemaining < 0 && !self.level().isClientSide() && !stack.useOnRelease()) this.completeUsingItem();
    }

    @WrapOperation(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack neoforgeBridge$useFinish(ItemStack stack, Level level, LivingEntity entity, Operation<ItemStack> finishUsingItem) {
        ItemStack copy = stack.copy();
        ItemStack result = finishUsingItem.call(stack, level, entity);
        return EventHooks.onItemUseFinish(entity, copy, this.getUseItemRemainingTicks(), result);
    }

    @WrapOperation(method = "releaseUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;releaseUsing(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void neoforgeBridge$useStop(ItemStack stack, Level level, LivingEntity entity, int remaining, Operation<Void> releaseUsing) {
        if (!EventHooks.onUseItemStop(entity, stack, remaining)) releaseUsing.call(stack, level, entity, remaining);
    }
}
