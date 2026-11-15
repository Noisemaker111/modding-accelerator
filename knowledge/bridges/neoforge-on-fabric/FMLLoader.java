package ${package};

import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * FancyModLoader's net.neoforged.fml.loading.FMLLoader, the parts mods read: the physical side (NeoForge's Dist is
 * Fabric's EnvType in the converted mod), whether the game is a production one, and the game folder, from Fabric
 * Loader. FML 12 (NeoForge 26.x) reaches them through getCurrent(); FML 4 (NeoForge 21.1) through static methods; both
 * are here.
 */
public final class FMLLoader {

    private static final FMLLoader CURRENT = new FMLLoader();

    private FMLLoader() {}

    public static FMLLoader getCurrent() {
        return CURRENT;
    }

    public static FMLLoader getCurrentOrNull() {
        return CURRENT;
    }

    /** FML 12's instance method, and FML 4's static one under the same name. */
    public static EnvType getDist() {
        return FabricLoader.getInstance().getEnvironmentType();
    }

    public static boolean isProduction() {
        return !FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path getGamePath() {
        return FabricLoader.getInstance().getGameDir();
    }
}
