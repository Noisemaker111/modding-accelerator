package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;

/**
 * NeoForge's client events, each posted from the Fabric API callback that fires at the same moment; the events Fabric API
 * has no callback for are posted by the bridge's client mixins through ClientHooks.
 */
final class ClientGameEvents {

    private ClientGameEvents() {}

    static void hook() {
        ClientTickEvents.START_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Pre(() -> true, level)));
        ClientTickEvents.END_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Post(() -> true, level)));
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> Bus.INSTANCE.post(new ItemTooltipEvent(stack, Minecraft.getInstance().player, lines, flag, context)));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ClientHooks.screen = screen;
            ScreenEvents.remove(screen).register(removed -> {
                if (ClientHooks.screen == removed) ClientHooks.screen = null;
            });
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientHooks.firePlayerLogin(client.gameMode, client.player, handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientHooks.firePlayerLogout(client.gameMode, client.player, handler.getConnection()));
        RegisterGuiLayersEvent.hook();
        RegisterClientCommandsEvent.hook();
    }

    static void keyMappings() {
        Bus.INSTANCE.post(new RegisterKeyMappingsEvent(KeyMappingHelper::registerKeyMapping));
    }

    /** The mod-bus registration events NeoForge posts while the client mods load, in its order. */
    static void registration() {
        Bus.INSTANCE.post(new RegisterRenderPipelinesEvent());
        Bus.INSTANCE.post(new RegisterSpecialModelRendererEvent());
        Bus.INSTANCE.post(new EntityRenderersEvent.RegisterLayerDefinitions());
        Bus.INSTANCE.post(new EntityRenderersEvent.RegisterRenderers());
        Bus.INSTANCE.post(new RegisterColorHandlersEvent.BlockTintSources());
        Bus.INSTANCE.post(new RegisterColorHandlersEvent.ColorResolvers());
        Bus.INSTANCE.post(new AddClientReloadListenersEvent());
        Bus.INSTANCE.post(new RegisterMenuScreensEvent());
        Bus.INSTANCE.post(new RegisterGuiLayersEvent());
    }
}
