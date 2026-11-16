package ${package}.fabric.api.block.v1;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric API's FabricBlock on NeoForge, added to Block by the bridge's mixin BlockFabricBlock. getAppearance is
 * NeoForge's own (IBlockExtension has it with the same parameters).
 */
public interface FabricBlock {

    /**
     * The enchanting power the block gives a nearby table: NeoForge's getEnchantPowerBonus, which is what NeoForge's
     * enchanting table counts (1 for blocks in #minecraft:enchantment_power_provider by default). Fabric answers 1 for a
     * block outside that tag, which its table never asks; here such a block answers 0, as NeoForge counts it. A converted
     * block that overrides this method is not counted by NeoForge: override getEnchantPowerBonus instead.
     */
    default float getProvidedEnchantmentPower(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return ((Block) this).getEnchantPowerBonus(state, level, pos);
    }
}
