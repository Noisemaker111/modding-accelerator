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
                "\"extra\":{\"fabric:type\":\"fabric:any\",\"ingredients\":[{\"tag\":\"c:crops\"}]},\"result\":{\"id\":\"minecraft:stick\",\"type\":\"demo:not_an_ingredient\"}}") &&
                Json.write(DataIngredients.convert(fabricRecipe, false)).equals(neoRecipe) &&
                DataIngredients.convert(Json.object(Json.parse("{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"minecraft:apple\"}}")), true) == null,
            "a recipe's custom ingredients move between NeoForge's type and Fabric's fabric:type (NeoForge's compound is Fabric's any), the recipe's own type and a stack stay: " + Json.write(fabricRecipe)
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
    
        String mojang = "net.minecraft.util.datafix.schemas.V3818_3 -> bla:\n    29:37:com.mojang.datafixers.types.templates.TypeTemplate lambda$registerTypes$0(com.mojang.datafixers.schemas.Schema) -> a\n" +
            "    45:45:com.mojang.datafixers.types.templates.TypeTemplate lambda$registerTypes$2(com.mojang.datafixers.schemas.Schema) -> a\nnet.minecraft.Other -> blb:\n";
        String tiny = "tiny\t2\t0\tofficial\tintermediary\nc\tbla\tnet/minecraft/class_9354\n" +
            "\tm\t(Lcom/mojang/datafixers/schemas/Schema;)Lcom/mojang/datafixers/types/templates/TypeTemplate;\ta\tmethod_58010\nc\tblb\tnet/minecraft/class_1\n";
        ConvertTests.check(
            "method_58010".equals(LambdaTargets.intermediary(mojang, tiny, "net.minecraft.util.datafix.schemas.V3818_3", "lambda$registerTypes$0")) &&
                LambdaTargets.intermediary(mojang, tiny, "net.minecraft.Other", "lambda$registerTypes$0") == null &&
                "net.minecraft.util.datafix.schemas.V3818_3".equals(Convert.mixinOwner("import net.minecraft.util.datafix.schemas.V3818_3;\n@Mixin(V3818_3.class)\nclass M {}")),
            "a mixin's game lambda target by the compiler's name has Loom's intermediary name, through Mojang's and intermediary's mappings"
        );
    
        String lookups = Convert.enumExtensionLookups(
            "A a = RecipeBookCategories.valueOf(\"MOD_TAB\"); B b = RecipeBookCategories.valueOf(\"CRAFTING_MISC\"); C c = HumanoidModel.ArmPose.valueOf(\"MOD_POSE\");",
            List.of(new String[] { "RecipeBookCategories", "MOD_TAB" }, new String[] { "HumanoidModel.ArmPose", "MOD_POSE" }),
            "demo.bridge"
        );
        ConvertTests.check(
            lookups.equals("A a = demo.bridge.EnumProxy.fallback(RecipeBookCategories.class); B b = RecipeBookCategories.valueOf(\"CRAFTING_MISC\"); C c = demo.bridge.EnumProxy.fallback(HumanoidModel.ArmPose.class);"),
            "a lookup of an enum constant the mod adds is the bridge's stand-in, of one the game has stays: " + lookups
        );
    
        Map<String, Object> alternatives = DataIngredients.convert(Json.object(Json.parse(
            "{\"type\":\"demo:cutting\",\"ingredients\":[{\"type\":\"demo:a\"},{\"item\":\"minecraft:apple\"}],\"tool\":[{\"type\":\"demo:item_ability\"},{\"tag\":\"c:tools/knife\"}]}"
        )), true);
        ConvertTests.check(
            Json.write(alternatives).equals("{\"type\":\"demo:cutting\",\"ingredients\":[{\"fabric:type\":\"demo:a\"},{\"item\":\"minecraft:apple\"}]," +
                "\"tool\":{\"fabric:type\":\"fabric:any\",\"ingredients\":[{\"fabric:type\":\"demo:item_ability\"},{\"tag\":\"c:tools/knife\"}]}}"),
            "an ingredient's alternatives holding a custom ingredient are Fabric's any; a list of ingredients stays a list: " + Json.write(alternatives)
        );
        Map<String, String> unwired = Convert.unwiredSourceSets(Json.object(Json.parse(
            "{\"projects\":[{\"path\":\":\",\"sourceSets\":[" +
            "{\"name\":\"main\",\"classesTask\":\"classes\",\"output\":[\"/p/build/main\"],\"runtime\":[\"/c/minecraft-merged-1.21.1.jar\"]}," +
            "{\"name\":\"test\",\"classesTask\":\"testClasses\",\"output\":[],\"runtime\":[\"/p/build/main\",\"/c/junit.jar\"]}," +
            "{\"name\":\"gametest\",\"classesTask\":\"gametestClasses\",\"output\":[],\"runtime\":[\"/c/neoforge-21.1.200.jar\"]}," +
            "{\"name\":\"datagen\",\"classesTask\":\"datagenClasses\",\"output\":[],\"runtime\":[\"/c/guava.jar\"]}]}," +
            "{\"path\":\":planned\",\"sourceSets\":[{\"name\":\"main\",\"classesTask\":\"classes\",\"output\":[],\"runtime\":[]},{\"name\":\"extra\",\"classesTask\":\"extraClasses\",\"runtime\":[]}]}]}"
        )));
        ConvertTests.check(
            unwired.equals(Map.of(":datagenClasses", "datagen")),
            "only a source set given neither the game nor main's classes is left out of the compile; a model without classpaths leaves none out: " + unwired
        );
        String keys = "package demo;\n\nimport net.minecraft.client.KeyMapping;\n\nclass K {\n" +
            "    KeyMapping a = new KeyMapping(\"a\", CONTEXT, key, CATEGORY);\n    KeyMapping b = new KeyMapping(\"b\", InputConstants.Type.KEYSYM, 71, CATEGORY);\n}\n";
        String repairedKeys = Convert.contextKeyMappings(keys, Set.of(6, 99), "demo.loaderbridge");
        ConvertTests.check(
            repairedKeys.equals(keys.replace("import net.minecraft.client.KeyMapping;\n", "import net.minecraft.client.KeyMapping;\nimport demo.loaderbridge.KeyConflictContext;\n")
                .replace("a = new KeyMapping(", "a = KeyConflictContext.keyMapping(")) &&
            Convert.contextKeyMappings(keys.replace("package demo;", "package demo.loaderbridge;"), Set.of(6), "demo.loaderbridge").equals(
                keys.replace("package demo;", "package demo.loaderbridge;").replace("a = new KeyMapping(", "a = KeyConflictContext.keyMapping(")
            ) &&
            Convert.contextKeyMappings(keys, Set.of(7 - 6), "demo.loaderbridge").equals(keys),
            "a refused KeyMapping constructor with a conflict context is the bridge's KeyConflictContext.keyMapping, imported once; other lines stay: " + repairedKeys
        );
        String failure = "> Could not find lib:base:3.\n     Required by:\n         root project 'demo' > net.demo:Ponder-Fabric-1.21.1:1.0.44\n" +
            "         root project 'demo' > net.demo:Ponder-Fabric-1.21.1:1.0.44 > net.demo:Ponder-Common-1.21.1:1.0.44\n";
        String depsBuild = "dependencies {\n    compileOnly(\"net.demo:Ponder-Fabric-${mc}:${ponder}\")\n    modCompileOnly 'net.demo:Ponder-Fabric-$mc:2'\n" +
            "    compileOnly(\"net.demo:Other:1\")\n    implementation(\"net.demo:Ponder-Fabric-1.21.1:1.0.44\")\n}\n";
        String nonTransitive = Convert.nonTransitiveCompileOnly(failure, depsBuild, Map.of("mc", "1.21.1", "ponder", "1.0.44"));
        ConvertTests.check(
            depsBuild.replace("    compileOnly(\"net.demo:Ponder-Fabric-${mc}:${ponder}\")", "    compileOnly(\"net.demo:Ponder-Fabric-${mc}:${ponder}\") { transitive = false }")
                .replace("    modCompileOnly 'net.demo:Ponder-Fabric-$mc:2'", "    modCompileOnly('net.demo:Ponder-Fabric-$mc:2') { transitive = false }").equals(nonTransitive) &&
            Convert.nonTransitiveCompileOnly(failure, depsBuild, Map.of("mc", "26.1", "ponder", "1")) == null &&
            Convert.nonTransitiveCompileOnly("> Could not find a:b:1.", depsBuild, Map.of()) == null,
            "a compile-only dependency whose own dependencies cannot be found is made non-transitive, matched with the build's properties; others stay: " + nonTransitive
        );
        Map<String, String> mod = Map.of(
            "FramedBlock", "public class FramedBlock extends Block {\n    @Override\n    public ItemStack getCloneItemStack(LevelReader l) { return null; }\n}",
            "FramedMiddle", "public class FramedMiddle extends FramedBlock {}"
        );
        String overMod = "class Frame extends FramedMiddle { ItemStack c() { return IBlockExtension.getCloneItemStack(super, level, pos); } }";
        String overGame = "class Torch extends net.minecraft.world.level.block.TorchBlock { int l() { return Math.max(1, IBlockExtension.getLightEmission(super, state)); } int m() { return Other.x(super, 1); } }";
        ConvertTests.check(
            Convert.superHookCalls(overMod, mod, Set.of("IBlockExtension")).equals(overMod.replace("IBlockExtension.getCloneItemStack(super, ", "super.getCloneItemStack(")) &&
            Convert.superHookCalls(overGame, mod, Set.of("IBlockExtension")).equals(overGame.replace("getLightEmission(super, ", "getLightEmission(this, ")),
            "a bridge hook helper given super is super's own method when a mod ancestor declares it, else the helper given this: " + Convert.superHookCalls(overGame, mod, Set.of("IBlockExtension"))
        );
    }
}
