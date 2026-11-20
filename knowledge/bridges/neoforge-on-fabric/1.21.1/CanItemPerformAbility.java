package ${package};

import ${package}.extensions.ItemHooks;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.loot.CanItemPerformAbility (the loot condition
 * neoforge:can_item_perform_ability): whether the tool of the loot roll can do the ability (canPerformAction, the tool's
 * own answer or NeoForge's defaults). Registered once by the bridge (register, from Bridge.hook).
 */
public record CanItemPerformAbility(ItemAbility ability) implements LootItemCondition {

    public static final MapCodec<CanItemPerformAbility> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
        ItemAbility.CODEC.fieldOf("ability").forGetter(CanItemPerformAbility::ability)
    ).apply(b, CanItemPerformAbility::new));
    public static final LootItemConditionType LOOT_CONDITION_TYPE = new LootItemConditionType(CODEC);

    static void register() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("neoforge", "can_item_perform_ability");
        if (!BuiltInRegistries.LOOT_CONDITION_TYPE.containsKey(id)) Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, id, LOOT_CONDITION_TYPE);
    }

    @Override
    public LootItemConditionType getType() {
        return LOOT_CONDITION_TYPE;
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.TOOL);
    }

    @Override
    public boolean test(LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool == null) return false;
        return tool.getItem() instanceof ItemHooks hooks ? hooks.canPerformAction(tool, this.ability) : ItemHooks.defaultCanPerformAction(tool.getItem(), this.ability);
    }

    public static LootItemCondition.Builder canItemPerformAbility(ItemAbility ability) {
        return () -> new CanItemPerformAbility(ability);
    }
}
