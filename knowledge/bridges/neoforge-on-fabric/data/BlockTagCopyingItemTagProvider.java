package ${package}.data;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.TagBuilder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge's net.neoforged.neoforge.common.data.BlockTagCopyingItemTagProvider: an item tag provider whose copy(block
 * tag, item tag) gives the item tag every entry of the block tag (a block's item has the block's id), once the block
 * tags it was given are built.
 */
public abstract class BlockTagCopyingItemTagProvider extends TagsProvider<Item> {

    private final CompletableFuture<TagsProvider.TagLookup<Block>> blockTags;
    private TagsProvider.TagLookup<Block> blockTagLookup;
    protected final String modId;

    public BlockTagCopyingItemTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, CompletableFuture<TagsProvider.TagLookup<Block>> blockTags, String modId) {
        super(output, Registries.ITEM, lookupProvider);
        this.blockTags = blockTags;
        this.modId = modId;
    }

    protected void copy(TagKey<Block> blockTag, TagKey<Item> itemTag) {
        TagBuilder from = this.blockTagLookup.apply(blockTag).orElseThrow(() -> new IllegalStateException("Missing block tag " + blockTag.location() + " to copy into " + itemTag.location()));
        TagBuilder to = getOrCreateRawBuilder(itemTag);
        from.build().forEach(to::add);
    }

    @Override
    protected CompletableFuture<HolderLookup.Provider> createContentsProvider() {
        // The game's contents provider runs addTags (and so copy): the block tags are taken first.
        return this.blockTags.thenCompose(tags -> {
            this.blockTagLookup = tags;
            return BlockTagCopyingItemTagProvider.super.createContentsProvider();
        });
    }
}
