package ${package}.mixin;

import ${package}.extensions.IMenuProviderExtension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's ServerPlayer patch: openMenu closes the open menu as the game does (telling the client) only when the new
 * menu's provider asks for it (shouldTriggerClientSideContainerClosingOnOpen), else on the server only (doCloseContainer).
 */
@Mixin(ServerPlayer.class)
abstract class MenuOpeningMixin {

    @WrapOperation(method = "openMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;closeContainer()V"))
    private void neoforgeBridge$closeOnOpen(ServerPlayer player, Operation<Void> close, MenuProvider provider) {
        if (((IMenuProviderExtension) provider).shouldTriggerClientSideContainerClosingOnOpen()) close.call(player);
        else player.doCloseContainer();
    }
}
