package ${package}.mixin;

import ${package}.ClientHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's MouseHandler patch: a click or release goes to the screen as ScreenEvent.MouseButtonPressed.Pre (or
 * MouseButtonReleased.Pre), then the screen's mouseClicked (mouseReleased) when Pre was not cancelled, then Post, whose
 * result decides whether the screen took it; a drag and a scroll go as MouseDragged and MouseScrolled Pre, the screen's
 * handler unless Pre was cancelled, and Post when the screen did not take it.
 */
@Mixin(MouseHandler.class)
abstract class ScreenMouseMixin {

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"))
    private boolean neoforgeBridge$mouseClicked(Screen screen, MouseButtonEvent event, boolean doubleClick, Operation<Boolean> mouseClicked) {
        if (ClientHooks.onScreenMouseClickedPre(screen, event, doubleClick)) return true;
        boolean handled = mouseClicked.call(screen, event, doubleClick);
        return ClientHooks.onScreenMouseClickedPost(screen, event, doubleClick, handled);
    }

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z"))
    private boolean neoforgeBridge$mouseReleased(Screen screen, MouseButtonEvent event, Operation<Boolean> mouseReleased) {
        if (ClientHooks.onScreenMouseReleasedPre(screen, event)) return true;
        boolean handled = mouseReleased.call(screen, event);
        return ClientHooks.onScreenMouseReleasedPost(screen, event, handled);
    }

    @WrapOperation(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"))
    private boolean neoforgeBridge$mouseDragged(Screen screen, MouseButtonEvent event, double dragX, double dragY, Operation<Boolean> mouseDragged) {
        if (ClientHooks.onScreenMouseDragPre(screen, event, dragX, dragY)) return true;
        if (mouseDragged.call(screen, event, dragX, dragY)) return true;
        ClientHooks.onScreenMouseDragPost(screen, event, dragX, dragY);
        return false;
    }

    @WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
    private boolean neoforgeBridge$mouseScrolled(Screen screen, double mouseX, double mouseY, double scrollX, double scrollY, Operation<Boolean> mouseScrolled) {
        MouseHandler mouse = (MouseHandler) (Object) this;
        if (ClientHooks.onScreenMouseScrollPre(mouse, screen, scrollX, scrollY)) return true;
        if (mouseScrolled.call(screen, mouseX, mouseY, scrollX, scrollY)) return true;
        ClientHooks.onScreenMouseScrollPost(mouse, screen, scrollX, scrollY);
        return false;
    }
}
