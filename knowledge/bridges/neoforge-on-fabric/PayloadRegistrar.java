package ${package};

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * NeoForge's net.neoforged.neoforge.network.registration.PayloadRegistrar on Fabric's networking API: each payload type
 * goes into Fabric's play payload registry for its direction; a server handler becomes a global server receiver, and a
 * client handler is kept until the client entrypoint registers it (ClientBridge), since Fabric's client networking only
 * exists on the client. Fabric runs play handlers on the game thread, as NeoForge's default (HandlerThread.MAIN) does.
 * The configuration phase has no counterpart here yet.
 */
public class PayloadRegistrar {

    static final Map<CustomPacketPayload.Type<?>, IPayloadHandler<?>> CLIENT_HANDLERS = new LinkedHashMap<>();

    final String version;

    public PayloadRegistrar(String version) {
        this.version = version;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        CLIENT_HANDLERS.put(type, handler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        receiveOnServer(type, handler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playBidirectional(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> serverHandler, IPayloadHandler<T> clientHandler) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        receiveOnServer(type, serverHandler);
        if (clientHandler != null) CLIENT_HANDLERS.put(type, clientHandler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playBidirectional(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> serverHandler) {
        return playBidirectional(type, codec, serverHandler, null);
    }

    public <T extends CustomPacketPayload> PayloadRegistrar commonToClient(CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        CLIENT_HANDLERS.put(type, handler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar commonToClient(CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar commonToServer(CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        receiveOnServer(type, handler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar commonBidirectional(CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> serverHandler, IPayloadHandler<T> clientHandler) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        receiveOnServer(type, serverHandler);
        if (clientHandler != null) CLIENT_HANDLERS.put(type, clientHandler);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar commonBidirectional(CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> serverHandler) {
        return commonBidirectional(type, codec, serverHandler, null);
    }

    public PayloadRegistrar executesOn(HandlerThread thread) {
        return this;
    }

    public PayloadRegistrar versioned(String version) {
        return new PayloadRegistrar(version);
    }

    public PayloadRegistrar optional() {
        return this;
    }

    static <T extends CustomPacketPayload> void receiveOnServer(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.handle(payload, new ServerPayloadContext(context)));
    }
}
