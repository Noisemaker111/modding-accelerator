package ${package}.mixin;

import ${package}.extensions.IDataGeneratorExtension;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Gives the game's DataGenerator NeoForge's additions (IDataGeneratorExtension); its main pack's output is its own field. */
@Mixin(DataGenerator.class)
public abstract class DataGeneratorExtensionMixin implements IDataGeneratorExtension {

    @Shadow
    @Final
    protected PackOutput vanillaPackOutput;

    @Override
    public PackOutput getPackOutput() {
        return this.vanillaPackOutput;
    }
}
