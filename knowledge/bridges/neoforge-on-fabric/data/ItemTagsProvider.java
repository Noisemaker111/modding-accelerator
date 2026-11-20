package ${package}.data;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.item.Item;

/** NeoForge's net.neoforged.neoforge.common.data.ItemTagsProvider: the game's tag provider for items, as BlockTagsProvider. */
public abstract class ItemTagsProvider extends TagsProvider<Item> {

    protected final String modId;

    public ItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String modId) {
        super(output, Registries.ITEM, lookupProvider);
        this.modId = modId;
    }
}
