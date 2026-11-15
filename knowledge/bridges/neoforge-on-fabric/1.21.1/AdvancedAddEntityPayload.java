package ${package};

import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's internal AdvancedAddEntityPayload (net.neoforged.neoforge.network.payload): an IEntityWithComplexSpawn's
 * extra spawn data, as the entity id and the bytes writeSpawnData wrote, sent in the entity's spawn bundle. Registered
 * as a clientbound play payload of this converted mod (${modid}:advanced_add_entity) with Fabric's PayloadTypeRegistry (playS2C);
 * ComplexSpawnMixin sends it and the client receiver (ClientAccess) reads it into the entity.
 */
public record AdvancedAddEntityPayload(int entityId, byte[] customPayload) implements CustomPacketPayload {

    public static final Type<AdvancedAddEntityPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("${modid}", "advanced_add_entity"));
    public static final StreamCodec<FriendlyByteBuf, AdvancedAddEntityPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        AdvancedAddEntityPayload::entityId,
        NeoForgeStreamCodecs.UNBOUNDED_BYTE_ARRAY,
        AdvancedAddEntityPayload::customPayload,
        AdvancedAddEntityPayload::new
    );

    @Override
    public Type<AdvancedAddEntityPayload> type() {
        return TYPE;
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, STREAM_CODEC);
    }

    /** Adds the payload to an entity's spawn bundle for a player, when the entity has extra spawn data and the player's client takes it. */
    @SuppressWarnings("unchecked")
    public static void send(Entity entity, ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> bundle) {
        if (!(entity instanceof IEntityWithComplexSpawn complex)) return;
        if (!ServerPlayNetworking.canSend(player, TYPE)) return;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), entity.registryAccess());
        try {
            complex.writeSpawnData(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            Packet<?> packet = new ClientboundCustomPayloadPacket(new AdvancedAddEntityPayload(entity.getId(), data));
            bundle.accept((Packet<ClientGamePacketListener>) packet);
        } finally {
            buf.release();
        }
    }

    /** On the client: the extra data read into the entity it names, as NeoForge's ClientPayloadHandler does. */
    static void handle(AdvancedAddEntityPayload payload, Player player) {
        Entity entity = player.level().getEntity(payload.entityId());
        if (!(entity instanceof IEntityWithComplexSpawn complex)) return;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.customPayload()), entity.registryAccess());
        try {
            complex.readSpawnData(buf);
        } finally {
            buf.release();
        }
    }
}
