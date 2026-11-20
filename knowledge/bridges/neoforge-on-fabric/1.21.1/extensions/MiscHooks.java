package ${package}.extensions;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.Block;

/**
 * More methods NeoForge 21.1 adds to game classes, each NeoForge's default in the game's own terms, added by the
 * bridge's mixins of the same names and, for the compile, Loom's interface injection.
 */
public final class MiscHooks {

    private MiscHooks() {}

    /** BlockItem.removeFromBlockToItemMap: its block's entry goes (NeoForge calls it when a registry entry is removed; the game never does). */
    public interface BlockItemMap {
        default void removeFromBlockToItemMap(Map<Block, Item> blockToItemMap, Item itemIn) {
            blockToItemMap.remove(((BlockItem) (Object) this).getBlock());
        }
    }

    /** HolderLookup.Provider.holder: the registry entry of a key, when the registry and the entry exist. */
    public interface LookupHolder {
        default <T> Optional<Holder.Reference<T>> holder(ResourceKey<T> key) {
            return ((HolderLookup.Provider) this).lookup(key.registryKey()).flatMap(lookup -> lookup.get(key));
        }
    }

    /** TagsProvider.TagAppender.addTags: each tag in turn (the game's addTag). */
    public interface TagAppenderTags<T> {
        @SuppressWarnings("unchecked")
        default TagsProvider.TagAppender<T> addTags(TagKey<T>... tags) {
            TagsProvider.TagAppender<T> self = (TagsProvider.TagAppender<T>) (Object) this;
            for (TagKey<T> tag : tags) self.addTag(tag);
            return self;
        }
    }

    /** BakedModel.applyTransform: the model's own transform for the display context, applied to the pose. */
    public interface ModelTransform {
        default BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
            BakedModel self = (BakedModel) this;
            self.getTransforms().getTransform(context).apply(applyLeftHandTransform, poseStack);
            return self;
        }
    }
}
