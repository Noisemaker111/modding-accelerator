package ${package}.loader.api.metadata;

import net.neoforged.api.distmarker.Dist;

/**
 * Fabric Loader's net.fabricmc.loader.api.metadata.ModEnvironment: the side a mod runs on. matches takes NeoForge's Dist,
 * which convert writes for Fabric's EnvType.
 */
public enum ModEnvironment {
    CLIENT,
    SERVER,
    UNIVERSAL;

    public boolean matches(Dist type) {
        return switch (this) {
            case CLIENT -> type == Dist.CLIENT;
            case SERVER -> type == Dist.DEDICATED_SERVER;
            case UNIVERSAL -> true;
        };
    }
}
