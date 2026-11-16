package ${package}.loader.api.metadata;

import java.nio.file.Path;
import java.util.List;

/**
 * Fabric Loader's net.fabricmc.loader.api.metadata.ModOrigin: where a mod was loaded from. A mod NeoForge loaded is
 * read from its own file (PATH, the jar or folder); NeoForge does not say which mod a jar-in-jar mod came in, so none is
 * NESTED here.
 */
public interface ModOrigin {

    Kind getKind();

    /** The mod's files, for a PATH origin. */
    List<Path> getPaths();

    /** The mod it came inside, for a NESTED origin. */
    String getParentModId();

    /** Where inside its parent it is, for a NESTED origin. */
    String getParentSubLocation();

    enum Kind {
        PATH,
        NESTED,
        UNKNOWN
    }
}
