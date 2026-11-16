package ${package};

import java.util.List;

/**
 * FancyModLoader's net.neoforged.fml.loading.LoadingModList, the mods found while loading, which NeoForge offers before
 * ModList exists. Fabric Loader has its whole list before any mod runs, so this reads the same mods as ModList.
 */
public class LoadingModList {

    private static final LoadingModList INSTANCE = new LoadingModList();

    public static LoadingModList get() {
        return INSTANCE;
    }

    /** The file of a mod being loaded, or null when no such mod is. */
    public IModFileInfo getModFileById(String modid) {
        return IModFileInfo.of(modid);
    }

    public List<IModInfo> getMods() {
        return ModList.get().getMods();
    }
}
