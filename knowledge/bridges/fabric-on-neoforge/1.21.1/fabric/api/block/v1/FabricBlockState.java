package ${package}.fabric.api.block.v1;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric API's FabricBlockState on NeoForge, added to BlockState by the bridge's mixin BlockStateFabricBlockState (with
 * BlockFabricBlock, which it asks). getAppearance is NeoForge's own (IBlockStateExtension).
 */
public interface FabricBlockState {

    /** The enchanting power the state's block gives (FabricBlock#getProvidedEnchantmentPower). */
    default float getProvidedEnchantmentPower(LevelReader level, BlockPos pos) {
        BlockState self = (BlockState) this;
        return ((FabricBlock) self.getBlock()).getProvidedEnchantmentPower(self, level, pos);
    }
}
