package ${package};

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * NeoForge's net.neoforged.fml.loading.FMLEnvironment: the physical side (NeoForge's Dist is Fabric's EnvType in the
 * converted mod) and whether this is a production game, from Fabric Loader.
 */
public final class FMLEnvironment {

    private FMLEnvironment() {}

    public static EnvType getDist() {
        return FabricLoader.getInstance().getEnvironmentType();
    }

    public static boolean isProduction() {
        return !FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
