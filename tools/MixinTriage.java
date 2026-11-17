import java.util.*;
import java.util.regex.*;

/** Mixin triage for legacy targets: sorts any mod's mixins into portable, adapt or manual
 *  against a pinned legacy Mixin's capability table, before any game jar or build exists.
 *  The table below is verified against the Mixin 0.7 runtime a legacy pack provisions
 *  (annotation classes and their attributes read from the distributed jar):
 *  every injector the table calls portable exists there with the attributes named,
 *  and every modern-only entry is absent from it. Apotheosis is one input to this,
 *  never a special case: a new mod runs the same classifier with no tool change. */
public final class MixinTriage {

    public enum Tier { PORTABLE, ADAPT, MANUAL }

    public record Verdict(String name, Tier tier, List<String> targets, List<String> reasons) {}

    public record ConfigVerdict(Tier tier, List<String> rewrites) {}

    /** Injectors the pinned legacy Mixin supports, read from its distributed jar. */
    static final Set<String> PORTABLE_INJECTORS = Set.of(
        "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "Overwrite"
    );

    /** Structural Mixin annotations with no version-gated behavior on the pinned runtime. */
    static final Set<String> PORTABLE_STRUCTURAL = Set.of(
        "Mixin", "Shadow", "Unique", "Final", "Mutable", "Accessor", "Invoker",
        "Pseudo", "Implements", "Interface", "Intrinsic", "SoftOverride", "Dynamic"
    );

    /** Injectors absent from the pinned legacy Mixin jar (MixinExtras-era): always manual. */
    static final Set<String> MODERN_ONLY = Set.of("ModifyExpressionValue", "WrapOperation", "WrapWithCondition");

    /** Extension points a human must re-check against the pinned runtime. */
    static final Set<String> CUSTOM_POINTS = Set.of("InjectionPoint", "IMixinConfigPlugin", "IClassTracker");

    static final Pattern ANNOTATION = Pattern.compile("@([A-Za-z][A-Za-z0-9_]*)");

    static final Pattern MIXIN_TARGETS = Pattern.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.class");

    /** The @Mixin(...) argument list, so .class tokens outside it (method bodies) are ignored. */
    static final Pattern MIXIN_ARGS = Pattern.compile("@Mixin\\s*\\(([^;{}]*)\\)", Pattern.DOTALL);

    static Verdict triage(String name, String source) {
        String blank = blankComments(source);
        Set<String> annotations = new LinkedHashSet<>();
        Matcher found = ANNOTATION.matcher(blank);
        while (found.find()) annotations.add(found.group(1));
        List<String> blocking = new ArrayList<>();
        for (String modern : MODERN_ONLY) {
            if (annotations.contains(modern)) blocking.add(modern + " is absent from the pinned legacy Mixin");
        }
        List<String> review = new ArrayList<>();
        if (hasCustomPoint(blank)) review.add("a custom extension point must be re-checked against the pinned legacy Mixin");
        if (blank.contains("constraints")) review.add("a constraints expression must be re-checked for the legacy target");
        List<String> targets = new ArrayList<>();
        Matcher args = MIXIN_ARGS.matcher(blank);
        while (args.find()) {
            Matcher target = MIXIN_TARGETS.matcher(args.group(1));
            while (target.find()) targets.add(target.group(1));
        }
        if (!blocking.isEmpty()) return new Verdict(name, Tier.MANUAL, targets, blocking);
        if (!review.isEmpty()) return new Verdict(name, Tier.ADAPT, targets, review);
        return new Verdict(name, Tier.PORTABLE, targets, List.of("injectors all exist on the pinned legacy Mixin"));
    }

    static boolean hasCustomPoint(String blank) {
        for (String custom : CUSTOM_POINTS) {
            if (blank.contains("extends " + custom) || blank.contains("implements " + custom)) return true;
        }
        return false;
    }

    /** Comments and string literals blanked so annotations in prose never triage as code.
     *  Same shape as MixinAudit's copy, kept here so this classifier stays dependency-free. */
    static String blankComments(String text) {
        char[] out = text.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '"' || c == '\'') {
                i = Math.max(skipString(text, i), i + 1);
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? out.length : end + 2;
                for (; i < stop; i++) if (out[i] != '\n' && out[i] != '\r') out[i] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    static int skipString(String text, int quote) {
        char q = text.charAt(quote);
        int i = quote + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) return i + 1;
            i++;
        }
        return text.length();
    }

    /** The mixin config itself: version pins the legacy runtime cannot honor become rewrites. */
    static ConfigVerdict triageConfig(String json) {
        List<String> rewrites = new ArrayList<>();
        Matcher min = Pattern.compile("\"minVersion\"\\s*:\\s*\"([\\d.]+)\"").matcher(json);
        if (min.find() && compareVersion(min.group(1), "0.7") > 0) {
            rewrites.add("lower minVersion " + min.group(1) + " to 0.7 once no mixin verdict is MANUAL");
        }
        Matcher level = Pattern.compile("\"compatibilityLevel\"\\s*:\\s*\"([A-Za-z0-9_]+)\"").matcher(json);
        if (level.find() && compareVersion(level.group(1).replace("JAVA_", ""), "8") > 0) {
            rewrites.add("lower compatibilityLevel " + level.group(1) + " to JAVA_8");
        }
        if (rewrites.isEmpty()) return new ConfigVerdict(Tier.PORTABLE, List.of("config needs no rewrite"));
        return new ConfigVerdict(Tier.ADAPT, rewrites);
    }

    static int compareVersion(String a, String b) {
        String[] left = a.split("[._-]");
        String[] right = b.split("[._-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? number(left[i]) : 0;
            int r = i < right.length ? number(right[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D", ""));
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
