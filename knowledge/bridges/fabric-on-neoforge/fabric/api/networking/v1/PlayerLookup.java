package ${package}.fabric.api.networking.v1;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric API's net.fabricmc.fabric.api.networking.v1.PlayerLookup, read from the game's own player lists and chunk map
 * as Fabric reads them. tracking(Entity) is not mirrored: the game keeps an entity's watchers private, and NeoForge only
 * sends to them (PacketDistributor.sendToPlayersTrackingEntity).
 */
public final class PlayerLookup {

    private PlayerLookup() {}

    public static Collection<ServerPlayer> all(MinecraftServer server) {
        Objects.requireNonNull(server, "The server cannot be null");
        if (server.getPlayerList() != null) return Collections.unmodifiableCollection(server.getPlayerList().getPlayers());
        return Collections.emptyList();
    }

    public static Collection<ServerPlayer> level(ServerLevel level) {
        Objects.requireNonNull(level, "The level cannot be null");
        return Collections.unmodifiableCollection(level.players());
    }

    public static Collection<ServerPlayer> tracking(ServerLevel level, ChunkPos pos) {
        Objects.requireNonNull(level, "The level cannot be null");
        Objects.requireNonNull(pos, "The chunk pos cannot be null");
        return level.getChunkSource().chunkMap.getPlayers(pos, false);
    }

    public static Collection<ServerPlayer> tracking(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(pos, "BlockPos cannot be null");
        return tracking(level, ChunkPos.containing(pos));
    }

    public static Collection<ServerPlayer> tracking(BlockEntity blockEntity) {
        Objects.requireNonNull(blockEntity, "BlockEntity cannot be null");
        if (!blockEntity.hasLevel() || blockEntity.getLevel().isClientSide()) throw new IllegalArgumentException("Only supported on server levels!");
        return tracking((ServerLevel) blockEntity.getLevel(), blockEntity.getBlockPos());
    }

    public static Collection<ServerPlayer> around(ServerLevel level, Vec3 pos, double radius) {
        double radiusSq = radius * radius;
        List<ServerPlayer> out = level(level).stream().filter(p -> p.distanceToSqr(pos) <= radiusSq).collect(Collectors.toList());
        return out;
    }

    public static Collection<ServerPlayer> around(ServerLevel level, Vec3i pos, double radius) {
        double radiusSq = radius * radius;
        List<ServerPlayer> out = level(level).stream().filter(p -> p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) <= radiusSq).collect(Collectors.toList());
        return out;
    }
}
