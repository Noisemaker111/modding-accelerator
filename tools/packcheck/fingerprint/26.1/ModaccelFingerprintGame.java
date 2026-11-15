package modaccel.packprobe;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * The game calls the behaviour fingerprint makes that changed between Minecraft versions, for Minecraft 26.1 and later:
 * the game runs in Mojang names and MinecraftServer.tickServer is protected, so a tick is run through reflection by its
 * name; as from 1.21.6 an entity saves into a ValueOutput and chunk tickets live in the chunk source's TicketStorage. ModaccelFingerprint is compiled with the variant in tools/packcheck/fingerprint/
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
            registry.getTags().forEach(named -> {
                List<String> members = new ArrayList<>();
                named.forEach(holder -> members.add(holder.unwrapKey().map(ModaccelFingerprint::id).orElse("(direct)")));
                Collections.sort(members);
                out.computeIfAbsent("tags/" + name, key -> new ArrayList<>()).add(ModaccelFingerprint.id(named.key()) + "=" + String.join(",", members));
            });
        });
        List<String> recipes = new ArrayList<>();
        for (var holder : server.getRecipeManager().getRecipes()) recipes.add(ModaccelFingerprint.id(holder.id()) + " " + BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
        out.put("recipes", recipes);
        List<String> loot = new ArrayList<>();
        server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds().forEach(key -> loot.add(ModaccelFingerprint.id(key)));
        out.put("loot_tables", loot);
        List<String> advancements = new ArrayList<>();
        for (var holder : server.getAdvancements().getAllAdvancements()) advancements.add(String.valueOf(holder.id()));
        out.put("advancements", advancements);
    }

    static Iterable<EntityType<?>> entityTypes() {
        return BuiltInRegistries.ENTITY_TYPE;
    }

    static String blockEntityNbt(BlockEntity blockEntity, MinecraftServer server) {
        return blockEntity.saveWithFullMetadata(server.registryAccess()).toString();
    }

    static String entityNbt(Entity entity, MinecraftServer server) {
        net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult().toString();
    }

    static Entity create(EntityType<?> type, ServerLevel level) {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE);
    }

    static String dimension(ServerLevel level) {
        return ModaccelFingerprint.id(level.dimension());
    }

    static Iterable<Long> forcedChunks(ServerLevel level) {
        TicketStorage storage = storage(level);
        return storage == null ? new ArrayList<Long>() : storage.getForceLoadedChunks();
    }

    /** The tickets on one chunk as "type level", sorted; how long each has left is left out. */
    static List<String> tickets(ServerLevel level, long chunk) {
        List<String> out = new ArrayList<>();
        TicketStorage storage = storage(level);
        if (storage == null) return out;
        for (Ticket ticket : storage.getTickets(chunk)) out.add(BuiltInRegistries.TICKET_TYPE.getKey(ticket.getType()) + " " + ticket.getTicketLevel());
        Collections.sort(out);
        return out;
    }

    static final Map<Object, Field> STORAGE_FIELDS = new WeakHashMap<>();

    /** The chunk source's ticket storage, found by its type: its field name differs with the names the loader runs. */
    static TicketStorage storage(ServerLevel level) {
        Object source = level.getChunkSource();
        try {
            Field field = STORAGE_FIELDS.get(source.getClass());
            if (field == null) {
                for (Class<?> c = source.getClass(); c != null && field == null; c = c.getSuperclass())
                    for (Field candidate : c.getDeclaredFields())
                        if (TicketStorage.class.isAssignableFrom(candidate.getType())) { field = candidate; break; }
                if (field == null) return null;
                field.setAccessible(true);
                STORAGE_FIELDS.put(source.getClass(), field);
            }
            return (TicketStorage) field.get(source);
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
        try {
            java.lang.reflect.Method tick = MinecraftServer.class.getDeclaredMethod("tickServer", java.util.function.BooleanSupplier.class);
            tick.setAccessible(true);
            tick.invoke(server, (java.util.function.BooleanSupplier) () -> true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not run a server tick", failure);
        }
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
