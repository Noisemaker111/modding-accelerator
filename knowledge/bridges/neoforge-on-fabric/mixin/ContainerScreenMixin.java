package ${package}.mixin;

import ${package}.extensions.ContainerScreenAccess;
import ${package}.extensions.IContainerScreenExtension;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Gives AbstractContainerScreen NeoForge's getGuiLeft, getGuiTop, getXSize, getYSize and getSlotUnderMouse. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin implements IContainerScreenExtension, ContainerScreenAccess {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Shadow
    protected int imageHeight;

    @Shadow
    protected Slot hoveredSlot;

    @Override
    public int neoforgeBridge$leftPos() {
        return this.leftPos;
    }

    @Override
    public int neoforgeBridge$topPos() {
        return this.topPos;
    }

    @Override
    public int neoforgeBridge$imageWidth() {
        return this.imageWidth;
    }

    @Override
    public int neoforgeBridge$imageHeight() {
        return this.imageHeight;
    }

    @Override
    public Slot neoforgeBridge$hoveredSlot() {
        return this.hoveredSlot;
    }
}
