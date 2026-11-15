package ${package};

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/**
 * NeoForge's net.neoforged.neoforge.registries.DeferredHolder: a holder for an entry a DeferredRegister registers
 * later. Like NeoForge's, it is a stand-alone Holder.Reference whose reads go to the registry's own holder once the
 * entry exists.
 */
public class DeferredHolder<R, T extends R> extends Holder.Reference<R> implements Supplier<T> {

    protected final ResourceKey<R> key;
    private Holder<R> holder;

    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<? extends Registry<R>> registry, Identifier id) {
        return create(ResourceKey.create(registry, id));
    }

    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<R> key) {
        return new DeferredHolder<>(key);
    }

    protected DeferredHolder(ResourceKey<R> key) {
        super(Holder.Reference.Type.STAND_ALONE, registryOf(key), key, null);
        this.key = key;
    }

    @SuppressWarnings("unchecked")
    static <R> Registry<R> registryOf(ResourceKey<R> key) {
        return (Registry<R>) BuiltInRegistries.REGISTRY.getValue(key.registryKey().identifier());
    }

    void bind() {
        if (holder != null) return;
        Registry<R> registry = registryOf(key);
        if (registry != null) holder = registry.get(key).orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T value() {
        bind();
        if (holder == null) throw new NullPointerException("Trying to access unbound value: " + key);
        return (T) holder.value();
    }

    @Override
    public T get() {
        return value();
    }

    public Optional<T> asOptional() {
        return isBound() ? Optional.of(value()) : Optional.empty();
    }

    public Identifier getId() {
        return key.identifier();
    }

    public ResourceKey<R> getKey() {
        return key;
    }

    @Override
    public boolean isBound() {
        bind();
        return holder != null && holder.isBound();
    }

    @Override
    public boolean areComponentsBound() {
        bind();
        return holder != null && holder.areComponentsBound();
    }

    @Override
    public DataComponentMap components() {
        bind();
        return holder == null ? DataComponentMap.EMPTY : holder.components();
    }

    @Override
    public boolean is(TagKey<R> tag) {
        bind();
        return holder != null && holder.is(tag);
    }

    @Override
    public boolean is(Holder<R> other) {
        bind();
        return holder != null && holder.is(other);
    }

    @Override
    public Stream<TagKey<R>> tags() {
        bind();
        return holder == null ? Stream.empty() : holder.tags();
    }

    @Override
    public boolean canSerializeIn(HolderOwner<R> owner) {
        bind();
        return holder != null && holder.canSerializeIn(owner);
    }

    public Holder<R> getDelegate() {
        bind();
        return holder != null ? holder : this;
    }

    @Override
    public String toString() {
        return "DeferredHolder{" + key + "}";
    }
}
