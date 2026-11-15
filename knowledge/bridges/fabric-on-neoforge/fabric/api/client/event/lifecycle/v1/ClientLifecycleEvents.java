package ${package}.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;

/**
 * Fabric API's net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents. NeoForge has no event at the
 * moments Fabric posts these, so each is posted from the nearest one, once: CLIENT_STARTED (Fabric: when Minecraft.run
 * starts, before its loop) from the first RenderFrameEvent.Pre, the first thing that loop does; CLIENT_STOPPING (Fabric:
 * when Minecraft closes the level on its way out) from GameShuttingDownEvent, when Minecraft is told to stop, a little
 * earlier.
 */
public final class ClientLifecycleEvents {

    public static final Event<ClientStarted> CLIENT_STARTED = EventFactory.createArrayBacked(ClientStarted.class, callbacks -> client -> {
        for (ClientStarted callback : callbacks) callback.onClientStarted(client);
    });

    public static final Event<ClientStopping> CLIENT_STOPPING = EventFactory.createArrayBacked(ClientStopping.class, callbacks -> client -> {
        for (ClientStopping callback : callbacks) callback.onClientStopping(client);
    });

    private static boolean started;

    static {
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, event -> {
            if (started) return;
            started = true;
            CLIENT_STARTED.invoker().onClientStarted(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> CLIENT_STOPPING.invoker().onClientStopping(Minecraft.getInstance()));
    }

    private ClientLifecycleEvents() {}

    @FunctionalInterface
    public interface ClientStarted {
        void onClientStarted(Minecraft client);
    }

    @FunctionalInterface
    public interface ClientStopping {
        void onClientStopping(Minecraft client);
    }
}
