package ${package};

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge's net.neoforged.neoforge.network.PacketDistributor on Fabric: the players each method names come from Fabric's
 * PlayerLookup, and each payload goes out with ServerPlayNetworking.send. sendToServer (NeoForge before 26.1) goes
 * through the client half, as ClientPacketDistributor does.
 */
public final class PacketDistributor {

    private PacketDistributor() {}

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        send(List.of(player), payload, payloads);
    }

    public static void sendToPlayersInDimension(ServerLevel level, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        send(PlayerLookup.level(level), payload, payloads);
    }

    public static void sendToPlayersNear(ServerLevel level, ServerPlayer excluded, double x, double y, double z, double radius, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        List<ServerPlayer> near = new ArrayList<>(PlayerLookup.around(level, new Vec3(x, y, z), radius));
        near.remove(excluded);
        send(near, payload, payloads);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload, CustomPacketPayload... payloads) {
        if (Bridge.server != null) send(PlayerLookup.all(Bridge.server), payload, payloads);
    }

    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        send(PlayerLookup.tracking(entity), payload, payloads);
    }

    public static void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        List<ServerPlayer> players = new ArrayList<>(PlayerLookup.tracking(entity));
        if (entity instanceof ServerPlayer self && !players.contains(self)) players.add(self);
        send(players, payload, payloads);
    }

    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        send(PlayerLookup.tracking(level, chunk), payload, payloads);
    }

    public static void sendToServer(CustomPacketPayload payload, CustomPacketPayload... payloads) {
        ClientPacketDistributor.sendToServer(payload, payloads);
    }

    static void send(Collection<ServerPlayer> players, CustomPacketPayload payload, CustomPacketPayload... payloads) {
        for (ServerPlayer player : players) {
            ServerPlayNetworking.send(player, payload);
            for (CustomPacketPayload more : payloads) ServerPlayNetworking.send(player, more);
        }
    }
}
