package ${package}.extensions;

import net.minecraft.client.gui.components.AbstractWidget;

/**
 * NeoForge 21.1's text colour of a widget (AbstractWidget.packedFGColor, UNSET_FG_COLOR when not set, and getFGColor,
 * setFGColor, clearFGColor): kept by WidgetHooksMixin (through WidgetColorAccess), which also draws a button's label in
 * it, as NeoForge's button does. A mod's reads of the field become neoforgeBridge$packedFGColor() (mmt convert's rule),
 * as a field cannot be added for the compile. Added to AbstractWidget by WidgetHooksMixin, and for the compile by Loom's
 * interface injection.
 */
public interface WidgetHooks {

    int UNSET_FG_COLOR = -1;

    default int neoforgeBridge$packedFGColor() {
        return ((WidgetColorAccess) this).neoforgeBridge$color();
    }

    default int getFGColor() {
        int packed = neoforgeBridge$packedFGColor();
        if (packed != UNSET_FG_COLOR) return packed;
        return ((AbstractWidget) (Object) this).active ? 16777215 : 10526880;
    }

    default void setFGColor(int color) {
        ((WidgetColorAccess) this).neoforgeBridge$color(color);
    }

    default void clearFGColor() {
        ((WidgetColorAccess) this).neoforgeBridge$color(UNSET_FG_COLOR);
    }
}
