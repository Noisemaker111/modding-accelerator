package ${package}.world;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * NeoForge 21.1's built-in biome modifiers (net.neoforged.neoforge.common.world.BiomeModifiers), with the codecs NeoForge
 * registers them by (neoforge:none, add_features, remove_features, add_spawns, remove_spawns, add_carvers,
 * remove_carvers; BiomeModifierBridge registers them), so a data pack's NeoForge biome modifiers read the same.
 */
public final class BiomeModifiers {

    private BiomeModifiers() {}

    /** NeoForge's "steps": one step or a list of them, every step when left out. */
    static <E extends Enum<E> & StringRepresentable> MapCodec<Set<E>> stepSet(Codec<E> codec, Class<E> type) {
        Codec<Set<E>> set = Codec.either(codec.listOf(), codec).xmap(
            either -> either.map(list -> list.isEmpty() ? (Set<E>) EnumSet.noneOf(type) : (Set<E>) EnumSet.copyOf(list), one -> (Set<E>) EnumSet.of(one)),
            steps -> steps.size() == 1 ? Either.right(steps.iterator().next()) : Either.left(List.copyOf(steps))
        );
        return set.optionalFieldOf("steps", EnumSet.allOf(type));
    }

    public record NoneBiomeModifier() implements BiomeModifier {
        public static final NoneBiomeModifier INSTANCE = new NoneBiomeModifier();
        static final MapCodec<NoneBiomeModifier> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {}

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record AddFeaturesBiomeModifier(HolderSet<Biome> biomes, HolderSet<PlacedFeature> features, GenerationStep.Decoration step) implements BiomeModifier {
        static final MapCodec<AddFeaturesBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(AddFeaturesBiomeModifier::biomes),
            PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(AddFeaturesBiomeModifier::features),
            GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(AddFeaturesBiomeModifier::step)
        ).apply(b, AddFeaturesBiomeModifier::new));

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase == Phase.ADD && this.biomes.contains(biome)) this.features.forEach(feature -> builder.getGenerationSettings().addFeature(this.step, feature));
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record RemoveFeaturesBiomeModifier(HolderSet<Biome> biomes, HolderSet<PlacedFeature> features, Set<GenerationStep.Decoration> steps) implements BiomeModifier {
        static final MapCodec<RemoveFeaturesBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(RemoveFeaturesBiomeModifier::biomes),
            PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(RemoveFeaturesBiomeModifier::features),
            stepSet(GenerationStep.Decoration.CODEC, GenerationStep.Decoration.class).forGetter(RemoveFeaturesBiomeModifier::steps)
        ).apply(b, RemoveFeaturesBiomeModifier::new));

        public static RemoveFeaturesBiomeModifier allSteps(HolderSet<Biome> biomes, HolderSet<PlacedFeature> features) {
            return new RemoveFeaturesBiomeModifier(biomes, features, EnumSet.allOf(GenerationStep.Decoration.class));
        }

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.REMOVE || !this.biomes.contains(biome)) return;
            for (GenerationStep.Decoration step : this.steps) builder.getGenerationSettings().getFeatures(step).removeIf(this.features::contains);
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record AddSpawnsBiomeModifier(HolderSet<Biome> biomes, List<MobSpawnSettings.SpawnerData> spawners) implements BiomeModifier {
        static final MapCodec<AddSpawnsBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(AddSpawnsBiomeModifier::biomes),
            Codec.either(MobSpawnSettings.SpawnerData.CODEC.listOf(), MobSpawnSettings.SpawnerData.CODEC).xmap(
                either -> either.map(list -> list, List::of),
                list -> list.size() == 1 ? Either.right(list.get(0)) : Either.left(list)
            ).fieldOf("spawners").forGetter(AddSpawnsBiomeModifier::spawners)
        ).apply(b, AddSpawnsBiomeModifier::new));

        public static AddSpawnsBiomeModifier singleSpawn(HolderSet<Biome> biomes, MobSpawnSettings.SpawnerData spawner) {
            return new AddSpawnsBiomeModifier(biomes, List.of(spawner));
        }

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.ADD || !this.biomes.contains(biome)) return;
            for (MobSpawnSettings.SpawnerData spawner : this.spawners) builder.getMobSpawnSettings().addSpawn(spawner.type.getCategory(), spawner);
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record RemoveSpawnsBiomeModifier(HolderSet<Biome> biomes, HolderSet<EntityType<?>> entityTypes) implements BiomeModifier {
        static final MapCodec<RemoveSpawnsBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(RemoveSpawnsBiomeModifier::biomes),
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).fieldOf("entity_types").forGetter(RemoveSpawnsBiomeModifier::entityTypes)
        ).apply(b, RemoveSpawnsBiomeModifier::new));

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.REMOVE || !this.biomes.contains(biome)) return;
            for (MobCategory category : MobCategory.values()) {
                builder.getMobSpawnSettings().getSpawner(category).removeIf(spawner -> this.entityTypes.contains(spawner.type.builtInRegistryHolder()));
            }
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record AddCarversBiomeModifier(HolderSet<Biome> biomes, HolderSet<ConfiguredWorldCarver<?>> carvers, GenerationStep.Carving step) implements BiomeModifier {
        static final MapCodec<AddCarversBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(AddCarversBiomeModifier::biomes),
            ConfiguredWorldCarver.LIST_CODEC.fieldOf("carvers").forGetter(AddCarversBiomeModifier::carvers),
            GenerationStep.Carving.CODEC.fieldOf("step").forGetter(AddCarversBiomeModifier::step)
        ).apply(b, AddCarversBiomeModifier::new));

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase == Phase.ADD && this.biomes.contains(biome)) this.carvers.forEach(carver -> builder.getGenerationSettings().addCarver(this.step, carver));
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }

    public record RemoveCarversBiomeModifier(HolderSet<Biome> biomes, HolderSet<ConfiguredWorldCarver<?>> carvers, Set<GenerationStep.Carving> steps) implements BiomeModifier {
        static final MapCodec<RemoveCarversBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(RemoveCarversBiomeModifier::biomes),
            ConfiguredWorldCarver.LIST_CODEC.fieldOf("carvers").forGetter(RemoveCarversBiomeModifier::carvers),
            stepSet(GenerationStep.Carving.CODEC, GenerationStep.Carving.class).forGetter(RemoveCarversBiomeModifier::steps)
        ).apply(b, RemoveCarversBiomeModifier::new));

        public static RemoveCarversBiomeModifier allSteps(HolderSet<Biome> biomes, HolderSet<ConfiguredWorldCarver<?>> carvers) {
            return new RemoveCarversBiomeModifier(biomes, carvers, EnumSet.allOf(GenerationStep.Carving.class));
        }

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if (phase != Phase.REMOVE || !this.biomes.contains(biome)) return;
            for (GenerationStep.Carving step : this.steps) builder.getGenerationSettings().getCarvers(step).removeIf(this.carvers::contains);
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return CODEC;
        }
    }
}
