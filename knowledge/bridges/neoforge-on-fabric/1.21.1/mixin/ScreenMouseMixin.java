package ${package}.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Empty on 1.21.1: the game hands a click to the screen inside a lambda there, so ScreenEvent's mouse events come from
 * Fabric API's ScreenMouseEvents (ClientGameEvents.screenInput) instead of wrapping the call as on 26.1 and later.
 */
@Mixin(MouseHandler.class)
abstract class ScreenMouseMixin {}
