package ${package}.data;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.block.Block;

/**
 * NeoForge's net.neoforged.neoforge.common.data.BlockTagsProvider: from 26.3 the game's own TagsProvider (whose tag(...)
 * takes registry keys), as NeoForge's is. The mod id names nothing in the files written; NeoForge also
 * puts it in the provider's name, which the game's provider keeps final here.
 */
public abstract class BlockTagsProvider extends TagsProvider<Block> {

    protected final String modId;

    public BlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String modId) {
        super(output, Registries.BLOCK, lookupProvider);
        this.modId = modId;
    }
}
