package ${package}.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Empty on 1.21.1: the game hands a key to the screen inside a lambda there, so ScreenEvent's key events come from Fabric
 * API's ScreenKeyboardEvents (ClientGameEvents.screenInput) instead of wrapping the call as on 26.1 and later.
 */
@Mixin(KeyboardHandler.class)
abstract class ScreenKeyboardMixin {}
