package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.server.ServerStartedEvent, posted from Fabric's ServerLifecycleEvents.SERVER_STARTED. */
public class ServerStartedEvent extends ServerLifecycleEvent {

    ServerStartedEvent(MinecraftServer server) {
        super(server);
    }
}
