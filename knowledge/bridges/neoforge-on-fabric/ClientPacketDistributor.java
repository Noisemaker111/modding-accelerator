package ${package};

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** NeoForge's net.neoforged.neoforge.client.network.ClientPacketDistributor, with Fabric's ClientPlayNetworking.send (client only). */
public final class ClientPacketDistributor {

    private ClientPacketDistributor() {}

    public static void sendToServer(CustomPacketPayload payload, CustomPacketPayload... payloads) {
        ClientPlayNetworking.send(payload);
        for (CustomPacketPayload more : payloads) ClientPlayNetworking.send(more);
    }
}
