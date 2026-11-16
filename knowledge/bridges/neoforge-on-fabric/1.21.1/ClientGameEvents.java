package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * NeoForge's client events, each posted from the Fabric API callback that fires at the same moment; the events Fabric API
 * has no callback for are posted by the bridge's client mixins through ClientHooks. On 1.21.1 a screen's key and mouse
 * events come from Fabric API's per-screen callbacks (see ScreenEvent).
 */
final class ClientGameEvents {

    private ClientGameEvents() {}

    static void hook() {
        ClientTickEvents.START_WORLD_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Pre(() -> true, level)));
        ClientTickEvents.END_WORLD_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Post(() -> true, level)));
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> Bus.INSTANCE.post(new ItemTooltipEvent(stack, Minecraft.getInstance().player, lines, flag, context)));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ClientHooks.screen = screen;
            ScreenEvents.remove(screen).register(removed -> {
                if (ClientHooks.screen == removed) ClientHooks.screen = null;
            });
            screenInput(screen);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHooks.firePlayerLogin(client.gameMode, client.player, handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientHooks.firePlayerLogout(client.gameMode, client.player, handler.getConnection()));
        RegisterGuiLayersEvent.hook();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof ${package}.extensions.BlockEntityHooks hooks) hooks.onLoad();
        });
        RegisterClientCommandsEvent.hook();
    }

    /** ScreenEvent's key and mouse events of one screen, from Fabric API's allow (Pre) and after (Post) callbacks. */
    static void screenInput(Screen screen) {
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> !ClientHooks.onScreenKeyPressedPre(s, key, scancode, modifiers));
        ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, modifiers) -> ClientHooks.onScreenKeyPressedPost(s, key, scancode, modifiers));
        ScreenKeyboardEvents.allowKeyRelease(screen).register((s, key, scancode, modifiers) -> !ClientHooks.onScreenKeyReleasedPre(s, key, scancode, modifiers));
        ScreenKeyboardEvents.afterKeyRelease(screen).register((s, key, scancode, modifiers) -> ClientHooks.onScreenKeyReleasedPost(s, key, scancode, modifiers));
        ScreenMouseEvents.allowMouseClick(screen).register((s, x, y, button) -> !ClientHooks.onScreenMouseClickedPre(s, x, y, button));
        ScreenMouseEvents.afterMouseClick(screen).register((s, x, y, button) -> ClientHooks.onScreenMouseClickedPost(s, x, y, button, false));
        ScreenMouseEvents.allowMouseRelease(screen).register((s, x, y, button) -> !ClientHooks.onScreenMouseReleasedPre(s, x, y, button));
        ScreenMouseEvents.afterMouseRelease(screen).register((s, x, y, button) -> ClientHooks.onScreenMouseReleasedPost(s, x, y, button, false));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horizontal, vertical) -> !ClientHooks.onScreenMouseScrollPre(s, x, y, horizontal, vertical));
        ScreenMouseEvents.afterMouseScroll(screen).register((s, x, y, horizontal, vertical) -> ClientHooks.onScreenMouseScrollPost(s, x, y, horizontal, vertical));
    }

    static void keyMappings() {
        Bus.INSTANCE.post(new RegisterKeyMappingsEvent(KeyBindingHelper::registerKeyBinding));
    }

    /** The mod-bus registration events NeoForge posts while the client mods load, in its order. */
    static void registration() {
        Bus.INSTANCE.post(new RegisterClientExtensionsEvent());
        Bus.INSTANCE.post(new RegisterRecipeBookCategoriesEvent());
        Bus.INSTANCE.post(new EntityRenderersEvent.RegisterLayerDefinitions());
        Bus.INSTANCE.post(new EntityRenderersEvent.RegisterRenderers());
        Bus.INSTANCE.post(new RegisterColorHandlersEvent.Block());
        Bus.INSTANCE.post(new RegisterColorHandlersEvent.Item());
        Bus.INSTANCE.post(new RegisterColorHandlersEvent.ColorResolvers());
        Bus.INSTANCE.post(new AddClientReloadListenersEvent());
        Bus.INSTANCE.post(new RegisterMenuScreensEvent());
        Bus.INSTANCE.post(new RegisterGuiLayersEvent());
    }
}
