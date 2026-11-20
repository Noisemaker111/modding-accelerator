package ${package};

import net.neoforged.bus.api.IEventBus;

/** The bridge's listeners on the mod's bus for both sides; each part of the bridge that needs one adds its line here. */
final class Hooks {

    private Hooks() {}

    static void common(IEventBus modBus) {
        Networking.common(modBus);
        Attachments.common(modBus);
    }
}
