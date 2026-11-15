package ${package};

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.client.event.ScreenEvent, posted by the bridge's client mixins where NeoForge's
 * patches post it: ScreenEventMixin (Screen: Init.Pre/Post around init() and setInitialFocus() in init(int, int) and
 * rebuildWidgets(), Render.Pre/Post around extractRenderStateWithTooltipAndSubtitles, Render.Background after the
 * background, Render.Foreground before the deferred elements), ScreenKeyboardMixin (KeyboardHandler: KeyPressed,
 * KeyReleased, CharacterTyped, Preedit around the screen's handler), ScreenMouseMixin (MouseHandler: MouseButtonPressed,
 * MouseButtonReleased, MouseDragged, MouseScrolled around the screen's handler) and ScreenOpeningMixin (Gui.setScreen,
 * Minecraft.setScreen before 26.3: Opening and Closing). Cancelling and the Post results work as on NeoForge.
 * Differences: Opening is not posted for the title, death or chat screen the game picks itself when asked to close a
 * screen; RenderInventoryMobEffects is never posted.
 */
public abstract class ScreenEvent extends Event {

    private final Screen screen;

    protected ScreenEvent(Screen screen) {
        this.screen = Objects.requireNonNull(screen);
    }

    public Screen getScreen() {
        return screen;
    }

    public static abstract class Init extends ScreenEvent {
        private final Consumer<GuiEventListener> add;
        private final Consumer<GuiEventListener> remove;
        private final List<GuiEventListener> listenerList;

        protected Init(Screen screen, List<GuiEventListener> listenerList, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
            super(screen);
            this.listenerList = Collections.unmodifiableList(listenerList);
            this.add = add;
            this.remove = remove;
        }

        public List<GuiEventListener> getListenersList() {
            return listenerList;
        }

        public void addListener(GuiEventListener listener) {
            add.accept(listener);
        }

        public void removeListener(GuiEventListener listener) {
            remove.accept(listener);
        }

        public static class Pre extends Init implements ICancellableEvent {
            public Pre(Screen screen, List<GuiEventListener> list, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
                super(screen, list, add, remove);
            }
        }

        public static class Post extends Init {
            public Post(Screen screen, List<GuiEventListener> list, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
                super(screen, list, add, remove);
            }
        }
    }

    public static abstract class Render extends ScreenEvent {
        private final GuiGraphicsExtractor guiGraphics;
        private final int mouseX;
        private final int mouseY;
        private final float partialTick;

        protected Render(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
            super(screen);
            this.guiGraphics = guiGraphics;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.partialTick = partialTick;
        }

        public GuiGraphicsExtractor getGuiGraphics() {
            return guiGraphics;
        }

        public int getMouseX() {
            return mouseX;
        }

        public int getMouseY() {
            return mouseY;
        }

        public float getPartialTick() {
            return partialTick;
        }

        public static class Pre extends Render implements ICancellableEvent {
            public Pre(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
                super(screen, guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        public static class Background extends ScreenEvent.Render {
            public Background(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
                super(screen, guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        public static class Foreground extends Render {
            public Foreground(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
                super(screen, guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        public static class Post extends Render {
            public Post(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
                super(screen, guiGraphics, mouseX, mouseY, partialTick);
            }
        }
    }

    public static class RenderInventoryMobEffects extends ScreenEvent implements ICancellableEvent {
        private final int availableSpace;
        private boolean compact;
        private int horizontalOffset;

        public RenderInventoryMobEffects(Screen screen, int availableSpace, boolean compact, int horizontalOffset) {
            super(screen);
            this.availableSpace = availableSpace;
            this.compact = compact;
            this.horizontalOffset = horizontalOffset;
        }

        public int getAvailableSpace() {
            return availableSpace;
        }

        public boolean isCompact() {
            return compact;
        }

        public int getHorizontalOffset() {
            return horizontalOffset;
        }

        public void setHorizontalOffset(int offset) {
            horizontalOffset = offset;
        }

        public void addHorizontalOffset(int offset) {
            horizontalOffset += offset;
        }

        public void setCompact(boolean compact) {
            this.compact = compact;
        }
    }

    private static abstract class MouseInput extends ScreenEvent {
        private final double mouseX;
        private final double mouseY;

        protected MouseInput(Screen screen, double mouseX, double mouseY) {
            super(screen);
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        public double getMouseX() {
            return mouseX;
        }

        public double getMouseY() {
            return mouseY;
        }
    }

    public static abstract class MouseButtonPressed extends MouseInput {
        private final MouseButtonEvent mouseEvent;
        private final boolean doubleClick;

        public MouseButtonPressed(Screen screen, MouseButtonEvent mouseEvent, boolean doubleClick) {
            super(screen, mouseEvent.x(), mouseEvent.y());
            this.mouseEvent = mouseEvent;
            this.doubleClick = doubleClick;
        }

        public MouseButtonEvent getMouseButtonEvent() {
            return this.mouseEvent;
        }

        public int getButton() {
            return this.mouseEvent.button();
        }

        public boolean isDoubleClick() {
            return doubleClick;
        }

        public static class Pre extends MouseButtonPressed implements ICancellableEvent {
            public Pre(Screen screen, MouseButtonEvent mouseEvent, boolean doubleClick) {
                super(screen, mouseEvent, doubleClick);
            }
        }

        public static class Post extends MouseButtonPressed {
            private final boolean handled;
            private Result result = Result.DEFAULT;

            public Post(Screen screen, MouseButtonEvent mouseEvent, boolean doubleClick, boolean handled) {
                super(screen, mouseEvent, doubleClick);
                this.handled = handled;
            }

            public boolean wasClickHandled() {
                return this.handled;
            }

            public void setResult(Result result) {
                this.result = result;
            }

            public Result getResult() {
                return this.result;
            }

            public boolean getClickResult() {
                if (this.result == Result.FORCE_HANDLED) {
                    return true;
                }
                return this.result == Result.DEFAULT && this.wasClickHandled();
            }

            public static enum Result {
                FORCE_HANDLED,
                DEFAULT,
                FORCE_UNHANDLED;
            }
        }
    }

    public static abstract class MouseButtonReleased extends MouseInput {
        private final MouseButtonEvent mouseEvent;

        public MouseButtonReleased(Screen screen, MouseButtonEvent mouseEvent) {
            super(screen, mouseEvent.x(), mouseEvent.y());
            this.mouseEvent = mouseEvent;
        }

        public MouseButtonEvent getMouseButtonEvent() {
            return this.mouseEvent;
        }

        public int getButton() {
            return this.mouseEvent.button();
        }

        public static class Pre extends MouseButtonReleased implements ICancellableEvent {
            public Pre(Screen screen, MouseButtonEvent mouseEvent) {
                super(screen, mouseEvent);
            }
        }

        public static class Post extends MouseButtonReleased {
            private final boolean handled;
            private Result result = Result.DEFAULT;

            public Post(Screen screen, MouseButtonEvent mouseEvent, boolean handled) {
                super(screen, mouseEvent);
                this.handled = handled;
            }

            public boolean wasReleaseHandled() {
                return handled;
            }

            public void setResult(Result result) {
                this.result = result;
            }

            public Result getResult() {
                return this.result;
            }

            public boolean getReleaseResult() {
                if (this.result == Result.FORCE_HANDLED) {
                    return true;
                }
                return this.result == Result.DEFAULT && this.wasReleaseHandled();
            }

            public static enum Result {
                FORCE_HANDLED,
                DEFAULT,
                FORCE_UNHANDLED;
            }
        }
    }

    public static abstract class MouseDragged extends MouseInput {
        private final MouseButtonEvent mouseEvent;
        private final double dragX;
        private final double dragY;

        public MouseDragged(Screen screen, MouseButtonEvent mouseEvent, double dragX, double dragY) {
            super(screen, mouseEvent.x(), mouseEvent.y());
            this.mouseEvent = mouseEvent;
            this.dragX = dragX;
            this.dragY = dragY;
        }

        public MouseButtonEvent getMouseButtonEvent() {
            return this.mouseEvent;
        }

        public int getMouseButton() {
            return this.mouseEvent.button();
        }

        public double getDragX() {
            return dragX;
        }

        public double getDragY() {
            return dragY;
        }

        public static class Pre extends MouseDragged implements ICancellableEvent {
            public Pre(Screen screen, MouseButtonEvent mouseEvent, double dragX, double dragY) {
                super(screen, mouseEvent, dragX, dragY);
            }
        }

        public static class Post extends MouseDragged {
            public Post(Screen screen, MouseButtonEvent mouseEvent, double dragX, double dragY) {
                super(screen, mouseEvent, dragX, dragY);
            }
        }
    }

    public static abstract class MouseScrolled extends MouseInput {
        private final double scrollDeltaX;
        private final double scrollDeltaY;

        public MouseScrolled(Screen screen, double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
            super(screen, mouseX, mouseY);
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
        }

        public double getScrollDeltaX() {
            return scrollDeltaX;
        }

        public double getScrollDeltaY() {
            return scrollDeltaY;
        }

        public static class Pre extends MouseScrolled implements ICancellableEvent {
            public Pre(Screen screen, double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
                super(screen, mouseX, mouseY, scrollDeltaX, scrollDeltaY);
            }
        }

        public static class Post extends MouseScrolled {
            public Post(Screen screen, double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
                super(screen, mouseX, mouseY, scrollDeltaX, scrollDeltaY);
            }
        }
    }

    private static abstract class KeyInput extends ScreenEvent {
        private final KeyEvent keyEvent;

        protected KeyInput(Screen screen, KeyEvent keyEvent) {
            super(screen);
            this.keyEvent = keyEvent;
        }

        public KeyEvent getKeyEvent() {
            return keyEvent;
        }

        public int getKey() {
            return this.keyEvent.key();
        }

        public int getKeycode() {
            return ClientHooks.keycode(this.keyEvent);
        }

        public int getModifiers() {
            return this.keyEvent.modifiers();
        }
    }

    public static abstract class KeyPressed extends KeyInput {
        public KeyPressed(Screen screen, KeyEvent keyEvent) {
            super(screen, keyEvent);
        }

        public static class Pre extends KeyPressed implements ICancellableEvent {
            public Pre(Screen screen, KeyEvent keyEvent) {
                super(screen, keyEvent);
            }
        }

        public static class Post extends KeyPressed implements ICancellableEvent {
            public Post(Screen screen, KeyEvent keyEvent) {
                super(screen, keyEvent);
            }
        }
    }

    public static abstract class KeyReleased extends KeyInput {
        public KeyReleased(Screen screen, KeyEvent keyEvent) {
            super(screen, keyEvent);
        }

        public static class Pre extends KeyReleased implements ICancellableEvent {
            public Pre(Screen screen, KeyEvent keyEvent) {
                super(screen, keyEvent);
            }
        }

        public static class Post extends KeyReleased implements ICancellableEvent {
            public Post(Screen screen, KeyEvent keyEvent) {
                super(screen, keyEvent);
            }
        }
    }

    public static abstract class CharacterTyped extends ScreenEvent {
        private final CharacterEvent charEvent;

        public CharacterTyped(Screen screen, CharacterEvent charEvent) {
            super(screen);
            this.charEvent = charEvent;
        }

        public CharacterEvent getCharacterEvent() {
            return charEvent;
        }

        public int getCodePoint() {
            return this.charEvent.codepoint();
        }

        public static class Pre extends CharacterTyped implements ICancellableEvent {
            public Pre(Screen screen, CharacterEvent charEvent) {
                super(screen, charEvent);
            }
        }

        public static class Post extends CharacterTyped {
            public Post(Screen screen, CharacterEvent charEvent) {
                super(screen, charEvent);
            }
        }
    }

    public static abstract class Preedit extends ScreenEvent {
        private final @Nullable PreeditEvent preeditEvent;

        public Preedit(Screen screen, @Nullable PreeditEvent preeditEvent) {
            super(screen);
            this.preeditEvent = preeditEvent;
        }

        public @Nullable PreeditEvent getPreeditEvent() {
            return preeditEvent;
        }

        public static class Pre extends Preedit implements ICancellableEvent {
            public Pre(Screen screen, @Nullable PreeditEvent preeditEvent) {
                super(screen, preeditEvent);
            }
        }

        public static class Post extends Preedit {
            public Post(Screen screen, @Nullable PreeditEvent preeditEvent) {
                super(screen, preeditEvent);
            }
        }
    }

    public static class Opening extends ScreenEvent implements ICancellableEvent {
        @Nullable
        private final Screen currentScreen;
        @Nullable
        private Screen newScreen;

        public Opening(@Nullable Screen currentScreen, Screen screen) {
            super(screen);
            this.currentScreen = currentScreen;
            this.newScreen = screen;
        }

        @Nullable
        public Screen getCurrentScreen() {
            return currentScreen;
        }

        @Nullable
        public Screen getNewScreen() {
            return newScreen;
        }

        public void setNewScreen(@Nullable Screen newScreen) {
            this.newScreen = newScreen;
        }
    }

    public static class Closing extends ScreenEvent {
        public Closing(Screen screen) {
            super(screen);
        }
    }
}
