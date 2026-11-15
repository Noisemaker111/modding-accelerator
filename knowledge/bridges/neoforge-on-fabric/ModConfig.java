package ${package};

import java.util.Locale;

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
}
