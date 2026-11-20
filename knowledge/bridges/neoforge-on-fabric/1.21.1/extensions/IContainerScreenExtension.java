package ${package}.extensions;

import net.minecraft.world.inventory.Slot;

/**
 * The getters NeoForge adds to AbstractContainerScreen: the screen's left and top edge, its image size and the slot
 * under the mouse (the game's leftPos, topPos, imageWidth, imageHeight and hoveredSlot), by NeoForge 26.x's names
 * (getLeftPos ...) and its older ones (getGuiLeft ...), which 26.1 still has. Added by ContainerScreenMixin,
 * which reads the fields through ContainerScreenAccess, and for the compile by Loom's interface injection.
 */
public interface IContainerScreenExtension {

    default int getGuiLeft() {
        return ((ContainerScreenAccess) this).neoforgeBridge$leftPos();
    }

    default int getGuiTop() {
        return ((ContainerScreenAccess) this).neoforgeBridge$topPos();
    }

    default int getXSize() {
        return ((ContainerScreenAccess) this).neoforgeBridge$imageWidth();
    }

    default int getYSize() {
        return ((ContainerScreenAccess) this).neoforgeBridge$imageHeight();
    }

    default Slot getSlotUnderMouse() {
        return ((ContainerScreenAccess) this).neoforgeBridge$hoveredSlot();
    }

    default int getLeftPos() {
        return getGuiLeft();
    }

    default int getTopPos() {
        return getGuiTop();
    }

    default int getImageWidth() {
        return getXSize();
    }

    default int getImageHeight() {
        return getYSize();
    }

    default Slot getHoveredSlot() {
        return getSlotUnderMouse();
    }
}
