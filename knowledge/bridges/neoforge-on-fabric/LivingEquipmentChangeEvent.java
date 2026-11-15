package ${package};

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent: an entity's item in a slot changed,
 * posted from Fabric's ServerEntityEvents.EQUIPMENT_CHANGE, which fires where NeoForge's patch posts it (in
 * LivingEntity.collectEquipmentChanges, on the server, once per changed slot). The same members as NeoForge's.
 */
public class LivingEquipmentChangeEvent extends LivingEvent {

    private final EquipmentSlot slot;
    private final ItemStack from;
    private final ItemStack to;

    public LivingEquipmentChangeEvent(LivingEntity entity, EquipmentSlot slot, ItemStack from, ItemStack to) {
        super(entity);
        this.slot = slot;
        this.from = from;
        this.to = to;
    }

    public EquipmentSlot getSlot() {
        return this.slot;
    }

    public ItemStack getFrom() {
        return this.from;
    }

    public ItemStack getTo() {
        return this.to;
    }
}
