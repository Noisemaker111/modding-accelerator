package modaccel.packprobe;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * The game calls the behaviour fingerprint makes that changed between Minecraft versions, for Minecraft 1.20.3 to 1.20.4:
 * saved data needs no registries, loot tables come from the loot data manager, and the tick manager freezes the world. ModaccelFingerprint is compiled with the variant in tools/packcheck/fingerprint/
 * whose folder names the newest version at or below the game's; each variant keeps the same methods.
 */
final class ModaccelFingerprintGame {

    private ModaccelFingerprintGame() {}

    static void registries(MinecraftServer server, Map<String, Collection<String>> out) {
        server.registryAccess().registries().forEach(entry -> {
            Registry<?> registry = entry.value();
            String name = ModaccelFingerprint.id(entry.key());
            List<String> ids = new ArrayList<>();
            for (Object id : registry.keySet()) ids.add(String.valueOf(id));
            out.put(name, ids);
            registry.getTags().forEach(pair -> {
                List<String> members = new ArrayList<>();
                pair.getSecond().forEach(holder -> members.add(holder.unwrapKey().map(ModaccelFingerprint::id).orElse("(direct)")));
                Collections.sort(members);
                out.computeIfAbsent("tags/" + name, key -> new ArrayList<>()).add(ModaccelFingerprint.id(pair.getFirst()) + "=" + String.join(",", members));
            });
        });
        List<String> recipes = new ArrayList<>();
        for (var holder : server.getRecipeManager().getRecipes()) recipes.add(holder.id() + " " + BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
        out.put("recipes", recipes);
        List<String> loot = new ArrayList<>();
        for (Object id : server.getLootData().getKeys(net.minecraft.world.level.storage.loot.LootDataType.TABLE)) loot.add(String.valueOf(id));
        out.put("loot_tables", loot);
        List<String> advancements = new ArrayList<>();
        for (var holder : server.getAdvancements().getAllAdvancements()) advancements.add(String.valueOf(holder.id()));
        out.put("advancements", advancements);
    }

    static Iterable<EntityType<?>> entityTypes() {
        return BuiltInRegistries.ENTITY_TYPE;
    }

    static String blockEntityNbt(BlockEntity blockEntity, MinecraftServer server) {
        return blockEntity.saveWithFullMetadata().toString();
    }

    static String entityNbt(Entity entity, MinecraftServer server) {
        return entity.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString();
    }

    static Entity create(EntityType<?> type, ServerLevel level) {
        return type.create(level);
    }

    static String dimension(ServerLevel level) {
        return ModaccelFingerprint.id(level.dimension());
    }

    static Iterable<Long> forcedChunks(ServerLevel level) {
        return level.getForcedChunks();
    }

    /** The tickets on one chunk as "type level", sorted; the time each was made is left out. */
    static List<String> tickets(ServerLevel level, long chunk) {
        List<String> out = new ArrayList<>();
        Object all = ticketMap(level);
        if (!(all instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> map)) return out;
        Object set = map.get(chunk);
        if (set instanceof Iterable<?> tickets) for (Object ticket : tickets) {
            if (ticket instanceof Ticket<?> t) out.add(t.getType() + " " + t.getTicketLevel());
        }
        Collections.sort(out);
        return out;
    }

    static final Map<Object, Field> TICKET_FIELDS = new WeakHashMap<>();

    /** DistanceManager's ticket map, found by its type: its field name differs with the names the loader runs. */
    static Object ticketMap(ServerLevel level) {
        DistanceManager manager = level.getChunkSource().chunkMap.getDistanceManager();
        try {
            Field field = TICKET_FIELDS.get(manager.getClass());
            if (field == null) {
                for (Class<?> c = manager.getClass(); c != null && field == null; c = c.getSuperclass())
                    for (Field candidate : c.getDeclaredFields())
                        if (candidate.getType() == it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.class) { field = candidate; break; }
                if (field == null) return null;
                field.setAccessible(true);
                TICKET_FIELDS.put(manager.getClass(), field);
            }
            return field.get(manager);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return null;
        }
    }

    static void scheduledTicks(ServerLevel level, LevelChunk chunk, List<String> out) {
        long now = level.getGameTime();
        if (chunk.getBlockTicks() instanceof LevelChunkTicks<?> blocks) blocks.getAll().forEach(tick -> out.add(tick(tick, now)));
        if (chunk.getFluidTicks() instanceof LevelChunkTicks<?> fluids) fluids.getAll().forEach(tick -> out.add(tick(tick, now)));
    }

    static String tick(ScheduledTick<?> tick, long now) {
        Object type = tick.type();
        String id = type instanceof net.minecraft.world.level.block.Block block ? String.valueOf(BuiltInRegistries.BLOCK.getKey(block))
            : type instanceof net.minecraft.world.level.material.Fluid fluid ? String.valueOf(BuiltInRegistries.FLUID.getKey(fluid)) : String.valueOf(type);
        return tick.pos().getX() + "," + tick.pos().getY() + "," + tick.pos().getZ() + " " + id + " +" + (tick.triggerTick() - now) + " " + tick.priority();
    }

    static boolean canFreeze() {
        return true;
    }

    static boolean frozen(MinecraftServer server) {
        return server.tickRateManager().isFrozen();
    }

    static void freeze(MinecraftServer server, boolean frozen) {
        server.tickRateManager().setFrozen(frozen);
    }

    static void tickServer(MinecraftServer server) {
        server.tickServer(() -> true);
    }

    static boolean save(MinecraftServer server) {
        return server.saveEverything(true, true, true);
    }

    static boolean empty(LevelChunkSection section) {
        return section.hasOnlyAir();
    }

    static boolean entitiesLoaded(ServerLevel level, long chunk) {
        return level.areEntitiesLoaded(chunk);
    }

    static boolean isRandom(Object value) {
        return value instanceof RandomSource || value instanceof Random;
    }

    static boolean setSeed(Object value, long seed) {
        if (!(value instanceof RandomSource random)) return false;
        random.setSeed(seed);
        return true;
    }
}
