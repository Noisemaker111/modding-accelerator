import java.util.*;

/**
 * Load conditions in a mod's data files, between NeoForge's ("neoforge:conditions", each {"type": ...}) and Fabric API's
 * resource conditions ("fabric:load_conditions", each {"condition": ...}). The loader a data file is not written for
 * ignores the other's key and loads the file anyway, so a recipe meant only for when another mod is present would load
 * without it. The loaders' own types map one to one: mod_loaded is all_mods_loaded, not, and, or, true and false keep
 * their names, tag_empty is not(tags_populated), item_exists is registry_contains on the item registry. A condition of
 * a type a mod registers itself is kept as it is and named in left.
 */
final class DataConditions {

    static final String NEOFORGE = "neoforge:conditions";
    static final String FABRIC = "fabric:load_conditions";

    final List<String> left = new ArrayList<>();

    /** A data file's JSON with its conditions in the other loader's form; null when it has none to change. */
    @SuppressWarnings("unchecked")
    Map<String, Object> convert(Map<String, Object> json, boolean toFabric, String file) {
        String from = toFabric ? NEOFORGE : FABRIC;
        if (!(json.get(from) instanceof List<?> conditions)) return null;
        int before = this.left.size();
        List<Object> out = new ArrayList<>();
        for (Object c : conditions) out.add(toFabric ? fabric(c, file) : neoforge(c, file));
        // A condition the other loader cannot read would fail there and drop the file; under the old key it is ignored
        // and the file loads, as it does with the condition true (a mod's own condition, usually a config default).
        if (this.left.size() > before) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (var e : json.entrySet()) {
            if (e.getKey().equals(from)) result.put(toFabric ? FABRIC : NEOFORGE, out);
            else result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    Object fabric(Object condition, String file) {
        if (!(condition instanceof Map<?, ?> raw)) return condition;
        Map<String, Object> c = Json.object(condition);
        String type = Json.text(c, "type", "");
        return switch (type) {
            case "neoforge:mod_loaded" -> Json.map("condition", "fabric:all_mods_loaded", "values", List.of(Json.text(c, "modid", "")));
            case "neoforge:not" -> Json.map("condition", "fabric:not", "value", fabric(c.get("value"), file));
            case "neoforge:and", "neoforge:or" -> Json.map("condition", type.equals("neoforge:and") ? "fabric:and" : "fabric:or", "values", all(c.get("values"), true, file));
            case "neoforge:true" -> Json.map("condition", "fabric:true");
            case "neoforge:false" -> Json.map("condition", "fabric:false");
            case "neoforge:tag_empty" -> Json.map(
                "condition", "fabric:not",
                "value", Json.map("condition", "fabric:tags_populated", "registry", Json.text(c, "registry", "minecraft:item"), "values", List.of(Json.text(c, "tag", "")))
            );
            case "neoforge:item_exists" -> Json.map("condition", "fabric:registry_contains", "registry", "minecraft:item", "values", List.of(Json.text(c, "item", "")));
            default -> {
                left.add(file + ": condition " + type + " is the mod's own; the file keeps NeoForge's conditions, which Fabric ignores, so it always loads");
                yield condition;
            }
        };
    }

    Object neoforge(Object condition, String file) {
        if (!(condition instanceof Map<?, ?>)) return condition;
        Map<String, Object> c = Json.object(condition);
        String type = Json.text(c, "condition", "");
        List<Object> values = c.get("values") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        return switch (type) {
            case "fabric:all_mods_loaded", "fabric:any_mods_loaded" -> {
                List<Object> each = new ArrayList<>();
                for (Object id : values) each.add(Json.map("type", "neoforge:mod_loaded", "modid", String.valueOf(id)));
                yield each.size() == 1 ? each.get(0) : Json.map("type", type.equals("fabric:all_mods_loaded") ? "neoforge:and" : "neoforge:or", "values", each);
            }
            case "fabric:not" -> {
                Object inner = c.get("value");
                // not(tags_populated one tag) is tag_empty, as the way to Fabric wrote it.
                if (inner instanceof Map<?, ?> m && "fabric:tags_populated".equals(Json.text(Json.object(inner), "condition", "")) && Json.array(Json.object(inner).get("values")).size() == 1) {
                    Map<String, Object> t = Json.object(inner);
                    Map<String, Object> out = Json.map("type", "neoforge:tag_empty", "tag", String.valueOf(Json.array(t.get("values")).get(0)));
                    if (!"minecraft:item".equals(Json.text(t, "registry", "minecraft:item"))) out.put("registry", Json.text(t, "registry", ""));
                    yield out;
                }
                yield Json.map("type", "neoforge:not", "value", neoforge(inner, file));
            }
            case "fabric:and", "fabric:or" -> Json.map("type", type.equals("fabric:and") ? "neoforge:and" : "neoforge:or", "values", all(values, false, file));
            case "fabric:true" -> Json.map("type", "neoforge:true");
            case "fabric:false" -> Json.map("type", "neoforge:false");
            case "fabric:registry_contains" -> {
                if (!"minecraft:item".equals(Json.text(c, "registry", "minecraft:item"))) {
                    left.add(file + ": condition " + type + " on " + Json.text(c, "registry", "") + " has no NeoForge counterpart");
                    yield condition;
                }
                List<Object> each = new ArrayList<>();
                for (Object id : values) each.add(Json.map("type", "neoforge:item_exists", "item", String.valueOf(id)));
                yield each.size() == 1 ? each.get(0) : Json.map("type", "neoforge:and", "values", each);
            }
            default -> {
                left.add(file + ": condition " + type + " has no NeoForge counterpart in the rules");
                yield condition;
            }
        };
    }

    List<Object> all(Object list, boolean toFabric, String file) {
        List<Object> out = new ArrayList<>();
        for (Object c : Json.array(list)) out.add(toFabric ? fabric(c, file) : neoforge(c, file));
        return out;
    }
}
