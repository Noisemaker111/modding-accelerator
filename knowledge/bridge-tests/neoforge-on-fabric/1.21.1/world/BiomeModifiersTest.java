package ${package}.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NeoForge's built-in biome modifiers change the biome's own lists as on NeoForge: add_features adds to its step in the
 * ADD phase only, remove_features removes from the steps it names in the REMOVE phase only, and "steps" reads one step,
 * a list, or every step when left out.
 */
class BiomeModifiersTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static Holder<PlacedFeature> feature() {
        return Holder.direct(new PlacedFeature(null, List.of()));
    }

    @Test
    void featuresAreAddedAndRemovedByPhaseAndStep() {
        Holder<PlacedFeature> flowers = feature();
        Holder<PlacedFeature> ore = feature();
        BiomeGenerationSettings.PlainBuilder original = new BiomeGenerationSettings.PlainBuilder();
        original.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, ore);
        ModifiableBiomeInfo.BiomeInfo.Builder builder = new ModifiableBiomeInfo.BiomeInfo.Builder(null, null, new BiomeGenerationSettingsBuilder(original.build()), null);
        HolderSet<net.minecraft.world.level.biome.Biome> anyBiome = HolderSet.direct(List.of());

        BiomeModifier add = new BiomeModifiers.AddFeaturesBiomeModifier(new AlwaysSet(), HolderSet.direct(flowers), GenerationStep.Decoration.VEGETAL_DECORATION);
        add.modify(null, BiomeModifier.Phase.REMOVE, builder);
        assertTrue(builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.VEGETAL_DECORATION).isEmpty());
        add.modify(null, BiomeModifier.Phase.ADD, builder);
        assertEquals(List.of(flowers), builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.VEGETAL_DECORATION));

        BiomeModifier removeElsewhere = new BiomeModifiers.RemoveFeaturesBiomeModifier(new AlwaysSet(), HolderSet.direct(ore), EnumSet.of(GenerationStep.Decoration.VEGETAL_DECORATION));
        removeElsewhere.modify(null, BiomeModifier.Phase.REMOVE, builder);
        assertEquals(List.of(ore), builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES));
        BiomeModifiers.RemoveFeaturesBiomeModifier.allSteps(new AlwaysSet(), HolderSet.direct(ore)).modify(null, BiomeModifier.Phase.REMOVE, builder);
        assertTrue(builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).isEmpty());
        assertEquals(0, anyBiome.size());
    }

    @Test
    void stepsReadOneAListOrAll() {
        var codec = BiomeModifiers.stepSet(GenerationStep.Decoration.CODEC, GenerationStep.Decoration.class).codec();
        Set<GenerationStep.Decoration> one = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"steps\": \"vegetal_decoration\"}")).getOrThrow();
        Set<GenerationStep.Decoration> two = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"steps\": [\"raw_generation\", \"lakes\"]}")).getOrThrow();
        Set<GenerationStep.Decoration> all = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();
        assertEquals(EnumSet.of(GenerationStep.Decoration.VEGETAL_DECORATION), one);
        assertEquals(EnumSet.of(GenerationStep.Decoration.RAW_GENERATION, GenerationStep.Decoration.LAKES), two);
        assertEquals(EnumSet.allOf(GenerationStep.Decoration.class), all);
    }

    /** A biome set that contains every biome (the modifiers ask it about the biome they change). */
    static final class AlwaysSet extends HolderSet.ListBacked<net.minecraft.world.level.biome.Biome> {
        @Override
        protected List<Holder<net.minecraft.world.level.biome.Biome>> contents() {
            return List.of();
        }

        @Override
        public boolean contains(Holder<net.minecraft.world.level.biome.Biome> holder) {
            return true;
        }

        @Override
        public com.mojang.datafixers.util.Either<net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome>, List<Holder<net.minecraft.world.level.biome.Biome>>> unwrap() {
            return com.mojang.datafixers.util.Either.right(List.of());
        }

        @Override
        public java.util.Optional<net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome>> unwrapKey() {
            return java.util.Optional.empty();
        }
    }
}
