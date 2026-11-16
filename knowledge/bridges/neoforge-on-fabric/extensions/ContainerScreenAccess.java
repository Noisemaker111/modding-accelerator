package ${package}.extensions;

import net.minecraft.world.inventory.Slot;

/** AbstractContainerScreen's protected fields, as ContainerScreenMixin gives them to IContainerScreenExtension. */
public interface ContainerScreenAccess {

    int neoforgeBridge$leftPos();

    int neoforgeBridge$topPos();

    int neoforgeBridge$imageWidth();

    int neoforgeBridge$imageHeight();

    Slot neoforgeBridge$hoveredSlot();
}
