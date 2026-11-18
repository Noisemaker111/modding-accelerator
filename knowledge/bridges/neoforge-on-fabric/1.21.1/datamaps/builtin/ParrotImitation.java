package ${package}.datamaps.builtin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/** NeoForge's net.neoforged.neoforge.registries.datamaps.builtin.ParrotImitation: the sound a parrot makes for a mob, {"sound": id} or the id. */
public record ParrotImitation(SoundEvent sound) {

    public static final Codec<ParrotImitation> SOUND_CODEC = BuiltInRegistries.SOUND_EVENT.byNameCodec().xmap(ParrotImitation::new, ParrotImitation::sound);

    public static final Codec<ParrotImitation> CODEC = Codec.withAlternative(
        RecordCodecBuilder.create(i -> i.group(BuiltInRegistries.SOUND_EVENT.byNameCodec().fieldOf("sound").forGetter(ParrotImitation::sound)).apply(i, ParrotImitation::new)),
        SOUND_CODEC
    );
}
