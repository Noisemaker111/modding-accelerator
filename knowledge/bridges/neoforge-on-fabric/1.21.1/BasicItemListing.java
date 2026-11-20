package ${package};

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import java.util.Optional;

/** NeoForge 21.1's net.neoforged.neoforge.common.BasicItemListing: a trade of fixed items, prices, uses and experience. */
public class BasicItemListing implements VillagerTrades.ItemListing {

    protected final ItemStack price;
    protected final ItemStack price2;
    protected final ItemStack forSale;
    protected final int maxTrades;
    protected final int xp;
    protected final float priceMult;

    public BasicItemListing(ItemStack price, ItemStack price2, ItemStack forSale, int maxTrades, int xp, float priceMult) {
        this.price = price;
        this.price2 = price2;
        this.forSale = forSale;
        this.maxTrades = maxTrades;
        this.xp = xp;
        this.priceMult = priceMult;
    }

    public BasicItemListing(ItemStack price, ItemStack forSale, int maxTrades, int xp, float priceMult) {
        this(price, ItemStack.EMPTY, forSale, maxTrades, xp, priceMult);
    }

    public BasicItemListing(int emeralds, ItemStack forSale, int maxTrades, int xp, float mult) {
        this(new ItemStack(Items.EMERALD, emeralds), forSale, maxTrades, xp, mult);
    }

    public BasicItemListing(int emeralds, ItemStack forSale, int maxTrades, int xp) {
        this(new ItemStack(Items.EMERALD, emeralds), forSale, maxTrades, xp, 1);
    }

    @Override
    public MerchantOffer getOffer(Entity trader, RandomSource random) {
        ItemCost first = new ItemCost(price.getItemHolder(), price.getCount(), net.minecraft.core.component.DataComponentPredicate.EMPTY, price);
        Optional<ItemCost> second = price2.isEmpty() ? Optional.empty() : Optional.of(new ItemCost(price2.getItemHolder(), price2.getCount(), net.minecraft.core.component.DataComponentPredicate.EMPTY, price2));
        return new MerchantOffer(first, second, forSale, maxTrades, xp, priceMult);
    }
}
