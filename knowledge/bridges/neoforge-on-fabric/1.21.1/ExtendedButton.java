package ${package};

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.gui.widget.ExtendedButton: a Button with public constructors, drawn as
 * the game draws a button (its sprite, then its label, which scrolls when it is too long where NeoForge's cuts it short).
 * NeoForge's Button.Builder constructor is not mirrored (the game's builder keeps its fields private).
 */
public class ExtendedButton extends Button {

    public ExtendedButton(int xPos, int yPos, int width, int height, Component displayString, OnPress handler) {
        this(xPos, yPos, width, height, displayString, handler, DEFAULT_NARRATION);
    }

    public ExtendedButton(int xPos, int yPos, int width, int height, Component displayString, OnPress handler, CreateNarration createNarration) {
        super(xPos, yPos, width, height, displayString, handler, createNarration);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
    }
}
