package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ServerPlayer patch: ServerPlayer.drop (through which the player's inventory drops on death) puts the
 * item entity into the player's drop capture (open while LivingEntity.dropAllDeathLoot runs, see LivingDropsMixin)
 * instead of the level.
 */
@Mixin(ServerPlayer.class)
abstract class InventoryDropsMixin {

    @WrapOperation(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean neoforgeBridge$captureDrop(Level level, Entity entity, Operation<Boolean> add) {
        Collection<ItemEntity> captured = CommonHooks.captureDrops((Entity) (Object) this);
        if (captured != null && entity instanceof ItemEntity item) {
            captured.add(item);
            return true;
        }
        return add.call(level, entity);
    }
}
