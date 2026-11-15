package ${package}.loader.api;

/**
 * Fabric Loader's net.fabricmc.loader.api.Version for a NeoForge mod's version, ordered as NeoForge orders versions
 * (Maven's ComparableVersion); getFriendlyString is the version as the mod wrote it.
 */
public final class Version implements Comparable<Version> {

    private final String text;
    private final org.apache.maven.artifact.versioning.ComparableVersion order;

    Version(String text) {
        this.text = text;
        this.order = new org.apache.maven.artifact.versioning.ComparableVersion(text);
    }

    public static Version parse(String string) {
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
