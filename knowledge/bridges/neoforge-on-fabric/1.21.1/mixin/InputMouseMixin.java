package ${package}.mixin;

import ${package}.ClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 21.1's MouseHandler patch: onPress posts InputEvent.MouseButton.Pre for a button of the game's window, and
 * ends there when it is cancelled, and MouseButton.Post last; onScroll posts InputEvent.MouseScrollingEvent with no screen
 * open, before the scroll moves the hotbar or the spectator menu, and ends there when it is cancelled (here, as the game
 * decides it has a whole wheel step to apply).
 */
@Mixin(MouseHandler.class)
abstract class InputMouseMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$buttonPre(long window, int button, int action, int modifiers, CallbackInfo info) {
        if (window == Minecraft.getInstance().getWindow().getWindow() && ClientHooks.onMouseButtonPre(button, action, modifiers)) info.cancel();
    }

    @Inject(method = "onPress", at = @At("TAIL"))
    private void neoforgeBridge$buttonPost(long window, int button, int action, int modifiers, CallbackInfo info) {
        if (window == Minecraft.getInstance().getWindow().getWindow()) ClientHooks.onMouseButtonPost(button, action, modifiers);
    }

    @Inject(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"), cancellable = true)
    private void neoforgeBridge$scroll(long window, double scrollX, double scrollY, CallbackInfo info) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean discrete = minecraft.options.discreteMouseScroll().get();
        double sensitivity = minecraft.options.mouseWheelSensitivity().get();
        double deltaX = (discrete ? Math.signum(scrollX) : scrollX) * sensitivity;
        double deltaY = (discrete ? Math.signum(scrollY) : scrollY) * sensitivity;
        Screen screen = minecraft.screen;
        if (screen == null && ClientHooks.onMouseScroll((MouseHandler) (Object) this, deltaX, deltaY)) info.cancel();
    }
}
