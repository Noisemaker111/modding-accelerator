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
        // "1.21.6-" accepts that version and any pre-release of it.
        String wanted = term.endsWith("-") ? term.substring(0, term.length() - 1) : term;
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
    /** Numeric dot/dash parts compare numerically; a missing part is zero, so 1.21 equals 1.21.0. */
    static int compare(String left, String right) {
        String[] a = left.split("[.\\-+]"), b = right.split("[.\\-+]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "0", y = i < b.length ? b[i] : "0";
            int result = x.matches("\\d+") && y.matches("\\d+") ? Long.compare(Long.parseLong(x), Long.parseLong(y)) : x.compareTo(y);
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
