package ${package};

import net.minecraft.server.MinecraftServer;

/**
 * NeoForge's net.neoforged.neoforge.event.server.ServerAboutToStartEvent, posted from Fabric's
 * ServerLifecycleEvents.SERVER_STARTING before ServerStartingEvent: the server exists and has not loaded its levels.
 * Difference: NeoForge posts it from the server's initServer, after the dedicated server read its properties; Fabric's
 * callback fires just before initServer.
 */
public class ServerAboutToStartEvent extends ServerLifecycleEvent {

    public ServerAboutToStartEvent(MinecraftServer server) {
        super(server);
    }
}
