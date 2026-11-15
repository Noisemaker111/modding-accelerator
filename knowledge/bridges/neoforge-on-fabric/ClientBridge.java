package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** The client half of Bridge: the client entrypoint ends with client(), which hooks client events and posts FMLClientSetupEvent. */
public final class ClientBridge {

    static boolean hooked;

    private ClientBridge() {}

    public static void client(String modid) {
        hook();
        Bus.INSTANCE.post(new FMLClientSetupEvent(Bridge.container(modid)));
    }

    static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ClientTickEvents.START_CLIENT_TICK.register(client -> Bus.INSTANCE.post(new ClientTickEvent.Pre()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> Bus.INSTANCE.post(new ClientTickEvent.Post()));
    }
}
