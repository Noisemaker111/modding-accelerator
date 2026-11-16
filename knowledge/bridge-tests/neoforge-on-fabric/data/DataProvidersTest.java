package ${package}.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.PackOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NeoForge's data providers on Fabric write the files NeoForge writes: the lang file sorted, literals as plain strings,
 * tag keys as tag.REGISTRY.NAMESPACE.PATH; sounds.json with a default sound as its bare name, the minecraft namespace
 * dropped and only the settings that differ from the game's defaults.
 */
class DataProvidersTest {

    @TempDir
    Path out;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The atlas source types, which the client's own start-up registers.
        net.minecraft.client.renderer.texture.atlas.SpriteSources.bootstrap();
    }

    static JsonElement read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file));
    }

    @Test
    void languageFileIsSortedWithTagKeys() throws Exception {
        new LanguageProvider(new PackOutput(this.out), "demo", "en_us") {
            @Override
            protected void addTranslations() {
                add("item.demo.zinc", "Zinc");
                add("block.demo.anvil", Component.literal("Anvil"));
                add(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "gems/ruby")), "Rubies");
            }
        }.run(CachedOutput.NO_CACHE).join();
        assertEquals(
            JsonParser.parseString("{\"block.demo.anvil\":\"Anvil\",\"item.demo.zinc\":\"Zinc\",\"tag.item.c.gems.ruby\":\"Rubies\"}"),
            read(this.out.resolve("assets/demo/lang/en_us.json"))
        );
    }

    @Test
    void aTranslationKeyAddedTwiceIsRefused() {
        LanguageProvider twice = new LanguageProvider(new PackOutput(this.out), "demo", "en_us") {
            @Override
            protected void addTranslations() {
                add("item.demo.zinc", "Zinc");
                add("item.demo.zinc", "Zinc again");
            }
        };
        assertThrows(IllegalStateException.class, () -> twice.run(CachedOutput.NO_CACHE));
    }

    @Test
    void atlasesAreWrittenInTheGamesForm() throws Exception {
        new SpriteSourceProvider(new PackOutput(this.out), java.util.concurrent.CompletableFuture.completedFuture(lookup()), "demo") {
            @Override
            protected void gather() {
                atlas(Identifier.fromNamespaceAndPath("minecraft", "blocks")).addSource(new net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister("demo_block", "demo_block/"));
            }
        }.run(CachedOutput.NO_CACHE).join();
        assertEquals(
            JsonParser.parseString("{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"demo_block\",\"prefix\":\"demo_block/\"}]}"),
            read(this.out.resolve("assets/minecraft/atlases/blocks.json"))
        );
    }

    @Test
    void anItemTagCopiesItsBlockTag() throws Exception {
        TagKey<net.minecraft.world.level.block.Block> blocks = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("demo", "stones"));
        net.minecraft.data.tags.TagsProvider.TagLookup<net.minecraft.world.level.block.Block> built =
            key -> key.equals(blocks) ? java.util.Optional.of(net.minecraft.tags.TagBuilder.create().addElement(Identifier.withDefaultNamespace("stone"))) : java.util.Optional.empty();
        new BlockTagCopyingItemTagProvider(new PackOutput(this.out), java.util.concurrent.CompletableFuture.completedFuture(lookup()), java.util.concurrent.CompletableFuture.completedFuture(built), "demo") {
            @Override
            protected void addTags(net.minecraft.core.HolderLookup.Provider provider) {
                copy(blocks, TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("demo", "stones")));
            }
        }.run(CachedOutput.NO_CACHE).join();
        assertEquals(JsonParser.parseString("{\"values\":[\"minecraft:stone\"]}"), read(this.out.resolve("data/demo/tags/item/stones.json")));
    }

    static net.minecraft.core.HolderLookup.Provider lookup() {
        return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    @Test
    void soundsAreWrittenInTheGamesShortestForm() throws Exception {
        new SoundDefinitionsProvider(new PackOutput(this.out), "demo") {
            @Override
            public void registerSounds() {
                add("demo:ding", definition().subtitle("demo.subtitle.ding").with(
                    sound("demo:ding"),
                    sound("demo:dong").volume(0.5).stream(),
                    sound("minecraft:ui.button.click", SoundDefinition.SoundType.EVENT)
                ));
            }
        }.run(CachedOutput.NO_CACHE).join();
        assertEquals(
            JsonParser.parseString("{\"ding\":{\"subtitle\":\"demo.subtitle.ding\",\"sounds\":[\"demo:ding\"," +
                "{\"name\":\"demo:dong\",\"volume\":0.5,\"stream\":true},{\"name\":\"ui.button.click\",\"type\":\"event\"}]}}"),
            read(this.out.resolve("assets/demo/sounds.json"))
        );
    }
}
