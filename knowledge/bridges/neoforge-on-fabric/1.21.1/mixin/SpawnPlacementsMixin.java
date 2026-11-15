package ${package}.mixin;

import ${package}.EventHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's SpawnPlacements patch: SpawnPlacements.checkSpawnRules posts MobSpawnEvent.SpawnPlacementCheck with
 * the type's own placement predicate as the default and returns the event's answer.
 */
@Mixin(SpawnPlacements.class)
abstract class SpawnPlacementsMixin {

    @ModifyReturnValue(method = "checkSpawnRules", at = @At("RETURN"))
    private static boolean neoforgeBridge$spawnPlacementCheck(
        boolean vanillaResult,
        @Local(argsOnly = true) EntityType<?> type,
        @Local(argsOnly = true) ServerLevelAccessor level,
        @Local(argsOnly = true) MobSpawnType spawnReason,
        @Local(argsOnly = true) BlockPos pos,
        @Local(argsOnly = true) RandomSource random
    ) {
        return EventHooks.checkSpawnPlacements(type, level, spawnReason, pos, random, vanillaResult);
    }
}
