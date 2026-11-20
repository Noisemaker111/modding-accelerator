package ${package}.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.world.BiomeGenerationSettingsBuilder: a biome's features and carvers by
 * step, as lists a biome modifier adds to and removes from (getFeatures, getCarvers, addFeature, addCarver), started from
 * the biome's own. BiomeModifierBridge compares them with the biome's afterwards.
 */
public class BiomeGenerationSettingsBuilder extends BiomeGenerationSettings.PlainBuilder {

    final List<List<Holder<PlacedFeature>>> featureSteps = new ArrayList<>();
    final Map<GenerationStep.Carving, List<Holder<ConfiguredWorldCarver<?>>>> carverSteps = new EnumMap<>(GenerationStep.Carving.class);

    public BiomeGenerationSettingsBuilder(BiomeGenerationSettings orig) {
        List<net.minecraft.core.HolderSet<PlacedFeature>> features = orig.features();
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> list = new ArrayList<>();
            if (step.ordinal() < features.size()) features.get(step.ordinal()).forEach(list::add);
            featureSteps.add(list);
        }
        for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
            List<Holder<ConfiguredWorldCarver<?>>> list = new ArrayList<>();
            orig.getCarvers(step).forEach(list::add);
            carverSteps.put(step, list);
        }
    }

    public List<Holder<PlacedFeature>> getFeatures(GenerationStep.Decoration stage) {
        return featureSteps.get(stage.ordinal());
    }

    public List<Holder<ConfiguredWorldCarver<?>>> getCarvers(GenerationStep.Carving stage) {
        return carverSteps.get(stage);
    }

    @Override
    public BiomeGenerationSettings.PlainBuilder addFeature(GenerationStep.Decoration step, Holder<PlacedFeature> feature) {
        getFeatures(step).add(feature);
        return this;
    }

    @Override
    public BiomeGenerationSettings.PlainBuilder addFeature(int step, Holder<PlacedFeature> feature) {
        featureSteps.get(step).add(feature);
        return this;
    }

    @Override
    public BiomeGenerationSettings.PlainBuilder addCarver(GenerationStep.Carving step, Holder<ConfiguredWorldCarver<?>> carver) {
        getCarvers(step).add(carver);
        return this;
    }
}
