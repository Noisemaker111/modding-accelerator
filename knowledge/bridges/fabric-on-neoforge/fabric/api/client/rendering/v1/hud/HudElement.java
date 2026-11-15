package ${package}.fabric.api.client.rendering.v1.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Fabric API's net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement: drawn as a NeoForge GUI layer (HudElementRegistry). */
@FunctionalInterface
public interface HudElement {

    void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
