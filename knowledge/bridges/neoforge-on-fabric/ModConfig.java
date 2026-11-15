package ${package};

import com.electronwill.nightconfig.core.CommentedConfig;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/** NeoForge's net.neoforged.fml.config.ModConfig: one registered config file of a mod. */
public class ModConfig {

    /** Every type name NeoForge has used (COMMON and SERVER before 26.1, LOCAL and SYNCED after). */
    public enum Type {
        LOCAL,
        COMMON,
        CLIENT,
        SERVER,
        SYNCED,
        STARTUP;

        public String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    final Type type;
    final ModConfigSpec spec;
    final ModContainer container;
    final String fileName;

    ModConfig(Type type, ModConfigSpec spec, ModContainer container, String fileName) {
        this.type = type;
        this.spec = spec;
        this.container = container;
        this.fileName = fileName;
    }

    public Type getType() {
        return type;
    }

    public ModConfigSpec getSpec() {
        return spec;
    }

    public String getModId() {
        return container.getModId();
    }

    public String getFileName() {
        return fileName;
    }

    /** The loaded values (null until the file is read): config() is them as NightConfig's config, save() writes them back to the file. */
    public IConfigSpec.ILoadedConfig getLoadedConfig() {
        if (!spec.loaded || spec.loadedConfig == null) return null;
        return new IConfigSpec.ILoadedConfig() {
            @Override
            public CommentedConfig config() {
                return spec.loadedConfig;
            }

            @Override
            public void save() {
                spec.afterReload();
                spec.save();
            }
        };
    }

    /** The config file's full path (in Fabric's config folder, where NeoForge's would be in its own). */
    public Path getFullPath() {
        return spec.file != null ? spec.file : FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }
}
