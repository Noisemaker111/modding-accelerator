package ${package}.fabric.api.networking.v1;

import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ${package}.Networking;

/**
 * Fabric API's net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry for play payloads, on NeoForge's payload
 * registration (see the bridge's Networking): a type registered here is registered with NeoForge, optional, in
 * RegisterPayloadHandlersEvent, so it must be registered before mod loading completes (Fabric's entrypoints all run
 * before that). registerLarge is register: NeoForge splits a large modded payload by itself, so the size limit is not
 * kept. The configuration-phase registries are not mirrored.
 */
public interface PayloadTypeRegistry<B extends FriendlyByteBuf> {

    <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec);

    default <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, int maxPacketSize) {
        return register(type, codec);
    }

    default <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, IntSupplier maxPacketSizeSupplier) {
        return register(type, codec);
    }

    static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay() {
        return Play.SERVERBOUND;
    }

    static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay() {
        return Play.CLIENTBOUND;
    }

    /** One direction of play payloads. */
    final class Play implements PayloadTypeRegistry<RegistryFriendlyByteBuf> {
        static final Play SERVERBOUND = new Play(Networking.SERVERBOUND);
        static final Play CLIENTBOUND = new Play(Networking.CLIENTBOUND);

        private final Map<Identifier, CustomPacketPayload.TypeAndCodec<?, ?>> types;

        private Play(Map<Identifier, CustomPacketPayload.TypeAndCodec<?, ?>> types) {
            this.types = types;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, T> register(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
            return (CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, T>) Networking.add(types, type, codec);
        }
    }
}
