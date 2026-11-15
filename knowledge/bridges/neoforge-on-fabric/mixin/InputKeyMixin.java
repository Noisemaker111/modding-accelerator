package ${package}.mixin;

import ${package}.ClientHooks;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's KeyboardHandler patch: keyPress posts InputEvent.Key last, for a key of the game's window. */
@Mixin(KeyboardHandler.class)
abstract class InputKeyMixin {

    @Inject(method = "keyPress", at = @At("TAIL"))
    private void neoforgeBridge$keyInput(long window, int action, KeyEvent event, CallbackInfo info) {
        if (window == Minecraft.getInstance().getWindow().handle()) ClientHooks.onKeyInput(event, action);
    }
}
