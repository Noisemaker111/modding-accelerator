package ${package};

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.village.VillagerTradesEvent, posted on the game bus for each villager
 * profession as the server starts (TradeEvents), with the game's own trades by level (1 to 5) to add to or change;
 * the result is the game's trade table for that profession, rebuilt from the game's own each time.
 */
public class VillagerTradesEvent extends Event {

    private final Int2ObjectMap<List<VillagerTrades.ItemListing>> trades;
    private final VillagerProfession type;

    public VillagerTradesEvent(Int2ObjectMap<List<VillagerTrades.ItemListing>> trades, VillagerProfession type) {
        this.trades = trades;
        this.type = type;
    }

    public Int2ObjectMap<List<VillagerTrades.ItemListing>> getTrades() {
        return trades;
    }

    public VillagerProfession getType() {
        return type;
    }
}
