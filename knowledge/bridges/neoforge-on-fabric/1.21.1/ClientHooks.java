package ${package};

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.ClientHooks: the static methods NeoForge's client patches call to post
 * its client events. Each one here posts the same event and returns the same result as NeoForge's; the bridge's client
 * mixins and Fabric API callbacks call them. The screen hooks NeoForge writes into its patches inline (Init, Render,
 * BackgroundRendered, Opening, Closing) are methods here too, and it keeps the open screen from Fabric API's screen events.
 */
public final class ClientHooks {

    /** The screen Fabric API's ScreenEvents last initialised and did not remove, or null. */
    static Screen screen;

    private ClientHooks() {}

    /** The open screen (bridge-only): the one Fabric API's ScreenEvents last initialised and did not remove, or null. */
    public static Screen currentScreen() {
        return screen;
    }

    public static boolean onScreenInitPre(Screen screen, List<GuiEventListener> listeners, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
        return Bus.INSTANCE.post(new ScreenEvent.Init.Pre(screen, listeners, add, remove)).isCanceled();
    }

    public static void onScreenInitPost(Screen screen, List<GuiEventListener> listeners, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
        Bus.INSTANCE.post(new ScreenEvent.Init.Post(screen, listeners, add, remove));
    }

    public static boolean onScreenRenderPre(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        return Bus.INSTANCE.post(new ScreenEvent.Render.Pre(screen, guiGraphics, mouseX, mouseY, partialTick)).isCanceled();
    }

    public static void onScreenRenderPost(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Bus.INSTANCE.post(new ScreenEvent.Render.Post(screen, guiGraphics, mouseX, mouseY, partialTick));
    }

    public static void onScreenBackgroundRendered(Screen screen, GuiGraphics guiGraphics) {
        Bus.INSTANCE.post(new ScreenEvent.BackgroundRendered(screen, guiGraphics));
    }

    /** ScreenEvent.Opening for a screen about to open over the current one; its new screen is what opens unless cancelled. */
    public static ScreenEvent.Opening onScreenOpening(Screen current, Screen screen) {
        return Bus.INSTANCE.post(new ScreenEvent.Opening(current, screen));
    }

    public static void onScreenClosing(Screen screen) {
        Bus.INSTANCE.post(new ScreenEvent.Closing(screen));
    }

    public static boolean onScreenMouseClickedPre(Screen guiScreen, double mouseX, double mouseY, int button) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonPressed.Pre(guiScreen, mouseX, mouseY, button)).isCanceled();
    }

    public static boolean onScreenMouseClickedPost(Screen guiScreen, double mouseX, double mouseY, int button, boolean handled) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonPressed.Post(guiScreen, mouseX, mouseY, button, handled)).getClickResult();
    }

    public static boolean onScreenMouseReleasedPre(Screen guiScreen, double mouseX, double mouseY, int button) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonReleased.Pre(guiScreen, mouseX, mouseY, button)).isCanceled();
    }

    public static boolean onScreenMouseReleasedPost(Screen guiScreen, double mouseX, double mouseY, int button, boolean handled) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonReleased.Post(guiScreen, mouseX, mouseY, button, handled)).getReleaseResult();
    }

    /** Bridge-only signature: Fabric API's callback hands the scaled mouse position, which NeoForge computes from the MouseHandler. */
    public static boolean onScreenMouseScrollPre(Screen guiScreen, double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseScrolled.Pre(guiScreen, mouseX, mouseY, scrollDeltaX, scrollDeltaY)).isCanceled();
    }

    public static void onScreenMouseScrollPost(Screen guiScreen, double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        Bus.INSTANCE.post(new ScreenEvent.MouseScrolled.Post(guiScreen, mouseX, mouseY, scrollDeltaX, scrollDeltaY));
    }

    public static boolean onScreenKeyPressedPre(Screen guiScreen, int keyCode, int scanCode, int modifiers) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyPressed.Pre(guiScreen, keyCode, scanCode, modifiers)).isCanceled();
    }

    public static boolean onScreenKeyPressedPost(Screen guiScreen, int keyCode, int scanCode, int modifiers) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyPressed.Post(guiScreen, keyCode, scanCode, modifiers)).isCanceled();
    }

    public static boolean onScreenKeyReleasedPre(Screen guiScreen, int keyCode, int scanCode, int modifiers) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyReleased.Pre(guiScreen, keyCode, scanCode, modifiers)).isCanceled();
    }

    public static boolean onScreenKeyReleasedPost(Screen guiScreen, int keyCode, int scanCode, int modifiers) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyReleased.Post(guiScreen, keyCode, scanCode, modifiers)).isCanceled();
    }

    public static boolean onMouseButtonPre(int button, int action, int mods) {
        return Bus.INSTANCE.post(new InputEvent.MouseButton.Pre(button, action, mods)).isCanceled();
    }

    public static void onMouseButtonPost(int button, int action, int mods) {
        Bus.INSTANCE.post(new InputEvent.MouseButton.Post(button, action, mods));
    }

    public static boolean onMouseScroll(MouseHandler mouseHelper, double scrollDeltaX, double scrollDeltaY) {
        var event = new InputEvent.MouseScrollingEvent(scrollDeltaX, scrollDeltaY, mouseHelper.isLeftPressed(), mouseHelper.isMiddlePressed(), mouseHelper.isRightPressed(), mouseHelper.xpos(), mouseHelper.ypos());
        return Bus.INSTANCE.post(event).isCanceled();
    }

    public static void onKeyInput(int key, int scanCode, int action, int modifiers) {
        Bus.INSTANCE.post(new InputEvent.Key(key, scanCode, action, modifiers));
    }

    public static CustomizeGuiOverlayEvent.BossEventProgress onCustomizeBossEventProgress(GuiGraphics guiGraphics, Window window, LerpingBossEvent bossInfo, int x, int y, int increment) {
        var event = new CustomizeGuiOverlayEvent.BossEventProgress(window, guiGraphics, Minecraft.getInstance().getTimer(), bossInfo, x, y, increment);
        return Bus.INSTANCE.post(event);
    }

    public static void firePlayerLogin(MultiPlayerGameMode pc, LocalPlayer player, Connection networkManager) {
        Bus.INSTANCE.post(new ClientPlayerNetworkEvent.LoggingIn(pc, player, networkManager));
    }

    public static void firePlayerLogout(MultiPlayerGameMode pc, LocalPlayer player, Connection networkManager) {
        Bus.INSTANCE.post(new ClientPlayerNetworkEvent.LoggingOut(pc, player, networkManager));
    }
}
