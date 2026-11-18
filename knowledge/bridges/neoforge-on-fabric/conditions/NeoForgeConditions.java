package ${package}.conditions;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

/** NeoForge's net.neoforged.neoforge.common.conditions.NeoForgeConditions: its own condition types, by name. */
public final class NeoForgeConditions {

    private NeoForgeConditions() {}

    public static ICondition and(ICondition... values) {
        return new Conditions.And(List.of(values));
    }

    public static ICondition or(ICondition... values) {
        return new Conditions.Or(List.of(values));
    }

    public static ICondition not(ICondition value) {
        return new Conditions.Not(value);
    }

    public static ICondition always() {
        return new Conditions.Constant(true);
    }

    public static ICondition never() {
        return new Conditions.Constant(false);
    }

    public static ICondition modLoaded(String modid) {
        return new ModLoadedCondition(modid);
    }

    public static <T> ICondition tagEmpty(TagKey<T> tag) {
        return new Conditions.TagEmpty(tag);
    }

    public static ICondition itemRegistered(Identifier item) {
        return new Conditions.ItemExists(item);
    }

    public static ICondition itemRegistered(String namespace, String path) {
        return itemRegistered(Identifier.fromNamespaceAndPath(namespace, path));
    }

    public static ICondition itemRegistered(String item) {
        return itemRegistered(Identifier.parse(item));
    }
}
