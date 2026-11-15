package ${package};

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * NeoForge's net.neoforged.neoforgespi.language.IModInfo, as far as mods read it from ModContainer.getModInfo(): the
 * mod's id, name, description and namespace, read from Fabric Loader's metadata of that mod.
 */
public interface IModInfo {

    String getModId();

    String getDisplayName();

    String getDescription();

    default String getNamespace() {
        return getModId();
    }

    /** The mod's metadata as Fabric Loader read it (the mod's id alone when Fabric Loader does not know it). */
    static IModInfo of(String modid) {
        ModMetadata meta = FabricLoader.getInstance().getModContainer(modid).map(c -> c.getMetadata()).orElse(null);
        return new IModInfo() {
            @Override
            public String getModId() {
                return modid;
            }

            @Override
            public String getDisplayName() {
                return meta == null ? modid : meta.getName();
            }

            @Override
            public String getDescription() {
                return meta == null ? "" : meta.getDescription();
            }
        };
    }
}
