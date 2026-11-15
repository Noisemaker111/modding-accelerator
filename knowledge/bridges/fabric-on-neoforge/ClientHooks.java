package ${package};

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import ${package}.fabric.api.client.keymapping.v1.KeyMappingHelper;
import ${package}.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import ${package}.fabric.api.resource.v1.ResourceLoader;

/**
 * The bridge's listeners on the mod's bus on the physical client; each client part of the bridge adds its line here.
 * The converted client entrypoint runs first in RegisterKeyMappingsEvent; the key mappings it registered are added last
 * in that same event, and its HUD elements and client reload listeners in the later RegisterGuiLayersEvent and
 * AddClientReloadListenersEvent.
 */
final class ClientHooks {

    private ClientHooks() {}

    static void client(IEventBus modBus) {
        ClientNetworking.client(modBus);
        ClientCommandForwarding.client();
        modBus.addListener(EventPriority.LOWEST, RegisterKeyMappingsEvent.class, KeyMappingHelper::register);
        modBus.addListener(RegisterGuiLayersEvent.class, HudElementRegistry.Layers::register);
        modBus.addListener(AddClientReloadListenersEvent.class, event -> ResourceLoader.Listeners.client().addTo(event));
        ResourceLoader.ClientReload.lateWith(listener -> {
            if (Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager manager) manager.registerReloadListener(listener);
        });
    }
}
