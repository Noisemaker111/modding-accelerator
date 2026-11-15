package ${package}.fabric.api.client.networking.v1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;
import ${package}.fabric.api.networking.v1.PacketSender;

/**
 * Fabric API's net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents, posted from NeoForge's
 * ClientPlayerNetworkEvent (see the bridge's ClientNetworking). JOIN is posted from LoggingIn, when the login packet has
 * been handled, as on Fabric. INIT has no NeoForge event of its own (Fabric posts it when the play packet listener is
 * made, before the login packet): it is posted from LoggingIn too, just before JOIN. DISCONNECT is posted from
 * LoggingOut, when Minecraft tears the level down after the connection ends, with the player's packet listener.
 */
public final class ClientPlayConnectionEvents {

    public static final Event<Init> INIT = EventFactory.createArrayBacked(Init.class, callbacks -> (listener, client) -> {
        for (Init callback : callbacks) callback.onPlayInit(listener, client);
    });

    public static final Event<Join> JOIN = EventFactory.createArrayBacked(Join.class, callbacks -> (listener, sender, client) -> {
        for (Join callback : callbacks) callback.onPlayReady(listener, sender, client);
    });

    public static final Event<Disconnect> DISCONNECT = EventFactory.createArrayBacked(Disconnect.class, callbacks -> (listener, client) -> {
        for (Disconnect callback : callbacks) callback.onPlayDisconnect(listener, client);
    });

    private ClientPlayConnectionEvents() {}

    @FunctionalInterface
    public interface Init {
        void onPlayInit(ClientPacketListener listener, Minecraft client);
    }

    @FunctionalInterface
    public interface Join {
        void onPlayReady(ClientPacketListener listener, PacketSender sender, Minecraft client);
    }

    @FunctionalInterface
    public interface Disconnect {
        void onPlayDisconnect(ClientPacketListener listener, Minecraft client);
    }
}
