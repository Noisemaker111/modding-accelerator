package ${package}.mixin;

import ${package}.extensions.GuiGraphicsAccess;
import ${package}.extensions.IGuiGraphicsExtension;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Gives GuiGraphics NeoForge 21.1's float drawString and stack-naming tooltips. */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin implements IGuiGraphicsExtension, GuiGraphicsAccess {

    @Shadow
    protected abstract void flushIfUnmanaged();

    @Override
    public void neoforgeBridge$flush() {
        this.flushIfUnmanaged();
    }
}
