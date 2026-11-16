package ${package};

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * NeoForge's net.neoforged.neoforgespi.language.IModFileInfo, a mod file and the mods in it. On Fabric each mod is its
 * own file (its root paths, jar-in-jar mods included), the same object for every call, so mods that skip a file they
 * have seen see each once. Fabric Loader's built-in mods (the game, Java) have no classes of a mod to scan.
 */
public interface IModFileInfo {

    Map<String, IModFileInfo> FILES = new ConcurrentHashMap<>();

    IModFile getFile();

    List<IModInfo> getMods();

    /** The file of a loaded mod, or null for a mod Fabric Loader did not load. */
    static IModFileInfo of(String modid) {
        var container = FabricLoader.getInstance().getModContainer(modid).orElse(null);
        if (container == null) return null;
        return FILES.computeIfAbsent(modid, id -> {
            boolean builtin = "builtin".equals(container.getMetadata().getType());
            ModFileScanData scan = new ModFileScanData(builtin ? List.of() : container.getRootPaths());
            IModFile file = () -> scan;
            List<IModInfo> mods = List.of(IModInfo.of(id));
            return new IModFileInfo() {
                @Override
                public IModFile getFile() {
                    return file;
                }

                @Override
                public List<IModInfo> getMods() {
                    return mods;
                }
            };
        });
    }
}
