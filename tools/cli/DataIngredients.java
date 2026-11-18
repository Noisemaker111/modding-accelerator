import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom ingredients in a mod's recipe files, between NeoForge's form and Fabric API's: NeoForge names an ingredient's type
 * with "type" ({"type": "farmersdelight:item_ability", ...}), Fabric with "fabric:type"; NeoForge's own compound,
 * intersection and difference ingredients are Fabric's any, all and difference (a list of "children" being Fabric's
 * "ingredients"). The recipe's own "type" (the top level) is the game's and stays. A NeoForge ingredient type Fabric has
 * no counterpart for (neoforge:components ...) keeps NeoForge's form.
 */
final class DataIngredients {

    private DataIngredients() {}

    /** The keys of recipes whose arrays are lists of things, not one ingredient's alternatives. */
    static final java.util.Set<String> LISTS = java.util.Set.of("ingredients", "results", "result", "pattern", "children", "values", "entries");

    /** The recipe with its custom ingredients in the other loader's form, or null when it has none. */
    static Map<String, Object> convert(Map<String, Object> recipe, boolean toFabric) {
        boolean[] changed = { false };
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : recipe.entrySet()) out.put(e.getKey(), member(e.getKey(), e.getValue(), toFabric, changed));
        return changed[0] ? out : null;
    }

    /**
     * An object's member in the other form. A single ingredient written as an array of alternatives (NeoForge reads any
     * ingredient there; Fabric's array takes only items and tags) with a custom ingredient in it is Fabric's any; a
     * list of ingredients ("ingredients") and results stay lists.
     */
    private static Object member(String key, Object value, boolean toFabric, boolean[] changed) {
        Object walked = walk(value, toFabric, changed);
        if (toFabric && walked instanceof List<?> alternatives && !LISTS.contains(key) && alternatives.stream().anyMatch(a -> a instanceof Map<?, ?> m && m.containsKey("fabric:type"))) {
            Map<String, Object> anyOf = new LinkedHashMap<>();
            anyOf.put("fabric:type", "fabric:any");
            anyOf.put("ingredients", walked);
            changed[0] = true;
            return anyOf;
        }
        return walked;
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object value, boolean toFabric, boolean[] changed) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(walk(item, toFabric, changed));
            return out;
        }
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> object = new LinkedHashMap<>();
        for (var e : ((Map<String, Object>) map).entrySet()) object.put(e.getKey(), member(e.getKey(), e.getValue(), toFabric, changed));
        return toFabric ? toFabric(object, changed) : toNeoForge(object, changed);
    }

    private static Map<String, Object> toFabric(Map<String, Object> object, boolean[] changed) {
        if (!(object.get("type") instanceof String type) || object.containsKey("id") || type.startsWith("minecraft:")) return object;
        String fabric = switch (type) {
            case "neoforge:compound" -> "fabric:any";
            case "neoforge:intersection" -> "fabric:all";
            case "neoforge:difference" -> "fabric:difference";
            default -> type.startsWith("neoforge:") ? null : type;
        };
        if (fabric == null) return object;
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : object.entrySet()) {
            if (e.getKey().equals("type")) out.put("fabric:type", fabric);
            else out.put(e.getKey().equals("children") && !fabric.equals(type) ? "ingredients" : e.getKey(), e.getValue());
        }
        changed[0] = true;
        return out;
    }

    private static Map<String, Object> toNeoForge(Map<String, Object> object, boolean[] changed) {
        if (!(object.get("fabric:type") instanceof String type)) return object;
        String neoforge = switch (type) {
            case "fabric:any" -> "neoforge:compound";
            case "fabric:all" -> "neoforge:intersection";
            case "fabric:difference" -> "neoforge:difference";
            default -> type.startsWith("fabric:") ? null : type;
        };
        if (neoforge == null) return object;
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : object.entrySet()) {
            if (e.getKey().equals("fabric:type")) out.put("type", neoforge);
            else out.put(e.getKey().equals("ingredients") && !neoforge.equals(type) ? "children" : e.getKey(), e.getValue());
        }
        changed[0] = true;
        return out;
    }
}
