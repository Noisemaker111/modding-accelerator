package ${package};

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.TooltipFlag;

/**
 * NeoForge's net.neoforged.neoforge.client.ClientTooltipFlag: a tooltip flag that also tells which modifier keys are
 * down, read from the client as NeoForge reads them (the game's TooltipFlag has no such methods without NeoForge).
 */
public record ClientTooltipFlag(boolean advanced, boolean creative, boolean shiftDown, boolean controlDown, boolean altDown) implements TooltipFlag {

    @Override
    public boolean isAdvanced() {
        return advanced;
    }

    @Override
    public boolean isCreative() {
        return creative;
    }

    public boolean hasShiftDown() {
        return shiftDown;
    }

    public boolean hasControlDown() {
        return controlDown;
    }

    public boolean hasAltDown() {
        return altDown;
    }

    public static TooltipFlag of(TooltipFlag other) {
        Minecraft minecraft = Minecraft.getInstance();
        return new ClientTooltipFlag(other.isAdvanced(), other.isCreative(), minecraft.hasShiftDown(), minecraft.hasControlDown(), minecraft.hasAltDown());
    }
}
