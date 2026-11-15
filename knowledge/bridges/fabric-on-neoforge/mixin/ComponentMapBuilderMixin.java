package ${package}.mixin;

import ${package}.fabric.api.item.v1.FabricComponentMapBuilder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Gives DataComponentMap.Builder Fabric API's FabricComponentMapBuilder at runtime, read from the builder's own map: a
 * value is there once set (set with null removes it), getOrCreate puts a fallback in, and getOrEmpty puts a mutable copy
 * of the list back, as Fabric's builder does.
 */
@Mixin(DataComponentMap.Builder.class)
public abstract class ComponentMapBuilderMixin implements FabricComponentMapBuilder {

    @Shadow
    @Final
    private Reference2ObjectMap<DataComponentType<?>, Object> map;

    @Shadow
    public abstract <T> DataComponentMap.Builder set(DataComponentType<T> type, T value);

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrCreate(DataComponentType<T> type, Supplier<T> fallback) {
        if (!this.map.containsKey(type)) {
            T value = Objects.requireNonNull(fallback.get(), "Cannot insert null values to component map builder");
            this.set(type, value);
        }
        return (T) this.map.get(type);
    }

    @Override
    public <T> List<T> getOrEmpty(DataComponentType<List<T>> type) {
        List<T> list = new ArrayList<>(this.getOrCreate(type, Collections::emptyList));
        this.set(type, list);
        return list;
    }

    @Override
    public boolean contains(DataComponentType<?> type) {
        return this.map.containsKey(type);
    }
}
