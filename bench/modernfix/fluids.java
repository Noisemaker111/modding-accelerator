import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
// Forge's fluid pushing on frozen input, on the server thread: a fixed rig 300 high over the player (still water, a
// flowing-water slope, lava) and armor stands in it, each run through updateFluidHeightAndDoFluidPushing() once from rest.
// Returns every stand's water and lava height and push vector: the same text in every launch of both arms.
var server = mc.m_91092_();
return server.m_18691_(() -> {
    var level = server.m_129783_();
    var player = server.m_6846_().m_11314_().get(0);
    int x = (int) Math.floor(player.m_20185_()) + 4, z = (int) Math.floor(player.m_20189_()), y = 300;
    for (int dx = -2; dx <= 6; dx++) for (int dy = -1; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++)
        level.m_7731_(new BlockPos(x + dx, y + dy, z + dz), Blocks.f_50016_.m_49966_(), 2 | 16);
    for (int dz = -1; dz <= 1; dz++) {
        level.m_7731_(new BlockPos(x, y, z + dz), Blocks.f_49990_.m_49966_(), 2 | 16);
        for (int step = 1; step <= 3; step++)
            level.m_7731_(new BlockPos(x + step, y, z + dz), Blocks.f_49990_.m_49966_().m_61124_(LiquidBlock.f_54688_, step * 2), 2 | 16);
        level.m_7731_(new BlockPos(x + 5, y, z + dz), Blocks.f_49991_.m_49966_(), 2 | 16);
    }
    StringBuilder out = new StringBuilder();
    double[][] spots = {{0.5, 0}, {1.5, 0}, {2.5, 0.2}, {3.3, 0}, {4.5, 0}, {5.5, 0}, {5.5, 0.6}};
    var water = net.minecraft.tags.FluidTags.f_13131_;
    var lava = net.minecraft.tags.FluidTags.f_13132_;
    for (double[] spot : spots) {
        ArmorStand stand = new ArmorStand(level, x + spot[0], y + spot[1], z + 0.5);
        stand.updateFluidHeightAndDoFluidPushing();
        var v = stand.m_20184_();
        out.append(String.format(java.util.Locale.ROOT, "%.3f,%.2f: water %.6f lava %.6f push %.9f %.9f %.9f%n",
            spot[0], spot[1], stand.m_204036_(water), stand.m_204036_(lava), v.f_82479_, v.f_82480_, v.f_82481_));
    }
    return out.toString();
}).get();
