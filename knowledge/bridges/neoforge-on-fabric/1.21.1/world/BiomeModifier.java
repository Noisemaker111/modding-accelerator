package ${package}.world;

import ${package}.NeoForgeRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.world.level.biome.Biome;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.world.BiomeModifier: a change to biomes, read from data packs
 * (data/NAMESPACE/neoforge/biome_modifier/*.json, typed by a codec in NeoForgeRegistries.BIOME_MODIFIER_SERIALIZERS) and
 * applied in phases when the server's registries are final. On Fabric, BiomeModifierBridge runs every modifier of each
 * phase on a copy of the biome and gives Fabric API's BiomeModifications what it changed.
 */
public interface BiomeModifier {

    Codec<BiomeModifier> DIRECT_CODEC = Codec.lazyInitialized(() -> NeoForgeRegistries.BIOME_MODIFIER_SERIALIZERS.byNameCodec())
        .dispatch(BiomeModifier::codec, Function.identity());
    Codec<Holder<BiomeModifier>> REFERENCE_CODEC = RegistryFileCodec.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, DIRECT_CODEC);
    Codec<HolderSet<BiomeModifier>> LIST_CODEC = RegistryCodecs.homogeneousList(NeoForgeRegistries.Keys.BIOME_MODIFIERS, DIRECT_CODEC);

    void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder);

    MapCodec<? extends BiomeModifier> codec();

    enum Phase {
        BEFORE_EVERYTHING,
        ADD,
        REMOVE,
        MODIFY,
        AFTER_EVERYTHING
    }
}
