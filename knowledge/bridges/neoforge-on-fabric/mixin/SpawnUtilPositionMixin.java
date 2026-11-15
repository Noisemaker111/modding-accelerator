package ${package}.mixin;

import ${package}.EventHooks;
import ${package}.MobSpawnEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.util.SpawnUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's SpawnUtil patch: where SpawnUtil.trySpawnMob (golems, wardens, raids) checks
 * mob.checkSpawnRules(...) && mob.checkSpawnObstruction(...), MobSpawnEvent.PositionCheck decides (EventHooks.
 * checkSpawnPosition): SUCCEED spawns, FAIL does not, DEFAULT keeps the mob's own two checks.
 */
@Mixin(SpawnUtil.class)
abstract class SpawnUtilPositionMixin {

    @WrapOperation(method = "trySpawnMob", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;checkSpawnRules(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;)Z"))
    private static boolean neoforgeBridge$positionCheck(Mob mob, LevelAccessor level, EntitySpawnReason spawnReason, Operation<Boolean> spawnRules, @Share("position") LocalRef<MobSpawnEvent.PositionCheck.Result> result) {
        MobSpawnEvent.PositionCheck.Result answer = EventHooks.firePositionCheck(mob, level, spawnReason);
        result.set(answer);
        if (answer == MobSpawnEvent.PositionCheck.Result.DEFAULT) return spawnRules.call(mob, level, spawnReason);
        return answer == MobSpawnEvent.PositionCheck.Result.SUCCEED;
    }

    @WrapOperation(method = "trySpawnMob", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;checkSpawnObstruction(Lnet/minecraft/world/level/LevelReader;)Z"))
    private static boolean neoforgeBridge$obstruction(Mob mob, LevelReader level, Operation<Boolean> obstruction, @Share("position") LocalRef<MobSpawnEvent.PositionCheck.Result> result) {
        if (result.get() == MobSpawnEvent.PositionCheck.Result.SUCCEED) return true;
        return obstruction.call(mob, level);
    }
}
