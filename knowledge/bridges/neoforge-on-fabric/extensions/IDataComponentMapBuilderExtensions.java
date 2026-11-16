package ${package}.extensions;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.item.v1.FabricComponentMapBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IDataComponentMapBuilderExtensions, as far as mods call it on a
 * DataComponentMap.Builder: has (whether the builder holds a value, Fabric API's contains) and set with a supplied type.
 * Added to the builder by ComponentMapBuilderExtensionsMixin, and for the compile by Loom's interface injection. get and
 * getOrDefault are Fabric API's own on the builder.
 */
public interface IDataComponentMapBuilderExtensions {

    default boolean has(DataComponentType<?> type) {
        return ((FabricComponentMapBuilder) this).contains(type);
    }

    default <T> DataComponentMap.Builder set(Supplier<? extends DataComponentType<T>> type, T value) {
        return ((DataComponentMap.Builder) this).set(type.get(), value);
    }
}
