package ${package}.datamaps.builtin;

import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.FuelValues;
import ${package}.datamaps.DataMapType;
import ${package}.datamaps.DataMaps;

/**
 * NeoForge 26.1's net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps that the converted mods use, and
 * what NeoForge does with them, on the game's own tables: FURNACE_FUELS go into the game's fuel values as it builds
 * them (FuelValuesBuilderMixin: an id adds that item, a #tag the tag, a removed #tag is removed), COMPOSTABLES into the
 * composter's chances after each data load (what an earlier load added goes first). Differences: a removed single item
 * stays a fuel, and a file's "replace" does not clear the game's own fuels; parrots keep the game's own imitation table.
 */
public final class NeoForgeDataMaps {

    private NeoForgeDataMaps() {}

    public static final DataMapType<Item, Compostable> COMPOSTABLES = DataMapType.builder(
        id("compostables"), Registries.ITEM, Compostable.CODEC
    ).synced(Compostable.CHANCE_CODEC, false).build();

    public static final DataMapType<Item, FurnaceFuel> FURNACE_FUELS = DataMapType.builder(
        id("furnace_fuels"), Registries.ITEM, FurnaceFuel.CODEC
    ).synced(FurnaceFuel.BURN_TIME_CODEC, false).build();

    public static final DataMapType<EntityType<?>, ParrotImitation> PARROT_IMITATIONS = DataMapType.builder(
        id("parrot_imitations"), Registries.ENTITY_TYPE, ParrotImitation.CODEC
    ).synced(ParrotImitation.SOUND_CODEC, false).build();

    private static final Set<Item> COMPOSTED = new HashSet<>();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neoforge", path);
    }

    public static void register() {
        DataMaps.registerBuiltIn(COMPOSTABLES);
        DataMaps.registerBuiltIn(FURNACE_FUELS);
        DataMaps.registerBuiltIn(PARROT_IMITATIONS);
    }

    public static void wire() {}

    /** The fuels of FURNACE_FUELS, into the game's fuel values as it builds them (FuelValuesBuilderMixin). */
    public static void addFuels(FuelValues.Builder builder) {
        DataMaps.forEachEntry(FURNACE_FUELS, JsonOps.INSTANCE, DataMaps.files(FURNACE_FUELS), () -> {}, (key, fuel) -> {
            if (key.startsWith("#")) builder.add(TagKey.create(Registries.ITEM, Identifier.parse(key.substring(1))), fuel.burnTime());
            else BuiltInRegistries.ITEM.getOptional(Identifier.parse(key)).ifPresent(item -> builder.add(item, fuel.burnTime()));
        }, key -> {
            if (key.startsWith("#")) builder.remove(TagKey.create(Registries.ITEM, Identifier.parse(key.substring(1))));
        });
    }

    /** The composter's chances from COMPOSTABLES, after each server data load. */
    public static synchronized void apply(boolean client) {
        if (client) return;
        for (Item item : COMPOSTED) ComposterBlock.COMPOSTABLES.removeFloat(item);
        COMPOSTED.clear();
        for (var e : DataMaps.all(COMPOSTABLES).entrySet()) {
            ResourceKey<Item> key = e.getKey();
            BuiltInRegistries.ITEM.getOptional(key).ifPresent(item -> {
                if (!ComposterBlock.COMPOSTABLES.containsKey(item)) COMPOSTED.add(item);
                ComposterBlock.COMPOSTABLES.put(item, e.getValue().chance());
            });
        }
    }
}
