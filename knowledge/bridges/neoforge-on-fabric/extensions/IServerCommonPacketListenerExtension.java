package ${package}.extensions;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IServerCommonPacketListenerExtension: a payload sent straight on a
 * player's connection (player.connection.send(payload)), wrapped in the game's custom payload packet as NeoForge does.
 * Added to ServerCommonPacketListenerImpl by ServerPayloadSendMixin, and for the compile by Loom's interface injection.
 */
public interface IServerCommonPacketListenerExtension {

    default void send(CustomPacketPayload payload) {
        ((ServerCommonPacketListenerImpl) this).send(new ClientboundCustomPayloadPacket(payload));
    }

    default void send(CustomPacketPayload payload, ChannelFutureListener listener) {
        ((ServerCommonPacketListenerImpl) this).send(new ClientboundCustomPayloadPacket(payload), listener);
    }
}
