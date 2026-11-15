package ${package};

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * NeoForge's net.neoforged.neoforge.client.gui.widget.ExtendedButton: a Button a mod can subclass (the game's own is
 * abstract), drawn as the game draws its plain button (its sprite, then its label, which scrolls when it is too long where
 * NeoForge's cuts it short). NeoForge's Button.Builder constructor is not mirrored (the game's builder keeps its press
 * action private).
 */
public class ExtendedButton extends Button {

    public ExtendedButton(int xPos, int yPos, int width, int height, Component displayString, OnPress handler) {
        this(xPos, yPos, width, height, displayString, handler, DEFAULT_NARRATION);
    }

    public ExtendedButton(int xPos, int yPos, int width, int height, Component displayString, OnPress handler, CreateNarration createNarration) {
        super(xPos, yPos, width, height, displayString, handler, createNarration);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.extractDefaultSprite(guiGraphics);
        this.extractDefaultLabel(guiGraphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
