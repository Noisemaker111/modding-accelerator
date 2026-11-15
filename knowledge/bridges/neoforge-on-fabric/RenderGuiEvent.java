package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RenderGuiEvent, posted from two Fabric API HUD elements: Pre from one
 * added first (HudElementRegistry.addFirst), Post from one added last. Cancelling Pre skips every layer the bridge wraps
 * (RenderGuiLayerEvent) and the Post, as on NeoForge; elements other Fabric mods added still draw. Unlike NeoForge,
 * neither is posted while the HUD is hidden (F1), since Fabric API draws its first and last elements with the game's.
 */
public abstract class RenderGuiEvent extends Event {

    private final GuiGraphicsExtractor guiGraphics;
    private final DeltaTracker partialTick;

    protected RenderGuiEvent(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public static class Pre extends RenderGuiEvent implements ICancellableEvent {
        public Pre(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick) {
            super(guiGraphics, partialTick);
        }
    }

    public static class Post extends RenderGuiEvent {
        public Post(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick) {
            super(guiGraphics, partialTick);
        }
    }
}
