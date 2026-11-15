package ${package};

import com.mojang.datafixers.util.Either;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * NeoForge 21.1's net.neoforged.neoforge.registries.DeferredHolder (Minecraft 1.21.1): a Holder for an entry a
 * DeferredRegister registers later, whose reads go to the registry's own holder once the entry exists.
 */
public class DeferredHolder<R, T extends R> implements Holder<R>, Supplier<T> {

    protected final ResourceKey<R> key;
    private Holder<R> holder;

    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<? extends Registry<R>> registry, ResourceLocation id) {
        return create(ResourceKey.create(registry, id));
    }

    public static <R, T extends R> DeferredHolder<R, T> create(ResourceLocation registry, ResourceLocation id) {
        return create(ResourceKey.<R>createRegistryKey(registry), id);
    }

    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<R> key) {
        return new DeferredHolder<>(key);
    }

    protected DeferredHolder(ResourceKey<R> key) {
        this.key = key;
    }

    @SuppressWarnings("unchecked")
    protected Registry<R> getRegistry() {
        return (Registry<R>) BuiltInRegistries.REGISTRY.get(key.registry());
    }

    void bind() {
        if (holder != null) return;
        Registry<R> registry = getRegistry();
        if (registry != null) holder = registry.getHolder(key).orElse(null);
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

    public ResourceLocation getId() {
        return key.location();
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
    public boolean is(ResourceLocation id) {
        return id.equals(key.location());
    }

    @Override
    public boolean is(ResourceKey<R> other) {
        return other == key;
    }

    @Override
    public boolean is(Predicate<ResourceKey<R>> filter) {
        return filter.test(key);
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
    public Either<ResourceKey<R>, R> unwrap() {
        return Either.left(key);
    }

    @Override
    public Optional<ResourceKey<R>> unwrapKey() {
        return Optional.of(key);
    }

    @Override
    public Holder.Kind kind() {
        return Holder.Kind.REFERENCE;
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
    public boolean equals(Object other) {
        return this == other || other instanceof Holder<?> h && h.kind() == Holder.Kind.REFERENCE && h.unwrapKey().equals(Optional.of(key));
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "DeferredHolder{" + key + "}";
    }
}
