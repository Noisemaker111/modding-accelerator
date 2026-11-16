package ${package};

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * NeoForge's net.neoforged.neoforge.common.loot.CanItemPerformAbility, the loot condition "the tool can perform this
 * ability". It is not registered on Fabric (no loot table names neoforge:can_item_perform_ability there), and no tool
 * reports NeoForge's abilities there, so a condition made in code never passes.
 */
public class CanItemPerformAbility implements LootItemCondition {

    public static final MapCodec<CanItemPerformAbility> CODEC = RecordCodecBuilder.mapCodec(
        builder -> builder.group(ItemAbility.CODEC.fieldOf("ability").forGetter(condition -> condition.ability)).apply(builder, CanItemPerformAbility::new)
    );

    final ItemAbility ability;

    public CanItemPerformAbility(ItemAbility ability) {
        this.ability = ability;
    }

    @Override
    public MapCodec<? extends LootItemCondition> codec() {
        return CODEC;
    }

    @Override
    public Set<ContextKey<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.TOOL);
    }

    @Override
    public boolean test(LootContext context) {
        return false;
    }

    public static LootItemCondition.Builder canItemPerformAbility(ItemAbility ability) {
        return () -> new CanItemPerformAbility(ability);
    }
}
