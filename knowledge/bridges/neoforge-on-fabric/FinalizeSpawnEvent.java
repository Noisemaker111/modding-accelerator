package ${package};

import com.mojang.datafixers.util.Either;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent, posted by EventHooks.finalizeMobSpawn
 * (which mods call) before the mob's finalizeSpawn; cancelling it skips finalizeSpawn. NeoForge also posts it from the
 * game's own spawners, which the bridge does not, and its setSpawnCancelled/isSpawnCancelled need NeoForge's Mob
 * patch, so they are not here.
 */
public class FinalizeSpawnEvent extends MobSpawnEvent implements ICancellableEvent {

    private final EntitySpawnReason spawnType;
    private final Either<BlockEntity, Entity> spawner;
    private DifficultyInstance difficulty;
    private SpawnGroupData spawnData;

    public FinalizeSpawnEvent(Mob entity, ServerLevelAccessor level, double x, double y, double z, DifficultyInstance difficulty, EntitySpawnReason spawnType, SpawnGroupData spawnData, Either<BlockEntity, Entity> spawner) {
        super(entity, level, x, y, z);
        this.difficulty = difficulty;
        this.spawnType = spawnType;
        this.spawnData = spawnData;
        this.spawner = spawner;
    }

    public DifficultyInstance getDifficulty() {
        return this.difficulty;
    }

    public void setDifficulty(DifficultyInstance inst) {
        this.difficulty = inst;
    }

    public EntitySpawnReason getSpawnType() {
        return this.spawnType;
    }

    public SpawnGroupData getSpawnData() {
        return this.spawnData;
    }

    public void setSpawnData(SpawnGroupData data) {
        this.spawnData = data;
    }

    public Either<BlockEntity, Entity> getSpawner() {
        return this.spawner;
    }
}
