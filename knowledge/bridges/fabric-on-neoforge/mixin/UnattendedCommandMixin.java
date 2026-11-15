package ${package}.mixin;

import ${package}.ClientCommandForwarding;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a command the game runs from clicked text (sendUnattendedCommand), where NeoForge's patch runs client commands
 * first, so the Fabric bridge checks it as Fabric does (ClientCommandForwarding). A typed command (sendCommand) is never
 * marked, whatever a failed click left behind.
 */
@Mixin(ClientPacketListener.class)
abstract class UnattendedCommandMixin {

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"))
    private void mmtBridge$clicked(String command, Screen screenAfterCommand, CallbackInfo info) {
        ClientCommandForwarding.UNATTENDED.set(true);
    }

    @Inject(method = "sendUnattendedCommand", at = @At("RETURN"))
    private void mmtBridge$clickedDone(String command, Screen screenAfterCommand, CallbackInfo info) {
        ClientCommandForwarding.UNATTENDED.set(false);
    }

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void mmtBridge$typed(String command, CallbackInfo info) {
        ClientCommandForwarding.UNATTENDED.set(false);
    }
}
