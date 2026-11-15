package ${package}.fabric.api.client.screen.v1;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;

/**
 * Fabric API's net.fabricmc.fabric.api.client.screen.v1.ScreenEvents, posted from NeoForge's ScreenEvent. BEFORE_INIT
 * and AFTER_INIT come from ScreenEvent.Init.Pre and Init.Post, around the screen's init(), as on Fabric; as on Fabric,
 * the events of one screen (remove, beforeExtract ...) start empty again at each BEFORE_INIT. NeoForge fires Init when
 * a screen is first shown and when it rebuilds its widgets; a resize that only moves the widgets fires neither (Fabric
 * fires both around every resize). beforeExtract and afterExtract come from ScreenEvent.Render.Pre and Post, around the
 * screen's frame; afterBackground and afterForeground from Render.Background and Render.Foreground; remove from
 * ScreenEvent.Closing, just before the screen's removed() (Fabric: just after). NeoForge has no screen tick event:
 * beforeTick and afterTick are posted for the screen shown from ClientTickEvent.Pre and Post, at the start and end of
 * the client tick the screen ticks in (Fabric: right around the screen's own tick).
 */
public final class ScreenEvents {

    public static final Event<BeforeInit> BEFORE_INIT = EventFactory.createArrayBacked(BeforeInit.class, callbacks -> (client, screen, scaledWidth, scaledHeight) -> {
        for (BeforeInit callback : callbacks) callback.beforeInit(client, screen, scaledWidth, scaledHeight);
    });

    public static final Event<AfterInit> AFTER_INIT = EventFactory.createArrayBacked(AfterInit.class, callbacks -> (client, screen, scaledWidth, scaledHeight) -> {
        for (AfterInit callback : callbacks) callback.afterInit(client, screen, scaledWidth, scaledHeight);
    });

    private static final Map<Screen, PerScreen> SCREENS = new WeakHashMap<>();

    static {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Init.Pre.class, event -> {
            Screen screen = event.getScreen();
            SCREENS.put(screen, new PerScreen());
            BEFORE_INIT.invoker().beforeInit(Minecraft.getInstance(), screen, screen.width, screen.height);
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
            Screen screen = event.getScreen();
            AFTER_INIT.invoker().afterInit(Minecraft.getInstance(), screen, screen.width, screen.height);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Render.Pre.class, event -> {
            Screen screen = event.getScreen();
            beforeExtract(screen).invoker().beforeExtract(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, event -> {
            Screen screen = event.getScreen();
            afterExtract(screen).invoker().afterExtract(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Background.class, event -> {
            Screen screen = event.getScreen();
            afterBackground(screen).invoker().afterBackground(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Foreground.class, event -> {
            Screen screen = event.getScreen();
            afterForeground(screen).invoker().afterForeground(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, event -> {
            Screen screen = event.getScreen();
            remove(screen).invoker().onRemove(screen);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, event -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen != null) beforeTick(screen).invoker().beforeTick(screen);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen != null) afterTick(screen).invoker().afterTick(screen);
        });
    }

    private ScreenEvents() {}

    private static PerScreen of(Screen screen) {
        Objects.requireNonNull(screen, "Screen cannot be null");
        return SCREENS.computeIfAbsent(screen, s -> new PerScreen());
    }

    public static Event<Remove> remove(Screen screen) {
        return of(screen).remove;
    }

    public static Event<BeforeExtract> beforeExtract(Screen screen) {
        return of(screen).beforeExtract;
    }

    public static Event<AfterBackground> afterBackground(Screen screen) {
        return of(screen).afterBackground;
    }

    public static Event<AfterForeground> afterForeground(Screen screen) {
        return of(screen).afterForeground;
    }

    public static Event<AfterExtract> afterExtract(Screen screen) {
        return of(screen).afterExtract;
    }

    public static Event<BeforeTick> beforeTick(Screen screen) {
        return of(screen).beforeTick;
    }

    public static Event<AfterTick> afterTick(Screen screen) {
        return of(screen).afterTick;
    }

    /** One screen's events, made again at each of its inits. */
    private static final class PerScreen {
        final Event<Remove> remove = EventFactory.createArrayBacked(Remove.class, callbacks -> screen -> {
            for (Remove callback : callbacks) callback.onRemove(screen);
        });
        final Event<BeforeExtract> beforeExtract = EventFactory.createArrayBacked(BeforeExtract.class, callbacks -> (screen, graphics, mouseX, mouseY, tickProgress) -> {
            for (BeforeExtract callback : callbacks) callback.beforeExtract(screen, graphics, mouseX, mouseY, tickProgress);
        });
        final Event<AfterBackground> afterBackground = EventFactory.createArrayBacked(AfterBackground.class, callbacks -> (screen, graphics, mouseX, mouseY, tickProgress) -> {
            for (AfterBackground callback : callbacks) callback.afterBackground(screen, graphics, mouseX, mouseY, tickProgress);
        });
        final Event<AfterForeground> afterForeground = EventFactory.createArrayBacked(AfterForeground.class, callbacks -> (screen, graphics, mouseX, mouseY, tickProgress) -> {
            for (AfterForeground callback : callbacks) callback.afterForeground(screen, graphics, mouseX, mouseY, tickProgress);
        });
        final Event<AfterExtract> afterExtract = EventFactory.createArrayBacked(AfterExtract.class, callbacks -> (screen, graphics, mouseX, mouseY, tickProgress) -> {
            for (AfterExtract callback : callbacks) callback.afterExtract(screen, graphics, mouseX, mouseY, tickProgress);
        });
        final Event<BeforeTick> beforeTick = EventFactory.createArrayBacked(BeforeTick.class, callbacks -> screen -> {
            for (BeforeTick callback : callbacks) callback.beforeTick(screen);
        });
        final Event<AfterTick> afterTick = EventFactory.createArrayBacked(AfterTick.class, callbacks -> screen -> {
            for (AfterTick callback : callbacks) callback.afterTick(screen);
        });
    }

    @FunctionalInterface
    public interface BeforeInit {
        void beforeInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight);
    }

    @FunctionalInterface
    public interface AfterInit {
        void afterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight);
    }

    @FunctionalInterface
    public interface Remove {
        void onRemove(Screen screen);
    }

    @FunctionalInterface
    public interface BeforeExtract {
        void beforeExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickProgress);
    }

    @FunctionalInterface
    public interface AfterBackground {
        void afterBackground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickProgress);
    }

    @FunctionalInterface
    public interface AfterForeground {
        void afterForeground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickProgress);
    }

    @FunctionalInterface
    public interface AfterExtract {
        void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickProgress);
    }

    @FunctionalInterface
    public interface BeforeTick {
        void beforeTick(Screen screen);
    }

    @FunctionalInterface
    public interface AfterTick {
        void afterTick(Screen screen);
    }
}
