package ${package};

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** NeoForge's net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent, posted by the client entrypoint. */
public class RegisterClientPayloadHandlersEvent extends Event {

    public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        PayloadRegistrar.CLIENT_HANDLERS.put(type, handler);
    }

    public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, HandlerThread thread, IPayloadHandler<T> handler) {
        register(type, handler);
    }
}
