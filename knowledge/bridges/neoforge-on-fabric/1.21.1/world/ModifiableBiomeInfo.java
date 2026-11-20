package ${package}.world;

import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.world.ModifiableBiomeInfo, as far as biome modifiers use it: BiomeInfo and
 * its Builder, which hands a modifier the biome's generation settings and spawns (to change) and its climate and
 * effects. On Fabric the builder is made by BiomeModifierBridge from the biome as it stands in each Fabric phase.
 */
public class ModifiableBiomeInfo {

    private ModifiableBiomeInfo() {}

    public record BiomeInfo(Object climateSettings, BiomeSpecialEffects effects, BiomeGenerationSettings generationSettings, MobSpawnSettings mobSpawnSettings) {

        public static class Builder {

            private final Object climateSettings;
            private final BiomeSpecialEffects effects;
            private final BiomeGenerationSettingsBuilder generationSettings;
            private final MobSpawnSettingsBuilder mobSpawnSettings;

            Builder(Object climateSettings, BiomeSpecialEffects effects, BiomeGenerationSettingsBuilder generationSettings, MobSpawnSettingsBuilder mobSpawnSettings) {
                this.climateSettings = climateSettings;
                this.effects = effects;
                this.generationSettings = generationSettings;
                this.mobSpawnSettings = mobSpawnSettings;
            }

            public static Builder copyOf(BiomeInfo original) {
                return new Builder(original.climateSettings(), original.effects(), new BiomeGenerationSettingsBuilder(original.generationSettings()), new MobSpawnSettingsBuilder(original.mobSpawnSettings()));
            }

            /** The climate (the game's Biome.ClimateSettings, private without NeoForge's access transformer; not carried to Fabric here). */
            public Object getClimateSettings() {
                return climateSettings;
            }

            /** The special effects as they are (NeoForge's BiomeSpecialEffectsBuilder changes them; not carried to Fabric here). */
            public BiomeSpecialEffects getSpecialEffects() {
                return effects;
            }

            public BiomeGenerationSettingsBuilder getGenerationSettings() {
                return generationSettings;
            }

            public MobSpawnSettingsBuilder getMobSpawnSettings() {
                return mobSpawnSettings;
            }
        }
    }
}
