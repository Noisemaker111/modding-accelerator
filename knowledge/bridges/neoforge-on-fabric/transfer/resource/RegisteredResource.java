package ${package}.transfer.resource;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.TypedInstance;

/** NeoForge's net.neoforged.neoforge.transfer.resource.RegisteredResource: a resource of a registry object. */
public interface RegisteredResource<T> extends Resource, TypedInstance<T> {

    T value();

    default boolean is(Predicate<Holder<T>> predicate) {
        return predicate.test(typeHolder());
    }
}
