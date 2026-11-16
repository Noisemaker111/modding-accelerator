package ${package}.fabric.api.item.v1;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentType;

/**
 * Fabric API's FabricComponentMapBuilder on NeoForge, added to DataComponentMap.Builder by the bridge's mixin
 * ComponentMapBuilderMixin, which reads the builder's map as Fabric's does. get and getOrDefault are NeoForge's: its
 * Builder is a DataComponentGetter already. NeoForge's getOrDefault does not put the default in the builder, where
 * Fabric's does; getOrCreate puts it in, as on Fabric.
 */
public interface FabricComponentMapBuilder extends DataComponentGetter {

    /** The builder's value for the type, after putting the fallback's value in when it has none (never null). */
    default <T> T getOrCreate(DataComponentType<T> type, Supplier<T> fallback) {
        throw new AssertionError("The Fabric bridge's mixin ComponentMapBuilderMixin implements this");
    }

    /** The builder's list for the type, made mutable and put back (an empty one when it has none). */
    default <T> List<T> getOrEmpty(DataComponentType<List<T>> type) {
        throw new AssertionError("The Fabric bridge's mixin ComponentMapBuilderMixin implements this");
    }

    /** Whether the builder has a value for the type. */
    default boolean contains(DataComponentType<?> type) {
        throw new AssertionError("The Fabric bridge's mixin ComponentMapBuilderMixin implements this");
    }
}
