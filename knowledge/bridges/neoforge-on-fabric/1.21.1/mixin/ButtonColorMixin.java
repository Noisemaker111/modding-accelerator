package ${package}.mixin;

import ${package}.extensions.WidgetHooks;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** NeoForge 21.1's AbstractButton patch: the label is drawn in the button's text colour (getFGColor), keeping the alpha the game gives it. */
@Mixin(AbstractButton.class)
abstract class ButtonColorMixin {

    @ModifyArg(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractButton;renderString(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"), index = 2)
    private int neoforgeBridge$labelColor(int color) {
        return ((WidgetHooks) this).getFGColor() & 0xFFFFFF | color & 0xFF000000;
    }
}
