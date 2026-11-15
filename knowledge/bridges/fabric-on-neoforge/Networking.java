package ${package};

import io.netty.channel.ChannelFutureListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ${package}.fabric.api.networking.v1.PacketSender;
import ${package}.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * The bridge's play payloads on NeoForge. Fabric registers a payload's type (PayloadTypeRegistry) apart from its
 * receivers, at any time; NeoForge fixes its payload types once, in RegisterPayloadHandlersEvent, which fires after mod
 * loading completes, so every type the mod registered in its entrypoints is known there. Each type is registered
 * optional (as on Fabric, a side without the channel is not refused; canSend tells), with a handler that runs on the
 * main thread (the server thread, or the client's render thread) and hands the payload to the receiver registered for
 * its id at that moment. A type registered both ways is one bidirectional NeoForge payload, which needs one codec.
 */
public final class Networking {

    /** Clientbound play payload types, by id, in registration order. */
    public static final Map<Identifier, CustomPacketPayload.TypeAndCodec<?, ?>> CLIENTBOUND = new LinkedHashMap<>();
    /** Serverbound play payload types, by id, in registration order. */
    public static final Map<Identifier, CustomPacketPayload.TypeAndCodec<?, ?>> SERVERBOUND = new LinkedHashMap<>();
    /** ServerPlayNetworking's global receivers, by payload id. */
    public static final Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>> SERVER_RECEIVERS = new ConcurrentHashMap<>();

    private static boolean fixed;

    private Networking() {}

    static void common(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, Networking::register);
    }

    /** Adds a payload type for one direction; NeoForge takes no new types once mod loading has completed. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static synchronized <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<?, T> add(
        Map<Identifier, CustomPacketPayload.TypeAndCodec<?, ?>> direction,
        CustomPacketPayload.Type<T> type,
        StreamCodec<?, T> codec
    ) {
        if (fixed) throw new IllegalStateException("NeoForge fixed the payload types when mod loading completed; " + type.id() + " comes too late");
        if (direction.containsKey(type.id())) throw new IllegalArgumentException("Packet type " + type.id() + " is already registered!");
        CustomPacketPayload.TypeAndCodec entry = new CustomPacketPayload.TypeAndCodec(type, codec);
        direction.put(type.id(), entry);
        return entry;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static synchronized void register(RegisterPayloadHandlersEvent event) {
        fixed = true;
        PayloadRegistrar registrar = event.registrar("1").optional();
        Set<Identifier> ids = new LinkedHashSet<>(SERVERBOUND.keySet());
        ids.addAll(CLIENTBOUND.keySet());
        IPayloadHandler server = (payload, context) -> receiveOnServer((CustomPacketPayload) payload, context);
        for (Identifier id : ids) {
            CustomPacketPayload.TypeAndCodec toClient = CLIENTBOUND.get(id);
            CustomPacketPayload.TypeAndCodec toServer = SERVERBOUND.get(id);
            if (toClient != null && toServer != null) {
                if (toClient.codec() != toServer.codec()) throw new IllegalStateException("NeoForge needs one codec for the payload " + id + ", registered both ways with two");
                registrar.playBidirectional(toServer.type(), (StreamCodec) toServer.codec(), server);
            } else if (toServer != null) {
                registrar.playToServer(toServer.type(), (StreamCodec) toServer.codec(), server);
            } else {
                registrar.playToClient(toClient.type(), (StreamCodec) toClient.codec());
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void receiveOnServer(CustomPacketPayload payload, IPayloadContext context) {
        ServerPlayNetworking.PlayPayloadHandler handler = SERVER_RECEIVERS.get(payload.type().id());
        if (handler == null) return;
        ServerPlayer player = (ServerPlayer) context.player();
        handler.receive(payload, new ServerContext(player));
    }

    /** The context a server receiver gets. */
    record ServerContext(ServerPlayer player) implements ServerPlayNetworking.Context {
        @Override
        public MinecraftServer server() {
            return player.level().getServer();
        }

        @Override
        public PacketSender responseSender() {
            return new ServerSender(player);
        }
    }

    /** Sends to one player's connection. */
    public record ServerSender(ServerPlayer player) implements PacketSender {
        @Override
        public Packet<?> createPacket(CustomPacketPayload payload) {
            return new ClientboundCustomPayloadPacket(payload);
        }

        @Override
        public void sendPacket(Packet<?> packet, ChannelFutureListener callback) {
            player.connection.send(packet, callback);
        }

        @Override
        public void disconnect(Component disconnectReason) {
            player.connection.disconnect(disconnectReason);
        }
    }
}
