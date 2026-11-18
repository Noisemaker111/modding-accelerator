package ${package}.loader.api;

/**
 * Fabric Loader's net.fabricmc.loader.api.Version for a NeoForge mod's version, ordered as NeoForge orders versions
 * (Maven's ComparableVersion); getFriendlyString is the version as the mod wrote it. SemanticVersion is one.
 */
public class Version implements Comparable<Version> {

    private final String text;
    private final org.apache.maven.artifact.versioning.ComparableVersion order;

    Version(String text) {
        this(text, text);
    }

    /** A version written as text, ordered by orderText (a semantic version's text without its build metadata). */
    Version(String text, String orderText) {
        this.text = text;
        this.order = new org.apache.maven.artifact.versioning.ComparableVersion(orderText);
    }

    /** Any non-blank text is a version here (as NeoForge reads versions); Fabric declares the exception, which mods catch. */
    public static Version parse(String string) throws VersionParsingException {
        if (string == null || string.isBlank()) throw new VersionParsingException("Empty version");
        return new Version(string);
    }

    public String getFriendlyString() {
        return text;
    }

    @Override
    public int compareTo(Version other) {
        return order.compareTo(other.order);
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version v && v.order.equals(order);
    }

    @Override
    public int hashCode() {
        return order.hashCode();
    }
}
