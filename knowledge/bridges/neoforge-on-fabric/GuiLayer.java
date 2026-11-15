package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** NeoForge's net.neoforged.neoforge.client.gui.GuiLayer: one layer of the HUD (drawn as a Fabric API HUD element here). */
@FunctionalInterface
public interface GuiLayer {

    void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker);
}
