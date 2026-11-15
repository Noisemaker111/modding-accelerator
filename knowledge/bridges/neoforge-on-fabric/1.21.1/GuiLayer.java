package ${package};

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * NeoForge's net.neoforged.neoforge.client.gui.GuiLayer as it reads on 1.21.1. NeoForge 21.1 has no such class (its HUD
 * layers are the game's LayeredDraw.Layer); this one is here so the bridge's files are the same set on every version.
 */
@FunctionalInterface
public interface GuiLayer {

    void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker);
}
