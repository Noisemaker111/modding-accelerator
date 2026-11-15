package ${package}.mixin;

import ${package}.EventHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 21.1's PersistentEntitySectionManager patch: PersistentEntitySectionManager.addEntity posts
 * EntityJoinLevelEvent first (loadedFromDisk is its second argument) and adds nothing when it is cancelled.
 */
@Mixin(PersistentEntitySectionManager.class)
abstract class EntityJoinLevelMixin {

    @Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$entityJoinLevel(EntityAccess entity, boolean loadedFromDisk, CallbackInfoReturnable<Boolean> info) {
        if (entity instanceof Entity actual && EventHooks.fireEntityJoinLevel(actual, actual.level(), loadedFromDisk)) info.setReturnValue(false);
    }
}
