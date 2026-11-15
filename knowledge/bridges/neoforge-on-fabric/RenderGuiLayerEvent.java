package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RenderGuiLayerEvent, posted around each HUD layer: the game's layers
 * through Fabric API's HudElementRegistry.replaceElement on the matching VanillaHudElements element, the mod's own from the
 * elements RegisterGuiLayersEvent attached. Cancelling Pre skips the layer and its Post, as on NeoForge. Unlike NeoForge,
 * a game layer the game does not draw at all (the HUD hidden with F1, the spectator hotbar outside spectator mode) posts
 * nothing.
 */
public abstract class RenderGuiLayerEvent extends Event {

    private final GuiGraphicsExtractor guiGraphics;
    private final DeltaTracker partialTick;
    private final Identifier name;
    private final GuiLayer layer;

    protected RenderGuiLayerEvent(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick, Identifier name, GuiLayer layer) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
        this.name = name;
        this.layer = layer;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return guiGraphics;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }

    public Identifier getName() {
        return name;
    }

    public GuiLayer getLayer() {
        return layer;
    }

    public static class Pre extends RenderGuiLayerEvent implements ICancellableEvent {
        public Pre(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick, Identifier name, GuiLayer layer) {
            super(guiGraphics, partialTick, name, layer);
        }
    }

    public static class Post extends RenderGuiLayerEvent {
        public Post(GuiGraphicsExtractor guiGraphics, DeltaTracker partialTick, Identifier name, GuiLayer layer) {
            super(guiGraphics, partialTick, name, layer);
        }
    }
}
