package mmt.packprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The game calls pack-check's probe makes that changed between Minecraft versions, for Minecraft 1.21.2 to 1.21.4: registries are
 * read with lookupOrThrow.
 * pack-check compiles the probe with the variant in tools/packcheck/game/ whose folder names the newest version at or
 * below the pack's, so one probe source serves every version; each variant keeps the same methods.
 */
final class MmtPackGame {

    private MmtPackGame() {}

    static Iterable<Item> items() {
        return BuiltInRegistries.ITEM;
    }

    static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static String itemNamespace(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getNamespace();
    }

    static String blockId(Block block) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
    }

    /** The server finished starting. */
    static boolean ready(MinecraftServer server) {
        return server.isReady();
    }

    static BlockPos spawn(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** The highest block a level can hold. */
    static int top(ServerLevel level) {
        return level.dimensionType().minY() + level.dimensionType().height() - 1;
    }

    static long chunkKey(int x, int z) {
        return net.minecraft.world.level.ChunkPos.asLong(x, z);
    }

    /** From 1.19.3 creative tabs list their items when built, not on the item; every block item is placed. */
    static boolean listed(Item item) {
        return true;
    }

    /**
     * Keeps a chunk loaded through the chunk system's own ticket, which loads it and its neighbours off the server
     * thread. Level.setChunkForced loads it at once on the server thread first, before its neighbours exist, and a mod
     * that reads a neighbour while the chunk finishes (Immersive Engineering's fluid hook) then loads chunk after chunk
     * there: All the Mods 10's watchdog stopped that after 300 s.
     */
    static void forceChunk(ServerLevel level, int x, int z) {
        level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(x, z), true);
    }

    static void moveTo(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.moveTo(x, y, z, yaw, pitch);
    }

    /**
     * Each dimension written the way level.dat stores it and read back the way the next start reads it.
     * A dimension that writes but cannot be read stops the world from opening again.
     */
    static JsonArray dimensions(MinecraftServer server) {
        JsonArray out = new JsonArray();
        var access = server.registryAccess();
        var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, access);
        var stems = access.lookupOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM);
        for (var entry : stems.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", entry.getKey().location().toString());
            try {
                var written = net.minecraft.world.level.dimension.LevelStem.CODEC.encodeStart(ops, entry.getValue());
                if (written.error().isPresent()) {
                    // The game still saves what it could write, and the next start rejects it: name the entries.
                    String message = written.error().get().message();
                    row.addProperty("write", message.length() > 600 ? message.substring(0, 600) + "..." : message);
                    written.resultOrPartial(ignored -> { }).ifPresent(partial -> row.add("outOfRangeBiomes", outOfRange(partial)));
                    out.add(row);
                    continue;
                }
                net.minecraft.nbt.Tag tag = written.result().get();
                row.addProperty("savedBytes", tag.toString().length());
                var read = net.minecraft.world.level.dimension.LevelStem.CODEC.parse(ops, tag);
                if (read.error().isPresent()) row.addProperty("read", read.error().get().message());
                // Find the smallest saved part that fails on its own, so the report names it.
                if (read.error().isPresent()) row.addProperty("failingPart", failingPart(ops, tag));
            } catch (Throwable failure) {
                row.addProperty("error", MmtPackProbe.stack(failure));
            }
            out.add(row);
        }
        return out;
    }

    /** Biome entries of a multi-noise list whose climate values fall outside what the game reads back. */
    static JsonArray outOfRange(net.minecraft.nbt.Tag tag) {
        JsonArray found = new JsonArray();
        Set<String> seen = new HashSet<>();
        outOfRange(tag, found, seen);
        return found;
    }

    static void outOfRange(net.minecraft.nbt.Tag tag, JsonArray found, Set<String> seen) {
        if (tag instanceof net.minecraft.nbt.ListTag list) {
            for (var child : list) outOfRange(child, found, seen);
            return;
        }
        if (!(tag instanceof net.minecraft.nbt.CompoundTag compound)) return;
        if (compound.contains("biome") && compound.get("parameters") instanceof net.minecraft.nbt.CompoundTag parameters) {
            for (String key : parameters.getAllKeys()) {
                double limit = key.equals("offset") ? 1 : 2, low = key.equals("offset") ? 0 : -2;
                List<Double> values = new ArrayList<>();
                var value = parameters.get(key);
                if (value instanceof net.minecraft.nbt.NumericTag number) values.add(number.getAsDouble());
                if (value instanceof net.minecraft.nbt.ListTag pair) for (var v : pair) if (v instanceof net.minecraft.nbt.NumericTag n) values.add(n.getAsDouble());
                for (double v : values) if (v < low || v > limit) {
                    String biome = compound.getString("biome");
                    if (seen.add(biome)) found.add(biome + " " + key + "=" + v);
                }
            }
            return;
        }
        for (String key : compound.getAllKeys()) outOfRange(compound.get(key), found, seen);
    }

    /** The path inside a saved dimension whose own codec rejects it: generator, then biome_source, then settings. */
    static String failingPart(net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops, net.minecraft.nbt.Tag stem) {
        if (!(stem instanceof net.minecraft.nbt.CompoundTag compound) || !(compound.get("generator") instanceof net.minecraft.nbt.CompoundTag generator)) return "";
        StringBuilder found = new StringBuilder();
        if (generator.contains("biome_source")) {
            var read = net.minecraft.world.level.biome.BiomeSource.CODEC.parse(ops, generator.get("biome_source"));
            if (read.error().isPresent()) found.append("generator.biome_source (").append(generator.getCompound("biome_source").getString("type")).append("): ").append(read.error().get().message()).append('\n');
        }
        if (generator.contains("settings")) {
            var read = net.minecraft.world.level.levelgen.NoiseGeneratorSettings.CODEC.parse(ops, generator.get("settings"));
            if (read.error().isPresent()) found.append("generator.settings: ").append(read.error().get().message()).append('\n');
        }
        String text = found.toString();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }
}
