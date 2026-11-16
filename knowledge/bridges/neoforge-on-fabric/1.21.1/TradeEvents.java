package ${package};

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;

/**
 * Posts NeoForge's VillagerTradesEvent (each profession) and WandererTradesEvent as the server starts, as NeoForge does
 * when it loads the trades, starting each time from the game's own trades (kept the first time), and puts the result in
 * the game's trade tables (VillagerTrades.TRADES and WANDERING_TRADER_TRADES).
 */
final class TradeEvents {

    private static Map<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> villagers;
    private static Int2ObjectMap<VillagerTrades.ItemListing[]> wanderer;
    static boolean hooked;

    private TradeEvents() {}

    static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> post());
    }

    static synchronized void post() {
        if (villagers == null) {
            villagers = new HashMap<>();
            for (var e : VillagerTrades.TRADES.entrySet()) villagers.put(e.getKey(), new Int2ObjectOpenHashMap<>(e.getValue()));
            wanderer = new Int2ObjectOpenHashMap<>(VillagerTrades.WANDERING_TRADER_TRADES);
        }
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            Int2ObjectMap<VillagerTrades.ItemListing[]> own = villagers.getOrDefault(profession, new Int2ObjectOpenHashMap<>());
            Int2ObjectMap<List<VillagerTrades.ItemListing>> trades = new Int2ObjectOpenHashMap<>();
            for (int level = 1; level <= 5; level++) trades.put(level, new ArrayList<>(List.of(own.getOrDefault(level, new VillagerTrades.ItemListing[0]))));
            Bus.INSTANCE.post(new VillagerTradesEvent(trades, profession));
            Int2ObjectMap<VillagerTrades.ItemListing[]> table = new Int2ObjectOpenHashMap<>();
            for (var level : trades.int2ObjectEntrySet()) table.put(level.getIntKey(), level.getValue().toArray(VillagerTrades.ItemListing[]::new));
            VillagerTrades.TRADES.put(profession, table);
        }
        List<VillagerTrades.ItemListing> generic = new ArrayList<>(List.of(wanderer.getOrDefault(1, new VillagerTrades.ItemListing[0])));
        List<VillagerTrades.ItemListing> rare = new ArrayList<>(List.of(wanderer.getOrDefault(2, new VillagerTrades.ItemListing[0])));
        Bus.INSTANCE.post(new WandererTradesEvent(generic, rare));
        VillagerTrades.WANDERING_TRADER_TRADES.put(1, generic.toArray(VillagerTrades.ItemListing[]::new));
        VillagerTrades.WANDERING_TRADER_TRADES.put(2, rare.toArray(VillagerTrades.ItemListing[]::new));
    }
}
