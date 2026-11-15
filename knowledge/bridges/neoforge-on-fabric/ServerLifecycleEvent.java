package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.server.ServerLifecycleEvent: the base of the server start and stop events. */
public abstract class ServerLifecycleEvent extends Event {

    final MinecraftServer server;

    ServerLifecycleEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
