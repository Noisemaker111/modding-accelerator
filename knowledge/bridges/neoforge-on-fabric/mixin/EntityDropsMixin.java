package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's Entity patch: Entity.spawnAtLocation puts the item entity into the entity's drop capture (open while
 * LivingEntity.dropAllDeathLoot runs, see LivingDropsMixin) instead of the level.
 */
@Mixin(Entity.class)
abstract class EntityDropsMixin {

    @WrapOperation(method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean neoforgeBridge$captureDrop(ServerLevel level, Entity entity, Operation<Boolean> add) {
        Collection<ItemEntity> captured = CommonHooks.captureDrops((Entity) (Object) this);
        if (captured != null && entity instanceof ItemEntity item) {
            captured.add(item);
            return true;
        }
        return add.call(level, entity);
    }
}
