package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's LivingEntity patch: LivingEntity.dropAllDeathLoot captures the item entities the death drops (from its
 * first line), then posts LivingDropsEvent with them at its end and adds them to the level unless it was cancelled;
 * LivingEntity.drop puts its item entity into the capture instead of the level while one is open.
 */
@Mixin(LivingEntity.class)
abstract class LivingDropsMixin {

    @Shadow
    protected int lastHurtByPlayerMemoryTime;

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void neoforgeBridge$captureDrops(ServerLevel level, DamageSource source, CallbackInfo info) {
        CommonHooks.captureDrops((Entity) (Object) this, new ArrayList<>());
    }

    @Inject(method = "dropAllDeathLoot", at = @At("TAIL"))
    private void neoforgeBridge$livingDrops(ServerLevel level, DamageSource source, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        Collection<ItemEntity> drops = CommonHooks.captureDrops(self, null);
        if (drops == null) return;
        if (!CommonHooks.onLivingDrops(self, source, drops, this.lastHurtByPlayerMemoryTime > 0)) {
            for (ItemEntity drop : drops) self.level().addFreshEntity(drop);
        }
    }

    @WrapOperation(method = "drop", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean neoforgeBridge$captureDrop(Level level, Entity entity, Operation<Boolean> add) {
        Collection<ItemEntity> captured = CommonHooks.captureDrops((Entity) (Object) this);
        if (captured != null && entity instanceof ItemEntity item) {
            captured.add(item);
            return true;
        }
        return add.call(level, entity);
    }
}
