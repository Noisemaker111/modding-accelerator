package ${package}.loader.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric Loader's net.fabricmc.loader.api.SemanticVersion: a version of dot-separated numbers, then an optional
 * pre-release after "-" and build metadata after "+" (which does not order versions). A pre-release orders before its
 * release, as in Semantic Versioning.
 */
public final class SemanticVersion extends Version {

    public static final int COMPONENT_WILDCARD = Integer.MIN_VALUE;

    private static final Pattern FORM = Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?");

    private final int[] components;
    private final String prerelease;
    private final String build;

    private SemanticVersion(String text, int[] components, String prerelease, String build) {
        super(text, build == null ? text : text.substring(0, text.indexOf('+')));
        this.components = components;
        this.prerelease = prerelease;
        this.build = build;
    }

    public static SemanticVersion parse(String string) throws VersionParsingException {
        Matcher m = FORM.matcher(string == null ? "" : string.strip());
        if (!m.matches()) throw new VersionParsingException("Invalid semantic version: " + string);
        String[] parts = m.group(1).split("\\.");
        int[] components = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) components[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            throw new VersionParsingException("Invalid semantic version: " + string);
        }
        return new SemanticVersion(string.strip(), components, m.group(2), m.group(3));
    }

    public int getVersionComponentCount() {
        return components.length;
    }

    /** The component at that position, 0 past the last one. */
    public int getVersionComponent(int position) {
        return position < components.length ? components[position] : 0;
    }

    public Optional<String> getPrereleaseKey() {
        return Optional.ofNullable(prerelease);
    }

    public Optional<String> getBuildKey() {
        return Optional.ofNullable(build);
    }

    public boolean hasWildcard() {
        return false;
    }
}
