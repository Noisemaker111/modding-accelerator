package ${package}.mixin;

import ${package}.EventHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's Player patch: Player.tick() posts PlayerTickEvent.Pre first and PlayerTickEvent.Post last, on both sides. */
@Mixin(Player.class)
abstract class PlayerTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void neoforgeBridge$tickPre(CallbackInfo info) {
        EventHooks.firePlayerTickPre((Player) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void neoforgeBridge$tickPost(CallbackInfo info) {
        EventHooks.firePlayerTickPost((Player) (Object) this);
    }
}
