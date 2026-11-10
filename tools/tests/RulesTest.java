import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Every port rule in knowledge/fixes.json is well formed, whether or not it has a test: something
 * selects it (a match on a compiler error, or "silent" saying what goes wrong when the compiler says
 * nothing), it rewrites something (find, literal or a named transform), its patterns compile, its
 * replacement only names groups its pattern has, and some route on the porting map runs it.
 * Every rule carries a test {error, before, after}; PortableTests runs it.
 */
public final class RulesTest {

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int rules = 0;
        for (Object item : Json.array(Json.read(home.resolve("knowledge/fixes.json")).get("rules"))) {
            var rule = Json.object(item);
            String id = Json.text(rule, "id", "");
            rules++;
            if (id.isEmpty()) {
                problems.add("rule " + rules + " has no id");
                continue;
            }
            if (!ids.add(id)) problems.add(id + ": id used twice");
            Pattern find = null;
            try {
                Pattern.compile(Json.text(rule, "match", ""));
                if (Json.text(rule, "match", "").isEmpty() && Json.text(rule, "silent", "").isBlank()) {
                    problems.add(
                        id +
                        ": no match, so no compiler error would ever select it (a rule for a change the compiler never reports says so in \"silent\")"
                    );
                }
                String when = Json.text(rule, "when", "");
                if (!when.isEmpty()) Pattern.compile(when);
                var apply = Json.object(rule.get("apply"));
                // {symbol} stands for the name javac reported; the porter fills it in before it compiles the pattern.
                if (apply.containsKey("find")) find = Pattern.compile(
                    apply.get("find").toString().replace("{symbol}", "symbol")
                );
                else if (apply.containsKey("literal")) {
                    // The porter replaces the literal text with "replace". Any other shape is read as text that is never there.
                    if (!(apply.get("literal") instanceof String) || !(apply.get("replace") instanceof String)) {
                        problems.add(
                            id + ": literal and replace must both be text, or the porter finds nothing to replace"
                        );
                    }
                } else if (Json.text(apply, "transform", "").isBlank()) {
                    problems.add(id + ": apply has no find, literal or transform, so it rewrites nothing");
                }
                if (find != null) {
                    String bad = badReplacement(find, Json.text(apply, "replace", "").replace("{symbol}", "symbol"));
                    if (bad != null) problems.add(id + ": replace " + bad);
                }
            } catch (PatternSyntaxException error) {
                problems.add(
                    id + ": a pattern does not compile: " + error.getDescription() + " in " + error.getPattern()
                );
            }
            String jump = Json.text(rule, "jump", "");
            String[] range = jump.replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
            if (range.length != 2) {
                problems.add(id + ": jump \"" + jump + "\" is not FROM -> TO");
            } else {
                Route route = Route.plan(home, range[0], range[1]);
                if (route == null || route.stepsCovered(jump, jump.contains("yarn") ? "yarn" : "mojmap").isEmpty()) {
                    problems.add(id + ": no route on the porting map runs jump \"" + jump + "\"");
                }
            }
            boolean tested = rule.get("test") instanceof Map<?, ?> || rule.get("examples") instanceof List<?>;
            if (!tested) {
                problems.add(
                    id +
                    ": a rule needs a test {error, before, after}; python bench/rule_test.py --only " +
                    id +
                    " --write writes it from real mod source"
                );
            }
        }
        if (!problems.isEmpty()) throw new AssertionError(
            problems.size() + " rule problem(s):\n  " + String.join("\n  ", problems)
        );
        System.out.println(rules + " port rules are well formed, each with a test");
    }

    /** What is wrong with a replacement for this pattern, the way Matcher.replaceAll would refuse it; null when nothing is. */
    static String badReplacement(Pattern find, String replace) {
        int groups = find.matcher("").groupCount();
        for (int i = 0; i < replace.length(); i++) {
            char c = replace.charAt(i);
            if (c == '\\') {
                if (++i == replace.length()) return "ends in a lone backslash";
            } else if (c == '$') {
                if (i + 1 == replace.length()) return "ends in a lone $";
                char next = replace.charAt(i + 1);
                if (next == '{') {
                    int close = replace.indexOf('}', i);
                    if (close < 0) return "has an unclosed ${";
                    String name = replace.substring(i + 2, close);
                    if (!find.pattern().contains("(?<" + name + ">")) return (
                        "names group ${" + name + "}, which the pattern does not have"
                    );
                    i = close;
                } else if (Character.isDigit(next)) {
                    if (next - '0' > groups) return "uses $" + next + ", but the pattern has " + groups + " group(s)";
                    i++;
                } else {
                    return "has a $ that is not a group ($" + next + "); write \\\\$ for a dollar sign";
                }
            }
        }
        return null;
    }
}
