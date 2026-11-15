package ${package}.fabric.api.networking.v1;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fabric API's net.fabricmc.fabric.api.networking.v1.PacketSender: sends to one connection, as Fabric's does. */
public interface PacketSender {

    Packet<?> createPacket(CustomPacketPayload payload);

    default void sendPacket(Packet<?> packet) {
        sendPacket(packet, null);
    }

    default void sendPacket(CustomPacketPayload payload) {
        sendPacket(createPacket(payload));
    }

    void sendPacket(Packet<?> packet, ChannelFutureListener callback);

    default void sendPacket(CustomPacketPayload payload, ChannelFutureListener callback) {
        sendPacket(createPacket(payload), callback);
    }

    void disconnect(Component disconnectReason);
}
