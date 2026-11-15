package ${package}.mixin;

import ${package}.ClientHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's MouseHandler patch: onButton posts InputEvent.MouseButton.Pre for a button of the game's window, and ends
 * there when it is cancelled, and MouseButton.Post last (NeoForge's patch also posts it when a screen took the click,
 * where the game returns early, so here it is posted then too); onScroll posts InputEvent.MouseScrollingEvent with no
 * screen open once the wheel moved, and ends there when it is cancelled.
 */
@Mixin(MouseHandler.class)
abstract class InputMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void neoforgeBridge$buttonPre(long handle, MouseButtonInfo button, int action, CallbackInfo info) {
        if (handle != 0L && handle == Minecraft.getInstance().getWindow().handle() && ClientHooks.onMouseButtonPre(button, action)) info.cancel();
    }

    @Inject(method = "onButton", at = @At("TAIL"))
    private void neoforgeBridge$buttonPost(long handle, MouseButtonInfo button, int action, CallbackInfo info) {
        if (handle != 0L && handle == Minecraft.getInstance().getWindow().handle()) ClientHooks.onMouseButtonPost(button, action);
    }

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"))
    private boolean neoforgeBridge$clickTaken(Screen screen, MouseButtonEvent event, boolean doubleClick, Operation<Boolean> mouseClicked, @Local(argsOnly = true) MouseButtonInfo button, @Local(argsOnly = true) int action) {
        boolean taken = mouseClicked.call(screen, event, doubleClick);
        if (taken) ClientHooks.onMouseButtonPost(button, action);
        return taken;
    }

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z"))
    private boolean neoforgeBridge$releaseTaken(Screen screen, MouseButtonEvent event, Operation<Boolean> mouseReleased, @Local(argsOnly = true) MouseButtonInfo button, @Local(argsOnly = true) int action) {
        boolean taken = mouseReleased.call(screen, event);
        if (taken) ClientHooks.onMouseButtonPost(button, action);
        return taken;
    }

    @WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
    private Vector2i neoforgeBridge$scroll(ScrollWheelHandler wheel, double scrollX, double scrollY, Operation<Vector2i> onMouseScroll) {
        Vector2i moved = onMouseScroll.call(wheel, scrollX, scrollY);
        if ((moved.x != 0 || moved.y != 0) && ClientHooks.onMouseScroll((MouseHandler) (Object) this, scrollX, scrollY, moved)) return new Vector2i();
        return moved;
    }
}
