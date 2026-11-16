package ${package}.extensions;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IClientCommonPacketListenerExtension: a payload sent straight on
 * the client's connection (minecraft.getConnection().send(payload)), wrapped in the game's custom payload packet as
 * NeoForge does. Added to ClientCommonPacketListenerImpl by ClientPayloadSendMixin, and for the compile by Loom.
 */
public interface IClientCommonPacketListenerExtension {

    default void send(CustomPacketPayload payload) {
        ((ClientCommonPacketListenerImpl) this).send(new ServerboundCustomPayloadPacket(payload));
    }
}
