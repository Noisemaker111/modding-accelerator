package ${package}.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import ${package}.conditions.ICondition;

/**
 * NeoForge's net.neoforged.neoforge.client.data.SpriteSourceProvider: the mod's atlas files,
 * assets/NAMESPACE/atlases/ATLAS.json, in the game's own form ({"sources": [...]}). Difference: a source given with
 * conditions is written without them (the game's atlases have no conditions on Fabric), so it is always in the atlas.
 */
public abstract class SpriteSourceProvider extends JsonCodecProvider<List<SpriteSource>> {

    private final Map<Identifier, SourceList> atlases = new LinkedHashMap<>();

    public SpriteSourceProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String modId) {
        super(output, PackOutput.Target.RESOURCE_PACK, "atlases", SpriteSources.FILE_CODEC, lookupProvider, modId);
    }

    protected final SourceList atlas(Identifier id) {
        return this.atlases.computeIfAbsent(id, i -> new SourceList());
    }

    @Override
    void collected() {
        this.atlases.forEach((id, sources) -> unconditional(id, sources.sources));
        this.atlases.clear();
    }

    public static final class SourceList {

        private final List<SpriteSource> sources = new ArrayList<>();

        public SourceList addSource(SpriteSource source) {
            this.sources.add(source);
            return this;
        }

        public SourceList addSource(SpriteSource source, ICondition... conditions) {
            return addSource(source);
        }
    }
}
