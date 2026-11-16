package ${package};

import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RenderTooltipEvent. GatherComponents is posted by the bridge's
 * TooltipGatherMixin where NeoForge's GuiGraphicsExtractor patch posts it for a tooltip of lines and an optional image
 * (the path item tooltips take), with the item when the tooltip is an item's and an empty stack otherwise: listeners may
 * change the elements, and a cancel shows no tooltip. NeoForge's wrapping of lines wider than the screen (or than
 * setMaxWidth) is not done; the game draws the lines as they are, as it does without NeoForge.
 */
public abstract class RenderTooltipEvent extends Event {

    protected final ItemStack itemStack;

    protected RenderTooltipEvent(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public static class GatherComponents extends RenderTooltipEvent implements ICancellableEvent {
        private final int screenWidth;
        private final int screenHeight;
        private final List<Either<FormattedText, TooltipComponent>> tooltipElements;
        private int maxWidth;

        public GatherComponents(ItemStack itemStack, int screenWidth, int screenHeight, List<Either<FormattedText, TooltipComponent>> tooltipElements, int maxWidth) {
            super(itemStack);
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.tooltipElements = tooltipElements;
            this.maxWidth = maxWidth;
        }

        public int getScreenWidth() {
            return screenWidth;
        }

        public int getScreenHeight() {
            return screenHeight;
        }

        public List<Either<FormattedText, TooltipComponent>> getTooltipElements() {
            return tooltipElements;
        }

        public int getMaxWidth() {
            return maxWidth;
        }

        public void setMaxWidth(int maxWidth) {
            this.maxWidth = maxWidth;
        }
    }

    /**
     * The tooltip's lines and image as NeoForge gathers them: the lines, the image second, GatherComponents posted, and
     * each element made a tooltip component; none when a listener cancels.
     */
    public static List<ClientTooltipComponent> gather(ItemStack stack, List<Component> lines, Optional<TooltipComponent> image, int screenWidth, int screenHeight, Font font) {
        List<Either<FormattedText, TooltipComponent>> elements = new ArrayList<>();
        for (Component line : lines) elements.add(Either.left(line));
        image.ifPresent(i -> elements.add(elements.isEmpty() ? 0 : 1, Either.right(i)));
        GatherComponents event = Bus.INSTANCE.post(new GatherComponents(stack, screenWidth, screenHeight, elements, -1));
        if (event.isCanceled()) return new ArrayList<>();
        List<ClientTooltipComponent> out = new ArrayList<>();
        for (var element : event.getTooltipElements()) out.add(element.map(
            text -> ClientTooltipComponent.create(Language.getInstance().getVisualOrder(text)),
            ClientTooltipComponent::create
        ));
        return out;
    }
}
