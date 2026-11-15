package ${package};

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The bridge's transfer listeners on the mod's bus: the providers registered with Fabric's lookups (BlockApiLookup,
 * ItemStorage.SIDED, FluidStorage.SIDED, Team Reborn's EnergyStorage.SIDED) become NeoForge capabilities in
 * RegisterCapabilitiesEvent, fallback providers last. See TransferBridge.
 */
final class TransferHooks {

    private TransferHooks() {}

    static void common(IEventBus modBus) {
        modBus.addListener(RegisterCapabilitiesEvent.class, TransferBridge::register);
        modBus.addListener(EventPriority.LOWEST, RegisterCapabilitiesEvent.class, TransferBridge::registerFallbacks);
    }
}
