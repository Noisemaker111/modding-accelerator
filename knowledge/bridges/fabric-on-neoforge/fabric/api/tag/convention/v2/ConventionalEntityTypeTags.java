package ${package}.fabric.api.tag.convention.v2;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * Fabric API's net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags: the same tag keys, in the shared
 * c: namespace, which NeoForge's own convention tags (Tags.EntityTypes) fill as well.
 */
public final class ConventionalEntityTypeTags {

    public static final TagKey<EntityType<?>> BOSSES = register("bosses");
    public static final TagKey<EntityType<?>> MINECARTS = register("minecarts");
    public static final TagKey<EntityType<?>> BOATS = register("boats");
    public static final TagKey<EntityType<?>> ITEM_FRAMES = register("item_frames");
    public static final TagKey<EntityType<?>> CAPTURING_NOT_SUPPORTED = register("capturing_not_supported");
    public static final TagKey<EntityType<?>> TELEPORTING_NOT_SUPPORTED = register("teleporting_not_supported");

    private ConventionalEntityTypeTags() {}

    private static TagKey<EntityType<?>> register(String tagId) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("c", tagId));
    }
}
