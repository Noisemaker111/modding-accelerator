package ${package}.mixin;

import ${package}.ClientHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 21.1's Screen patch: init(Minecraft, int, int) and rebuildWidgets() post ScreenEvent.Init.Pre before init(),
 * skip init() and setInitialFocus() when it is cancelled, and post Init.Post after them; renderBackground posts
 * BackgroundRendered last. NeoForge 21.1's GameRenderer patch posts Render.Pre and Post around renderWithTooltip, skipping
 * it when Pre is cancelled; here that wraps the method itself.
 */
@Mixin(Screen.class)
abstract class ScreenEventMixin {

    @Shadow
    @Final
    private List<GuiEventListener> children;

    @Shadow
    @Final
    private List<Renderable> renderables;

    @Shadow
    @Final
    private List<NarratableEntry> narratables;

    @Shadow
    protected abstract void removeWidget(GuiEventListener listener);

    @WrapOperation(method = { "init(Lnet/minecraft/client/Minecraft;II)V", "rebuildWidgets()V" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init()V"))
    private void neoforgeBridge$initPre(Screen screen, Operation<Void> init, @Share("canceled") LocalBooleanRef canceled) {
        canceled.set(ClientHooks.onScreenInitPre(screen, children, listener -> neoforgeBridge$add(listener), listener -> removeWidget(listener)));
        if (!canceled.get()) init.call(screen);
    }

    @WrapOperation(method = { "init(Lnet/minecraft/client/Minecraft;II)V", "rebuildWidgets()V" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;setInitialFocus()V"))
    private void neoforgeBridge$initPost(Screen screen, Operation<Void> setInitialFocus, @Share("canceled") LocalBooleanRef canceled) {
        if (!canceled.get()) setInitialFocus.call(screen);
        ClientHooks.onScreenInitPost(screen, children, listener -> neoforgeBridge$add(listener), listener -> removeWidget(listener));
    }

    /** NeoForge's Screen.addEventWidget: a listener the screen draws and narrates when it can. */
    private void neoforgeBridge$add(GuiEventListener listener) {
        if (listener instanceof Renderable renderable) renderables.add(renderable);
        if (listener instanceof NarratableEntry narratable) narratables.add(narratable);
        children.add(listener);
    }

    @WrapMethod(method = "renderWithTooltip")
    private void neoforgeBridge$render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Operation<Void> render) {
        Screen screen = (Screen) (Object) this;
        if (!ClientHooks.onScreenRenderPre(screen, graphics, mouseX, mouseY, partialTick)) render.call(graphics, mouseX, mouseY, partialTick);
        ClientHooks.onScreenRenderPost(screen, graphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void neoforgeBridge$background(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        ClientHooks.onScreenBackgroundRendered((Screen) (Object) this, graphics);
    }
}
