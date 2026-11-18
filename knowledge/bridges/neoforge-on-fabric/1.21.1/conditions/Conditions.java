package ${package}.conditions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge's own condition types, written as NeoForge writes them (neoforge:mod_loaded, not, and, or, true, false,
 * tag_empty, item_exists), and each as the Fabric API resource condition that means the same, for the data Fabric
 * generates. A mod's own condition has no Fabric form (fabric returns null): the entry is written without it.
 */
public final class Conditions {

    private Conditions() {}

    /** ICondition.CODEC, read only when a nested condition is: ICondition's types are made from these codecs. */
    static final Codec<ICondition> NESTED = Codec.lazyInitialized(() -> ICondition.CODEC);

    public record Not(ICondition value) implements ICondition {
        static final MapCodec<Not> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(NESTED.fieldOf("value").forGetter(Not::value)).apply(i, Not::new));

        @Override
        public boolean test(IContext context) {
            return !this.value.test(context);
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    public record And(List<ICondition> values) implements ICondition {
        static final MapCodec<And> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(NESTED.listOf().fieldOf("values").forGetter(And::values)).apply(i, And::new));

        @Override
        public boolean test(IContext context) {
            return this.values.stream().allMatch(c -> c.test(context));
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    public record Or(List<ICondition> values) implements ICondition {
        static final MapCodec<Or> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(NESTED.listOf().fieldOf("values").forGetter(Or::values)).apply(i, Or::new));

        @Override
        public boolean test(IContext context) {
            return this.values.stream().anyMatch(c -> c.test(context));
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    public record Constant(boolean value) implements ICondition {
        static final MapCodec<Constant> TRUE = MapCodec.unit(new Constant(true));
        static final MapCodec<Constant> FALSE = MapCodec.unit(new Constant(false));

        @Override
        public boolean test(IContext context) {
            return this.value;
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return this.value ? TRUE : FALSE;
        }
    }

    public record TagEmpty(TagKey<?> tag) implements ICondition {
        static final MapCodec<TagEmpty> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceLocation.CODEC.fieldOf("tag").forGetter(c -> c.tag.location())
        ).apply(i, id -> new TagEmpty(TagKey.create(Registries.ITEM, id))));

        @Override
        public boolean test(IContext context) {
            return !context.isTagLoaded(this.tag) || context.getTag(this.tag).isEmpty();
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    public record ItemExists(ResourceLocation item) implements ICondition {
        static final MapCodec<ItemExists> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(ResourceLocation.CODEC.fieldOf("item").forGetter(ItemExists::item)).apply(i, ItemExists::new));

        @Override
        public boolean test(IContext context) {
            return BuiltInRegistries.ITEM.containsKey(this.item);
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    static Map<ResourceLocation, MapCodec<? extends ICondition>> builtIn() {
        Map<ResourceLocation, MapCodec<? extends ICondition>> out = new LinkedHashMap<>();
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "mod_loaded"), ModLoadedCondition.CODEC);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "not"), Not.CODEC);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "and"), And.CODEC);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "or"), Or.CODEC);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "true"), Constant.TRUE);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "false"), Constant.FALSE);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "tag_empty"), TagEmpty.CODEC);
        out.put(ResourceLocation.fromNamespaceAndPath("neoforge", "item_exists"), ItemExists.CODEC);
        return out;
    }

    /** The Fabric API resource condition that means the same; null for a mod's own condition. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static @Nullable ResourceCondition fabric(ICondition condition) {
        return switch (condition) {
            case ModLoadedCondition c -> ResourceConditions.allModsLoaded(c.modid());
            case Not c -> {
                ResourceCondition inner = fabric(c.value());
                yield inner == null ? null : ResourceConditions.not(inner);
            }
            case And c -> {
                ResourceCondition[] all = all(c.values());
                yield all == null ? null : ResourceConditions.and(all);
            }
            case Or c -> {
                ResourceCondition[] all = all(c.values());
                yield all == null ? null : ResourceConditions.or(all);
            }
            case Constant c -> c.value() ? ResourceConditions.alwaysTrue() : ResourceConditions.alwaysFalse();
            case TagEmpty c -> ResourceConditions.not(ResourceConditions.tagsPopulated((TagKey) c.tag()));
            case ItemExists c -> ResourceConditions.registryContains(ResourceKey.create(Registries.ITEM, c.item()));
            default -> null;
        };
    }

    static ResourceCondition @Nullable [] all(List<ICondition> conditions) {
        List<ResourceCondition> out = new ArrayList<>();
        for (ICondition c : conditions) {
            ResourceCondition f = fabric(c);
            if (f == null) return null;
            out.add(f);
        }
        return out.toArray(ResourceCondition[]::new);
    }

    /** Only the conditions Fabric can read, for an entry Fabric's data generation writes. */
    public static ResourceCondition[] fabricAll(ICondition... conditions) {
        List<ResourceCondition> out = new ArrayList<>();
        for (ICondition c : conditions) {
            ResourceCondition f = fabric(c);
            if (f != null) out.add(f);
        }
        return out.toArray(ResourceCondition[]::new);
    }
}
