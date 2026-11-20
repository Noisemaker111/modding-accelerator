package ${package};

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.ModifyDefaultComponentsEvent, posted on the mod bus from Fabric API's
 * DefaultItemComponentEvents.MODIFY (after every item has its default components, before they are frozen): each patch
 * a mod builds is set on the item's defaults (a removal removes the component).
 */
public class ModifyDefaultComponentsEvent extends Event implements IModBusEvent {

    private final DefaultItemComponentEvents.ModifyContext context;

    ModifyDefaultComponentsEvent(DefaultItemComponentEvents.ModifyContext context) {
        this.context = context;
    }

    static void hook() {
        DefaultItemComponentEvents.MODIFY.register(context -> Bus.INSTANCE.post(new ModifyDefaultComponentsEvent(context)));
    }

    public void modify(ItemLike item, Consumer<DataComponentPatch.Builder> patch) {
        context.modify(item.asItem(), builder -> apply(builder, patch));
    }

    public void modifyMatching(Predicate<? super Item> predicate, Consumer<DataComponentPatch.Builder> patch) {
        context.modify(item -> predicate.test(item), (builder, item) -> apply(builder, patch));
    }

    public Stream<Item> getAllItems() {
        return BuiltInRegistries.ITEM.stream();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(DataComponentMap.Builder builder, Consumer<DataComponentPatch.Builder> patch) {
        DataComponentPatch.Builder changes = DataComponentPatch.builder();
        patch.accept(changes);
        for (var entry : changes.build().entrySet()) builder.set((net.minecraft.core.component.DataComponentType) entry.getKey(), entry.getValue().orElse(null));
    }
}
