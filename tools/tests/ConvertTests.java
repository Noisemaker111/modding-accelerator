import java.io.*;
import java.nio.file.*;
import java.util.*;

/** convert: moving a mod between Fabric and NeoForge and back, and undoing it. */
public final class ConvertTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /**
     * convert: every rule in knowledge/loaders.json against its own before/after test, both ways; then a small Fabric
     * mod moved to NeoForge and back with --no-compile (the release lookups come from a seeded cache, so no network),
     * and both conversions undone byte for byte.
     */
    static void convertChecks(Path home, Path scratch) throws Exception {
        Map<String, Object> rules = Json.read(home.resolve("knowledge/loaders.json"));
        int tested = 0;
        for (String kind : List.of("types", "expressions", "statements", "events")) for (Object item : Json.array(
            rules.get(kind)
        )) {
            var rule = Json.object(item);
            var test = Json.object(rule.get("test"));
            String fabric = Json.text(test, "fabric", ""), neo = Json.text(test, "neoforge", ""), back = Json.text(
                test,
                "back",
                fabric
            );
            // A rule written for a Minecraft range is tested at the version its test names (the newest release otherwise).
            String game = Json.text(test, "minecraft", "26.3");
            String forward = new LoaderRules(rules, "fabric", "neoforge", "demo", game).apply(fabric).text;
            String reverse = new LoaderRules(rules, "neoforge", "fabric", "demo", game).apply(neo).text;
            if (!forward.equals(neo)) throw new AssertionError(
                "rule " + rule.get("id") + " to NeoForge gave:\n" + forward
            );
            if (!reverse.equals(back)) throw new AssertionError(
                "rule " + rule.get("id") + " back to Fabric gave:\n" + reverse
            );
            tested++;
        }
        check(
            tested >= 19,
            "every loader rule turns its Fabric test into its NeoForge test and back (" + tested + " rules)"
        );
        check(
            LoaderRules.find(
                "String s = \"FabricLoader.getInstance().getGameDir()\"; // FabricLoader.getInstance().getGameDir()",
                "FabricLoader.getInstance().getGameDir()"
            ).isEmpty(),
            "a rule never matches inside a string or a comment"
        );
        check(
            Convert.mavenRange("~26.3").equals("[26.3,26.4)") &&
            Convert.mavenRange(">=1.2 <2").equals("[1.2,2)") &&
            Convert.mavenRange("*").equals("[0,)") &&
            Convert.mavenRange("26.1.2").equals("[26.1.2]") &&
            Convert.fabricPredicate("[26.3,26.4)").equals("~26.3") &&
            Convert.fabricPredicate("[1.2,)").equals(">=1.2"),
            "Fabric version predicates and Maven ranges convert both ways"
        );
        check(
            Convert.neoforgeModId("pointless-piglins").equals("pointless_piglins") && Convert.neoforgeModId("demo").equals("demo") &&
            Convert.neoforgeModId("2fast").equals("mod_2fast"),
            "a Fabric mod id with a hyphen becomes one NeoForge accepts"
        );
        // Found by an independent check: modaccel package copied whatever desktop app build was in app/target/release.
        Path app = scratch.resolve("stale-app/app");
        Files.createDirectories(app.resolve("src"));
        Files.createDirectories(app.resolve("target/release"));
        Path exe = app.resolve("target/release/" + Installation.DESKTOP_WINDOWS), source = app.resolve("src/main.rs");
        Files.writeString(exe, "MZ");
        Files.writeString(source, "fn main() {}\n");
        Files.writeString(app.resolve("target/release/build.log"), "later output\n");
        Files.setLastModifiedTime(exe, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(1_000_000_500_000L));
        Path stale = Installation.newerSource(app, exe);
        Files.setLastModifiedTime(exe, java.nio.file.attribute.FileTime.fromMillis(1_000_001_000_000L));
        check(
            source.equals(stale) && Installation.newerSource(app, exe) == null,
            "a desktop app build older than the app's sources is found stale, and its own build output does not count: " + stale
        );
        // Found by an independent check on Krypton: NeoForge 26.1+ logs an ERROR for every @OnlyIn class (OnlyInWarningsHandler).
        String sided = "import net.fabricmc.api.EnvType;\nimport net.fabricmc.api.Environment;\n\n@Environment(EnvType.CLIENT)\nclass A {\n\t// @Environment(EnvType.SERVER)\n\t@Environment(EnvType.CLIENT) void draw() {}\n}\n";
        String unsided = Convert.withoutSideOnly(sided);
        check(
            unsided.equals("import net.fabricmc.api.EnvType;\n\nclass A {\n\t// @Environment(EnvType.SERVER)\n\tvoid draw() {}\n}\n") &&
            Convert.ONLY_IN_ERROR.equals("26.1"),
            "converted to NeoForge 26.1 or later, Fabric's @Environment marks go instead of becoming @OnlyIn:\n" + unsided
        );
        // Found by the convert sweep on MoreRed (26.1.2): its gradle.properties names only neo_version.
        check(
            Convert.minecraftOfNeoForge("26.1.2.48-beta").equals("26.1.2") &&
            Convert.minecraftOfNeoForge("26.1.0.3").equals("26.1") &&
            Convert.minecraftOfNeoForge("21.1.250").equals("1.21.1") &&
            Convert.minecraftOfNeoForge("21.0.167").equals("1.21") &&
            Convert.minecraftOfNeoForge("20.4.232").equals("1.20.4") &&
            Convert.minecraftOfNeoForge("${neo}").isEmpty(),
            "the Minecraft version is read from a NeoForge release number, before and after 26.1"
        );
        // Found by the convert sweep on Client Commands and SeedMapper: Loom configured in two loom blocks and in
        // top-level loom. statements; all of it goes with the plugin.
        String loomTwice = "loom {\n\taccessWidenerPath = file('a')\n}\n\nloom.runs.configureEach {\n\tvmArg '-Dx'\n}\ndelete loom.runs.client.runDirectory\n\nloom {\n\tmixin { useLegacyMixinAp = false }\n}\n";
        int[] firstLoom = Convert.block(loomTwice, "loom");
        int[] secondLoom = Convert.nextBlock(loomTwice, "loom", firstLoom[1]);
        List<String> loomDropped = new ArrayList<>();
        String loomLeft = Convert.dropStatements(loomTwice, Convert::loomStatement, loomDropped);
        check(
            secondLoom != null && loomTwice.substring(secondLoom[0]).startsWith("loom {\n\tmixin") &&
            loomDropped.size() == 2 && !loomLeft.contains("loom.") && loomLeft.contains("mixin {"),
            "every loom block is found, and top-level loom. statements are dropped: " + loomDropped
        );
        // Found by the convert sweep on Client Commands: a configuration of its own, includedLibrary, became jarJardLibrary.
        check(
            Convert.loomConfigurationsToNeoForge("\tincludedLibrary \"a:b:1\"\n").equals("\tincludedLibrary \"a:b:1\"\n") &&
            Convert.loomConfigurationsToNeoForge("\tinclude implementation(\"a:b:1\")\n").equals("\tjarJar implementation(\"a:b:1\")\n") &&
            Convert.loomConfigurationsToNeoForge("\tmodImplementation include(\"a:b:1\")\n").equals("\timplementation jarJar(\"a:b:1\")\n"),
            "Loom's configurations become ModDevGradle's, and only whole configuration names"
        );
        List<String> unconverted = new ArrayList<>();
        List<String> transformers = Convert.widenerToTransformer(
            "classTweaker v1 official\n# comment\naccessible class net/minecraft/A$B\nextendable class net/minecraft/C\n" +
            "accessible method net/minecraft/D run (I)V\nextendable method net/minecraft/D run (I)V\n" +
            "accessible field net/minecraft/E size I\nmutable field net/minecraft/E size I\n" +
            "transitive-accessible method net/minecraft/F <init> ()V\ninject-interface net/minecraft/G demo/Extra\n",
            true,
            unconverted
        );
        check(
            transformers.equals(
                List.of(
                    "public net.minecraft.A$B",
                    "public-f net.minecraft.C",
                    "public-f net.minecraft.D run(I)V",
                    "public-f net.minecraft.E size",
                    "public net.minecraft.F <init>()V"
                )
            ) &&
            unconverted.equals(List.of("inject-interface net/minecraft/G demo/Extra")),
            "a Fabric access widener becomes NeoForge access transformer lines, entries for one target merged: " +
            transformers +
            " " +
            unconverted
        );
        List<String> yarnLeft = new ArrayList<>();
        check(
            Convert.widenerToTransformer("accessWidener v2 named\naccessible class net/minecraft/a\n", false, yarnLeft).isEmpty() &&
            yarnLeft.size() == 1,
            "an access widener in yarn's names is named, not converted"
        );
        List<String> widenerLeft = new ArrayList<>();
        String widener = Convert.transformerToWidener(
            "# opens\npublic net.minecraft.A$B\npublic-f net.minecraft.C\nprotected-f net.minecraft.D run(I)V\npublic net.minecraft.E size\npublic net.minecraft.H *()\n",
            true,
            widenerLeft
        );
        check(
            widener.equals(
                "accessWidener v2 official\naccessible class net/minecraft/A$B\nextendable class net/minecraft/C\n" +
                "accessible method net/minecraft/D run (I)V\nextendable method net/minecraft/D run (I)V\n"
            ) &&
            widenerLeft.size() == 2 &&
            widenerLeft.get(0).startsWith("public net.minecraft.E size") &&
            widenerLeft.get(1).equals("public net.minecraft.H *()") &&
            Convert.transformerToWidener("public net.minecraft.A\n", false, new ArrayList<>()).startsWith("accessWidener v2 named\n"),
            "NeoForge access transformer lines become a Fabric access widener; a field (no type) and a wildcard are named:\n" +
            widener +
            widenerLeft
        );
        Map<String, String> fieldTypes = Convert.mappingFieldTypes(
            "# {\"fileName\":\"client.txt\"}\nnet.minecraft.client.renderer.block.ModelBlockRenderer -> fxx:\n" +
            "    net.minecraft.client.color.block.BlockColors blockColors -> b\n    int[][] grid -> c\n" +
            "    15:20:void <init>(net.minecraft.client.color.block.BlockColors) -> <init>\n"
        );
        List<String> typedLeft = new ArrayList<>();
        String typed = Convert.transformerToWidener(
            "public-f net.minecraft.client.renderer.block.ModelBlockRenderer blockColors\npublic net.minecraft.client.renderer.block.ModelBlockRenderer missing\n",
            false,
            typedLeft,
            (owner, field) -> fieldTypes.get(owner + " " + field)
        );
        check(
            fieldTypes.get("net/minecraft/client/renderer/block/ModelBlockRenderer grid").equals("[[I") &&
            fieldTypes.size() == 2 &&
            typed.equals(
                "accessWidener v2 named\naccessible field net/minecraft/client/renderer/block/ModelBlockRenderer blockColors Lnet/minecraft/client/color/block/BlockColors;\n" +
                "mutable field net/minecraft/client/renderer/block/ModelBlockRenderer blockColors Lnet/minecraft/client/color/block/BlockColors;\n"
            ) &&
            typedLeft.size() == 1,
            "an access transformer field line becomes an access widener field with its type from Mojang's mappings:\n" + typed + typedLeft
        );
        String cleaned = Convert.dropUnusedImports(
            "import net.fabricmc.loader.api.FabricLoader;\nimport net.fabricmc.api.EnvType;\nimport java.util.List;\n\n// FabricLoader is only named here\nclass A {\n\tEnvType side;\n\tString s = \"FabricLoader\";\n}\n",
            List.of("net.fabricmc.")
        );
        check(
            cleaned.equals("import net.fabricmc.api.EnvType;\nimport java.util.List;\n\n// FabricLoader is only named here\nclass A {\n\tEnvType side;\n\tString s = \"FabricLoader\";\n}\n"),
            "an import of the old loader that no code uses is dropped, not reported:\n" + cleaned
        );
        check(
            "jei-26.1.2-fabric".equals(Convert.swapLoaderWord("jei-26.1.2-neoforge", "neoforge", "fabric")) &&
            "Ponder-Fabric-26.1".equals(Convert.swapLoaderWord("Ponder-NeoForge-26.1", "neoforge", "fabric")) &&
            "curios-neoforge".equals(Convert.swapLoaderWord("curios-fabric", "fabric", "neoforge")) &&
            Convert.swapLoaderWord("fabricator", "fabric", "neoforge") == null &&
            Convert.loaderNeutral("jei-26.1.2-common-api") &&
            !Convert.loaderNeutral("betterconfig-fabric") &&
            Convert.remapConfiguration("\tcompileOnly \"a:b-fabric:1\"").equals("\tmodCompileOnly \"a:b-fabric:1\"") &&
            Convert.repositories("repositories {\n\tmaven { url = 'https://maven.blamejared.com/' }\n\tmaven { url \"https://maven.example.org/releases\" }\n}\n").equals(
                List.of("https://maven.blamejared.com", "https://maven.example.org/releases", "https://repo.maven.apache.org/maven2")
            ),
            "a dependency's loader word changes only as a whole part of its artifact name; loader-neutral artifacts are told apart"
        );
        check(
            Convert.dropCalls(
                "CreativeModeTab.builder()\n\t.title(t)\n\t.withTabsBefore(CreativeModeTabs.COMBAT)\n\t.icon(() -> f(\".withTabsAfter(\"))\n\t.build()",
                List.of("withTabsBefore", "withTabsAfter")
            ).equals("CreativeModeTab.builder()\n\t.title(t)\n\t.icon(() -> f(\".withTabsAfter(\"))\n\t.build()"),
            "NeoForge's tab order calls leave a builder chain whole, and a string that names one is kept"
        );
        List<String> removed = new ArrayList<>();
        String script = Convert.dropLoomTypes(
            "import net.fabricmc.loom.task.prod.ClientProductionRunTask\nimport java.util.List\n\nplugins {\n\tid 'java'\n}\n\n" +
            "// a ClientProductionRunTask comment stays\ntasks.register('prod', ClientProductionRunTask) {\n\tjvmArgs.add('-Xmx2G')\n}\n\nversion = '1'\n",
            removed
        );
        check(
            script.equals(
                "import java.util.List\n\nplugins {\n\tid 'java'\n}\n\n// a ClientProductionRunTask comment stays\n\nversion = '1'\n"
            ) &&
            removed.size() == 2,
            "a build script's Loom imports and the statements that use their types are removed:\n" + script
        );

        LoaderRules.Provider models = LoaderRules.datagenProvider(
            "package d;\n\nimport net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;\nimport net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;\n\npublic class Models extends FabricModelProvider {\n\tpublic Models(FabricPackOutput output) {\n\t\tsuper(output);\n\t}\n\n\t@Override\n\tpublic void generateBlockStateModels(BlockModelGenerators blocks) {\n\t}\n\n\t@Override\n\tpublic void generateItemModels(ItemModelGenerators items) {\n\t}\n}\n",
            "demo"
        );
        check(
            models.kind().equals("model") &&
            models.text().contains("public class Models extends ModelProvider {") &&
            models.text().contains("super(output, \"demo\");") &&
            models
                .text()
                .contains(
                    "protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {\n\t\tgenerateBlockStateModels(blockModels);"
                ) &&
            !models.text().contains("fabricmc") &&
            models.text().contains("\tpublic void generateItemModels(ItemModelGenerators items) {"),
            "a Fabric model provider becomes NeoForge's ModelProvider for the mod, one registerModels calling both Fabric methods:\n" +
            models.text()
        );
        LoaderRules.Provider loot = LoaderRules.datagenProvider(
            "package d;\n\nimport java.util.concurrent.CompletableFuture;\nimport net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;\nimport net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;\n\npublic class Loot extends FabricBlockLootSubProvider {\n\tpublic Loot(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {\n\t\tsuper(output, lookup);\n\t}\n\n\t@Override\n\tpublic void generate() {\n\t\tdropSelf(GEM_BLOCK);\n\t}\n}\n",
            "demo"
        );
        String entry = LoaderRules.datagenEntry(
            "package d;\n\nimport net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;\nimport net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;\n\npublic class Gen implements DataGeneratorEntrypoint {\n\t@Override\n\tpublic void onInitializeDataGenerator(FabricDataGenerator generator) {\n\t\tFabricDataGenerator.Pack pack = generator.createPack();\n\t\tpack.addProvider(Models::new);\n\t\tpack.addProvider(Loot::new);\n\t}\n}\n",
            Map.of("Models", "model", "Loot", "blockLoot"),
            new ArrayList<>()
        );
        check(
            loot.kind().equals("blockLoot") &&
            loot
                .text()
                .contains(
                    "public Loot(HolderLookup.Provider lookup) {\n\t\tsuper(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);"
                ) &&
            loot.text().contains("getNamespace().equals(\"demo\")") &&
            !loot.text().contains("CompletableFuture") &&
            entry.contains(
                "public void onInitializeDataGenerator(GatherDataEvent generator) {\n\t\tgenerator.createProvider(Models::new);\n\t\tgenerator.createProvider((output, lookup) -> new LootTableProvider(output, Set.of(), List.of(new LootTableProvider.SubProviderEntry(Loot::new, LootContextParamSets.BLOCK)), lookup));"
            ) &&
            !entry.contains("fabricmc") &&
            !entry.contains("@Override"),
            "a block loot provider covers the mod's own blocks, and the datagen entrypoint registers each provider with GatherDataEvent:\n" +
            loot.text() +
            "\n" +
            entry
        );

        Path root = scratch.resolve("convert-mod");
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
        );
        files.put(
            "settings.gradle",
            "pluginManagement {\n\trepositories {\n\t\tmaven {\n\t\t\tname = 'Fabric'\n\t\t\turl = 'https://maven.fabricmc.net/'\n\t\t}\n\t\tgradlePluginPortal()\n\t}\n}\n"
        );
        files.put(
            "gradle.properties",
            "minecraft_version=26.3\nloader_version=0.19.5\nloom_version=1.18.2\nmod_version=1.0.0\nfabric_api_version=0.161.0+26.3\nmodmenu_version=21.0.0\n"
        );
        files.put(
            "build.gradle",
            "plugins {\n\tid 'net.fabricmc.fabric-loom' version \"${loom_version}\"\n}\n\nversion = project.mod_version\n\nloom {\n\truns {\n\t\tselftest {\n\t\t\tclient()\n\t\t\tname = \"Self Test\"\n\t\t\tvmArg \"-Ddemo.selftest=true\"\n\t\t\trunDir = \"run\"\n\t\t}\n\t}\n}\n\ndependencies {\n\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n\n\tcompileOnly \"com.terraformersmc:modmenu:${project.modmenu_version}\"\n}\n\nprocessResources {\n\tfilesMatching(\"fabric.mod.json\") {\n\t\texpand \"version\": project.version\n\t}\n}\n\ntasks.withType(JavaCompile).configureEach {\n\tit.options.release = 21\n}\n"
        );
        files.put(
            "src/main/resources/fabric.mod.json",
            "{\n\t\"schemaVersion\": 1,\n\t\"id\": \"demo\",\n\t\"version\": \"${version}\",\n\t\"name\": \"Demo\",\n\t\"description\": \"A demo.\",\n\t\"authors\": [\"Someone\"],\n\t\"license\": \"MIT\",\n\t\"icon\": \"assets/demo/icon.png\",\n\t\"environment\": \"*\",\n\t\"entrypoints\": {\n\t\t\"main\": [\"demo.Demo\"],\n\t\t\"client\": [\"demo.DemoClient\"],\n\t\t\"modmenu\": [\"demo.Menu\"]\n\t},\n\t\"mixins\": [\"demo.mixins.json\"],\n\t\"depends\": {\n\t\t\"fabricloader\": \">=0.19.5\",\n\t\t\"minecraft\": \"~26.3\",\n\t\t\"java\": \">=21\",\n\t\t\"fabric-api\": \"*\"\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/Demo.java",
            "package demo;\n\nimport net.fabricmc.api.ModInitializer;\nimport net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;\nimport net.fabricmc.fabric.api.registry.FlammableBlockRegistry;\nimport net.minecraft.world.level.block.Blocks;\n\npublic class Demo implements ModInitializer {\n\tpublic static int ticks;\n\n\t@Override\n\tpublic void onInitialize() {\n\t\tServerTickEvents.END_SERVER_TICK.register(server -> ticks++);\n\t\tFlammableBlockRegistry.getDefaultInstance().add(Blocks.WHITE_WOOL, 30, 60);\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/DemoClient.java",
            "package demo;\n\nimport net.fabricmc.api.ClientModInitializer;\nimport net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\nimport net.minecraft.client.KeyMapping;\n\npublic class DemoClient implements ClientModInitializer {\n\tstatic KeyMapping key;\n\n\t@Override\n\tpublic void onInitializeClient() {\n\t\tkey = KeyMappingHelper.registerKeyMapping(new KeyMapping(\"key.demo\", 0, KeyMapping.Category.MISC));\n\t\tClientTickEvents.END_CLIENT_TICK.register(client -> {\n\t\t\twhile (key.consumeClick()) Config.toggle();\n\t\t});\n\t}\n}\n"
        );
        files.put(
            "src/main/java/demo/Config.java",
            "package demo;\n\nimport java.nio.file.Path;\nimport net.fabricmc.loader.api.FabricLoader;\n\nclass Config {\n\tstatic Path path() {\n\t\treturn FabricLoader.getInstance().getConfigDir().resolve(\"demo.json\");\n\t}\n\n\tstatic void toggle() {}\n}\n"
        );
        files.put(
            "src/main/java/demo/Menu.java",
            "package demo;\n\nimport com.terraformersmc.modmenu.api.ConfigScreenFactory;\nimport com.terraformersmc.modmenu.api.ModMenuApi;\n\npublic class Menu implements ModMenuApi {\n\t@Override\n\tpublic ConfigScreenFactory<?> getModConfigScreenFactory() {\n\t\treturn Settings::new;\n\t}\n}\n"
        );
        for (var file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel project = new ProjectModel(root);
        // The release lookups convert makes, answered from Target.fetch's cache as the loaders' own Maven repositories would.
        Map<String, String> answers = Map.of(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
            "<metadata><versioning><versions><version>26.3.0.90</version><version>26.3.0.103</version><version>26.1.2.126</version><version>26.3.0.104-beta</version></versions></versioning></metadata>",
            "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml",
            "<metadata><versioning><versions><version>2.0.140</version><version>2.0.148</version><version>2.1.0-beta.3</version></versions></versioning></metadata>",
            "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/2.0.148/moddev-gradle-2.0.148.module",
            "{\"variants\": [{\"attributes\": {}}]}",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml",
            "<metadata><versioning><versions><version>1.18.2</version><version>1.18.4</version><version>1.20.0-alpha.31</version></versions></versioning></metadata>",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.18.4/fabric-loom-1.18.4.module",
            "{\"variants\": [{\"attributes\": {\"org.gradle.plugin.api-version\": \"9.7.0\"}}]}",
            "https://meta.fabricmc.net/v2/versions/loader",
            "[{\"version\": \"0.20.2-beta\", \"stable\": false}, {\"version\": \"0.20.1\", \"stable\": true}]",
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml",
            "<metadata><versioning><versions><version>0.161.0+26.3</version><version>0.166.2+26.3</version><version>0.150.0+26.1.2</version></versions></versioning></metadata>",
            "https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.3%22%5D&loaders=%5B%22fabric%22%5D",
            "[{\"version_number\": \"21.0.1\"}, {\"version_number\": \"21.0.0\"}, {\"version_number\": \"21.1.0-beta.1\"}]"
        );
        for (var answer : answers.entrySet()) {
            Path cache = project
                .state()
                .resolve(
                    "metadata/" +
                    Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                    ".json"
                );
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        Map<String, byte[]> original = new LinkedHashMap<>();
        for (String name : files.keySet()) original.put(name, Files.readAllBytes(root.resolve(name)));

        // Found by an independent check: the command line's --dry-run still left modaccel's state, its cached release lookups and
        // a receipt in the mod folder, and exited 1 when items were left. Its preview runs on a copy outside the project.
        Map<String, String> beforePreview = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) beforePreview.put(root.relativize(p).toString(), Fingerprints.bytes(Files.readAllBytes(p)));
        }
        Map<String, Object> preview = Convert.preview(root, home, new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--dry-run" }));
        Map<String, String> afterPreview = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) afterPreview.put(root.relativize(p).toString(), Fingerprints.bytes(Files.readAllBytes(p)));
        }
        check(
            afterPreview.equals(beforePreview) &&
            Boolean.TRUE.equals(preview.get("ok")) &&
            Boolean.TRUE.equals(preview.get("dryRun")) &&
            Json.array(preview.get("changed")).contains("src/main/resources/META-INF/neoforge.mods.toml"),
            "convert --dry-run from the command line leaves every file in the project, its state included, as it was: " + preview.get("details")
        );
        // Found by an independent check: the command line kept the dry run's receipt in the temp folder, though help says it keeps none.
        Path previewTemp = Files.createTempDirectory(root.getParent(), "preview-temp-");
        Path previewOut = previewTemp.resolve("out.json");
        Process previewRun = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Djava.io.tmpdir=" + previewTemp,
            "-Dmodaccel.home=" + home,
            "-cp",
            Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            "Modaccel",
            "convert",
            "--root",
            root.toString(),
            "--to",
            "neoforge",
            "--dry-run",
            "--json"
        ).redirectErrorStream(true).redirectOutput(previewOut.toFile()).start();
        check(previewRun.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "convert --dry-run child finishes");
        List<String> leftInTemp;
        try (var list = Files.list(previewTemp)) {
            leftInTemp = list.map(p -> p.getFileName().toString()).filter(n -> !n.equals("out.json")).toList();
        }
        String previewText = Files.readString(previewOut);
        check(
            previewRun.exitValue() == 0 && leftInTemp.isEmpty() && !previewText.contains("\"receipt\""),
            "convert --dry-run leaves no receipt in the temp folder: exit " + previewRun.exitValue() + ", left " + leftInTemp + ", " +
            previewText.substring(Math.max(0, previewText.length() - 400))
        );

        Build build = new Build(project, home, "", new Modaccel.Report(root, "convert"));
        Map<String, Object> dry = new Convert(build).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--dry-run" })
        );
        boolean untouched = true;
        for (var file : original.entrySet()) if (
            !Files.isRegularFile(root.resolve(file.getKey())) ||
            !Arrays.equals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())))
        ) untouched = false;
        try (var all = Files.walk(root)) {
            List<String> made = all
                .filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace(File.separatorChar, '/'))
                .filter(p -> !p.startsWith(".modaccel/") && !p.startsWith(".runs/") && !original.containsKey(p))
                .toList();
            check(
                untouched && made.isEmpty() && !Files.exists(project.state().resolve(Convert.FOLDER)) && dry.get("undoConvert") == null,
                "convert --dry-run writes nothing, not even an undo record: " + made
            );
        }
        Map<String, Object> toNeo = new Convert(build).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge" }),
            false
        );
        check(
            dry.get("changed").equals(toNeo.get("changed")) &&
            dry.get("removed").equals(toNeo.get("removed")) &&
            dry.get("left").equals(toNeo.get("left")) &&
            dry.get("rulesApplied").equals(toNeo.get("rulesApplied")) &&
            Json.array(dry.get("changed")).contains("src/main/resources/META-INF/neoforge.mods.toml") &&
            Convert.text(dry).contains("  remove src/main/resources/fabric.mod.json"),
            "convert --dry-run reports exactly what the conversion then changes: " + Convert.text(dry)
        );
        String demo = Files.readString(root.resolve("src/main/java/demo/Demo.java")), client = Files.readString(
            root.resolve("src/main/java/demo/DemoClient.java")
        );
        String gradle = Files.readString(root.resolve("build.gradle")), toml = Files.readString(
            root.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        );
        check(
            Boolean.TRUE.equals(toNeo.get("ok")) &&
            Json.array(toNeo.get("left")).isEmpty() &&
            !Files.exists(root.resolve("src/main/resources/fabric.mod.json")),
            "a Fabric mod whose every loader call has a rule converts with nothing left: " + toNeo.get("details")
        );
        check(
            demo.contains("@Mod(\"demo\")\npublic class Demo {") &&
            demo.contains("if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) onInitialize();") &&
            demo.contains("NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {") &&
            demo.contains("((FireBlock) Blocks.FIRE).setFlammable(Blocks.WHITE_WOOL, 30, 60);") &&
            !demo.contains("@Override") &&
            !demo.contains("fabricmc"),
            "the main entrypoint becomes a @Mod class that runs it in the first registry event, with its events and flammability converted:\n" +
            demo
        );
        check(
            client.contains("@Mod(value = \"demo\", dist = Dist.CLIENT)") &&
            client.contains(
                "modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class, event -> onInitializeClient());"
            ) &&
            client.contains(
                "container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new demo.Menu().getModConfigScreenFactory().apply(parent));"
            ) &&
            client.contains("event.register(key)"),
            "the client entrypoint runs at NeoForge's first client event, registers its key mapping and Mod Menu's config screen:\n" +
            client
        );
        check(
            gradle.contains("id 'net.neoforged.moddev' version '2.0.148'") &&
            gradle.contains("version = project.neo_version") &&
            gradle.contains("systemProperty 'demo.selftest', 'true'") &&
            gradle.contains("ideName = \"Self Test\"") &&
            !gradle.contains("fabric") &&
            !gradle.contains("modmenu") &&
            Files.readString(root.resolve("gradle.properties")).contains("neo_version=26.3.0.103") &&
            Files.readString(root.resolve("src/main/resources/META-INF/accesstransformer.cfg")).contains(
                "public net.minecraft.world.level.block.FireBlock setFlammable("
            ),
            "the build moves to ModDevGradle (newest stable) and NeoForge (newest release for 26.3), keeping the custom run:\n" +
            gradle
        );
        check(
            toml.contains("modId = \"demo\"") &&
            toml.contains("iconFile = \"assets/demo/icon.png\"") &&
            toml.contains("versionRange = \"[26.3,26.4)\"") &&
            toml.contains("[[mixins]]\nconfig = \"demo.mixins.json\"") &&
            toml.contains("[[accessTransformers]]"),
            "fabric.mod.json becomes neoforge.mods.toml:\n" + toml
        );
        Map<String, Object> back = new Convert(new Build(project, home, "", new Modaccel.Report(root, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "fabric" }),
            false
        );
        boolean javaSame = true;
        for (String name : files.keySet()) if (
            name.endsWith(".java") && !Arrays.equals(original.get(name), Files.readAllBytes(root.resolve(name)))
        ) javaSame = false;
        String fabricGradle = Files.readString(root.resolve("build.gradle"));
        check(
            Boolean.TRUE.equals(back.get("ok")) &&
            javaSame &&
            !Files.exists(root.resolve("src/main/resources/META-INF")) &&
            fabricGradle.contains("id 'net.fabricmc.fabric-loom' version '1.18.4'") &&
            fabricGradle.contains("vmArg \"-Ddemo.selftest=true\"") &&
            fabricGradle.contains("localRuntime \"com.terraformersmc:modmenu:${project.modmenu_version}\"") &&
            Files.readString(root.resolve("gradle.properties")).contains("modmenu_version=21.0.1") &&
            Files.readString(root.resolve("src/main/resources/fabric.mod.json")).contains(
                "\"modmenu\": [\n\t\t\t\"demo.Menu\"\n\t\t]"
            ),
            "converted back to Fabric, every Java file is byte for byte the original (Loom: newest stable on this Gradle): " +
            back.get("details")
        );
        Port.undo(project, String.valueOf(back.get("undoConvert")), Convert.FOLDER);
        Port.undo(project, String.valueOf(toNeo.get("undoConvert")), Convert.FOLDER);
        boolean restored = true;
        for (var file : original.entrySet()) if (
            !Arrays.equals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())))
        ) restored = false;
        check(
            restored && !Files.exists(root.resolve("src/main/resources/META-INF")),
            "undoing both conversions restores every file byte for byte and removes the files they made, and the folders those emptied"
        );

        // Found by an independent check on FastQuit (contariaa/FastQuit 8efed17): dependencies with an exclude block are whole
        // statements. Mod Menu goes with its block, and Cloth Config moves to its NeoForge build, which its repository has.
        Path quit = scratch.resolve("convert-exclude-mod");
        Map<String, String> quitFiles = new LinkedHashMap<>();
        quitFiles.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        quitFiles.put("settings.gradle", "rootProject.name = 'quit'\n");
        quitFiles.put("gradle.properties", "minecraft_version=26.3\nloader_version=0.19.5\nfabric_api_version=0.161.0+26.3\nmodmenu_version=21.0.0\ncloth_config_version=26.3.1\n");
        quitFiles.put(
            "build.gradle",
            "plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.18.2'\n}\n\nrepositories {\n\tmaven { url \"https://maven.shedaniel.me/\" }\n}\n\ndependencies {\n" +
            "\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n" +
            "\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n\n\t// ModMenu\n" +
            "\timplementation(\"com.terraformersmc:modmenu:${project.modmenu_version}\"){\n\t\texclude(group: \"eu.pb4\")\n\t}\n\n\t// Cloth Config\n" +
            "\timplementation(\"me.shedaniel.cloth:cloth-config-fabric:${project.cloth_config_version}\") {\n\t\texclude(group: \"net.fabricmc.fabric-api\")\n\t}\n}\n"
        );
        quitFiles.put(
            "src/main/resources/fabric.mod.json",
            "{\n\t\"schemaVersion\": 1,\n\t\"id\": \"quit\",\n\t\"version\": \"1.0.0\",\n\t\"entrypoints\": {\n\t\t\"main\": [\"quit.Quit\"]\n\t},\n\t\"depends\": {\n\t\t\"minecraft\": \"~26.3\"\n\t}\n}\n"
        );
        quitFiles.put(
            "src/main/java/quit/Quit.java",
            "package quit;\n\nimport net.fabricmc.api.ModInitializer;\n\npublic class Quit implements ModInitializer {\n\t@Override\n\tpublic void onInitialize() {\n\t}\n}\n"
        );
        for (var file : quitFiles.entrySet()) {
            Path path = quit.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel quitProject = new ProjectModel(quit);
        Map<String, String> quitAnswers = new LinkedHashMap<>(answers);
        quitAnswers.put(
            "https://maven.shedaniel.me/me/shedaniel/cloth/cloth-config-neoforge/maven-metadata.xml",
            "<metadata><versioning><versions><version>26.3.1</version></versions></versioning></metadata>"
        );
        for (var answer : quitAnswers.entrySet()) {
            Path cache = quitProject.state().resolve("metadata/" + Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        Convert quitConvert = new Convert(new Build(quitProject, home, "", new Modaccel.Report(quit, "convert")));
        quitConvert.run(new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--dry-run" }));
        // A dry run writes nothing; the converted text is what it staged.
        String quitGradle = quitConvert.current("build.gradle");
        check(
            quitGradle.chars().filter(c -> c == '{').count() == quitGradle.chars().filter(c -> c == '}').count() &&
            !quitGradle.contains("modmenu") &&
            !quitGradle.contains("eu.pb4") &&
            quitGradle.contains("\timplementation(\"me.shedaniel.cloth:cloth-config-neoforge:${project.cloth_config_version}\") {\n\t\texclude(group: \"net.fabricmc.fabric-api\")\n\t}\n") &&
            !quitGradle.contains("cloth-config-fabric"),
            "a dependency with an exclude block is removed or moved whole; Cloth Config moves to its NeoForge build:\n" + quitGradle
        );

        // A mod written for NeoForge first goes to Fabric through the NeoForge bridge (knowledge/loaders.json "bridges").
        Map<String, Object> bridgeRule = null;
        for (Object b : LoaderRules.list(rules.get("bridges"))) if ("neoforge-on-fabric".equals(Json.object(b).get("id"))) bridgeRule = Json.object(b);
        check(bridgeRule != null, "knowledge/loaders.json has the NeoForge bridge");
        Map<String, Object> bridgeTest = Json.object(bridgeRule.get("test"));
        Path neo = scratch.resolve("convert-neoforge-mod");
        Map<String, String> neoFiles = new LinkedHashMap<>();
        neoFiles.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        neoFiles.put("settings.gradle", "rootProject.name = 'demo'\n");
        neoFiles.put("gradle.properties", "minecraft_version=26.3\nneo_version=26.3.0.103\nmod_id=demo\n");
        neoFiles.put(
            "build.gradle",
            "plugins {\n\tid 'net.neoforged.moddev' version '2.0.148'\n}\n\nneoForge {\n\tversion = project.neo_version\n\truns {\n\t\tclient {\n\t\t\tclient()\n\t\t}\n\t}\n\tmods {\n\t\t\"demo\" {\n\t\t\tsourceSet(sourceSets.main)\n\t\t}\n\t}\n}\n\ndependencies {\n}\n"
        );
        neoFiles.put(
            "src/main/resources/META-INF/neoforge.mods.toml",
            "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"${mod_id}\"\nversion=\"1.0.0\"\ndisplayName=\"Demo\"\n"
        );
        neoFiles.put("src/main/java/demo/Demo.java", Json.text(bridgeTest, "neoforge", ""));
        for (var file : neoFiles.entrySet()) {
            Path path = neo.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        ProjectModel neoProject = new ProjectModel(neo);
        for (var answer : answers.entrySet()) {
            Path cache = neoProject.state().resolve("metadata/" + Fingerprints.bytes(answer.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer.getValue());
        }
        Convert neoConvert = new Convert(new Build(neoProject, home, "", new Modaccel.Report(neo, "convert")));
        // Written, not a dry run: the way back and undo below start from the converted mod.
        Map<String, Object> bridged = neoConvert.run(new Modaccel.Options(new String[] { "convert", "--to", "fabric" }), false);
        String bridgedEntry = neoConvert.current("src/main/java/demo/Demo.java");
        String fabricJson = neoConvert.current("src/main/resources/fabric.mod.json");
        String bridgedRegister = neoConvert.current("src/main/java/demo/loaderbridge/DeferredRegister.java");
        check(
            bridgedEntry.equals(Json.text(bridgeTest, "fabric", "")) &&
            bridgedRegister != null &&
            bridgedRegister.startsWith("package demo.loaderbridge;") &&
            neoConvert.current("src/main/java/demo/loaderbridge/ClientEntrypoint.java") != null &&
            fabricJson.contains("\"demo.Demo\"") &&
            fabricJson.contains("\"demo.loaderbridge.ClientEntrypoint\"") &&
            Json.array(bridged.get("left")).isEmpty(),
            "a NeoForge @Mod class becomes a Fabric entrypoint through the NeoForge bridge, its listeners typed and its imports on the bridge:\n" +
            bridgedEntry +
            bridged.get("details")
        );
        // Found by an independent check on EndNetwork: the bridge, which both sides use, went into a client package.
        check(
            "net.x.core.loaderbridge".equals(Convert.bridgePackage(List.of(
                "package net.x.client;\n@Mod(value = X.ID, dist = Dist.CLIENT)\nclass XClient {}\n",
                "package net.x.core;\n@Mod(X.ID)\nclass X {}\n"
            ))) &&
            "net.x.loaderbridge".equals(Convert.bridgePackage(List.of("package net.x.client;\n@Mod(value = X.ID, dist = Dist.CLIENT)\nclass XClient {}\n"))) &&
            Convert.bridgePackage(List.of("package net.x;\nclass X {}\n")) == null,
            "the NeoForge bridge goes beside the common @Mod class, never in a client package"
        );
        // Found by the convert sweep: the NeoForge MDK converted to Fabric did not convert back. The way back takes the bridge out.
        Map<String, Object> unbridged = new Convert(new Build(neoProject, home, "", new Modaccel.Report(neo, "convert"))).run(
            new Modaccel.Options(new String[] { "convert", "--to", "neoforge", "--no-compile" })
        );
        String backEntry = Files.readString(neo.resolve("src/main/java/demo/Demo.java"));
        String neoOriginal = Json.text(bridgeTest, "neoforge", "");
        check(
            backEntry.contains("@Mod(Demo.MODID)\npublic class Demo {") &&
            backEntry.contains("\tpublic Demo(IEventBus modEventBus, ModContainer modContainer) {\n\t\tmodEventBus.addListener(this::commonSetup);\n\t\tBLOCKS.register(modEventBus);\n\t\tNeoForge.EVENT_BUS.register(this);\n\t\tmodContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);\n\t}\n") &&
            !backEntry.contains("loaderbridge") &&
            !backEntry.contains("fabricmc") &&
            neoOriginal.lines().filter(l -> l.startsWith("import ")).allMatch(backEntry::contains) &&
            !Files.exists(neo.resolve("src/main/java/demo/loaderbridge")) &&
            Files.isRegularFile(neo.resolve("src/main/resources/META-INF/neoforge.mods.toml")),
            "a mod that went to Fabric through the NeoForge bridge comes back as a NeoForge @Mod class, without the bridge:\n" +
            backEntry +
            unbridged.get("details")
        );
        // Found by an independent check on EndNetwork: undo left the bridge's emptied folder behind.
        Port.undo(neoProject, String.valueOf(unbridged.get("undoConvert")), Convert.FOLDER);
        Port.undo(neoProject, String.valueOf(bridged.get("undoConvert")), Convert.FOLDER);
        check(
            !Files.exists(neo.resolve("src/main/java/demo/loaderbridge")) &&
            !Files.exists(neo.resolve("src/main/resources/fabric.mod.json")) &&
            Files.readString(neo.resolve("src/main/java/demo/Demo.java")).equals(Json.text(bridgeTest, "neoforge", "")),
            "undoing both conversions restores the NeoForge mod and leaves no folder the bridge emptied"
        );
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        convertChecks(home, scratch);
        System.out.println(count + " convert checks passed");
    }
}
