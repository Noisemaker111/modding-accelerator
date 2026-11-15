package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.server.ServerStoppedEvent, posted from Fabric's ServerLifecycleEvents.SERVER_STOPPED. */
public class ServerStoppedEvent extends ServerLifecycleEvent {

    ServerStoppedEvent(MinecraftServer server) {
        super(server);
    }
}
