package ${package}.mixin;

import ${package}.CommonHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's ServerPlayerGameMode patch: destroyBlock posts BlockEvent.BreakEvent (CommonHooks.fireBlockBreak)
 * first, in place of the game's Item.canAttackBlock check (which the event's pre-cancel includes); when it is cancelled
 * the block stays and the client is sent it again.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class BlockBreakMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    private GameType gameModeForPlayer;

    @WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;canAttackBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Z"))
    private boolean neoforgeBridge$breakEvent(Item item, BlockState state, Level level, BlockPos pos, Player player, Operation<Boolean> canAttack) {
        return !CommonHooks.fireBlockBreak(this.level, this.gameModeForPlayer, this.player, pos, state).isCanceled();
    }
}
