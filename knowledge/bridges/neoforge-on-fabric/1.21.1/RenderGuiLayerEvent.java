package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RenderGuiLayerEvent. Fabric API for 1.21.1 has no HUD layers, so on
 * this version it is posted only around the layers mods registered with RegisterGuiLayersEvent (drawn from Fabric API's
 * HudRenderCallback, after the game's HUD), never around the game's own; cancelling Pre skips the layer and its Post.
 */
public abstract class RenderGuiLayerEvent extends Event {

    private final GuiGraphics guiGraphics;
    private final DeltaTracker partialTick;
    private final ResourceLocation name;
    private final LayeredDraw.Layer layer;

    protected RenderGuiLayerEvent(GuiGraphics guiGraphics, DeltaTracker partialTick, ResourceLocation name, LayeredDraw.Layer layer) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
        this.name = name;
        this.layer = layer;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public ResourceLocation getName() {
        return name;
    }

    public LayeredDraw.Layer getLayer() {
        return layer;
    }

    public static class Pre extends RenderGuiLayerEvent implements ICancellableEvent {
        public Pre(GuiGraphics guiGraphics, DeltaTracker partialTick, ResourceLocation name, LayeredDraw.Layer layer) {
            super(guiGraphics, partialTick, name, layer);
        }
    }

    public static class Post extends RenderGuiLayerEvent {
        public Post(GuiGraphics guiGraphics, DeltaTracker partialTick, ResourceLocation name, LayeredDraw.Layer layer) {
            super(guiGraphics, partialTick, name, layer);
        }
    }
}
