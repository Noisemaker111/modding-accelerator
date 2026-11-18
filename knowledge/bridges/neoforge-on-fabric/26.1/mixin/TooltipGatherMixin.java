package ${package}.mixin;

import ${package}.RenderTooltipEvent;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 26.1's GuiGraphicsExtractor patch for tooltips of lines and an image: the item whose tooltip it is (set while
 * setTooltipForNextFrame(font, stack, x, y) runs, as NeoForge's tooltipStack), and the components the game makes of the
 * lines replaced by the ones RenderTooltipEvent.GatherComponents leaves (RenderTooltipEvent.gather).
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class TooltipGatherMixin {

    @Unique
    private ItemStack neoforgeBridge$tooltipStack = ItemStack.EMPTY;

    @Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"))
    private void neoforgeBridge$itemTooltip(Font font, ItemStack stack, int x, int y, CallbackInfo info) {
        neoforgeBridge$tooltipStack = stack;
    }

    @Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At("RETURN"))
    private void neoforgeBridge$itemTooltipDone(Font font, ItemStack stack, int x, int y, CallbackInfo info) {
        neoforgeBridge$tooltipStack = ItemStack.EMPTY;
    }

    @ModifyArg(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrameInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;Z)V"),
        index = 1
    )
    private List<ClientTooltipComponent> neoforgeBridge$gather(
        List<ClientTooltipComponent> components,
        @Local(argsOnly = true) Font font,
        @Local(argsOnly = true) List<Component> lines,
        @Local(argsOnly = true) Optional<TooltipComponent> image
    ) {
        GuiGraphicsExtractor graphics = (GuiGraphicsExtractor) (Object) this;
        return RenderTooltipEvent.gather(neoforgeBridge$tooltipStack, lines, image, graphics.guiWidth(), graphics.guiHeight(), font);
    }
}
