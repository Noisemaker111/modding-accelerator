package ${package}.extensions;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * What NeoForge 21.1 adds to GuiGraphics that mods call: drawString at a float position (drawn as the game's own
 * drawString draws, without rounding the position) and the tooltips that name their item stack (which on NeoForge only
 * reach RenderTooltipEvent, not posted on 1.21.1 Fabric, so drawn as the game's tooltip of those lines). Added by
 * GuiGraphicsMixin, and for the compile by Loom's interface injection.
 */
public interface IGuiGraphicsExtension {

    private GuiGraphics self() {
        return (GuiGraphics) (Object) this;
    }

    default int drawString(Font font, String text, float x, float y, int color, boolean dropShadow) {
        if (text == null) return 0;
        int width = font.drawInBatch(text, x, y, color, dropShadow, self().pose().last().pose(), self().bufferSource(), Font.DisplayMode.NORMAL, 0, 15728880, font.isBidirectional());
        ((GuiGraphicsAccess) this).neoforgeBridge$flush();
        return width;
    }

    default int drawString(Font font, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
        int width = font.drawInBatch(text, x, y, color, dropShadow, self().pose().last().pose(), self().bufferSource(), Font.DisplayMode.NORMAL, 0, 15728880);
        ((GuiGraphicsAccess) this).neoforgeBridge$flush();
        return width;
    }

    default void renderTooltip(Font font, List<Component> lines, Optional<TooltipComponent> image, ItemStack stack, int x, int y) {
        self().renderTooltip(font, lines, image, x, y);
    }

    default void renderComponentTooltip(Font font, List<? extends FormattedText> lines, int x, int y, ItemStack stack) {
        self().renderTooltip(font, Language.getInstance().getVisualOrder(java.util.List.<FormattedText>copyOf(lines)), x, y);
    }
}
