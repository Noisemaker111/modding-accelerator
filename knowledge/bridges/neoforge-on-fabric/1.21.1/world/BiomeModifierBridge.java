package ${package}.world;

import ${package}.NeoForgeRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * NeoForge's biome modifiers on Fabric API. The data pack registry neoforge:biome_modifier (read from
 * data/NAMESPACE/neoforge/biome_modifier, as on NeoForge) is Fabric API's DynamicRegistries, made once for all converted
 * mods; its codec reads any modifier type in NeoForgeRegistries.BIOME_MODIFIER_SERIALIZERS. Each NeoForge phase runs in a
 * Fabric API BiomeModifications phase (BEFORE_EVERYTHING and ADD in ADDITIONS, REMOVE in REMOVALS, MODIFY in REPLACEMENTS,
 * AFTER_EVERYTHING in POST_PROCESSING): the modifiers, in registry order, change a copy of the biome's features, carvers
 * and spawns, and the features, carvers and spawns added or removed are given to Fabric's modification context. A feature
 * or carver written inline (with no registry key) cannot be added through Fabric and is left out. Every converted mod has
 * its own copy of this bridge, so each applies the modifiers of its own BiomeModifier type.
 */
public final class BiomeModifierBridge {

    private static final String SHARED = "mmt:neoforge_biome_modifier_registry";
    private static Registry<Object> modifiers;

    private BiomeModifierBridge() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void start(Registry<MapCodec<? extends BiomeModifier>> serializers) {
        register(serializers, "none", BiomeModifiers.NoneBiomeModifier.CODEC);
        register(serializers, "add_features", BiomeModifiers.AddFeaturesBiomeModifier.CODEC);
        register(serializers, "remove_features", BiomeModifiers.RemoveFeaturesBiomeModifier.CODEC);
        register(serializers, "add_spawns", BiomeModifiers.AddSpawnsBiomeModifier.CODEC);
        register(serializers, "remove_spawns", BiomeModifiers.RemoveSpawnsBiomeModifier.CODEC);
        register(serializers, "add_carvers", BiomeModifiers.AddCarversBiomeModifier.CODEC);
        register(serializers, "remove_carvers", BiomeModifiers.RemoveCarversBiomeModifier.CODEC);
        ResourceKey<Registry<Object>> key = (ResourceKey) NeoForgeRegistries.Keys.BIOME_MODIFIERS;
        if (FabricLoader.getInstance().getObjectShare().putIfAbsent(SHARED, Boolean.TRUE) == null) {
            Codec<Object> codec = ((Registry) serializers).byNameCodec().dispatch(BiomeModifierBridge::codecOf, c -> (MapCodec<Object>) c);
            DynamicRegistries.register(key, codec);
        }
        DynamicRegistrySetupCallback.EVENT.register(view -> view.getOptional(key).ifPresent(registry -> modifiers = registry));
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("neoforge_bridge", Integer.toHexString(System.identityHashCode(BiomeModifierBridge.class)));
        BiomeModifications.create(id)
            .add(ModificationPhase.ADDITIONS, selection -> true, (selection, context) -> apply(selection, context, BiomeModifier.Phase.BEFORE_EVERYTHING, BiomeModifier.Phase.ADD))
            .add(ModificationPhase.REMOVALS, selection -> true, (selection, context) -> apply(selection, context, BiomeModifier.Phase.REMOVE))
            .add(ModificationPhase.REPLACEMENTS, selection -> true, (selection, context) -> apply(selection, context, BiomeModifier.Phase.MODIFY))
            .add(ModificationPhase.POST_PROCESSING, selection -> true, (selection, context) -> apply(selection, context, BiomeModifier.Phase.AFTER_EVERYTHING));
    }

    private static void register(Registry<MapCodec<? extends BiomeModifier>> serializers, String name, MapCodec<? extends BiomeModifier> codec) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("neoforge", name);
        if (!serializers.containsKey(id)) Registry.register(serializers, id, codec);
    }

    @SuppressWarnings("unchecked")
    private static MapCodec<Object> codecOf(Object modifier) {
        try {
            return (MapCodec<Object>) modifier.getClass().getMethod("codec").invoke(modifier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Not a NeoForge biome modifier: " + modifier, e);
        }
    }

    /** This bridge's modifiers, in registry order. */
    static List<BiomeModifier> modifiers() {
        List<BiomeModifier> out = new ArrayList<>();
        if (modifiers != null) for (Object modifier : modifiers) if (modifier instanceof BiomeModifier own) out.add(own);
        return out;
    }

    static void apply(BiomeSelectionContext selection, BiomeModificationContext context, BiomeModifier.Phase... phases) {
        List<BiomeModifier> list = modifiers();
        if (list.isEmpty()) return;
        Holder<Biome> holder = selection.getBiomeRegistryEntry();
        Biome biome = selection.getBiome();
        ModifiableBiomeInfo.BiomeInfo.Builder builder = new ModifiableBiomeInfo.BiomeInfo.Builder(
            null,
            biome.getSpecialEffects(),
            new BiomeGenerationSettingsBuilder(biome.getGenerationSettings()),
            new MobSpawnSettingsBuilder(biome.getMobSettings())
        );
        for (BiomeModifier.Phase phase : phases) for (BiomeModifier modifier : list) modifier.modify(holder, phase, builder);
        carry(biome, builder, context);
    }

    /** What the modifiers added and removed, given to Fabric's context. */
    static void carry(Biome biome, ModifiableBiomeInfo.BiomeInfo.Builder builder, BiomeModificationContext context) {
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> before = new ArrayList<>();
            if (step.ordinal() < biome.getGenerationSettings().features().size()) biome.getGenerationSettings().features().get(step.ordinal()).forEach(before::add);
            List<Holder<PlacedFeature>> after = builder.getGenerationSettings().getFeatures(step);
            for (Holder<PlacedFeature> feature : after) if (!before.contains(feature)) feature.unwrapKey().ifPresent(key -> context.getGenerationSettings().addFeature(step, key));
            for (Holder<PlacedFeature> feature : before) if (!after.contains(feature)) feature.unwrapKey().ifPresent(key -> context.getGenerationSettings().removeFeature(step, key));
        }
        for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
            List<Holder<ConfiguredWorldCarver<?>>> before = new ArrayList<>();
            biome.getGenerationSettings().getCarvers(step).forEach(before::add);
            List<Holder<ConfiguredWorldCarver<?>>> after = builder.getGenerationSettings().getCarvers(step);
            for (Holder<ConfiguredWorldCarver<?>> carver : after) if (!before.contains(carver)) carver.unwrapKey().ifPresent(key -> context.getGenerationSettings().addCarver(step, key));
            for (Holder<ConfiguredWorldCarver<?>> carver : before) if (!after.contains(carver)) carver.unwrapKey().ifPresent(key -> context.getGenerationSettings().removeCarver(step, key));
        }
        Set<MobSpawnSettings.SpawnerData> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MobCategory category : MobCategory.values()) {
            List<MobSpawnSettings.SpawnerData> before = biome.getMobSettings().getMobs(category).unwrap();
            List<MobSpawnSettings.SpawnerData> after = builder.getMobSpawnSettings().getSpawner(category);
            for (MobSpawnSettings.SpawnerData spawner : after) if (!before.contains(spawner)) context.getSpawnSettings().addSpawn(category, spawner);
            for (MobSpawnSettings.SpawnerData spawner : before) if (!after.contains(spawner)) removed.add(spawner);
        }
        if (!removed.isEmpty()) context.getSpawnSettings().removeSpawns((category, spawner) -> removed.contains(spawner));
    }
}
