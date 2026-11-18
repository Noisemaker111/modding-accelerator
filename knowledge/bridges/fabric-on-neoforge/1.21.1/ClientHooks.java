package ${package};

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import ${package}.fabric.api.client.keybinding.v1.KeyBindingHelper;

/**
 * The bridge's listeners on the mod's bus on the physical client; each client part of the bridge adds its line here.
 * The converted client entrypoint runs first in RegisterKeyMappingsEvent; the key mappings it registered are added last
 * in that same event. (Minecraft 1.21.1: Fabric API's KeyBindingHelper; no client commands, HUD or reload listeners yet.)
 */
final class ClientHooks {

    private ClientHooks() {}

    static void client(IEventBus modBus) {
        ClientNetworking.client(modBus);
        modBus.addListener(EventPriority.LOWEST, RegisterKeyMappingsEvent.class, KeyBindingHelper::register);
    }
}
