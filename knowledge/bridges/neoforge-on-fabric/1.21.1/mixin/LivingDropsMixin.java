package ${package}.mixin;

import ${package}.CommonHooks;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 21.1's LivingEntity patch: LivingEntity.dropAllDeathLoot captures the item entities the death drops (from
 * its first line), then posts LivingDropsEvent with them at its end and adds them to the level unless it was cancelled.
 */
@Mixin(LivingEntity.class)
abstract class LivingDropsMixin {

    @Shadow
    protected int lastHurtByPlayerTime;

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void neoforgeBridge$captureDrops(ServerLevel level, DamageSource source, CallbackInfo info) {
        CommonHooks.captureDrops((Entity) (Object) this, new ArrayList<>());
    }

    @Inject(method = "dropAllDeathLoot", at = @At("TAIL"))
    private void neoforgeBridge$livingDrops(ServerLevel level, DamageSource source, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        Collection<ItemEntity> drops = CommonHooks.captureDrops(self, null);
        if (drops == null) return;
        if (!CommonHooks.onLivingDrops(self, source, drops, this.lastHurtByPlayerTime > 0)) {
            for (ItemEntity drop : drops) self.level().addFreshEntity(drop);
        }
    }
}
