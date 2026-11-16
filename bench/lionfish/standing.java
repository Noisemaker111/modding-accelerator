import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
// Lionfish API's fluid standing on frozen input, on the server thread: a fixed rig 300 high over the player (a lava
// pool, a still-water pool, a flowing-water slope, stone between) and armor stands over it at whole, half and odd
// spots, bare and in Cataclysm's Ignitium Boots (which let their wearer stand on lava), each moved down once from rest
// through Entity.move. Returns every stand's height, ground flag and fall distance: the same text in every launch of
// both arms.
var server = mc.m_91092_();
return server.m_18691_(() -> {
    var level = server.m_129783_();
    var player = server.m_6846_().m_11314_().get(0);
    int x = (int) Math.floor(player.m_20185_()) + 4, z = (int) Math.floor(player.m_20189_()), y = 300;
    for (int dx = -3; dx <= 12; dx++) for (int dy = -2; dy <= 4; dy++) for (int dz = -3; dz <= 3; dz++)
        level.m_7731_(new BlockPos(x + dx, y + dy, z + dz), Blocks.f_50016_.m_49966_(), 2 | 16);
    for (int dx = -2; dx <= 11; dx++) for (int dz = -2; dz <= 2; dz++)
        level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_50069_.m_49966_(), 2 | 16);
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = 0; dx <= 2; dx++) level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_49991_.m_49966_(), 2 | 16);
        for (int dx = 4; dx <= 6; dx++) level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_49990_.m_49966_(), 2 | 16);
        for (int step = 1; step <= 3; step++)
            level.m_7731_(new BlockPos(x + 7 + step, y - 1, z + dz), Blocks.f_49990_.m_49966_().m_61124_(LiquidBlock.f_54688_, step * 2), 2 | 16);
    }
    var boots = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("cataclysm", "ignitium_boots"));
    StringBuilder out = new StringBuilder("boots " + boots + "\n");
    double[] xs = { 0.5, 1.0, 1.5, 2.7, 3.0, 3.5, 4.2, 5.5, 6.9, 7.5, 8.5, 9.3, 10.5 };
    double[] zs = { 0.5, 0.0, -0.8, 1.2 };
    double[] heights = { 0.0, 0.3, -0.4 };
    var self = net.minecraft.world.entity.MoverType.values()[0];
    for (int booted = 0; booted <= 1; booted++) for (double sx : xs) for (double sz : zs) for (double h : heights) {
        ArmorStand stand = new ArmorStand(level, x + sx, y + h, z + sz);
        if (booted == 1) stand.m_8061_(EquipmentSlot.FEET, new ItemStack(boots));
        stand.m_6478_(self, new Vec3(0, -0.2, 0));
        out.append(String.format(java.util.Locale.ROOT, "%d %.2f %.2f %.2f: y %.9f ground %b fall %.6f%n",
            booted, sx, sz, h, stand.m_20186_(), stand.m_20096_(), stand.f_19789_));
    }
    return out.toString();
}).get();
