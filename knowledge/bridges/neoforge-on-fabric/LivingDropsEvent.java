package ${package};

import java.util.Collection;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.LivingDropsEvent, posted where NeoForge's LivingEntity patch
 * posts it: at the end of LivingEntity.dropAllDeathLoot, with the item entities the death dropped (captured, as
 * NeoForge captures them, by the LivingDropsMixin family instead of being added to the level). Listeners may change
 * the collection; cancelling the event drops none of them, as on NeoForge.
 */
public class LivingDropsEvent extends LivingEvent implements ICancellableEvent {

    private final DamageSource source;
    private final Collection<ItemEntity> drops;
    private final boolean recentlyHit;

    public LivingDropsEvent(LivingEntity entity, DamageSource source, Collection<ItemEntity> drops, boolean recentlyHit) {
        super(entity);
        this.source = source;
        this.drops = drops;
        this.recentlyHit = recentlyHit;
    }

    public DamageSource getSource() {
        return source;
    }

    public Collection<ItemEntity> getDrops() {
        return drops;
    }

    public boolean isRecentlyHit() {
        return recentlyHit;
    }
}
