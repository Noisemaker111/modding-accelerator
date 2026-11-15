import com.lothrazar.cyclic.block.crafter.TileCrafter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.neoforged.neoforge.items.IItemHandler;
// Reports the live base: crafters whose timer is counting down, items in sinks, in crafter outputs and inputs, game time.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    int previews = 0;
    long sinks = 0;
    long outputs = 0;
    long sources = 0;
    java.util.TreeMap<String, Long> made = new java.util.TreeMap<>();
    for (int r = 0; r < 8; r++) for (int i = 0; i < 16; i++) {
        BlockPos pos = new BlockPos(2 * i, -58, 3 * r);
        TileCrafter crafter = (TileCrafter) level.getBlockEntity(pos);
        IItemHandler inv = crafter.getItemHandler(null);
        for (int s = 10; s < 20; s++) outputs += inv.getStackInSlot(s).getCount();
        Container sink = (Container) level.getBlockEntity(pos.below(2));
        Container src = (Container) level.getBlockEntity(pos.above(2));
        for (int s = 0; s < 27; s++) {
            sinks += sink.getItem(s).getCount();
            sources += src.getItem(s).getCount();
            if (!sink.getItem(s).isEmpty()) made.merge(sink.getItem(s).getItem().toString(), (long) sink.getItem(s).getCount(), Long::sum);
        }
        if (crafter.getField(0) < TileCrafter.TIMER_FULL) previews++;
    }
    return "counting=" + previews + " sinks=" + sinks + " outputs=" + outputs + " sources=" + sources + " time=" + level.getGameTime() + " made=" + made;
}).join();
