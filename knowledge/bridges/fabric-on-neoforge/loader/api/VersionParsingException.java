package ${package}.loader.api;

/** Fabric Loader's net.fabricmc.loader.api.VersionParsingException: a version string that is not a valid version. */
public class VersionParsingException extends Exception {

    public VersionParsingException() {
        super();
    }

    public VersionParsingException(String message) {
        super(message);
    }

    public VersionParsingException(Throwable cause) {
        super(cause);
    }

    public VersionParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
