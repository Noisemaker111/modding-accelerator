package ${package};

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * NeoForge's net.neoforged.neoforge.common.tooltip.ItemTooltipHandler, which NeoForge's patch to ItemStack's
 * addDetailsToTooltip calls in three parts. The middle part is the stack's data component lines (with durability when
 * advanced): on Fabric, the game's addDetailsToTooltip without the item's own hover text before them and the advanced
 * id and component count lines after. Head and tail hold only what mods register with NeoForge, nothing on Fabric.
 */
public final class ItemTooltipHandler {

    private ItemTooltipHandler() {}

    public static void addDetailsToTooltipHead(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Player player, TooltipFlag flag, Consumer<Component> builder) {}

    public static void addDetailsToTooltipMiddle(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Player player, TooltipFlag flag, Consumer<Component> builder) {
        List<Component> own = new ArrayList<>();
        stack.getItem().appendHoverText(stack, context, display, own::add, flag);
        List<Component> all = new ArrayList<>();
        stack.addDetailsToTooltip(context, display, player, flag, all::add);
        int end = all.size() - (flag.isAdvanced() ? (stack.getComponents().isEmpty() ? 1 : 2) : 0);
        for (int i = own.size(); i < end; i++) builder.accept(all.get(i));
    }

    public static void addDetailsToTooltipTail(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Player player, TooltipFlag flag, Consumer<Component> builder) {}
}
