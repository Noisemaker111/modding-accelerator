package ${package}.extensions;

import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * NeoForge 21.1's BlockBehaviour.Properties.lootFrom(Supplier): the block drops what another block drops, that block
 * asked when the loot table is first needed (it may not be registered yet when the properties are made). Kept by
 * BlockPropertiesHooksMixin and carried into the block by BlockLootFromMixin; added for the compile by Loom's interface
 * injection.
 */
public interface BlockPropertiesHooks {

    default BlockBehaviour.Properties lootFrom(Supplier<? extends Block> blockIn) {
        ((LootFromAccess) this).neoforgeBridge$lootFrom(blockIn);
        return (BlockBehaviour.Properties) (Object) this;
    }
}
