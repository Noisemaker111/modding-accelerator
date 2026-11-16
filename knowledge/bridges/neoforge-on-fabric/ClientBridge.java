package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The client half of Bridge: the client entrypoint ends with client(), which hooks client events and posts FMLClientSetupEvent. */
public final class ClientBridge {

    static boolean hooked;

    private ClientBridge() {}

    public static void client(String modid) {
        hook();
        ClientGameEvents.keyMappings();
        ClientGameEvents.registration();
        Bus.INSTANCE.post(new RegisterClientPayloadHandlersEvent());
        for (var handler : PayloadRegistrar.CLIENT_HANDLERS.entrySet()) receive(handler.getKey(), handler.getValue());
        PayloadRegistrar.CLIENT_HANDLERS.clear();
        Bus.INSTANCE.post(new RegisterParticleProvidersEvent());
        Bus.INSTANCE.post(new FMLClientSetupEvent(Bridge.container(modid)));
    }

    @SuppressWarnings("unchecked")
    static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<?> type, IPayloadHandler<?> handler) {
        IPayloadHandler<T> typed = (IPayloadHandler<T>) handler;
        ClientPlayNetworking.registerGlobalReceiver((CustomPacketPayload.Type<T>) type, (payload, context) -> typed.handle(payload, new ClientPayloadContext(context)));
    }

    static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ClientGameEvents.hook();
        ClientTickEvents.START_CLIENT_TICK.register(client -> Bus.INSTANCE.post(new ClientTickEvent.Pre()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> Bus.INSTANCE.post(new ClientTickEvent.Post()));
    }
}
