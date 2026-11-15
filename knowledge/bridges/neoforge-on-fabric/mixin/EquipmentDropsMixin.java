package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Collection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's EntityEquipment patch: EntityEquipment.dropAll puts each item entity into the dropper's drop capture
 * (open while LivingEntity.dropAllDeathLoot runs, see LivingDropsMixin) instead of the level.
 */
@Mixin(EntityEquipment.class)
abstract class EquipmentDropsMixin {

    @WrapOperation(method = "dropAll", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean neoforgeBridge$captureDrop(Level level, Entity entity, Operation<Boolean> add, @Local(argsOnly = true) LivingEntity dropper) {
        Collection<ItemEntity> captured = CommonHooks.captureDrops(dropper);
        if (captured != null && entity instanceof ItemEntity item) {
            captured.add(item);
            return true;
        }
        return add.call(level, entity);
    }
}
