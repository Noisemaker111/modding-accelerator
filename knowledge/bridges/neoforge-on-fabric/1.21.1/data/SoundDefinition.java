package ${package}.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge's net.neoforged.neoforge.common.data.SoundDefinition: one sound event of a sounds.json, written in the game's
 * sounds.json form. A sound with every setting at the game's default is written as its bare name; a name in the
 * minecraft namespace drops the namespace; replace and subtitle are written only when set.
 */
public final class SoundDefinition {

    private final List<Sound> sounds = new ArrayList<>();
    private boolean replace;
    private @Nullable String subtitle;

    private SoundDefinition() {}

    public static SoundDefinition definition() {
        return new SoundDefinition();
    }

    public SoundDefinition replace(boolean replace) {
        this.replace = replace;
        return this;
    }

    public SoundDefinition subtitle(@Nullable String subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    public SoundDefinition with(Sound sound) {
        this.sounds.add(sound);
        return this;
    }

    public SoundDefinition with(Sound... sounds) {
        this.sounds.addAll(Arrays.asList(sounds));
        return this;
    }

    List<Sound> soundList() {
        return this.sounds;
    }

    JsonObject serialize() {
        if (this.sounds.isEmpty()) throw new IllegalStateException("A sound definition needs at least one sound");
        JsonObject out = new JsonObject();
        if (this.replace) out.addProperty("replace", true);
        if (this.subtitle != null) out.addProperty("subtitle", this.subtitle);
        JsonArray list = new JsonArray();
        for (Sound sound : this.sounds) list.add(sound.serialize());
        out.add("sounds", list);
        return out;
    }

    /** Whether a sound is a sound file or another sound event; written as "file" or "event". */
    public enum SoundType {
        SOUND("file"),
        EVENT("event");

        final String jsonString;

        SoundType(String jsonString) {
            this.jsonString = jsonString;
        }
    }

    /** One sound of a definition, with the game's defaults: volume 1, pitch 1, weight 1, attenuation 16. */
    public static final class Sound {

        private final ResourceLocation name;
        private final SoundType type;
        private float volume = 1.0F;
        private float pitch = 1.0F;
        private int weight = 1;
        private boolean stream;
        private int attenuationDistance = 16;
        private boolean preload;

        private Sound(ResourceLocation name, SoundType type) {
            this.name = name;
            this.type = type;
        }

        public static Sound sound(ResourceLocation name, SoundType type) {
            return new Sound(name, type);
        }

        public Sound volume(double volume) {
            return volume((float) volume);
        }

        public Sound volume(float volume) {
            if (volume <= 0.0F) throw new IllegalArgumentException("Volume must be positive for sound " + this.name + ", but was " + volume);
            this.volume = volume;
            return this;
        }

        public Sound pitch(double pitch) {
            return pitch((float) pitch);
        }

        public Sound pitch(float pitch) {
            if (pitch <= 0.0F) throw new IllegalArgumentException("Pitch must be positive for sound " + this.name + ", but was " + pitch);
            this.pitch = pitch;
            return this;
        }

        public Sound weight(int weight) {
            if (weight <= 0) throw new IllegalArgumentException("Weight must be positive for sound " + this.name + ", but was " + weight);
            this.weight = weight;
            return this;
        }

        public Sound stream() {
            return stream(true);
        }

        public Sound stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Sound attenuationDistance(int attenuationDistance) {
            this.attenuationDistance = attenuationDistance;
            return this;
        }

        public Sound preload() {
            return preload(true);
        }

        public Sound preload(boolean preload) {
            this.preload = preload;
            return this;
        }

        ResourceLocation name() {
            return this.name;
        }

        SoundType type() {
            return this.type;
        }

        JsonElement serialize() {
            String written = "minecraft".equals(this.name.getNamespace()) ? this.name.getPath() : this.name.toString();
            boolean defaults = this.type == SoundType.SOUND && this.volume == 1.0F && this.pitch == 1.0F && this.weight == 1 &&
                !this.stream && this.attenuationDistance == 16 && !this.preload;
            if (defaults) return new JsonPrimitive(written);
            JsonObject out = new JsonObject();
            out.addProperty("name", written);
            if (this.type != SoundType.SOUND) out.addProperty("type", this.type.jsonString);
            if (this.volume != 1.0F) out.addProperty("volume", this.volume);
            if (this.pitch != 1.0F) out.addProperty("pitch", this.pitch);
            if (this.weight != 1) out.addProperty("weight", this.weight);
            if (this.stream) out.addProperty("stream", true);
            if (this.preload) out.addProperty("preload", true);
            if (this.attenuationDistance != 16) out.addProperty("attenuation_distance", this.attenuationDistance);
            return out;
        }
    }
}
