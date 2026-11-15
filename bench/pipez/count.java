import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
// Behaviour gate: the base still holds every item it started with (27 stacks of 64 in 8 barrels a
// row, 4 rows: 55296), and items are on both sides of every row, so every pipe line is moving them.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    long total = 0;
    boolean moving = true;
    for (int row = 0; row < 16; row += 4) {
        long odd = 0;
        long even = 0;
        for (int x = 0; x < 16; x++) {
            Container barrel = (Container) level.getBlockEntity(new BlockPos(x, -60, row));
            for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                int count = barrel.getItem(slot).getCount();
                total += count;
                if (x % 2 == 0) even += count;
                else odd += count;
            }
        }
        moving &= odd > 0 && even > 0;
    }
    return Boolean.toString(total == 55296 && moving);
}).join();
