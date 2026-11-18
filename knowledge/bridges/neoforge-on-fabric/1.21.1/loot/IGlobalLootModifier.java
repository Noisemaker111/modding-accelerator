package ${package}.loot;

import ${package}.NeoForgeRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.loot.IGlobalLootModifier: a change to the items every loot table rolls,
 * read from data packs (data/NAMESPACE/loot_modifiers/PATH.json, typed by a codec in
 * NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS, enabled by data/neoforge/loot_modifiers/global_loot_modifiers.json).
 * On Fabric, LootModifierManager reads them and the bridge's LootModifiersMixin applies them where NeoForge does, at the end
 * of each loot table's roll.
 */
public interface IGlobalLootModifier {

    Codec<IGlobalLootModifier> DIRECT_CODEC = Codec.lazyInitialized(() -> NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS.byNameCodec())
        .dispatch(IGlobalLootModifier::codec, Function.identity());

    Codec<LootItemCondition[]> LOOT_CONDITIONS_CODEC = LootItemCondition.DIRECT_CODEC.listOf().xmap(list -> list.toArray(LootItemCondition[]::new), List::of);

    ObjectArrayList<ItemStack> apply(ObjectArrayList<ItemStack> generatedLoot, LootContext context);

    MapCodec<? extends IGlobalLootModifier> codec();
}
