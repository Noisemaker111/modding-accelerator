package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NeoForge's trade events change the game's trade tables as NeoForge's do: a villager trade added for a profession's
 * level and a wandering trader trade are in the tables after the events, and posting again starts from the game's own
 * trades (a server's second start does not add them twice).
 */
class TradeEventsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tradesJoinTheGamesTables() {
        int farmerLevelOne = VillagerTrades.TRADES.get(VillagerProfession.FARMER).get(1).length;
        int wanderer = VillagerTrades.WANDERING_TRADER_TRADES.get(1).length;
        BasicItemListing pie = new BasicItemListing(3, new ItemStack(Items.PUMPKIN_PIE), 12, 2);
        BasicItemListing apple = new BasicItemListing(1, new ItemStack(Items.APPLE), 8, 1);
        Bus.INSTANCE.addListener(VillagerTradesEvent.class, event -> {
            if (event.getType() == VillagerProfession.FARMER) event.getTrades().get(1).add(pie);
        });
        Bus.INSTANCE.addListener(WandererTradesEvent.class, event -> event.getGenericTrades().add(apple));

        TradeEvents.post();
        TradeEvents.post();

        VillagerTrades.ItemListing[] farmer = VillagerTrades.TRADES.get(VillagerProfession.FARMER).get(1);
        assertEquals(farmerLevelOne + 1, farmer.length);
        assertTrue(Arrays.asList(farmer).contains(pie));
        assertEquals(wanderer + 1, VillagerTrades.WANDERING_TRADER_TRADES.get(1).length);
        assertTrue(Arrays.asList(VillagerTrades.WANDERING_TRADER_TRADES.get(1)).contains(apple));
    }
}
