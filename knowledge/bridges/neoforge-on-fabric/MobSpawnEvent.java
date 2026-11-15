package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.living.MobSpawnEvent. SpawnPlacementCheck is posted where NeoForge's
 * SpawnPlacements patch posts it, from SpawnPlacements.checkSpawnRules with the game's own answer as the default
 * (SpawnPlacementsMixin); PositionCheck where NeoForge's NaturalSpawner and SpawnUtil patches post it, in place of the
 * mob's own spawn rules and obstruction check (MobSpawnPositionMixin). Both are also posted by EventHooks'
 * checkSpawnPlacements and checkSpawnPosition, which mods call. The spawner-specific PositionCheck NeoForge posts from
 * BaseSpawner is not posted here.
 */
public abstract class MobSpawnEvent extends EntityEvent {

    private final ServerLevelAccessor level;
    private final double x;
    private final double y;
    private final double z;

    protected MobSpawnEvent(Mob mob, ServerLevelAccessor level, double x, double y, double z) {
        super(mob);
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public Mob getEntity() {
        return (Mob) super.getEntity();
    }

    public ServerLevelAccessor getLevel() {
        return this.level;
    }

    public double getX() {
        return this.x;
    }

    public double getY() {
        return this.y;
    }

    public double getZ() {
        return this.z;
    }

    /** Whether an entity type may spawn at a position by its spawn placement rules; the result overrides the game's. */
    public static class SpawnPlacementCheck extends Event {

        private final EntityType<?> entityType;
        private final ServerLevelAccessor level;
        private final EntitySpawnReason spawnType;
        private final BlockPos pos;
        private final RandomSource random;
        private final boolean defaultResult;
        private Result result = Result.DEFAULT;

        public SpawnPlacementCheck(EntityType<?> entityType, ServerLevelAccessor level, EntitySpawnReason spawnType, BlockPos pos, RandomSource random, boolean defaultResult) {
            this.entityType = entityType;
            this.level = level;
            this.spawnType = spawnType;
            this.pos = pos;
            this.random = random;
            this.defaultResult = defaultResult;
        }

        public EntityType<?> getEntityType() {
            return this.entityType;
        }

        public ServerLevelAccessor getLevel() {
            return this.level;
        }

        public EntitySpawnReason getSpawnType() {
            return this.spawnType;
        }

        public BlockPos getPos() {
            return this.pos;
        }

        public RandomSource getRandom() {
            return this.random;
        }

        public boolean getDefaultResult() {
            return this.defaultResult;
        }

        public void setResult(Result result) {
            this.result = result;
        }

        public Result getResult() {
            return this.result;
        }

        public boolean getPlacementCheckResult() {
            if (this.result == Result.SUCCEED) {
                return true;
            }
            return this.result == Result.DEFAULT && this.getDefaultResult();
        }

        public static enum Result {
            SUCCEED,
            DEFAULT,
            FAIL;
        }
    }

    /** Whether a mob may spawn where it stands; by default its spawn rules and obstruction check decide. */
    public static class PositionCheck extends MobSpawnEvent {

        private final BaseSpawner spawner;
        private final EntitySpawnReason spawnType;
        private Result result = Result.DEFAULT;

        public PositionCheck(Mob mob, ServerLevelAccessor level, EntitySpawnReason spawnType, BaseSpawner spawner) {
            super(mob, level, mob.getX(), mob.getY(), mob.getZ());
            this.spawnType = spawnType;
            this.spawner = spawner;
        }

        public BaseSpawner getSpawner() {
            return spawner;
        }

        public EntitySpawnReason getSpawnType() {
            return this.spawnType;
        }

        public void setResult(Result result) {
            this.result = result;
        }

        public Result getResult() {
            return this.result;
        }

        public static enum Result {
            SUCCEED,
            DEFAULT,
            FAIL;
        }
    }
}
