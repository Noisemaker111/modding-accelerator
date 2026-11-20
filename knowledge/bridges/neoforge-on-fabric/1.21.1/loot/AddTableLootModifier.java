package ${package}.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.loot.AddTableLootModifier (neoforge:add_table): adds another loot table's
 * roll to the loot, rolled raw (that table's own modifiers are not applied again), as NeoForge does.
 */
public class AddTableLootModifier extends LootModifier {

    public static final MapCodec<AddTableLootModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        IGlobalLootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(modifier -> modifier.conditions),
        ResourceKey.codec(Registries.LOOT_TABLE).fieldOf("table").forGetter(AddTableLootModifier::table)
    ).apply(instance, AddTableLootModifier::new));

    private final ResourceKey<LootTable> table;

    public AddTableLootModifier(LootItemCondition[] conditionsIn, ResourceKey<LootTable> table) {
        super(conditionsIn);
        this.table = table;
    }

    public ResourceKey<LootTable> table() {
        return this.table;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        context.getResolver().get(Registries.LOOT_TABLE, this.table).ifPresent(extra -> extra.value().getRandomItemsRaw(context, LootTable.createStackSplitter(context.getLevel(), generatedLoot::add)));
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
