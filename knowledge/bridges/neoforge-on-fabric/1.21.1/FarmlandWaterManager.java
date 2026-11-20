package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.FarmlandWaterManager: the tickets other mods hand out to keep farmland
 * moist without water nearby (a sprinkler). Nothing hands them out on Fabric, so no block has one.
 */
public final class FarmlandWaterManager {

    private FarmlandWaterManager() {}

    public static boolean hasBlockWaterTicket(LevelReader level, BlockPos pos) {
        return false;
    }
}
