package ${package}.conditions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * NeoForge's net.neoforged.neoforge.common.conditions.ICondition: a condition on loading a data entry, with the codec
 * that writes it. A mod's own conditions implement test and codec as on NeoForge. On Fabric the loaders' own types
 * (Conditions.fabric) become Fabric API resource conditions where data is generated, and convert moves the
 * conditions in the mod's shipped data files to Fabric's form; CODEC reads and writes NeoForge's form ("type").
 */
public interface ICondition {

    /** The condition codecs by type id: NeoForge's own here; a mod's own register(...) adds its codec. */
    Map<ResourceLocation, MapCodec<? extends ICondition>> TYPES = new ConcurrentHashMap<>(Conditions.builtIn());

    Codec<ICondition> CODEC = ResourceLocation.CODEC.dispatch("type", ICondition::typeOf, id -> {
        MapCodec<? extends ICondition> codec = TYPES.get(id);
        if (codec == null) throw new IllegalArgumentException("Unknown condition type " + id);
        return codec;
    });

    Codec<List<ICondition>> LIST_CODEC = CODEC.listOf();

    boolean test(IContext context);

    MapCodec<? extends ICondition> codec();

    static void register(ResourceLocation type, MapCodec<? extends ICondition> codec) {
        TYPES.put(type, codec);
    }

    private static ResourceLocation typeOf(ICondition condition) {
        MapCodec<? extends ICondition> codec = condition.codec();
        for (var e : TYPES.entrySet()) if (e.getValue() == codec) return e.getKey();
        throw new IllegalArgumentException("Condition codec of " + condition + " is not registered");
    }

    /** What a condition may ask while data loads: whether a tag is loaded and what it holds. */
    interface IContext {

        IContext EMPTY = new IContext() {
            @Override
            public <T> boolean isTagLoaded(TagKey<T> key) {
                return false;
            }
        };

        IContext TAGS_INVALID = new IContext() {
            @Override
            public <T> boolean isTagLoaded(TagKey<T> key) {
                throw new UnsupportedOperationException("Tags are not loaded at this point of data loading");
            }
        };

        <T> boolean isTagLoaded(TagKey<T> key);

        default <T> Collection<Holder<T>> getTag(TagKey<T> key) {
            return List.of();
        }
    }
}
