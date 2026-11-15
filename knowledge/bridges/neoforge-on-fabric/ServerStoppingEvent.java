package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.server.ServerStoppingEvent, posted from Fabric's ServerLifecycleEvents.SERVER_STOPPING. */
public class ServerStoppingEvent extends ServerLifecycleEvent {

    ServerStoppingEvent(MinecraftServer server) {
        super(server);
    }
}
