package ${package}.mixin;

import ${package}.AddServerReloadListenersEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's ReloadableServerResources patch: loadResources reloads the listeners EventHooks.onResourceReload returns
 * (AddServerReloadListenersEvent posted with the game's listeners, then sorted) in place of the game's listeners().
 */
@Mixin(ReloadableServerResources.class)
abstract class ReloadListenersMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ReloadableServerResources;listeners()Ljava/util/List;"))
    private static List<PreparableReloadListener> neoforgeBridge$listeners(ReloadableServerResources resources, Operation<List<PreparableReloadListener>> listeners) {
        return AddServerReloadListenersEvent.post(resources, listeners.call(resources));
    }
}
