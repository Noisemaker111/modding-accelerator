package ${package}.datamaps.builtin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;

/** NeoForge 26.1's net.neoforged.neoforge.registries.datamaps.builtin.FurnaceFuel: an item's burn time, {"burn_time": ticks} or the ticks. */
public record FurnaceFuel(int burnTime) {

    public static final Codec<FurnaceFuel> BURN_TIME_CODEC = ExtraCodecs.POSITIVE_INT.xmap(FurnaceFuel::new, FurnaceFuel::burnTime);

    public static final Codec<FurnaceFuel> CODEC = Codec.withAlternative(
        RecordCodecBuilder.create(i -> i.group(ExtraCodecs.POSITIVE_INT.fieldOf("burn_time").forGetter(FurnaceFuel::burnTime)).apply(i, FurnaceFuel::new)),
        BURN_TIME_CODEC
    );
}
