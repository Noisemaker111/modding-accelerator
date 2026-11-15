package ${package}.mixin;

import ${package}.ClientHooks;
import ${package}.ScreenEvent;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * NeoForge's Minecraft patch for the games before 26.3, which open screens in Minecraft.setScreen: a screen about to open
 * posts ScreenEvent.Opening, which may cancel it or open another, and the screen it replaces posts ScreenEvent.Closing
 * before it is removed. Since 26.3 Minecraft has no setScreen (ScreenOpeningMixin wraps Gui.setScreen there), so this one
 * may find nothing to wrap.
 */
@Mixin(Minecraft.class)
abstract class ScreenOpeningLegacyMixin {

    @WrapMethod(method = "setScreen", require = 0)
    private void neoforgeBridge$setScreen(Screen screen, Operation<Void> setScreen) {
        Screen old = ClientHooks.currentScreen();
        Screen next = screen;
        if (next != null) {
            ScreenEvent.Opening event = ClientHooks.onScreenOpening(old, next);
            if (event.isCanceled()) return;
            next = event.getNewScreen();
        }
        if (old != null && next != old) ClientHooks.onScreenClosing(old);
        setScreen.call(next);
    }
}
