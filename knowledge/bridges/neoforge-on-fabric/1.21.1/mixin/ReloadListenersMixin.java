package ${package}.mixin;

import ${package}.AddReloadListenerEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ReloadableServerResources patch: loadResources reloads the game's listeners() followed by the ones
 * AddReloadListenerEvent added (EventHooks.onResourceReload with the load's registries).
 */
@Mixin(ReloadableServerResources.class)
abstract class ReloadListenersMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ReloadableServerResources;listeners()Ljava/util/List;"))
    private static List<PreparableReloadListener> neoforgeBridge$listeners(ReloadableServerResources resources, Operation<List<PreparableReloadListener>> listeners, @Local(argsOnly = true) LayeredRegistryAccess<RegistryLayer> registries) {
        return AddReloadListenerEvent.post(resources, registries.compositeAccess(), listeners.call(resources));
    }
}
