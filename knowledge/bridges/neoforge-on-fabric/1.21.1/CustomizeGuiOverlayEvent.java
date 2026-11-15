package ${package};

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent. BossEventProgress is posted by the
 * bridge's BossEventProgressMixin where NeoForge's BossHealthOverlay patch posts it, before each boss bar: cancelling skips
 * the bar and its name, and the next bar moves down by the event's increment, as on NeoForge. DebugText and Chat are never
 * posted.
 */
public abstract class CustomizeGuiOverlayEvent extends Event {

    private final Window window;
    private final GuiGraphics guiGraphics;
    private final DeltaTracker partialTick;

    protected CustomizeGuiOverlayEvent(Window window, GuiGraphics guiGraphics, DeltaTracker partialTick) {
        this.window = window;
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public Window getWindow() {
        return window;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public static class BossEventProgress extends CustomizeGuiOverlayEvent implements ICancellableEvent {
        private final LerpingBossEvent bossEvent;
        private final int x;
        private final int y;
        private int increment;

        public BossEventProgress(Window window, GuiGraphics guiGraphics, DeltaTracker partialTick, LerpingBossEvent bossEvent, int x, int y, int increment) {
            super(window, guiGraphics, partialTick);
            this.bossEvent = bossEvent;
            this.x = x;
            this.y = y;
            this.increment = increment;
        }

        public LerpingBossEvent getBossEvent() {
            return bossEvent;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getIncrement() {
            return increment;
        }

        public void setIncrement(int increment) {
            this.increment = increment;
        }
    }

    public static class DebugText extends CustomizeGuiOverlayEvent {
        private final List<String> left;
        private final List<String> right;

        public DebugText(Window window, GuiGraphics guiGraphics, DeltaTracker partialTick, List<String> left, List<String> right) {
            super(window, guiGraphics, partialTick);
            this.left = left;
            this.right = right;
        }

        public List<String> getLeft() {
            return left;
        }

        public List<String> getRight() {
            return right;
        }
    }

    public static class Chat extends CustomizeGuiOverlayEvent {
        private int posX;
        private int posY;

        public Chat(Window window, GuiGraphics guiGraphics, DeltaTracker partialTick, int posX, int posY) {
            super(window, guiGraphics, partialTick);
            this.setPosX(posX);
            this.setPosY(posY);
        }

        public int getPosX() {
            return posX;
        }

        public void setPosX(int posX) {
            this.posX = posX;
        }

        public int getPosY() {
            return posY;
        }

        public void setPosY(int posY) {
            this.posY = posY;
        }
    }
}
