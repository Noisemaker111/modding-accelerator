package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RenderGuiEvent. Fabric API for 1.21.1 calls back only after the
 * game drew its HUD (HudRenderCallback), so on this version Pre is posted there, before the layers mods registered, and
 * then Post; cancelling Pre skips those layers and Post but cannot skip the game's own HUD, which is already drawn.
 */
public abstract class RenderGuiEvent extends Event {

    private final GuiGraphics guiGraphics;
    private final DeltaTracker partialTick;

    protected RenderGuiEvent(GuiGraphics guiGraphics, DeltaTracker partialTick) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public static class Pre extends RenderGuiEvent implements ICancellableEvent {
        public Pre(GuiGraphics guiGraphics, DeltaTracker partialTick) {
            super(guiGraphics, partialTick);
        }
    }

    public static class Post extends RenderGuiEvent {
        public Post(GuiGraphics guiGraphics, DeltaTracker partialTick) {
            super(guiGraphics, partialTick);
        }
    }
}
