package ${package};

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** NeoForge's net.neoforged.neoforge.network.handling.IPayloadHandler. */
@FunctionalInterface
public interface IPayloadHandler<T extends CustomPacketPayload> {
    void handle(T payload, IPayloadContext context);
}
