package ${package};

import com.electronwill.nightconfig.core.CommentedConfig;

/**
 * NeoForge's net.neoforged.fml.config.IConfigSpec, as far as mods use it: the type of a config spec (ModConfigSpec here)
 * and ILoadedConfig, the loaded values ModConfig.getLoadedConfig() hands out.
 */
public interface IConfigSpec {

    /** A loaded config file: its values as NightConfig's commented config, and save, which writes them to the file. */
    interface ILoadedConfig {

        CommentedConfig config();

        void save();
    }
}
