package ${package}.extensions;

import net.minecraft.core.Holder;
import org.jspecify.annotations.Nullable;
import ${package}.datamaps.DataMapType;
import ${package}.datamaps.DataMaps;

/**
 * What NeoForge adds to the game's Holder for data maps: getData, the holder's value in a data map (null when it has
 * none). Injected into Holder on Fabric (HolderExtensionMixin), read from DataMaps.
 */
public interface IHolderExtension<T> {

    @SuppressWarnings("unchecked")
    default <A> @Nullable A getData(DataMapType<T, A> type) {
        return DataMaps.get(type, (Holder<T>) this);
    }
}
