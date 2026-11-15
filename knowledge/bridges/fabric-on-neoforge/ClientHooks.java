package ${package};

import net.neoforged.bus.api.IEventBus;

/** The bridge's listeners on the mod's bus on the physical client; each client part of the bridge adds its line here. */
final class ClientHooks {

    private ClientHooks() {}

    static void client(IEventBus modBus) {
    }
}
