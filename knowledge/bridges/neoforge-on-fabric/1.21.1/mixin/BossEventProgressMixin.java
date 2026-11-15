package ${package}.mixin;

import ${package}.ClientHooks;
import ${package}.CustomizeGuiOverlayEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge 21.1's BossHealthOverlay patch: before each boss bar, CustomizeGuiOverlayEvent.BossEventProgress (x, y and an
 * increment of 10 + the font's line height); a cancel skips the bar and its name, and the next bar goes down by the
 * event's increment (the game's own step of 19 is taken back from the y offset, its second int local, before it adds it).
 */
@Mixin(BossHealthOverlay.class)
abstract class BossEventProgressMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V"))
    private void neoforgeBridge$bar(BossHealthOverlay overlay, GuiGraphics graphics, int x, int y, BossEvent bossEvent, Operation<Void> drawBar, @Share("progress") LocalRef<CustomizeGuiOverlayEvent.BossEventProgress> progress) {
        Minecraft minecraft = Minecraft.getInstance();
        progress.set(ClientHooks.onCustomizeBossEventProgress(graphics, minecraft.getWindow(), (LerpingBossEvent) bossEvent, x, y, 10 + minecraft.font.lineHeight));
        if (!progress.get().isCanceled()) drawBar.call(overlay, graphics, x, y, bossEvent);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int neoforgeBridge$name(GuiGraphics graphics, Font font, Component name, int x, int y, int color, Operation<Integer> drawString, @Share("progress") LocalRef<CustomizeGuiOverlayEvent.BossEventProgress> progress, @Local(ordinal = 1) LocalIntRef yOffset) {
        int drawn = progress.get().isCanceled() ? 0 : drawString.call(graphics, font, name, x, y, color);
        yOffset.set(yOffset.get() + progress.get().getIncrement() - 19);
        return drawn;
    }
}
