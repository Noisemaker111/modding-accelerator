package ${package}.fabric.api.networking.v1;

import java.util.Objects;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import ${package}.Networking;

/**
 * Fabric API's net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking on NeoForge's networking: global receivers
 * get the payloads NeoForge hands the bridge, on the server thread, as on Fabric; send goes through NeoForge's
 * PacketDistributor; canSend asks NeoForge whether the player's connection negotiated the payload's channel. Receivers
 * for one connection (registerReceiver) and the configuration phase are not mirrored.
 */
public final class ServerPlayNetworking {

    private ServerPlayNetworking() {}

    public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
        Objects.requireNonNull(handler, "Channel handler cannot be null");
        return Networking.SERVER_RECEIVERS.putIfAbsent(type.id(), handler) == null;
    }

    public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
        return Networking.SERVER_RECEIVERS.remove(id);
    }

    public static Set<Identifier> getGlobalReceivers() {
        return Set.copyOf(Networking.SERVER_RECEIVERS.keySet());
    }

    public static boolean canSend(ServerPlayer player, Identifier channelName) {
        Objects.requireNonNull(player, "Server player cannot be null");
        return player.connection.hasChannel(channelName);
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return canSend(player, type.id());
    }

    public static <T extends CustomPacketPayload> Packet<ClientCommonPacketListener> createClientboundPacket(T packet) {
        return new ClientboundCustomPayloadPacket(packet);
    }

    public static PacketSender getSender(ServerPlayer player) {
        Objects.requireNonNull(player, "Server player cannot be null");
        return new Networking.ServerSender(player);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        Objects.requireNonNull(player, "Server player cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        PacketDistributor.sendToPlayer(player, payload);
    }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        MinecraftServer server();

        ServerPlayer player();

        PacketSender responseSender();
    }
}
