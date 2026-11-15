package ${package};

import io.netty.channel.ChannelFutureListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import ${package}.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import ${package}.fabric.api.client.networking.v1.ClientPlayNetworking;
import ${package}.fabric.api.networking.v1.PacketSender;

/**
 * The bridge's client side of play payloads: every clientbound type the mod registered gets a NeoForge client handler
 * (RegisterClientPayloadHandlersEvent), run on the render thread, which hands the payload to the ClientPlayNetworking
 * receiver registered for its id at that moment; a payload with no receiver is dropped. ClientPlayConnectionEvents are
 * posted from ClientPlayerNetworkEvent.
 */
public final class ClientNetworking {

    /** ClientPlayNetworking's global receivers, by payload id. */
    public static final Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>> RECEIVERS = new ConcurrentHashMap<>();

    private ClientNetworking() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void client(IEventBus modBus) {
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, event -> {
            IPayloadHandler handler = (payload, context) -> receive((CustomPacketPayload) payload, context);
            for (CustomPacketPayload.TypeAndCodec<?, ?> type : Networking.CLIENTBOUND.values()) event.register(type.type(), handler);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
            ClientPacketListener listener = event.getPlayer().connection;
            Minecraft client = Minecraft.getInstance();
            ClientPlayConnectionEvents.INIT.invoker().onPlayInit(listener, client);
            ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(listener, new ServerSender(), client);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            LocalPlayer player = event.getPlayer();
            ClientPacketListener listener = player != null ? player.connection : Minecraft.getInstance().getConnection();
            if (listener != null) ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(listener, Minecraft.getInstance());
        });
        FabricBridge.clientConnections(player -> player instanceof LocalPlayer local && local.connection != null ? local.connection.getConnection() : null);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void receive(CustomPacketPayload payload, IPayloadContext context) {
        ClientPlayNetworking.PlayPayloadHandler handler = RECEIVERS.get(payload.type().id());
        if (handler == null) return;
        handler.receive(payload, new ClientContext((LocalPlayer) context.player()));
    }

    /** The context a client receiver gets. */
    record ClientContext(LocalPlayer player) implements ClientPlayNetworking.Context {
        @Override
        public Minecraft client() {
            return Minecraft.getInstance();
        }

        @Override
        public PacketSender responseSender() {
            return new ServerSender();
        }
    }

    /** Sends to the server the client is connected to. */
    public static final class ServerSender implements PacketSender {
        @Override
        public Packet<?> createPacket(CustomPacketPayload payload) {
            return new ServerboundCustomPayloadPacket(payload);
        }

        @Override
        public void sendPacket(Packet<?> packet, ChannelFutureListener callback) {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null) throw new IllegalStateException("Cannot send packets when not in game!");
            listener.getConnection().send(packet, callback);
        }

        @Override
        public void disconnect(Component disconnectReason) {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener != null) listener.getConnection().disconnect(disconnectReason);
        }
    }
}
