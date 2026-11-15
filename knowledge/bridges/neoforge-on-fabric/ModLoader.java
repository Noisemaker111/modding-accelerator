package ${package};

/**
 * FancyModLoader's net.neoforged.fml.ModLoader, the calls mods make: posting a mod-bus event to the mods (here the
 * converted mod's one bus), and whether data generation is running (Fabric API's data generation sets fabric-api.datagen).
 */
public final class ModLoader {

    private ModLoader() {}

    public static <T extends Event> void postEvent(T event) {
        Bus.INSTANCE.post(event);
    }

    public static <T extends Event> T postEventWithReturn(T event) {
        return Bus.INSTANCE.post(event);
    }

    public static <T extends Event> void postEventWrapContainerInModOrder(T event) {
        Bus.INSTANCE.post(event);
    }

    public static boolean isRunningDataGen() {
        return System.getProperty("fabric-api.datagen") != null;
    }

    public static boolean hasErrors() {
        return false;
    }
}
