package ${package};

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;

/** NeoForge's net.neoforged.fml.ModList, read from Fabric Loader's list of mods. */
public class ModList {

    private static final ModList INSTANCE = new ModList();

    public static ModList get() {
        return INSTANCE;
    }

    public boolean isLoaded(String modid) {
        return FabricLoader.getInstance().isModLoaded(modid);
    }

    public Optional<? extends ModContainer> getModContainerById(String modid) {
        return isLoaded(modid) ? Optional.of(Bridge.container(modid)) : Optional.empty();
    }
}
