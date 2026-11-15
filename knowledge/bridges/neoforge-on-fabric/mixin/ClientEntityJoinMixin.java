package ${package}.mixin;

import ${package}.EventHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's ClientLevel patch: ClientLevel.addEntity posts EntityJoinLevelEvent first and adds nothing when it is cancelled. */
@Mixin(ClientLevel.class)
abstract class ClientEntityJoinMixin {

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$entityJoinLevel(Entity entity, CallbackInfo info) {
        if (EventHooks.fireEntityJoinLevel(entity, (ClientLevel) (Object) this, false)) info.cancel();
    }
}
