import java.util.*;

/** Published version constraints, compared strictly enough to accept or reject a chosen game version. */
final class Versions {
    private Versions() { }
    /** True when a Fabric manifest or Maven range accepts the chosen version. Empty or "*" accepts anything. */
    static boolean matches(String constraint, String version) {
        if (constraint == null) return false;
        String text = constraint.trim();
        if (text.isEmpty() || text.equals("*") || text.equals("+")) return true;
        for (String term : text.split("\\s+")) if (!term(term, version)) return false;
        return true;
    }
    private static boolean term(String term, String version) {
        String operator = "";
        for (String prefix : List.of(">=", "<=", ">", "<", "=", "~", "^")) {
            if (term.startsWith(prefix)) { operator = prefix; term = term.substring(prefix.length()).trim(); break; }
        }
        if (term.isEmpty()) return false;
        // ">=1.21.6-" accepts that version and any pre-release of it: a bare "-" is the lowest pre-release.
        String wanted = term.endsWith("-") && operator.isEmpty() ? term.substring(0, term.length() - 1) : term;
        // "1.21.x" (also X or *) is an x-range: the parts before the wildcard must agree.
        String[] parts = wanted.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[xX*]")) continue;
            String fixed = String.join(".", Arrays.copyOfRange(parts, 0, i));
            if (operator.isEmpty() || operator.equals("=")) return i == 0 || prefix(version, fixed, i);
            parts[i] = "0"; wanted = String.join(".", parts);
        }
        int comparison = compare(version, wanted);
        return switch (operator) {
            case ">=" -> comparison >= 0;
            case ">" -> comparison > 0;
            case "<=" -> comparison <= 0;
            case "<" -> comparison < 0;
            case "~" -> comparison >= 0 && prefix(version, wanted, 2);
            case "^" -> comparison >= 0 && prefix(version, wanted, 1);
            default -> comparison == 0;
        };
    }
    /**
     * Release order. Numeric dot parts compare numerically and a missing part is zero, so 1.21
     * equals 1.21.0. Anything after the first "-" is a pre-release and sorts before its release
     * (1.21.6-pre1 and 1.21.6-rc1 come before 1.21.6, 26.1-snapshot-1 before 26.1), in Minecraft's
     * order snapshot, pre, rc. A trailing "-" with nothing after it is the lowest pre-release.
     * Build metadata after "+" only breaks ties.
     */
    static int compare(String left, String right) {
        String[] a = pieces(left), b = pieces(right);
        int result = parts(a[0].split("\\."), b[0].split("\\."));
        if (result != 0) return result;
        if (a[1] == null || b[1] == null) {
            if (a[1] != b[1]) return a[1] == null ? 1 : -1;
        } else if ((result = parts(preParts(a[1]), preParts(b[1]))) != 0) return result;
        return parts(a[2] == null ? new String[0] : a[2].split("[.\\-]"), b[2] == null ? new String[0] : b[2].split("[.\\-]"));
    }
    /** {core, pre-release or null, build or null}. */
    private static String[] pieces(String version) {
        String text = version.strip(), build = null, pre = null;
        int plus = text.indexOf('+');
        if (plus >= 0) { build = text.substring(plus + 1); text = text.substring(0, plus); }
        int dash = text.indexOf('-');
        if (dash >= 0) { pre = text.substring(dash + 1); text = text.substring(0, dash); }
        return new String[] {text, pre, build};
    }
    /** "pre1" is "pre", "1"; "snapshot-2" is "snapshot", "2". */
    private static String[] preParts(String pre) {
        if (pre.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>();
        for (String piece : pre.split("[.\\-]")) for (String part : piece.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")) if (!part.isEmpty()) out.add(part);
        return out.toArray(String[]::new);
    }
    private static final List<String> PRE_ORDER = List.of("snapshot", "alpha", "beta", "pre", "rc");
    private static int parts(String[] a, String[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            // In the core a missing part is zero; among pre-release parts the shorter list sorts first.
            if (i >= a.length || i >= b.length) {
                String present = i < a.length ? a[i] : b[i];
                if (present.matches("0+")) continue;
                return i >= a.length ? -1 : 1;
            }
            String x = a[i], y = b[i];
            int result;
            if (x.matches("\\d+") && y.matches("\\d+")) result = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            else if (x.matches("\\d+") != y.matches("\\d+")) result = x.matches("\\d+") ? -1 : 1;
            else {
                int p = PRE_ORDER.indexOf(x.toLowerCase(Locale.ROOT)), q = PRE_ORDER.indexOf(y.toLowerCase(Locale.ROOT));
                result = p >= 0 && q >= 0 ? Integer.compare(p, q) : p >= 0 ? -1 : q >= 0 ? 1 : x.compareTo(y);
            }
            if (result != 0) return result;
        }
        return 0;
    }
    /** True when the first {@code count} numeric parts agree, used for ~ and ^ ranges. */
    private static boolean prefix(String version, String wanted, int count) {
        String[] a = version.split("[.\\-+]"), b = wanted.split("[.\\-+]");
        if (b.length < count) return true;
        for (int i = 0; i < count; i++) if (i >= a.length || !a[i].equals(b[i])) return false;
        return true;
    }
}
