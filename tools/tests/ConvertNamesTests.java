import java.util.*;

/**
 * convert's move of a yarn-named Fabric mod to Mojang's names (Convert.yarnToMojang), part of the convert suite
 * (ConvertTests): the access widener, mixin names from the yarn -> Mojang table and the game's hierarchy (MixinNames),
 * what migrateMappings leaves in any source, and the exact names from the mod's own refmap (RefmapNames).
 */
final class ConvertNamesTests {

    private ConvertNamesTests() {}

    static void run() {
        Map<String, Object> yarnTable = Json.object(Json.parse(
            "{\"classes\":[{\"from\":\"net.minecraft.client.render.WorldRenderer\",\"to\":\"net.minecraft.client.renderer.LevelRenderer\"},{\"from\":\"net.minecraft.util.math.BlockPos\",\"to\":\"net.minecraft.core.BlockPos\"}]," +
            "\"methods\":[{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"scheduleBlockRender\",\"desc\":\"(Lnet/minecraft/util/math/BlockPos;)V\",\"to\":\"setSectionDirty\"}]," +
            "\"fields\":[{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"world\",\"desc\":\"Lnet/minecraft/client/world/ClientWorld;\",\"to\":\"level\"}]}"
        ));
        String mojangWidener = Convert.widenerToMojang(
            "accessWidener v2 named\n# opens\naccessible class net/minecraft/client/render/WorldRenderer\n" +
            "accessible method net/minecraft/client/render/WorldRenderer scheduleBlockRender (Lnet/minecraft/util/math/BlockPos;)V\n" +
            "accessible field net/minecraft/client/render/WorldRenderer world Lnet/minecraft/client/world/ClientWorld;\n" +
            "accessible field net/minecraft/client/render/WorldRenderer frustum Lnet/minecraft/client/render/Frustum;\n",
            yarnTable
        );
        ConvertTests.check(
            mojangWidener.equals(
                "accessWidener v2 named\n# opens\naccessible class net/minecraft/client/renderer/LevelRenderer\n" +
                "accessible method net/minecraft/client/renderer/LevelRenderer setSectionDirty (Lnet/minecraft/core/BlockPos;)V\n" +
                "accessible field net/minecraft/client/renderer/LevelRenderer level Lnet/minecraft/client/world/ClientWorld;\n" +
                "accessible field net/minecraft/client/renderer/LevelRenderer frustum Lnet/minecraft/client/render/Frustum;\n"
            ),
            "an access widener in yarn's names is written in Mojang's from port's yarn -> Mojang table; names it leaves out stay:\n" + mojangWidener
        );
        MixinNames mixinNames = new MixinNames(Json.object(Json.parse(
            "{\"classes\":[{\"from\":\"net.minecraft.client.render.WorldRenderer\",\"to\":\"net.minecraft.client.renderer.LevelRenderer\"},{\"from\":\"net.minecraft.util.math.BlockPos\",\"to\":\"net.minecraft.core.BlockPos\"}," +
            "{\"from\":\"net.minecraft.block.Block\",\"to\":\"net.minecraft.world.level.block.Block\"},{\"from\":\"net.minecraft.block.AbstractBlock\",\"to\":\"net.minecraft.world.level.block.state.BlockBehaviour\"},{\"from\":\"net.minecraft.block.AbstractBlock$Settings\",\"to\":\"net.minecraft.world.level.block.state.BlockBehaviour$Properties\"}," +
            "{\"from\":\"net.minecraft.block.Block$Type\",\"to\":\"net.minecraft.world.level.block.Block$TypeAndCodec\"},{\"from\":\"net.minecraft.block.Block$Id\",\"to\":\"net.minecraft.world.level.block.Block$Type\"}]," +
            "\"methods\":[{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"scheduleBlockRender\",\"desc\":\"(Lnet/minecraft/util/math/BlockPos;)V\",\"to\":\"setSectionDirty\"},{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"render\",\"desc\":\"()V\",\"to\":\"renderLevel\"}," +
            "{\"owner\":\"net.minecraft.Base\",\"from\":\"readNbt\",\"desc\":\"(I)V\",\"to\":\"loadAdditional\"},{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"of\",\"desc\":\"(I)V\",\"to\":\"create\"},{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"of\",\"desc\":\"(II)V\",\"to\":\"build\"}," +
            "{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"method_1\",\"desc\":\"()V\",\"to\":\"lambda$render$0\"},{\"owner\":\"net.minecraft.Other\",\"from\":\"getAttributes\",\"desc\":\"()I\",\"to\":\"getBasicAttributes\"}," +
            "{\"owner\":\"net.minecraft.Base\",\"from\":\"getRenderLayer\",\"desc\":\"(IZ)V\",\"to\":\"getRenderType\"}]," +
            "\"supers\":{\"net/minecraft/client/renderer/LevelRenderer\":[\"net/minecraft/Base\"],\"net/minecraft/world/level/block/Block\":[\"net/minecraft/world/level/block/state/BlockBehaviour\"]}," +
            "\"fields\":[{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"world\",\"desc\":\"Lnet/minecraft/client/world/ClientWorld;\",\"to\":\"level\"},{\"owner\":\"net.minecraft.client.render.WorldRenderer\",\"from\":\"MAX_DISTANCE\",\"desc\":\"I\",\"to\":\"OUTLINE_DISTANCE\"}]}"
        )));
        String mixedIn = mixinNames.remap(
            "import net.minecraft.client.renderer.LevelRenderer;\n@Mixin(LevelRenderer.class)\nabstract class M {\n\t@Shadow private ClientLevel world;\n" +
            "\t@Invoker(\"scheduleBlockRender\") abstract void my$schedule(BlockPos pos);\n\t@Accessor(\"MAX_DISTANCE\") static int my$distance() { throw new AssertionError(); }\n" +
            "\t@Inject(method = \"render\", at = @At(value = \"INVOKE\", target = \"Lnet/minecraft/client/render/WorldRenderer;scheduleBlockRender(Lnet/minecraft/util/math/BlockPos;)V\"))\n" +
            "\tvoid a(CallbackInfo ci) { this.world.tick(); String s = \"world\"; }\n\t@Inject(method = \"readNbt\", at = @At(\"TAIL\")) void b(CallbackInfo ci) {}\n\t@Inject(method = {\"render()V\", \"method_1\"}, at = @At(\"TAIL\")) void c() {}\n\t@Shadow abstract int getAttributes();\n" +
            "\t@Invoker(\"of\") abstract void my$of(@Coerce int a, Map<String, Integer> b);\n\t@Mixin(BlockPos.class) interface N { @Accessor(\"world\") int w(); }\n}\n"
        );
        ConvertTests.check(
            mixedIn.contains("private ClientLevel level;") && mixedIn.contains("this.level.tick()") && mixedIn.contains("String s = \"world\"") &&
            mixedIn.contains("@Invoker(\"setSectionDirty\") abstract void my$schedule(") && mixedIn.contains("@Accessor(\"OUTLINE_DISTANCE\") static int my$distance()") &&
            mixedIn.contains("method = \"renderLevel\"") && mixedIn.contains("method = \"loadAdditional\"") && mixedIn.contains("method = {\"renderLevel()V\", \"method_1\"}") && mixedIn.contains("abstract int getAttributes();") && mixedIn.contains("@Invoker(\"build\")") && mixedIn.contains("@Accessor(\"world\") int w()") &&
            mixedIn.contains("\"Lnet/minecraft/client/renderer/LevelRenderer;setSectionDirty(Lnet/minecraft/core/BlockPos;)V\""),
            "a mixin's names Mixin reads at run time (shadows and their uses, $-named invokers and accessors, overloads by arity, inherited targets, method = and @At targets) move, lambdas keep the intermediary name Loom's game has, from yarn to Mojang, each @Mixin against its own target; strings that name nothing stay:\n" + mixedIn
        );
        RefmapNames refmap = new RefmapNames(Json.object(Json.parse(
            "{\"classes\":{\"net/minecraft/class_1\":\"net/minecraft/A\",\"net/minecraft/class_2\":\"net/minecraft/B\"},\"members\":{\"method_1\":[\"net/minecraft/A\",\"tick\",\"(Lnet/minecraft/B;)V\"]," +
            "\"field_1\":[\"net/minecraft/A\",\"DISTANCE\",\"I\"],\"method_2\":[\"net/minecraft/A\",\"lambda$tick$0\",\"()V\"]}}"
        )));
        Map<String, String> refs = Map.of("update", "Lnet/minecraft/class_1;method_1(Lnet/minecraft/class_2;)V", "MAX", "field_1:I", "range", "field_1:I", "method_2", "Lnet/minecraft/class_1;method_2()V", "a/B.c(I)V", "method_1(Lnet/minecraft/class_2;)V", "Lcom/x/R;f()V", "Lcom/x/R;f()V");
        String exact = refmap.apply(
            "@Mixin(A.class)\nabstract class M {\n\t@Inject(method = \"update\", at = @At(\"HEAD\")) void a() { String s = \"update\"; }\n\t@Accessor(\"MAX\") static int max();\n\t@Accessor int getRange();\n\t@Inject(method = \"method_2\", at = @At(target = \"a/B.c (I)V\")) void b() {}\n}\n", refs
        );
        ConvertTests.check(
            exact.contains("method = \"Lnet/minecraft/A;tick(Lnet/minecraft/B;)V\"") && exact.contains("String s = \"update\"") && exact.contains("@Accessor(\"DISTANCE\") static int max()") &&
            exact.contains("@Accessor(\"DISTANCE\") int getRange()") && exact.contains("method = \"Lnet/minecraft/A;method_2()V\"") && exact.contains("target = \"tick(Lnet/minecraft/B;)V\"") &&
            refmap.check(Map.of("M", refs), Map.of("M", Map.of("x", "Lnet/minecraft/class_1;method_1(Lnet/minecraft/class_2;)V", "y", "field_1:I"))).isEmpty() &&
            refmap.check(Map.of("M", refs), Map.of("M", Map.of("y", "field_1:I"))).size() == 1,
            "a yarn mod's refmap renames its mixin strings exactly (lambdas keep their intermediary name), and the renamed build's refmap must resolve the same members:\n" + exact
        );
        String plain = mixinNames.javaNames(
            "import net.minecraft.world.level.block.Block;\nimport net.minecraft.client.renderer.LevelRenderer;\nclass R extends LevelRenderer {\n\tBlock.Settings s = Block.Settings.of(Blocks.STONE);\n\tBlock.Type<R> t;\n" +
            "\t@Override\n\tprotected void getRenderLayer(int a, boolean b) { super.getRenderType(a, b); }\n\t@Override public void getRenderLayer() {}\n}\n"
        );
        ConvertTests.check(
            plain.contains("Block.Properties s = Block.Properties.of(Blocks.STONE)") && plain.contains("Block.Type<R> t;") && plain.contains("protected void getRenderType(int a, boolean b)") && plain.contains("public void getRenderLayer() {}"),
            "migrateMappings' gaps in any source: a nested game type named through its outer class, and an override of a game method (by its parameter count) move to Mojang's names:\n" + plain
        );
    }
}
