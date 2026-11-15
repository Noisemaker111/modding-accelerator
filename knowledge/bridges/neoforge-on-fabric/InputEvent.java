package ${package};

import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.InteractionHand;
import org.joml.Vector2ic;

/**
 * NeoForge's net.neoforged.neoforge.client.event.InputEvent, posted by the bridge's client mixins where NeoForge's
 * patches post it: InputKeyMixin (Key, last in KeyboardHandler.keyPress) and InputMouseMixin (MouseButton.Pre first in
 * MouseHandler.onButton, which a cancel ends; MouseButton.Post last in it, or when a screen took the click, since the game
 * then returns early where NeoForge's patch does not; MouseScrollingEvent in MouseHandler.onScroll with no screen open,
 * once the wheel moved, which a cancel ends). Differences: MouseButton.Pre comes before the game records the pressed
 * button, not after; InteractionKeyMappingTriggered is never posted.
 */
public abstract class InputEvent extends Event {

    protected InputEvent() {}

    public static abstract class MouseButton extends InputEvent {
        private final MouseButtonInfo mouseButtonInfo;
        private final int action;

        protected MouseButton(MouseButtonInfo mouseButtonInfo, int action) {
            this.mouseButtonInfo = mouseButtonInfo;
            this.action = action;
        }

        public MouseButtonInfo getMouseButtonInfo() {
            return mouseButtonInfo;
        }

        public int getButton() {
            return this.mouseButtonInfo.button();
        }

        public int getAction() {
            return this.action;
        }

        public int getModifiers() {
            return this.mouseButtonInfo.modifiers();
        }

        public static class Pre extends MouseButton implements ICancellableEvent {
            public Pre(MouseButtonInfo mouseButtonInfo, int action) {
                super(mouseButtonInfo, action);
            }
        }

        public static class Post extends MouseButton {
            public Post(MouseButtonInfo mouseButtonInfo, int action) {
                super(mouseButtonInfo, action);
            }
        }
    }

    public static class MouseScrollingEvent extends InputEvent implements ICancellableEvent {
        private final double scrollDeltaX;
        private final double scrollDeltaY;
        private final int accumulatedScrollX;
        private final int accumulatedScrollY;
        private final double mouseX;
        private final double mouseY;
        private final boolean leftDown;
        private final boolean middleDown;
        private final boolean rightDown;

        public MouseScrollingEvent(double scrollDeltaX, double scrollDeltaY, Vector2ic accumulatedScroll, boolean leftDown, boolean middleDown, boolean rightDown, double mouseX, double mouseY) {
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
            this.accumulatedScrollX = accumulatedScroll.x();
            this.accumulatedScrollY = accumulatedScroll.y();
            this.leftDown = leftDown;
            this.middleDown = middleDown;
            this.rightDown = rightDown;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        public double getScrollDeltaX() {
            return this.scrollDeltaX;
        }

        public double getScrollDeltaY() {
            return this.scrollDeltaY;
        }

        public int getAccumulatedScrollX() {
            return accumulatedScrollX;
        }

        public int getAccumulatedScrollY() {
            return accumulatedScrollY;
        }

        public boolean isLeftDown() {
            return this.leftDown;
        }

        public boolean isRightDown() {
            return this.rightDown;
        }

        public boolean isMiddleDown() {
            return this.middleDown;
        }

        public double getMouseX() {
            return this.mouseX;
        }

        public double getMouseY() {
            return this.mouseY;
        }
    }

    public static class Key extends InputEvent {
        private final KeyEvent keyEvent;
        private final int action;

        public Key(KeyEvent keyEvent, int action) {
            this.keyEvent = keyEvent;
            this.action = action;
        }

        public KeyEvent getKeyEvent() {
            return keyEvent;
        }

        public int getKey() {
            return this.keyEvent.key();
        }

        public int getKeycode() {
            return this.keyEvent.keycode();
        }

        public int getAction() {
            return this.action;
        }

        public int getModifiers() {
            return this.keyEvent.modifiers();
        }
    }

    public static class InteractionKeyMappingTriggered extends InputEvent implements ICancellableEvent {
        private final int button;
        private final KeyMapping keyMapping;
        private final InteractionHand hand;
        private boolean handSwing = true;

        public InteractionKeyMappingTriggered(int button, KeyMapping keyMapping, InteractionHand hand) {
            this.button = button;
            this.keyMapping = keyMapping;
            this.hand = hand;
        }

        public void setSwingHand(boolean value) {
            handSwing = value;
        }

        public boolean shouldSwingHand() {
            return handSwing;
        }

        public InteractionHand getHand() {
            return hand;
        }

        public boolean isAttack() {
            return button == 0;
        }

        public boolean isUseItem() {
            return button == 1;
        }

        public boolean isPickBlock() {
            return button == 2;
        }

        public KeyMapping getKeyMapping() {
            return keyMapping;
        }
    }
}
