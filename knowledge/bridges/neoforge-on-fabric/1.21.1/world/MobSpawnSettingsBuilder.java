package ${package}.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.world.MobSpawnSettingsBuilder: a biome's spawns by category, as lists a
 * biome modifier adds to and removes from (getSpawner, addSpawn), started from the biome's own. BiomeModifierBridge
 * compares them with the biome's afterwards.
 */
public class MobSpawnSettingsBuilder extends MobSpawnSettings.Builder {

    final Map<MobCategory, List<MobSpawnSettings.SpawnerData>> spawns = new EnumMap<>(MobCategory.class);

    public MobSpawnSettingsBuilder(MobSpawnSettings orig) {
        for (MobCategory category : MobCategory.values()) spawns.put(category, new ArrayList<>(orig.getMobs(category).unwrap()));
    }

    public List<MobSpawnSettings.SpawnerData> getSpawner(MobCategory category) {
        return spawns.get(category);
    }

    @Override
    public MobSpawnSettings.Builder addSpawn(MobCategory category, MobSpawnSettings.SpawnerData data) {
        getSpawner(category).add(data);
        return this;
    }
}
