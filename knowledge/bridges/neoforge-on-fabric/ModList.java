package ${package};

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;

/**
 * NeoForge's net.neoforged.fml.ModList, read from Fabric Loader's list of mods. getSortedMods and getMods list every mod
 * Fabric Loader loaded, ordered by id (NeoForge orders them by their dependencies, which Fabric Loader does not expose).
 */
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

    /** Every loaded mod's container. */
    public List<ModContainer> getSortedMods() {
        return FabricLoader.getInstance()
            .getAllMods()
            .stream()
            .map(c -> c.getMetadata().getId())
            .sorted(Comparator.naturalOrder())
            .map(Bridge::container)
            .toList();
    }

    /** Every loaded mod's metadata. */
    public List<IModInfo> getMods() {
        return getSortedMods().stream().map(ModContainer::getModInfo).toList();
    }
}
