package ${package}.data;

/** NeoForge's net.neoforged.neoforge.data.loading.DatagenModLoader: whether the game runs to generate data (Fabric's data run sets fabric-api.datagen). */
public final class DatagenModLoader {

    private DatagenModLoader() {}

    public static boolean isRunningDataGen() {
        return System.getProperty("fabric-api.datagen") != null;
    }
}
