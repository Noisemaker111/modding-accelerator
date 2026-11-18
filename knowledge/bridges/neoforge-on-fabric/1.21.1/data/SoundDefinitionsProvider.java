package ${package}.data;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * NeoForge's net.neoforged.neoforge.common.data.SoundDefinitionsProvider: the mod's assets/MODID/sounds.json, one
 * definition per sound event (keyed by the event's path, as the game reads a namespace's sounds.json). An event added
 * twice is an error, and so is a sound of type event that names an event neither defined here nor in the game.
 */
public abstract class SoundDefinitionsProvider implements DataProvider {

    private final PackOutput output;
    private final String modId;
    private final Map<String, SoundDefinition> sounds = new LinkedHashMap<>();

    protected SoundDefinitionsProvider(PackOutput output, String modId, ExistingFileHelper existingFileHelper) {
        this.output = output;
        this.modId = modId;
    }

    public abstract void registerSounds();

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        this.sounds.clear();
        registerSounds();
        List<String> invalid = this.sounds.entrySet().stream()
            .filter(e -> e.getValue().soundList().stream()
                .anyMatch(s -> s.type() == SoundDefinition.SoundType.EVENT && !this.sounds.containsKey(e.getKey()) && !BuiltInRegistries.SOUND_EVENT.containsKey(s.name())))
            .map(e -> this.modId + ":" + e.getKey())
            .toList();
        if (!invalid.isEmpty()) throw new IllegalStateException("Found invalid sound events: " + invalid);
        if (this.sounds.isEmpty()) return CompletableFuture.allOf();
        JsonObject json = new JsonObject();
        this.sounds.forEach((name, definition) -> json.add(name, definition.serialize()));
        return DataProvider.saveStable(cache, json, this.output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(this.modId).resolve("sounds.json"));
    }

    @Override
    public String getName() {
        return "Sound Definitions";
    }

    protected static SoundDefinition definition() {
        return SoundDefinition.definition();
    }

    protected static SoundDefinition.Sound sound(ResourceLocation name, SoundDefinition.SoundType type) {
        return SoundDefinition.Sound.sound(name, type);
    }

    protected static SoundDefinition.Sound sound(ResourceLocation name) {
        return sound(name, SoundDefinition.SoundType.SOUND);
    }

    protected static SoundDefinition.Sound sound(String name, SoundDefinition.SoundType type) {
        return sound(ResourceLocation.parse(name), type);
    }

    protected static SoundDefinition.Sound sound(String name) {
        return sound(ResourceLocation.parse(name));
    }

    protected void add(Holder<SoundEvent> soundEvent, SoundDefinition definition) {
        add(soundEvent.value(), definition);
    }

    protected void add(SoundEvent soundEvent, SoundDefinition definition) {
        add(soundEvent.getLocation(), definition);
    }

    protected void add(ResourceLocation soundEvent, SoundDefinition definition) {
        if (this.sounds.put(soundEvent.getPath(), definition) != null) {
            throw new IllegalStateException("Sound event '" + this.modId + ":" + soundEvent.getPath() + "' already exists");
        }
    }

    protected void add(String soundEvent, SoundDefinition definition) {
        add(ResourceLocation.parse(soundEvent), definition);
    }
}
