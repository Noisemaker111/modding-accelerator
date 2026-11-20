package ${package}.extensions;

import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;

/** The block whose loot a Properties or a block takes (NeoForge's lootFrom), as the bridge's mixins keep it. */
public interface LootFromAccess {

    Supplier<? extends Block> neoforgeBridge$lootFrom();

    void neoforgeBridge$lootFrom(Supplier<? extends Block> block);
}
