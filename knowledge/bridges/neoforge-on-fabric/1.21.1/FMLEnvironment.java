package ${package};

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * FancyModLoader 4's net.neoforged.fml.loading.FMLEnvironment (NeoForge 21.1): the physical side (NeoForge's Dist is
 * Fabric's EnvType in the converted mod) and whether this is a production game, from Fabric Loader.
 */
public class FMLEnvironment {

    public static final EnvType dist = FabricLoader.getInstance().getEnvironmentType();
    public static final boolean production = !FabricLoader.getInstance().isDevelopmentEnvironment();
}
