package ${package}.extensions;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's IDataComponentHolderExtension and MutableDataComponentHolder as ItemStack has them: get, getOrDefault, has,
 * set, update, remove and copyFrom taking a supplied component type (a DeferredHolder from registration), each the
 * game's own method with the type the supplier gives. Added to ItemStack by ItemStackComponentsMixin, and for the
 * compile by Loom's interface injection.
 */
public interface IItemStackComponentsExtension {

    private ItemStack self() {
        return (ItemStack) (Object) this;
    }

    default <T> T get(Supplier<? extends DataComponentType<? extends T>> type) {
        return self().get(type.get());
    }

    default <T> T getOrDefault(Supplier<? extends DataComponentType<? extends T>> type, T defaultValue) {
        return self().getOrDefault(type.get(), defaultValue);
    }

    /** NeoForge's ItemStack.isComponentsPatchEmpty: whether the stack has no components of its own beyond its item's. */
    default boolean isComponentsPatchEmpty() {
        return self().getComponentsPatch().isEmpty();
    }

    default boolean has(Supplier<? extends DataComponentType<?>> type) {
        return self().has(type.get());
    }

    @SuppressWarnings("unchecked")
    default <T> T set(Supplier<? extends DataComponentType<? super T>> type, T value) {
        // The game's set takes DataComponentType<T> on 26.x and DataComponentType<? super T> on 1.21.1.
        return self().set((DataComponentType<T>) (DataComponentType<?>) type.get(), value);
    }

    default <T> T update(Supplier<? extends DataComponentType<T>> type, T value, UnaryOperator<T> updater) {
        return self().update(type.get(), value, updater);
    }

    default <T, U> T update(Supplier<? extends DataComponentType<T>> type, T value, U updateContext, BiFunction<T, U, T> updater) {
        return self().update(type.get(), value, updateContext, updater);
    }

    default <T> T remove(Supplier<? extends DataComponentType<? extends T>> type) {
        return self().remove(type.get());
    }

    default void copyFrom(DataComponentHolder src, DataComponentType<?>... types) {
        for (DataComponentType<?> type : types) copy(src, type);
    }

    @SuppressWarnings("unchecked")
    default void copyFrom(DataComponentHolder src, Supplier<? extends DataComponentType<?>>... types) {
        for (Supplier<? extends DataComponentType<?>> type : types) copy(src, type.get());
    }

    private <T> void copy(DataComponentHolder src, DataComponentType<T> type) {
        self().set(type, src.get(type));
    }
}
