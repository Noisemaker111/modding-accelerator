package ${package}.conditions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.loader.api.FabricLoader;

/** NeoForge's net.neoforged.neoforge.common.conditions.ModLoadedCondition: true when a mod of that id is loaded (on Fabric, Fabric Loader's list). */
public record ModLoadedCondition(String modid) implements ICondition {

    public static final MapCodec<ModLoadedCondition> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(Codec.STRING.fieldOf("modid").forGetter(ModLoadedCondition::modid)).apply(i, ModLoadedCondition::new)
    );

    @Override
    public boolean test(IContext context) {
        return FabricLoader.getInstance().isModLoaded(this.modid);
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
