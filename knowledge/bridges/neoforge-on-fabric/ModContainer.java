package ${package};

import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * NeoForge's net.neoforged.fml.ModContainer for a converted mod. A registered config is read from Fabric's config
 * folder right away and ModConfigEvent.Loading is posted. Extension points (NeoForge's mod list config screen) have
 * no Fabric counterpart without another mod, so registering one does nothing.
 */
public class ModContainer {

    final String modid;

    ModContainer(String modid) {
        this.modid = modid;
    }

    public final String getModId() {
        return modid;
    }

    public final String getNamespace() {
        return modid;
    }

    public IEventBus getEventBus() {
        return Bus.INSTANCE;
    }

    public void registerConfig(ModConfig.Type type, ModConfigSpec spec) {
        registerConfig(type, spec, modid + "-" + type.extension() + ".toml");
    }

    public void registerConfig(ModConfig.Type type, ModConfigSpec spec, String fileName) {
        ModConfig config = new ModConfig(type, spec, this, fileName);
        spec.load(FabricLoader.getInstance().getConfigDir().resolve(fileName));
        Bus.INSTANCE.post(new ModConfigEvent.Loading(config));
    }

    public <T> void registerExtensionPoint(Class<T> type, T extension) {}

    public <T> void registerExtensionPoint(Class<T> type, Supplier<T> extension) {}
}
