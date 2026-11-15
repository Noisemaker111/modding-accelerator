package ${package};

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * NeoForge's net.neoforged.neoforge.event.entity.player.ItemTooltipEvent, posted on the client from Fabric's
 * ItemTooltipCallback with the client's player (null on the title screen, as on NeoForge).
 */
public class ItemTooltipEvent extends PlayerEvent {

    private final ItemStack stack;
    private final List<Component> toolTip;
    private final TooltipFlag flags;
    private final Item.TooltipContext context;

    public ItemTooltipEvent(ItemStack stack, Player player, List<Component> toolTip, TooltipFlag flags, Item.TooltipContext context) {
        super(player);
        this.stack = stack;
        this.toolTip = toolTip;
        this.flags = flags;
        this.context = context;
    }

    public TooltipFlag getFlags() {
        return flags;
    }

    public ItemStack getItemStack() {
        return stack;
    }

    public List<Component> getToolTip() {
        return toolTip;
    }

    public Item.TooltipContext getContext() {
        return context;
    }
}
