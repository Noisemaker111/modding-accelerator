package ${package}.extensions;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * What NeoForge adds to the game's DataGenerator (its patch): addProvider with a run flag into the main pack, and the
 * main pack's output. Injected into DataGenerator on Fabric (mixin DataGeneratorExtensionMixin, which supplies
 * getPackOutput from the generator's own vanillaPackOutput); Fabric's FabricDataGenerator is a DataGenerator, so a
 * NeoForge mod's GatherDataEvent handler can call them on event.getGenerator() unchanged.
 */
public interface IDataGeneratorExtension {

    PackOutput getPackOutput();

    default PackOutput getPackOutput(String path) {
        return new PackOutput(getPackOutput().getOutputFolder().resolve(path));
    }

    default <T extends DataProvider> T addProvider(boolean run, T provider) {
        return ((DataGenerator) (Object) this).getVanillaPack(run).addProvider(output -> provider);
    }

    default <T extends DataProvider> T addProvider(boolean run, DataProvider.Factory<T> factory) {
        return ((DataGenerator) (Object) this).getVanillaPack(run).addProvider(factory);
    }
}
