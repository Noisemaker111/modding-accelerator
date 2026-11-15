package ${package};

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;

/**
 * NeoForge's net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent. BossEventProgress is posted by the bridge's
 * BossEventProgressMixin where NeoForge's BossHealthOverlay patch posts it, before each boss bar: cancelling skips the bar
 * and its name, and the next bar moves down by the event's increment, as on NeoForge. Chat is never posted (the chat
 * stays where the game draws it).
 */
public abstract class CustomizeGuiOverlayEvent extends Event {

    private final Window window;
    private final GuiGraphicsExtractor guiGraphics;
    private final DeltaTracker partialTick;

    protected CustomizeGuiOverlayEvent(Window window, GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick) {
        this.window = window;
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public Window getWindow() {
        return window;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
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

        public BossEventProgress(Window window, GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick, LerpingBossEvent bossEvent, int x, int y, int increment) {
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

    public static class Chat extends CustomizeGuiOverlayEvent {
        private int posX;
        private int posY;

        public Chat(Window window, GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick, int posX, int posY) {
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
