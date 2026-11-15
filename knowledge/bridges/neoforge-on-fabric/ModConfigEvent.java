package ${package};

/** NeoForge's net.neoforged.fml.event.config.ModConfigEvent: posted when a config file is loaded. */
public class ModConfigEvent extends Event {

    final ModConfig config;

    ModConfigEvent(ModConfig config) {
        this.config = config;
    }

    public ModConfig getConfig() {
        return config;
    }

    public static class Loading extends ModConfigEvent {

        Loading(ModConfig config) {
            super(config);
        }
    }

    public static class Reloading extends ModConfigEvent {

        Reloading(ModConfig config) {
            super(config);
        }
    }

    public static class Unloading extends ModConfigEvent {

        Unloading(ModConfig config) {
            super(config);
        }
    }
}
