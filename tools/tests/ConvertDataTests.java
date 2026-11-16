import java.util.*;

/**
 * convert's changes to a mod's data files (part of the convert suite, ConvertTests): load conditions between NeoForge's
 * and Fabric's form (DataConditions), and custom ingredients in recipes between NeoForge's "type" and Fabric's
 * "fabric:type" (DataIngredients).
 */
final class ConvertDataTests {

    private ConvertDataTests() {}

    static void run() {
        String neoData = "{\"type\":\"minecraft:crafting_shaped\",\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"create\"}," +
            "{\"type\":\"neoforge:not\",\"value\":{\"type\":\"neoforge:mod_loaded\",\"modid\":\"jumbofurnace\"}},{\"type\":\"neoforge:tag_empty\",\"tag\":\"c:ingots/zinc\"}]}";
        DataConditions dataConditions = new DataConditions();
        Map<String, Object> fabricData = dataConditions.convert(Json.object(Json.parse(neoData)), true, "data/demo/recipe/a.json");
        ConvertTests.check(
            Json.write(fabricData).equals("{\"type\":\"minecraft:crafting_shaped\",\"fabric:load_conditions\":[{\"condition\":\"fabric:all_mods_loaded\",\"values\":[\"create\"]}," +
                "{\"condition\":\"fabric:not\",\"value\":{\"condition\":\"fabric:all_mods_loaded\",\"values\":[\"jumbofurnace\"]}}," +
                "{\"condition\":\"fabric:not\",\"value\":{\"condition\":\"fabric:tags_populated\",\"registry\":\"minecraft:item\",\"values\":[\"c:ingots/zinc\"]}}]}") &&
            Json.write(new DataConditions().convert(fabricData, false, "a.json")).equals(neoData) &&
            dataConditions.convert(Json.object(Json.parse("{\"type\":\"x\"}")), true, "b.json") == null &&
            dataConditions.convert(Json.object(Json.parse("{\"neoforge:conditions\":[{\"type\":\"demo:enabled\"}]}")), true, "c.json") == null && dataConditions.left.size() == 1,
            "NeoForge's load conditions in a data file become Fabric's and back; a file with a mod's own condition type keeps NeoForge's and is listed: " + Json.write(fabricData)
        );
        String neoRecipe = "{\"type\":\"demo:cutting\",\"ingredients\":[{\"item\":\"minecraft:apple\"}],\"tool\":{\"type\":\"demo:item_ability\",\"action\":\"axe_dig\"}," +
            "\"extra\":{\"type\":\"neoforge:compound\",\"children\":[{\"tag\":\"c:crops\"}]},\"result\":{\"id\":\"minecraft:stick\",\"type\":\"demo:not_an_ingredient\"}}";
        Map<String, Object> fabricRecipe = DataIngredients.convert(Json.object(Json.parse(neoRecipe)), true);
        ConvertTests.check(
            Json.write(fabricRecipe).equals("{\"type\":\"demo:cutting\",\"ingredients\":[{\"item\":\"minecraft:apple\"}],\"tool\":{\"fabric:type\":\"demo:item_ability\",\"action\":\"axe_dig\"}," +
                "\"extra\":{\"fabric:type\":\"fabric:any_of\",\"ingredients\":[{\"tag\":\"c:crops\"}]},\"result\":{\"id\":\"minecraft:stick\",\"type\":\"demo:not_an_ingredient\"}}") &&
                Json.write(DataIngredients.convert(fabricRecipe, false)).equals(neoRecipe) &&
                DataIngredients.convert(Json.object(Json.parse("{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"minecraft:apple\"}}")), true) == null,
            "a recipe's custom ingredients move between NeoForge's type and Fabric's fabric:type (NeoForge's compound is Fabric's any_of), the recipe's own type and a stack stay: " + Json.write(fabricRecipe)
        );
    
        String mixin = Convert.neoforgeParametersOutOfMixin(
            "@Mixin(BlockRenderDispatcher.class)\nclass M {\n@Inject(method = \"renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/neoforged/neoforge/client/model/data/ModelData;)V\", at = @At(\"HEAD\"))\n" +
            "void hide(BlockState state, VertexConsumer consumer, net.neoforged.neoforge.client.model.data.ModelData data, CallbackInfo info) {}\n}"
        );
        ConvertTests.check(
            mixin.contains("\"renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V\"") &&
                mixin.contains("void hide(BlockState state, VertexConsumer consumer, CallbackInfo info)") && mixin.contains("@At(\"HEAD\")"),
            "a mixin of a method NeoForge gives an extra parameter targets the game's own method, its handler without that parameter: " + mixin
        );
    }
}
