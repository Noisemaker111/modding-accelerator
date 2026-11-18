package ${package}.mixin;

import ${package}.extensions.WidgetColorAccess;
import ${package}.extensions.WidgetHooks;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives AbstractWidget NeoForge 21.1's text colour (WidgetHooks), kept per widget, unset at first. */
@Mixin(AbstractWidget.class)
public abstract class WidgetHooksMixin implements WidgetHooks, WidgetColorAccess {

    @Unique
    private int neoforgeBridge$packedFGColor = WidgetHooks.UNSET_FG_COLOR;

    @Override
    public int neoforgeBridge$color() {
        return this.neoforgeBridge$packedFGColor;
    }

    @Override
    public void neoforgeBridge$color(int color) {
        this.neoforgeBridge$packedFGColor = color;
    }
}
