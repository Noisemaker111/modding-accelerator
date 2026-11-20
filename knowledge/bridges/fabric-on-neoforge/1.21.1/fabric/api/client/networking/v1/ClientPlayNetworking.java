package ${package}.fabric.api.client.networking.v1;

import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.PacketDistributor;
import ${package}.ClientNetworking;
import ${package}.fabric.api.networking.v1.PacketSender;

/**
 * Fabric API's net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking on NeoForge's client networking:
 * global receivers get the payloads of the clientbound types the mod registered (PayloadTypeRegistry.clientboundPlay),
 * on the render thread, as on Fabric; send goes through NeoForge's ClientPacketDistributor; canSend asks NeoForge
 * whether the connection negotiated the payload's channel. Receivers for one connection (registerReceiver) and the
 * configuration phase are not mirrored.
 */
public final class ClientPlayNetworking {

    private ClientPlayNetworking() {}

    public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
        Objects.requireNonNull(handler, "Channel handler cannot be null");
        return ClientNetworking.RECEIVERS.putIfAbsent(type.id(), handler) == null;
    }

    public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
        return ClientNetworking.RECEIVERS.remove(id);
    }

    public static Set<Identifier> getGlobalReceivers() {
        return Set.copyOf(ClientNetworking.RECEIVERS.keySet());
    }

    public static boolean canSend(Identifier channelName) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.hasChannel(channelName);
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        return canSend(type.id());
    }

    public static <T extends CustomPacketPayload> Packet<ServerCommonPacketListener> createServerboundPacket(T packet) {
        return new ServerboundCustomPayloadPacket(packet);
    }

    public static PacketSender getSender() {
        if (Minecraft.getInstance().getConnection() == null) throw new IllegalStateException("Cannot get payload sender when not in game!");
        return new ClientNetworking.ServerSender();
    }

    public static void send(CustomPacketPayload payload) {
        Objects.requireNonNull(payload, "Payload cannot be null");
        if (Minecraft.getInstance().getConnection() == null) throw new IllegalStateException("Cannot send packets when not in game!");
        PacketDistributor.sendToServer(payload);
    }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        Minecraft client();

        LocalPlayer player();

        PacketSender responseSender();
    }
}
