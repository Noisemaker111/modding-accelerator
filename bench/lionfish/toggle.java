import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
// perf-toggle setup for Lionfish API's fluid standing, on frozen input. A rig 300 high over the player, built on the
// server thread: a stone floor 44 x 9 with a lava pool, a still-water pool and a flowing-water slope at one end. 1,008
// armor stand spots over it (most over plain stone, as most mobs stand; the rest over and beside the fluids), bare and
// in Ignitium Boots. The work puts each stand back on its spot and moves it down once through Entity.move, and returns
// a hash of every stand's height, ground flag and fall distance, so any change in behaviour changes the hash.
var server = mc.m_91092_();
var level = server.m_129783_();
var player = server.m_6846_().m_11314_().get(0);
int x = (int) Math.floor(player.m_20185_()) + 4, z = (int) Math.floor(player.m_20189_()), y = 300;
server.m_18691_(() -> {
    for (int dx = -3; dx <= 44; dx++) for (int dy = -2; dy <= 4; dy++) for (int dz = -5; dz <= 5; dz++)
        level.m_7731_(new BlockPos(x + dx, y + dy, z + dz), Blocks.f_50016_.m_49966_(), 2 | 16);
    for (int dx = -2; dx <= 43; dx++) for (int dz = -4; dz <= 4; dz++)
        level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_50069_.m_49966_(), 2 | 16);
    for (int dz = -1; dz <= 1; dz++) {
        for (int dx = 0; dx <= 2; dx++) level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_49991_.m_49966_(), 2 | 16);
        for (int dx = 4; dx <= 6; dx++) level.m_7731_(new BlockPos(x + dx, y - 1, z + dz), Blocks.f_49990_.m_49966_(), 2 | 16);
        for (int step = 1; step <= 3; step++)
            level.m_7731_(new BlockPos(x + 7 + step, y - 1, z + dz), Blocks.f_49990_.m_49966_().m_61124_(LiquidBlock.f_54688_, step * 2), 2 | 16);
    }
    return true;
}).get();
var boots = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("cataclysm", "ignitium_boots"));
List<ArmorStand> stands = new ArrayList<>();
List<double[]> spots = new ArrayList<>();
for (int booted = 0; booted <= 1; booted++) for (int i = 0; i < 168; i++) for (double h : new double[] { 0.0, 0.3, -0.4 }) {
    double sx = 0.5 + (i % 42) * 0.97, sz = -3.5 + (i / 42) * 2.3;
    ArmorStand stand = new ArmorStand(level, x + sx, y + h, z + sz);
    if (booted == 1) stand.m_8061_(EquipmentSlot.FEET, new ItemStack(boots));
    stands.add(stand);
    spots.add(new double[] { x + sx, y + h, z + sz });
}
var self = net.minecraft.world.entity.MoverType.values()[0];
var down = new Vec3(0, -0.2, 0);
java.util.function.Supplier<Object> work = () -> {
    long hash = 17;
    for (int round = 0; round < 10; round++) for (int i = 0; i < stands.size(); i++) {
        ArmorStand stand = stands.get(i);
        double[] spot = spots.get(i);
        stand.m_6034_(spot[0], spot[1], spot[2]);
        stand.m_20256_(Vec3.f_82478_);
        stand.m_6853_(false);
        stand.f_19789_ = 0;
        stand.m_6478_(self, down);
        if (round == 9) hash = hash * 31 + Double.hashCode(stand.m_20186_()) * 7 + (stand.m_20096_() ? 3 : 5) + Float.hashCode(stand.f_19789_);
    }
    return hash;
};
vars.put("perf.work", work);
return stands.size() + " stands, boots " + boots;
