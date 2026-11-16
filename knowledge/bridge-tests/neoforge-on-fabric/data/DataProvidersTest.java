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
