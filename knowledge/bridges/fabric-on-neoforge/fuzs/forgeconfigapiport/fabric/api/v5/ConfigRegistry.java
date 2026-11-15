package ${package}.fuzs.forgeconfigapiport.fabric.api.v5;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import ${package}.loader.api.FabricLoader;

/**
 * Forge Config API Port's fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry on NeoForge, whose own config system
 * that library ports to Fabric: register is the mod container's registerConfig, so NeoForge loads, watches and
 * syncs the config as its own (client and local configs once the registries are made, synced configs with each
 * server). The config types are NeoForge's: Forge Config API Port's COMMON is NeoForge's LOCAL and its SERVER is
 * SYNCED (convert rewrites them). The mod id may be the Fabric id a converted mod keeps.
 */
public interface ConfigRegistry {

    ConfigRegistry INSTANCE = new ConfigRegistry() {};

    default void register(String modId, ModConfig.Type type, IConfigSpec spec) {
        container(modId).registerConfig(type, spec);
    }

    default void register(String modId, ModConfig.Type type, IConfigSpec spec, String fileName) {
        container(modId).registerConfig(type, spec, fileName);
    }

    private static ModContainer container(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).orElseThrow(() -> new IllegalArgumentException("No mod " + modId)).neoforge();
    }
}
