package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's Inventory patch: Inventory.dropAll puts each item entity into the player's drop capture (open while
 * LivingEntity.dropAllDeathLoot runs, see LivingDropsMixin) instead of the level.
 */
@Mixin(Inventory.class)
abstract class InventoryDropsMixin {

    @Shadow
    @Final
    public Player player;

    @WrapOperation(method = "dropAll", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean neoforgeBridge$captureDrop(Level level, Entity entity, Operation<Boolean> add) {
        Collection<ItemEntity> captured = CommonHooks.captureDrops(this.player);
        if (captured != null && entity instanceof ItemEntity item) {
            captured.add(item);
            return true;
        }
        return add.call(level, entity);
    }
}
