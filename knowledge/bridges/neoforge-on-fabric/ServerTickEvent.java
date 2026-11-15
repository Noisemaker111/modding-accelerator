package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.event.tick.ServerTickEvent, posted from Fabric's ServerTickEvents. */
public abstract class ServerTickEvent extends Event {

    final MinecraftServer server;

    ServerTickEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public static class Pre extends ServerTickEvent {

        Pre(MinecraftServer server) {
            super(server);
        }
    }

    public static class Post extends ServerTickEvent {

        Post(MinecraftServer server) {
            super(server);
        }
    }
}
