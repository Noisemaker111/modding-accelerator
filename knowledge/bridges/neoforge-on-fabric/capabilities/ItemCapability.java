package ${package}.capabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.lookup.v1.item.ItemApiLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.capabilities.ItemCapability, as a Fabric API ItemApiLookup of the same name (see
 * BlockCapability). The built-in ones (Capabilities.Item.ITEM, Fluid.ITEM) are Fabric's ItemStorage.ITEM and
 * FluidStorage.ITEM, whose context is a ContainerItemContext: an ItemAccess crosses as one (TransferBridge.context).
 */
public final class ItemCapability<T, C> extends BaseCapability<T, C> {

    private static final Map<Identifier, ItemCapability<?, ?>> ALL = new LinkedHashMap<>();

    private final ItemApiLookup<Object, Object> lookup;
    private final Function<Object, T> fromLookup;
    private final Function<T, Object> toLookup;
    private final Function<C, Object> contextToLookup;
    private final Function<Object, C> contextFromLookup;
    private final Map<Item, List<ICapabilityProvider<ItemStack, C, T>>> providers = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    private <A, L> ItemCapability(
        Identifier name,
        Class<T> typeClass,
        Class<C> contextClass,
        ItemApiLookup<A, L> lookup,
        Function<A, T> fromLookup,
        Function<T, A> toLookup,
        Function<C, L> contextToLookup,
        Function<L, C> contextFromLookup
    ) {
        super(name, typeClass, contextClass);
        this.lookup = (ItemApiLookup<Object, Object>) lookup;
        this.fromLookup = (Function<Object, T>) fromLookup;
        this.toLookup = (Function<T, Object>) toLookup;
        this.contextToLookup = (Function<C, Object>) contextToLookup;
        this.contextFromLookup = (Function<Object, C>) contextFromLookup;
    }

    public static <T, C> ItemCapability<T, C> create(Identifier name, Class<T> typeClass, Class<C> contextClass) {
        return bridged(name, typeClass, contextClass, ItemApiLookup.get(name, typeClass, contextClass), Function.identity(), Function.identity(), Function.identity(), Function.identity());
    }

    public static <T> ItemCapability<T, @Nullable Void> createVoid(Identifier name, Class<T> typeClass) {
        return create(name, typeClass, Void.class);
    }

    @SuppressWarnings("unchecked")
    static synchronized <T, C, A, L> ItemCapability<T, C> bridged(
        Identifier name,
        Class<T> typeClass,
        Class<C> contextClass,
        ItemApiLookup<A, L> lookup,
        Function<A, T> fromLookup,
        Function<T, A> toLookup,
        Function<C, L> contextToLookup,
        Function<L, C> contextFromLookup
    ) {
        ItemCapability<?, ?> existing = ALL.get(name);
        if (existing != null) {
            if (existing.typeClass() != typeClass || existing.contextClass() != contextClass) throw new IllegalStateException("Capability " + name + " already exists with other classes");
            return (ItemCapability<T, C>) existing;
        }
        ItemCapability<T, C> capability = new ItemCapability<>(name, typeClass, contextClass, lookup, fromLookup, toLookup, contextToLookup, contextFromLookup);
        ALL.put(name, capability);
        return capability;
    }

    public static synchronized List<ItemCapability<?, ?>> getAll() {
        return List.copyOf(ALL.values());
    }

    public @Nullable T getCapability(ItemStack stack, C context) {
        Object found = lookup.find(stack, contextToLookup.apply(context));
        return found == null ? null : fromLookup.apply(found);
    }

    synchronized void register(Item item, ICapabilityProvider<ItemStack, C, T> provider) {
        List<ICapabilityProvider<ItemStack, C, T>> list = providers.get(item);
        if (list == null) {
            List<ICapabilityProvider<ItemStack, C, T>> created = new ArrayList<>();
            providers.put(item, created);
            lookup.registerForItems((stack, context) -> {
                for (ICapabilityProvider<ItemStack, C, T> each : created) {
                    T found = each.getCapability(stack, contextFromLookup.apply(context));
                    if (found != null) return toLookup.apply(found);
                }
                return null;
            }, item);
            list = created;
        }
        list.add(provider);
    }

    boolean isRegistered(Item item) {
        return lookup.getProvider(item) != null;
    }

    @Override
    public String toString() {
        return "ItemCapability[" + name() + "]";
    }
}
