package mmt.packprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The game calls the behaviour fingerprint makes that changed between Minecraft versions, for Minecraft 1.16 to 1.17,
 * as Java 8 source (1.16 servers run on Java 8): built-in registries only (no tags, no data-pack registries), saved data
 * with save(CompoundTag), no scheduled-tick containers to read, and no tick manager to freeze the world.
 * MinecraftServer.tickServer is protected in 1.16, so a tick is run through reflection by the names the loaders run it
 * under (Mojang, Forge's SRG, Fabric's intermediary).
 */
final class MmtFingerprintGame {

    private MmtFingerprintGame() {}

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static void registries(MinecraftServer server, Map<String, Collection<String>> out) {
        for (Object builtIn : (Iterable) Registry.REGISTRY) {
            List<String> ids = new ArrayList<String>();
            for (Object id : ((Registry) builtIn).keySet()) ids.add(String.valueOf(id));
            out.put(String.valueOf(((Registry) Registry.REGISTRY).getKey(builtIn)), ids);
        }
        List<String> recipes = new ArrayList<String>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) recipes.add(recipe.getId() + " " + Registry.RECIPE_TYPE.getKey(recipe.getType()));
        out.put("recipes", recipes);
        List<String> loot = new ArrayList<String>();
        for (Object id : server.getLootTables().getIds()) loot.add(String.valueOf(id));
        out.put("loot_tables", loot);
        List<String> advancements = new ArrayList<String>();
        for (net.minecraft.advancements.Advancement advancement : server.getAdvancements().getAllAdvancements()) advancements.add(String.valueOf(advancement.getId()));
        out.put("advancements", advancements);
    }

    static Iterable<EntityType<?>> entityTypes() {
        return Registry.ENTITY_TYPE;
    }

    static String blockEntityNbt(BlockEntity blockEntity, MinecraftServer server) {
        return blockEntity.save(new net.minecraft.nbt.CompoundTag()).toString();
    }

    static String entityNbt(Entity entity, MinecraftServer server) {
        return entity.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString();
    }

    static Entity create(EntityType<?> type, ServerLevel level) {
        return type.create(level);
    }

    static String dimension(ServerLevel level) {
        return MmtFingerprint.id(level.dimension());
    }

    static Iterable<Long> forcedChunks(ServerLevel level) {
        return level.getForcedChunks();
    }

    static List<String> tickets(ServerLevel level, long chunk) {
        List<String> out = new ArrayList<String>();
        Object all = ticketMap(level);
        if (!(all instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap)) return out;
        Object set = ((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) all).get(chunk);
        if (set instanceof Iterable) for (Object ticket : (Iterable<?>) set) {
            if (ticket instanceof Ticket) out.add(((Ticket<?>) ticket).getType() + " " + ((Ticket<?>) ticket).getTicketLevel());
        }
        Collections.sort(out);
        return out;
    }

    static final Map<Object, Field> FIELDS = new WeakHashMap<Object, Field>();

    /** The chunk map's distance manager, and its ticket map, found by their types: field names differ with the loader. */
    static Object ticketMap(ServerLevel level) {
        try {
            ChunkMap map = level.getChunkSource().chunkMap;
            Object manager = byType(map, DistanceManager.class);
            return manager == null ? null : byType(manager, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.class);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return null;
        }
    }

    static Object byType(Object owner, Class<?> type) throws ReflectiveOperationException {
        Field field = FIELDS.get(owner.getClass().getName() + "/" + type.getName());
        if (field == null) {
            for (Class<?> c = owner.getClass(); c != null && field == null; c = c.getSuperclass())
                for (Field candidate : c.getDeclaredFields())
                    if (type.isAssignableFrom(candidate.getType()) && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) { field = candidate; break; }
            if (field == null) return null;
            field.setAccessible(true);
            FIELDS.put(owner.getClass().getName() + "/" + type.getName(), field);
        }
        return field.get(owner);
    }

    /** 1.16 and 1.17 keep scheduled ticks in level-wide lists, not per chunk: none are read. */
    static void scheduledTicks(ServerLevel level, LevelChunk chunk, List<String> out) {}

    static boolean canFreeze() {
        return false;
    }

    static boolean frozen(MinecraftServer server) {
        return false;
    }

    static void freeze(MinecraftServer server, boolean frozen) {}

    static final String[] TICK_NAMES = { "tickServer", "m_5705_", "func_71217_p", "method_3748" };

    static void tickServer(MinecraftServer server) {
        for (String name : TICK_NAMES) {
            try {
                Method tick = MinecraftServer.class.getDeclaredMethod(name, BooleanSupplier.class);
                tick.setAccessible(true);
                tick.invoke(server, new BooleanSupplier() { public boolean getAsBoolean() { return true; } });
                return;
            } catch (NoSuchMethodException other) {
                // the next name
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not run a server tick", failure);
            }
        }
        throw new IllegalStateException("No server tick method under any known name");
    }

    static boolean save(MinecraftServer server) {
        return server.saveAllChunks(true, true, true);
    }

    static boolean empty(LevelChunkSection section) {
        return LevelChunkSection.isEmpty(section);
    }

    static boolean entitiesLoaded(ServerLevel level, long chunk) {
        return true;
    }

    static boolean isRandom(Object value) {
        return value instanceof Random;
    }

    static boolean setSeed(Object value, long seed) {
        return false;
    }
}
