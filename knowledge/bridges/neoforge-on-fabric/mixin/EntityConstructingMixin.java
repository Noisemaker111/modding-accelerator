package ${package}.mixin;

import ${package}.EntityEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's Entity patch: EntityEvent.EntityConstructing is posted once an entity's own constructor has run. */
@Mixin(Entity.class)
abstract class EntityConstructingMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void constructed(CallbackInfo ci) {
        EntityEvent.EntityConstructing.post((Entity) (Object) this);
    }
}
