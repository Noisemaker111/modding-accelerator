package ${package}.capabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.lookup.v1.entity.EntityApiLookup;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.EntityCapability, as a Fabric API EntityApiLookup of the same name
 * (see BlockCapability). Fabric API has no item, fluid or energy lookup for entities; the built-in ones
 * (Capabilities.*.ENTITY) are lookups the bridge creates, seen by mods converted from NeoForge.
 */
public final class EntityCapability<T, C> extends BaseCapability<T, C> {

    private static final Map<Identifier, EntityCapability<?, ?>> ALL = new LinkedHashMap<>();

    private final EntityApiLookup<Object, C> lookup;
    private final Function<Object, T> fromLookup;
    private final Function<T, Object> toLookup;
    private final Map<EntityType<?>, List<ICapabilityProvider<Entity, C, T>>> providers = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    private <A> EntityCapability(Identifier name, Class<T> typeClass, Class<C> contextClass, EntityApiLookup<A, C> lookup, Function<A, T> fromLookup, Function<T, A> toLookup) {
        super(name, typeClass, contextClass);
        this.lookup = (EntityApiLookup<Object, C>) lookup;
        this.fromLookup = (Function<Object, T>) fromLookup;
        this.toLookup = (Function<T, Object>) toLookup;
    }

    public static <T, C> EntityCapability<T, C> create(Identifier name, Class<T> typeClass, Class<C> contextClass) {
        return bridged(name, typeClass, contextClass, EntityApiLookup.get(name, typeClass, contextClass), Function.identity(), Function.identity());
    }

    public static <T> EntityCapability<T, @Nullable Void> createVoid(Identifier name, Class<T> typeClass) {
        return create(name, typeClass, Void.class);
    }

    public static <T> EntityCapability<T, @Nullable Direction> createSided(Identifier name, Class<T> typeClass) {
        return create(name, typeClass, Direction.class);
    }

    @SuppressWarnings("unchecked")
    static synchronized <T, C, A> EntityCapability<T, C> bridged(Identifier name, Class<T> typeClass, Class<C> contextClass, EntityApiLookup<A, C> lookup, Function<A, T> fromLookup, Function<T, A> toLookup) {
        EntityCapability<?, ?> existing = ALL.get(name);
        if (existing != null) {
            if (existing.typeClass() != typeClass || existing.contextClass() != contextClass) throw new IllegalStateException("Capability " + name + " already exists with other classes");
            return (EntityCapability<T, C>) existing;
        }
        EntityCapability<T, C> capability = new EntityCapability<>(name, typeClass, contextClass, lookup, fromLookup, toLookup);
        ALL.put(name, capability);
        return capability;
    }

    public static synchronized List<EntityCapability<?, ?>> getAll() {
        return List.copyOf(ALL.values());
    }

    public @Nullable T getCapability(Entity entity, C context) {
        Object found = lookup.find(entity, context);
        return found == null ? null : fromLookup.apply(found);
    }

    synchronized void register(EntityType<?> type, ICapabilityProvider<Entity, C, T> provider) {
        List<ICapabilityProvider<Entity, C, T>> list = providers.get(type);
        if (list == null) {
            List<ICapabilityProvider<Entity, C, T>> created = new ArrayList<>();
            providers.put(type, created);
            lookup.registerForTypes((entity, context) -> {
                for (ICapabilityProvider<Entity, C, T> each : created) {
                    T found = each.getCapability(entity, context);
                    if (found != null) return toLookup.apply(found);
                }
                return null;
            }, type);
            list = created;
        }
        list.add(provider);
    }

    boolean isRegistered(EntityType<?> type) {
        return lookup.getProvider(type) != null;
    }

    @Override
    public String toString() {
        return "EntityCapability[" + name() + "]";
    }
}
