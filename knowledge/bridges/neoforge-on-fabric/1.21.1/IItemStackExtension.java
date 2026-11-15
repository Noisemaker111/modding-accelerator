package ${package};

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.extensions.IItemStackExtension, whose methods NeoForge adds to ItemStack:
 * here as static methods taking the stack first, which modaccel convert's rules write in place of the calls
 * (stack.getEquipmentSlot() becomes IItemStackExtension.getEquipmentSlot(stack)). Each returns what NeoForge's default
 * returns; an item cannot override them on Fabric.
 */
public final class IItemStackExtension {

    private IItemStackExtension() {}

    /** The equipment slot the stack's item asks for, or null to let the game decide: NeoForge's default (IItemExtension) is null. */
    public static EquipmentSlot getEquipmentSlot(ItemStack stack) {
        return null;
    }

    /**
     * Not NeoForge's: Minecraft 1.21.1's own Equipable items and blocks have a getEquipmentSlot() of their own, which
     * the same rule writes here too; this keeps the game's answer.
     */
    public static EquipmentSlot getEquipmentSlot(Equipable equipable) {
        return equipable.getEquipmentSlot();
    }
}
