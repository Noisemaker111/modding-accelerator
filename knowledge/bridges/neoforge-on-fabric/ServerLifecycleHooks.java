package ${package};

import net.minecraft.server.MinecraftServer;

/** NeoForge's net.neoforged.neoforge.server.ServerLifecycleHooks: the running server, from Fabric's server lifecycle events. */
public class ServerLifecycleHooks {

    public static MinecraftServer getCurrentServer() {
        return Bridge.server;
    }
}
