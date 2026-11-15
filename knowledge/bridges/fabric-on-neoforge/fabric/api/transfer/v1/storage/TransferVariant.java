package ${package}.fabric.api.transfer.v1.storage;

import java.util.Objects;
import net.minecraft.core.TypedInstance;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant: an immutable object (an item, a fluid) with a
 * data component patch; NeoForge's transfer Resource is the same thing (ItemResource, FluidResource).
 */
public interface TransferVariant<O> extends DataComponentHolder, TypedInstance<O> {

    boolean isBlank();

    O getObject();

    DataComponentPatch getComponentsPatch();

    @Override
    DataComponentMap getComponents();

    default boolean hasComponents() {
        return !getComponentsPatch().isEmpty();
    }

    default boolean componentsMatch(DataComponentPatch other) {
        return Objects.equals(getComponentsPatch(), other);
    }

    default boolean isOf(O object) {
        return getObject() == object;
    }

    default TransferVariant<O> withComponents(DataComponentPatch patch) {
        throw new UnsupportedOperationException("withComponents is not supported by this TransferVariant");
    }
}
