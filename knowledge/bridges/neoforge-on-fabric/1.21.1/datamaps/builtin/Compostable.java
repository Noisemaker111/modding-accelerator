package ${package}.datamaps.builtin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * NeoForge 26.1's net.neoforged.neoforge.registries.datamaps.builtin.Compostable: an item's chance to fill a composter
 * level, {"chance": 0..1, "can_villager_compost": bool} or the chance.
 */
public record Compostable(float chance, boolean canVillagerCompost) {

    public Compostable(float chance) {
        this(chance, false);
    }

    public static final Codec<Compostable> CHANCE_CODEC = Codec.floatRange(0f, 1f).xmap(Compostable::new, Compostable::chance);

    public static final Codec<Compostable> CODEC = Codec.withAlternative(
        RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(Compostable::chance),
            Codec.BOOL.optionalFieldOf("can_villager_compost", false).forGetter(Compostable::canVillagerCompost)
        ).apply(i, Compostable::new)),
        CHANCE_CODEC
    );
}
