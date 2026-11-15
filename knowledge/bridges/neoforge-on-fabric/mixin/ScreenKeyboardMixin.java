package ${package}.mixin;

import ${package}.ClientHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's KeyboardHandler patch: keyPress hands a key to the screen as ScreenEvent.KeyPressed.Pre (or KeyReleased.Pre),
 * then the screen's keyPressed (keyReleased) when Pre was not cancelled, then Post when the screen did not take it, and
 * treats the key as taken when any of them did; charTyped and submitPreeditEvent do the same with CharacterTyped and
 * Preedit.
 */
@Mixin(KeyboardHandler.class)
abstract class ScreenKeyboardMixin {

    @WrapOperation(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z"))
    private boolean neoforgeBridge$keyPressed(Screen screen, KeyEvent event, Operation<Boolean> keyPressed) {
        return ClientHooks.onScreenKeyPressedPre(screen, event) || keyPressed.call(screen, event) || ClientHooks.onScreenKeyPressedPost(screen, event);
    }

    @WrapOperation(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z"))
    private boolean neoforgeBridge$keyReleased(Screen screen, KeyEvent event, Operation<Boolean> keyReleased) {
        return ClientHooks.onScreenKeyReleasedPre(screen, event) || keyReleased.call(screen, event) || ClientHooks.onScreenKeyReleasedPost(screen, event);
    }

    @WrapOperation(method = "charTyped", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z"))
    private boolean neoforgeBridge$charTyped(Screen screen, CharacterEvent event, Operation<Boolean> charTyped) {
        if (ClientHooks.onScreenCharTypedPre(screen, event)) return true;
        if (charTyped.call(screen, event)) return true;
        ClientHooks.onScreenCharTypedPost(screen, event);
        return false;
    }

    @WrapOperation(method = "submitPreeditEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/events/GuiEventListener;preeditUpdated(Lnet/minecraft/client/input/PreeditEvent;)Z"))
    private static boolean neoforgeBridge$preedit(GuiEventListener element, PreeditEvent event, Operation<Boolean> preeditUpdated) {
        if (element instanceof Screen screen && ClientHooks.onScreenPreeditPre(screen, event)) return true;
        if (preeditUpdated.call(element, event)) return true;
        if (element instanceof Screen screen) ClientHooks.onScreenPreeditPost(screen, event);
        return false;
    }
}
