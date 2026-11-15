package ${package};

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/** FancyModLoader's net.neoforged.fml.Logging: its log4j markers, which mods pass to their own loggers. */
public final class Logging {

    private Logging() {}

    public static final Marker CORE = MarkerManager.getMarker("CORE");
    public static final Marker LOADING = MarkerManager.getMarker("LOADING");
    public static final Marker SCAN = MarkerManager.getMarker("SCAN");
}
