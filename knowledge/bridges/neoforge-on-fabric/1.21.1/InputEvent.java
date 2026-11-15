package ${package};

import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.InputEvent, posted by the bridge's client mixins where NeoForge's
 * patches post it: InputKeyMixin (Key, last in KeyboardHandler.keyPress, for the game's window) and InputMouseMixin
 * (MouseButton.Pre first in MouseHandler.onPress, which a cancel ends, and MouseButton.Post last in it;
 * MouseScrollingEvent in onScroll with no screen open, once the wheel moved a step, which a cancel ends). Differences:
 * MouseButton.Pre comes before the game records the pressed button, and MouseButton.Post is not posted when a screen took
 * the click (the game returns early there); InteractionKeyMappingTriggered is never posted.
 */
public abstract class InputEvent extends Event {

    protected InputEvent() {}

    public static abstract class MouseButton extends InputEvent {
        private final int button;
        private final int action;
        private final int modifiers;

        protected MouseButton(int button, int action, int modifiers) {
            this.button = button;
            this.action = action;
            this.modifiers = modifiers;
        }

        public int getButton() {
            return this.button;
        }

        public int getAction() {
            return this.action;
        }

        public int getModifiers() {
            return this.modifiers;
        }

        public static class Pre extends MouseButton implements ICancellableEvent {
            public Pre(int button, int action, int modifiers) {
                super(button, action, modifiers);
            }
        }

        public static class Post extends MouseButton {
            public Post(int button, int action, int modifiers) {
                super(button, action, modifiers);
            }
        }
    }

    public static class MouseScrollingEvent extends InputEvent implements ICancellableEvent {
        private final double scrollDeltaX;
        private final double scrollDeltaY;
        private final double mouseX;
        private final double mouseY;
        private final boolean leftDown;
        private final boolean middleDown;
        private final boolean rightDown;

        public MouseScrollingEvent(double scrollDeltaX, double scrollDeltaY, boolean leftDown, boolean middleDown, boolean rightDown, double mouseX, double mouseY) {
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
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
        private final int key;
        private final int scanCode;
        private final int action;
        private final int modifiers;

        public Key(int key, int scanCode, int action, int modifiers) {
            this.key = key;
            this.scanCode = scanCode;
            this.action = action;
            this.modifiers = modifiers;
        }

        public int getKey() {
            return this.key;
        }

        public int getScanCode() {
            return this.scanCode;
        }

        public int getAction() {
            return this.action;
        }

        public int getModifiers() {
            return this.modifiers;
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
