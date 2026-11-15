package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.server.ServerStartingEvent, posted from Fabric's ServerLifecycleEvents.SERVER_STARTING. */
public class ServerStartingEvent extends ServerLifecycleEvent {

    ServerStartingEvent(MinecraftServer server) {
        super(server);
    }
}
