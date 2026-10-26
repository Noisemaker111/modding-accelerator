import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/**
 * Maven version ranges as NeoForge and Forge read a mods.toml versionRange: FML hands the text to
 * maven-artifact's VersionRange.createFromVersionSpec and asks containsVersion. When the build's
 * own maven-artifact jar is on its classpath (every NeoForge build has it) that library decides.
 * Otherwise this class's copy of the same rules does, and says so: ComparableVersion ordering,
 * where an unknown qualifier such as "pre1" sorts after the release, exactly as FML sees it.
 */
final class MavenRange {
    private MavenRange() { }
    /** contains: true or false; decidedBy names who decided; error: the range text is invalid. */
    record Answer(boolean contains, String decidedBy, String error) { }

    static Answer contains(String spec, String version, List<Path> classpath) {
        Path library = classpath.stream().filter(p -> p.getFileName().toString().matches("maven-artifact-[0-9].*\\.jar")).findFirst().orElse(null);
        if (library != null && Files.isRegularFile(library)) {
            List<URL> urls = new ArrayList<>();
            try {
                for (Path jar : classpath) if (jar.getFileName().toString().matches("(maven-artifact|commons-lang3|plexus-utils)-[0-9].*\\.jar")) urls.add(jar.toUri().toURL());
                try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                    Class<?> range = loader.loadClass("org.apache.maven.artifact.versioning.VersionRange");
                    Class<?> artifact = loader.loadClass("org.apache.maven.artifact.versioning.ArtifactVersion");
                    Object parsed;
                    try { parsed = range.getMethod("createFromVersionSpec", String.class).invoke(null, spec); }
                    catch (java.lang.reflect.InvocationTargetException invalid) { return new Answer(false, library.getFileName().toString(), String.valueOf(invalid.getCause().getMessage())); }
                    Object wanted = loader.loadClass("org.apache.maven.artifact.versioning.DefaultArtifactVersion").getConstructor(String.class).newInstance(version);
                    return new Answer((Boolean)range.getMethod("containsVersion", artifact).invoke(parsed, wanted), library.getFileName().toString() + " from the build classpath", null);
                }
            } catch (Exception unusable) { /* fall through to the copy of the rules */ }
        }
        try { return new Answer(own(spec, version), "modaccel's copy of Maven's range rules (no maven-artifact on the build classpath)", null); }
        catch (IllegalArgumentException invalid) { return new Answer(false, "modaccel's copy of Maven's range rules", invalid.getMessage()); }
    }

    /** VersionRange.createFromVersionSpec(spec).containsVersion(version), as in maven-artifact 3.8. */
    static boolean own(String spec, String version) {
        String process = spec.trim();
        List<Restriction> restrictions = new ArrayList<>();
        Comparable upper = null; boolean any = false;
        while (process.startsWith("[") || process.startsWith("(")) {
            int close = process.indexOf(')'), bracket = process.indexOf(']'), index = bracket;
            if (bracket < 0 || close < bracket) if (close >= 0) index = close;
            if (index < 0) throw new IllegalArgumentException("Unbounded range: " + spec);
            Restriction restriction = restriction(process.substring(0, index + 1));
            if (upper != null && (restriction.lower == null || restriction.lower.compareTo(upper) < 0)) throw new IllegalArgumentException("Ranges overlap: " + spec);
            restrictions.add(restriction); upper = restriction.upper;
            process = process.substring(index + 1).trim();
            if (process.startsWith(",")) process = process.substring(1).trim();
        }
        if (!process.isEmpty()) {
            if (!restrictions.isEmpty()) throw new IllegalArgumentException("Only fully-qualified sets allowed in multiple set scenario: " + spec);
            any = true; // a bare version is only a recommendation: Maven accepts every version
        }
        if (any) return true;
        Comparable wanted = new Comparable(version);
        for (Restriction r : restrictions) if (r.contains(wanted)) return true;
        return false;
    }
    private record Restriction(Comparable lower, boolean lowerInclusive, Comparable upper, boolean upperInclusive) {
        boolean contains(Comparable version) {
            if (lower != null) { int c = lower.compareTo(version); if (c == 0 && !lowerInclusive || c > 0) return false; }
            if (upper != null) { int c = upper.compareTo(version); if (c == 0 && !upperInclusive || c < 0) return false; }
            return true;
        }
    }
    private static Restriction restriction(String spec) {
        boolean lowerInclusive = spec.startsWith("["), upperInclusive = spec.endsWith("]");
        String process = spec.substring(1, spec.length() - 1).trim();
        int comma = process.indexOf(',');
        if (comma < 0) {
            if (!lowerInclusive || !upperInclusive) throw new IllegalArgumentException("Single version must be surrounded by []: " + spec);
            Comparable exact = new Comparable(process);
            return new Restriction(exact, true, exact, true);
        }
        String low = process.substring(0, comma).trim(), high = process.substring(comma + 1).trim();
        Comparable lower = low.isEmpty() ? null : new Comparable(low), upper = high.isEmpty() ? null : new Comparable(high);
        if (lower != null && upper != null && upper.compareTo(lower) < 0) throw new IllegalArgumentException("Range defies version ordering: " + spec);
        return new Restriction(lower, lowerInclusive, upper, upperInclusive);
    }

    /** maven-artifact's ComparableVersion: lists of int and string items, "-" opening a sub-list. */
    static final class Comparable implements java.lang.Comparable<Comparable> {
        final ListItem items;
        Comparable(String version) { items = parse(version); }
        public int compareTo(Comparable other) { return items.compareTo(other.items); }

        private interface Item { int compareTo(Item other); boolean isNull(); }
        private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");
        private static final Map<String, String> ALIASES = Map.of("ga", "", "final", "", "release", "", "cr", "rc");
        private static final String RELEASE = String.valueOf(QUALIFIERS.indexOf(""));
        private record IntItem(BigInteger value) implements Item {
            public boolean isNull() { return value.signum() == 0; }
            public int compareTo(Item other) {
                if (other == null) return value.signum() == 0 ? 0 : 1;
                if (other instanceof IntItem i) return value.compareTo(i.value);
                return 1; // an int is newer than any string or list
            }
        }
        private record StringItem(String value) implements Item {
            static StringItem of(String text, boolean followedByDigit) {
                if (followedByDigit && text.length() == 1) text = switch (text.charAt(0)) { case 'a' -> "alpha"; case 'b' -> "beta"; case 'm' -> "milestone"; default -> text; };
                return new StringItem(ALIASES.getOrDefault(text, text));
            }
            static String comparable(String qualifier) {
                int i = QUALIFIERS.indexOf(qualifier);
                return i == -1 ? QUALIFIERS.size() + "-" + qualifier : String.valueOf(i);
            }
            public boolean isNull() { return comparable(value).compareTo(RELEASE) == 0; }
            public int compareTo(Item other) {
                if (other == null) return comparable(value).compareTo(RELEASE);
                if (other instanceof IntItem) return -1;
                if (other instanceof StringItem s) return comparable(value).compareTo(comparable(s.value));
                return -1;
            }
        }
        private static final class ListItem extends ArrayList<Item> implements Item {
            public boolean isNull() { return isEmpty(); }
            void normalize() {
                for (int i = size() - 1; i >= 0; i--) {
                    Item last = get(i);
                    if (last.isNull()) remove(i);
                    else if (!(last instanceof ListItem)) break;
                }
            }
            public int compareTo(Item other) {
                if (other == null) {
                    if (isEmpty()) return 0;
                    return get(0).compareTo(null);
                }
                if (other instanceof IntItem) return -1;
                if (other instanceof StringItem) return 1;
                Iterator<Item> left = iterator(), right = ((ListItem)other).iterator();
                while (left.hasNext() || right.hasNext()) {
                    Item l = left.hasNext() ? left.next() : null, r = right.hasNext() ? right.next() : null;
                    int result = l == null ? (r == null ? 0 : -1 * r.compareTo(null)) : l.compareTo(r);
                    if (result != 0) return result;
                }
                return 0;
            }
        }
        private static Item item(boolean digit, String text) {
            if (!digit) return StringItem.of(text, false);
            String stripped = text.replaceFirst("^0+(?=.)", "");
            return new IntItem(new BigInteger(stripped));
        }
        private static ListItem parse(String version) {
            String text = version.toLowerCase(Locale.ENGLISH);
            ListItem items = new ListItem(), list = items;
            Deque<ListItem> stack = new ArrayDeque<>(); stack.push(list);
            boolean digit = false; int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '.') {
                    list.add(i == start ? new IntItem(BigInteger.ZERO) : item(digit, text.substring(start, i)));
                    start = i + 1;
                } else if (c == '-') {
                    list.add(i == start ? new IntItem(BigInteger.ZERO) : item(digit, text.substring(start, i)));
                    start = i + 1;
                    ListItem next = new ListItem(); list.add(next); list = next; stack.push(list);
                } else if (Character.isDigit(c)) {
                    if (!digit && i > start) {
                        list.add(StringItem.of(text.substring(start, i), true));
                        start = i;
                        ListItem next = new ListItem(); list.add(next); list = next; stack.push(list);
                    }
                    digit = true;
                } else {
                    if (digit && i > start) {
                        list.add(item(true, text.substring(start, i)));
                        start = i;
                        ListItem next = new ListItem(); list.add(next); list = next; stack.push(list);
                    }
                    digit = false;
                }
            }
            if (text.length() > start) list.add(item(digit, text.substring(start)));
            while (!stack.isEmpty()) stack.pop().normalize();
            return items;
        }
    }
}
