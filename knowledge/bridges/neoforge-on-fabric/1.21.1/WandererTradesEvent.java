package ${package};

import java.util.List;
import net.minecraft.world.entity.npc.VillagerTrades;

/** NeoForge 21.1's net.neoforged.neoforge.event.village.WandererTradesEvent: the wandering trader's common and rare trades, posted with the villagers' (TradeEvents). */
public class WandererTradesEvent extends Event {

    private final List<VillagerTrades.ItemListing> generic;
    private final List<VillagerTrades.ItemListing> rare;

    public WandererTradesEvent(List<VillagerTrades.ItemListing> generic, List<VillagerTrades.ItemListing> rare) {
        this.generic = generic;
        this.rare = rare;
    }

    public List<VillagerTrades.ItemListing> getGenericTrades() {
        return generic;
    }

    public List<VillagerTrades.ItemListing> getRareTrades() {
        return rare;
    }
}
