package ${package}.fabric.api.client.screen.v1;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Fabric API's net.fabricmc.fabric.api.client.screen.v1.Screens: a screen's widgets as a list (adding one adds it to
 * the screen as the screen's own addRenderableWidget does, removing one removes it as removeWidget does), its font and
 * its client. NeoForge runs with the game's own names, so the two protected methods are called by name.
 */
public final class Screens {

    private Screens() {}

    public static List<AbstractWidget> getWidgets(Screen screen) {
        return new AbstractList<>() {
            private List<AbstractWidget> widgets() {
                return screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
            }

            @Override
            public AbstractWidget get(int index) {
                return widgets().get(index);
            }

            @Override
            public int size() {
                return widgets().size();
            }

            @Override
            public void add(int index, AbstractWidget widget) {
                call(screen, "addRenderableWidget", widget);
            }

            @Override
            public AbstractWidget remove(int index) {
                AbstractWidget widget = widgets().get(index);
                call(screen, "removeWidget", widget);
                return widget;
            }
        };
    }

    /** Fabric API's older name for getWidgets. */
    public static List<AbstractWidget> getButtons(Screen screen) {
        return getWidgets(screen);
    }

    public static Font getTextRenderer(Screen screen) {
        return Minecraft.getInstance().font;
    }

    public static Minecraft getClient(Screen screen) {
        return Minecraft.getInstance();
    }

    private static void call(Screen screen, String name, GuiEventListener widget) {
        try {
            Method method = Screen.class.getDeclaredMethod(name, GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Screen." + name + " could not be called", e);
        }
    }
}
