package ${package};

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import org.joml.Vector2ic;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.client.ClientHooks: the static methods NeoForge's client patches call to post its
 * client events. Each one here posts the same event and returns the same result as NeoForge's; the bridge's client mixins
 * call them from the points NeoForge's patches do. The screen hooks NeoForge writes into its patches inline (Init,
 * Render, Opening, Closing) are methods here too. It also keeps the open screen, from Fabric API's screen events, which
 * KeyConflictContext.GUI reads (the game keeps it on Minecraft before 26.3 and on Gui since).
 */
public final class ClientHooks {

    /** The screen Fabric API's ScreenEvents last initialised and did not remove, or null. */
    static @Nullable Screen screen;

    private ClientHooks() {}

    /** The open screen (bridge-only): the one Fabric API's ScreenEvents last initialised and did not remove, or null. */
    public static @Nullable Screen currentScreen() {
        return screen;
    }

    public static boolean onScreenInitPre(Screen screen, List<GuiEventListener> listeners, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
        return Bus.INSTANCE.post(new ScreenEvent.Init.Pre(screen, listeners, add, remove)).isCanceled();
    }

    public static void onScreenInitPost(Screen screen, List<GuiEventListener> listeners, Consumer<GuiEventListener> add, Consumer<GuiEventListener> remove) {
        Bus.INSTANCE.post(new ScreenEvent.Init.Post(screen, listeners, add, remove));
    }

    public static boolean onScreenRenderPre(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        return Bus.INSTANCE.post(new ScreenEvent.Render.Pre(screen, guiGraphics, mouseX, mouseY, partialTick)).isCanceled();
    }

    public static void onScreenRenderBackground(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        Bus.INSTANCE.post(new ScreenEvent.Render.Background(screen, guiGraphics, mouseX, mouseY, partialTick));
    }

    public static void onScreenRenderForeground(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        Bus.INSTANCE.post(new ScreenEvent.Render.Foreground(screen, guiGraphics, mouseX, mouseY, partialTick));
    }

    public static void onScreenRenderPost(Screen screen, GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        Bus.INSTANCE.post(new ScreenEvent.Render.Post(screen, guiGraphics, mouseX, mouseY, partialTick));
    }

    /** ScreenEvent.Opening for a screen about to open over the current one; its new screen is what opens unless cancelled. */
    public static ScreenEvent.Opening onScreenOpening(@Nullable Screen current, Screen screen) {
        return Bus.INSTANCE.post(new ScreenEvent.Opening(current, screen));
    }

    public static void onScreenClosing(Screen screen) {
        Bus.INSTANCE.post(new ScreenEvent.Closing(screen));
    }

    public static boolean onScreenMouseClickedPre(Screen guiScreen, MouseButtonEvent mouseEvent, boolean doubleClick) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonPressed.Pre(guiScreen, mouseEvent, doubleClick)).isCanceled();
    }

    public static boolean onScreenMouseClickedPost(Screen guiScreen, MouseButtonEvent mouseEvent, boolean doubleClick, boolean handled) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonPressed.Post(guiScreen, mouseEvent, doubleClick, handled)).getClickResult();
    }

    public static boolean onScreenMouseReleasedPre(Screen guiScreen, MouseButtonEvent mouseEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonReleased.Pre(guiScreen, mouseEvent)).isCanceled();
    }

    public static boolean onScreenMouseReleasedPost(Screen guiScreen, MouseButtonEvent mouseEvent, boolean handled) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseButtonReleased.Post(guiScreen, mouseEvent, handled)).getReleaseResult();
    }

    public static boolean onScreenMouseDragPre(Screen guiScreen, MouseButtonEvent mouseEvent, double dragX, double dragY) {
        return Bus.INSTANCE.post(new ScreenEvent.MouseDragged.Pre(guiScreen, mouseEvent, dragX, dragY)).isCanceled();
    }

    public static void onScreenMouseDragPost(Screen guiScreen, MouseButtonEvent mouseEvent, double dragX, double dragY) {
        Bus.INSTANCE.post(new ScreenEvent.MouseDragged.Post(guiScreen, mouseEvent, dragX, dragY));
    }

    public static boolean onScreenMouseScrollPre(MouseHandler mouseHelper, Screen guiScreen, double scrollDeltaX, double scrollDeltaY) {
        Window mainWindow = Minecraft.getInstance().getWindow();
        double mouseX = mouseHelper.xpos() * (double) mainWindow.getGuiScaledWidth() / (double) mainWindow.getScreenWidth();
        double mouseY = mouseHelper.ypos() * (double) mainWindow.getGuiScaledHeight() / (double) mainWindow.getScreenHeight();
        return Bus.INSTANCE.post(new ScreenEvent.MouseScrolled.Pre(guiScreen, mouseX, mouseY, scrollDeltaX, scrollDeltaY)).isCanceled();
    }

    public static void onScreenMouseScrollPost(MouseHandler mouseHelper, Screen guiScreen, double scrollDeltaX, double scrollDeltaY) {
        Window mainWindow = Minecraft.getInstance().getWindow();
        double mouseX = mouseHelper.xpos() * (double) mainWindow.getGuiScaledWidth() / (double) mainWindow.getScreenWidth();
        double mouseY = mouseHelper.ypos() * (double) mainWindow.getGuiScaledHeight() / (double) mainWindow.getScreenHeight();
        Bus.INSTANCE.post(new ScreenEvent.MouseScrolled.Post(guiScreen, mouseX, mouseY, scrollDeltaX, scrollDeltaY));
    }

    public static boolean onScreenKeyPressedPre(Screen guiScreen, KeyEvent keyEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyPressed.Pre(guiScreen, keyEvent)).isCanceled();
    }

    public static boolean onScreenKeyPressedPost(Screen guiScreen, KeyEvent keyEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyPressed.Post(guiScreen, keyEvent)).isCanceled();
    }

    public static boolean onScreenKeyReleasedPre(Screen guiScreen, KeyEvent keyEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyReleased.Pre(guiScreen, keyEvent)).isCanceled();
    }

    public static boolean onScreenKeyReleasedPost(Screen guiScreen, KeyEvent keyEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.KeyReleased.Post(guiScreen, keyEvent)).isCanceled();
    }

    public static boolean onScreenCharTypedPre(Screen guiScreen, CharacterEvent charEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.CharacterTyped.Pre(guiScreen, charEvent)).isCanceled();
    }

    public static void onScreenCharTypedPost(Screen guiScreen, CharacterEvent charEvent) {
        Bus.INSTANCE.post(new ScreenEvent.CharacterTyped.Post(guiScreen, charEvent));
    }

    public static boolean onScreenPreeditPre(Screen guiScreen, @Nullable PreeditEvent preeditEvent) {
        return Bus.INSTANCE.post(new ScreenEvent.Preedit.Pre(guiScreen, preeditEvent)).isCanceled();
    }

    public static void onScreenPreeditPost(Screen guiScreen, @Nullable PreeditEvent preeditEvent) {
        Bus.INSTANCE.post(new ScreenEvent.Preedit.Post(guiScreen, preeditEvent));
    }

    public static boolean onMouseButtonPre(MouseButtonInfo mouseButtonInfo, int action) {
        return Bus.INSTANCE.post(new InputEvent.MouseButton.Pre(mouseButtonInfo, action)).isCanceled();
    }

    public static void onMouseButtonPost(MouseButtonInfo mouseButtonInfo, int action) {
        Bus.INSTANCE.post(new InputEvent.MouseButton.Post(mouseButtonInfo, action));
    }

    public static boolean onMouseScroll(MouseHandler mouseHelper, double scrollDeltaX, double scrollDeltaY, Vector2ic accumulatedScroll) {
        var event = new InputEvent.MouseScrollingEvent(scrollDeltaX, scrollDeltaY, accumulatedScroll, mouseHelper.isLeftPressed(), mouseHelper.isMiddlePressed(), mouseHelper.isRightPressed(), mouseHelper.xpos(), mouseHelper.ypos());
        return Bus.INSTANCE.post(event).isCanceled();
    }

    public static void onKeyInput(KeyEvent keyEvent, int action) {
        Bus.INSTANCE.post(new InputEvent.Key(keyEvent, action));
    }

    public static CustomizeGuiOverlayEvent.BossEventProgress onCustomizeBossEventProgress(GuiGraphicsExtractor guiGraphics, Window window, LerpingBossEvent bossInfo, int x, int y, int increment) {
        var event = new CustomizeGuiOverlayEvent.BossEventProgress(window, guiGraphics, Minecraft.getInstance().getDeltaTracker(), bossInfo, x, y, increment);
        return Bus.INSTANCE.post(event);
    }

    public static void firePlayerLogin(MultiPlayerGameMode pc, LocalPlayer player, Connection networkManager) {
        Bus.INSTANCE.post(new ClientPlayerNetworkEvent.LoggingIn(pc, player, networkManager));
    }

    public static void firePlayerLogout(@Nullable MultiPlayerGameMode pc, @Nullable LocalPlayer player, @Nullable Connection networkManager) {
        Bus.INSTANCE.post(new ClientPlayerNetworkEvent.LoggingOut(pc, player, networkManager));
    }
}
