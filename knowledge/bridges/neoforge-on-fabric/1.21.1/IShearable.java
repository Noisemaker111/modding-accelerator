package ${package};

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.IShearable: a block or entity that shears can shear, with NeoForge's
 * defaults (shearable, nothing dropped). On Fabric the game's shears ask the game's own Shearable for entities only.
 */
public interface IShearable {

    default boolean isShearable(@Nullable Player player, ItemStack item, Level level, BlockPos pos) {
        return true;
    }

    default List<ItemStack> onSheared(@Nullable Player player, ItemStack item, Level level, BlockPos pos) {
        return List.of();
    }
}
