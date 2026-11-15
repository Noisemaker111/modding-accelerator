import java.util.*;
import java.util.regex.*;

/**
 * The source rewrites of a loader change (knowledge/loaders.json), applied to one Java file's text.
 * Patterns are matched on code only (never inside strings or comments) with $name holes that take one
 * balanced expression each. Every rule runs both ways: Fabric to NeoForge and NeoForge to Fabric.
 */
final class LoaderRules {

    /** One rewrite that could not be made, with where it is and why. */
    record Left(int line, String what, String why) {}

    /** What one file's conversion did: the new text, the rules that changed it, what stays for a person. */
    static final class Result {

        String text;
        final List<String> applied = new ArrayList<>();
        final List<Left> left = new ArrayList<>();
        /** Access transformer lines the converted code needs (NeoForge), from the rules that changed it. */
        final Set<String> accessTransformers = new LinkedHashSet<>();

        Result(String text) {
            this.text = text;
        }
    }

    final Map<String, Object> rules;
    final String from, to, modid;
    /** The game version the code is for: a rule that names its Minecraft range applies only inside it; null applies every rule. */
    String minecraft;

    LoaderRules(Map<String, Object> rules, String from, String to, String modid, String minecraft) {
        this(rules, from, to, modid);
        this.minecraft = minecraft;
    }

    /** Whether a rule is written for this game version: its optional minecraft {from, below} range. */
    static boolean applies(Map<String, Object> rule, String minecraft) {
        if (minecraft == null || !(rule.get("minecraft") instanceof Map<?, ?> range)) return true;
        Object from = range.get("from"), below = range.get("below");
        if (from != null && Versions.compare(minecraft, String.valueOf(from)) < 0) return false;
        return below == null || Versions.compare(minecraft, String.valueOf(below)) < 0;
    }

    LoaderRules(Map<String, Object> rules, String from, String to, String modid) {
        if (
            !Set.of("fabric", "neoforge").contains(from) ||
            !Set.of("fabric", "neoforge").contains(to) ||
            from.equals(to)
        ) {
            throw new IllegalArgumentException(
                "Loader rules run between fabric and neoforge, not " + from + " to " + to
            );
        }
        this.rules = rules;
        this.from = from;
        this.to = to;
        this.modid = modid;
    }

    /** Applies every rule kind to one file, in the order types, expressions, statements, events. */
    Result apply(String text) {
        Result result = new Result(text);
        for (Object rule : list(rules.get("types"))) if (applies(Json.object(rule), minecraft)) type(result, Json.object(rule));
        for (Object rule : list(rules.get("expressions"))) if (applies(Json.object(rule), minecraft)) expression(result, Json.object(rule));
        for (Object rule : list(rules.get("statements"))) if (applies(Json.object(rule), minecraft)) statement(result, Json.object(rule));
        for (Object rule : list(rules.get("events"))) {
            if (!applies(Json.object(rule), minecraft)) continue;
            if (from.equals("fabric")) eventToNeoForge(result, Json.object(rule));
            else eventToFabric(result, Json.object(rule));
        }
        return result;
    }

    // ---- code mask, brackets and tokens -------------------------------------------------------------

    /** True at every index that is code: not inside a string, a char literal, a text block or a comment. */
    static boolean[] code(String s) {
        boolean[] mask = new boolean[s.length() + 1];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else {
                mask[i] = true;
                i++;
            }
        }
        mask[s.length()] = true;
        return mask;
    }

    static boolean ident(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /** The index just past the bracket that closes the one at open, or -1. */
    static int close(String s, boolean[] code, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (!code[i]) continue;
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i + 1;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    /** Skips whitespace and comments, never a string. */
    static int skipSpace(String s, boolean[] code, int i) {
        while (i < s.length()) {
            if (Character.isWhitespace(s.charAt(i))) i++;
            else if (!code[i] && s.charAt(i) == '/') {
                while (i < s.length() && !code[i]) i++;
            } else break;
        }
        return i;
    }

    /** The pieces of a pattern: literal tokens, and holes written $name. */
    static List<String> pieces(String pattern) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '$') {
                int j = i + 1;
                while (j < pattern.length() && ident(pattern.charAt(j))) j++;
                if (pattern.startsWith(".name", j) && (j + 5 == pattern.length() || !ident(pattern.charAt(j + 5)))) j +=
                    5;
                out.add(pattern.substring(i, j));
                i = j;
            } else if (ident(c)) {
                int j = i;
                while (j < pattern.length() && ident(pattern.charAt(j))) j++;
                out.add(pattern.substring(i, j));
                i = j;
            } else if (c == '"') {
                int j = pattern.indexOf('"', i + 1);
                out.add(pattern.substring(i, j + 1));
                i = j + 1;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }

    /** A match of a pattern: where it starts and ends in the text, and what each hole took. */
    record Match(int start, int end, Map<String, String> holes) {}

    /** Matches the pattern's pieces from piece k at position at; the holes found so far are in bound. */
    static int matchFrom(String s, boolean[] code, List<String> pieces, int k, int at, Map<String, String> bound) {
        if (k == pieces.size()) return at;
        String piece = pieces.get(k);
        if (piece.startsWith("$") && piece.endsWith(".name")) {
            int start = skipSpace(s, code, at), end = start;
            while (end < s.length() && ident(s.charAt(end))) end++;
            String taken = s.substring(start, end);
            if (taken.isEmpty() || (bound.containsKey(piece) && !bound.get(piece).equals(taken))) return -1;
            Map<String, String> next = new LinkedHashMap<>(bound);
            next.put(piece, taken);
            int done = matchFrom(s, code, pieces, k + 1, end, next);
            if (done >= 0) {
                bound.clear();
                bound.putAll(next);
            }
            return done;
        }
        if (piece.startsWith("$")) {
            int start = skipSpace(s, code, at), depth = 0;
            for (int i = start; i <= s.length(); i++) {
                if (i > start && depth == 0) {
                    String taken = s.substring(start, i).strip();
                    if (!taken.isEmpty() && (!bound.containsKey(piece) || bound.get(piece).equals(taken))) {
                        Map<String, String> next = new LinkedHashMap<>(bound);
                        next.put(piece, taken);
                        int end = matchFrom(s, code, pieces, k + 1, i, next);
                        if (end >= 0) {
                            bound.clear();
                            bound.putAll(next);
                            return end;
                        }
                    }
                }
                if (i == s.length()) break;
                if (!code[i]) continue;
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') {
                    if (--depth < 0) break;
                } else if (depth == 0 && (c == ';' || c == ',')) break;
            }
            return -1;
        }
        int i = skipSpace(s, code, at);
        if (!s.startsWith(piece, i) || !code[i]) return -1;
        int end = i + piece.length();
        if (ident(piece.charAt(piece.length() - 1)) && end < s.length() && ident(s.charAt(end))) return -1;
        return matchFrom(s, code, pieces, k + 1, end, bound);
    }

    /** Every match of the pattern in the text, left to right, never overlapping, never inside strings or comments. */
    static List<Match> find(String s, String pattern) {
        boolean[] code = code(s);
        List<String> pieces = pieces(pattern);
        List<Match> found = new ArrayList<>();
        int first = 0;
        while (first < pieces.size() && pieces.get(first).startsWith("$")) first++;
        if (first == pieces.size()) return found;
        // A pattern that opens with one hole and ".name" ($stack.getEquipmentSlot()) is a call on a receiver: it is found
        // by the member's name, and the hole takes the expression before the dot.
        boolean receiver = first == 1 && pieces.size() > 2 && pieces.get(1).equals(".") && ident(pieces.get(2).charAt(0));
        String anchor = pieces.get(receiver ? 2 : first);
        int from = 0;
        while (true) {
            int at = s.indexOf(anchor, from);
            if (at < 0) break;
            from = at + 1;
            if (!code[at]) continue;
            if (
                ident(anchor.charAt(0)) &&
                at > 0 &&
                (ident(s.charAt(at - 1)) || (first == 0 && s.charAt(at - 1) == '.'))
            ) continue;
            int start = at;
            if (receiver) {
                int dot = at - 1;
                while (dot >= 0 && Character.isWhitespace(s.charAt(dot))) dot--;
                if (dot < 0 || s.charAt(dot) != '.' || !code[dot]) continue;
                start = receiverStart(s, code, dot);
                if (start < 0) continue;
                // A class name before the dot (CommonHooks.canEntityDestroy(...)) is a static call, not a receiver.
                String name = s.substring(start, dot).strip();
                if (name.matches("[A-Z][\\w$]*") && !name.equals(name.toUpperCase(Locale.ROOT))) continue;
            } else if (first > 0) {
                // A pattern that opens with holes (a statement's target) starts at the statement's start.
                start = statementStart(s, code, at);
            }
            Map<String, String> holes = new LinkedHashMap<>();
            int end = matchFrom(s, code, pieces, 0, start, holes);
            if (end < 0) continue;
            found.add(new Match(skipSpace(s, code, start), end, holes));
            from = end;
        }
        return found;
    }

    /**
     * Where the receiver of a call starts, reading back from the dot before the member: a chain of names, calls and
     * indexes (a.b(c).d[0]), a parenthesised expression or cast ((Zombie) e), or new X(...); -1 when there is none.
     */
    static int receiverStart(String s, boolean[] code, int dot) {
        int i = dot - 1, start = -1;
        while (true) {
            while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
            if (i < 0 || !code[i]) return start;
            char c = s.charAt(i);
            if (c == ')' || c == ']') {
                int depth = 0, open = -1;
                for (int j = i; j >= 0; j--) {
                    if (!code[j]) continue;
                    char d = s.charAt(j);
                    if (d == ')' || d == ']' || d == '}') depth++;
                    else if ((d == '(' || d == '[' || d == '{') && --depth == 0) {
                        open = j;
                        break;
                    }
                }
                if (open < 0) return -1;
                start = open;
                i = open - 1;
                int j = i;
                while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
                // A call or an index goes on to its name; a bracket after anything else is the receiver's start.
                if (j >= 0 && ident(s.charAt(j)) && code[j]) {
                    i = j;
                    continue;
                }
                // new X<>(...) or a generic call: not read here, so not matched.
                if (j >= 0 && s.charAt(j) == '>') return -1;
                return start;
            }
            if (!ident(c)) return start;
            int j = i;
            while (j >= 0 && ident(s.charAt(j))) j--;
            String word = s.substring(j + 1, i + 1);
            if (Set.of("return", "throw", "case", "yield", "else", "do", "assert").contains(word)) return start;
            start = j + 1;
            int k = j;
            while (k >= 0 && Character.isWhitespace(s.charAt(k))) k--;
            if (k >= 0 && s.charAt(k) == '.' && code[k]) {
                i = k - 1;
                continue;
            }
            if (k >= 2 && s.startsWith("new", k - 2) && (k < 3 || !ident(s.charAt(k - 3)))) return k - 2;
            return start;
        }
    }

    static int statementStart(String s, boolean[] code, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            if (!code[i]) continue;
            char c = s.charAt(i);
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') {
                if (depth-- == 0) return i + 1;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) return i + 1;
        }
        return 0;
    }

    /** True when an expression can stand before a "." without parentheses: a name, a call chain or a literal. */
    static boolean simple(String expression) {
        boolean[] code = code(expression);
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (!code[i]) continue;
            char c = expression.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && !(ident(c) || c == '.' || c == '"' || c == '\'')) return false;
        }
        return !expression.startsWith("new ") || (expression.endsWith(")") && depth == 0);
    }

    /** Fills a template's holes. A hole written before "." is parenthesised when it is not simple; $x.name is x's last name. */
    static String render(String template, Map<String, String> holes) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < template.length() && ident(template.charAt(j))) j++;
            String name = template.substring(i, j);
            String value = holes.get(name);
            if (value == null) {
                out.append(name);
                i = j;
                continue;
            }
            if (template.startsWith(".name", j) && (j + 5 == template.length() || !ident(template.charAt(j + 5)))) {
                Matcher last = Pattern.compile("([\\w$]+)\\s*$").matcher(value);
                out.append(last.find() ? last.group(1) : value);
                i = j + 5;
                continue;
            }
            boolean member = j < template.length() && template.charAt(j) == '.';
            out.append(member && !simple(value) ? "(" + value + ")" : value);
            i = j;
        }
        return out.toString();
    }

    // ---- imports -------------------------------------------------------------------------------------

    static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;[^\\n]*\\n?");

    /** The class an import names, for a simple name: Mod.EventBusSubscriber is imported as the outer class. */
    static String outer(String name) {
        String[] parts = name.split("\\.");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('.');
            out.append(part);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) break;
        }
        return out.toString();
    }

    /** How code refers to a class name once its outer class is imported: ClientTickEvent.Post. */
    static String reference(String name) {
        String outer = outer(name);
        return name.substring(outer.lastIndexOf('.') + 1);
    }

    static Map<String, String> imports(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = IMPORT.matcher(text);
        while (m.find()) if (m.group(1) == null && m.group(3) == null) out.put(
            m.group(2).substring(m.group(2).lastIndexOf('.') + 1),
            m.group(2)
        );
        return out;
    }

    static boolean imported(String text, String name) {
        if (imports(text).containsValue(name)) return true;
        // A wildcard import of the class's package (import net.minecraft.world.item.*;) names it too.
        String outer = outer(name);
        if (!outer.contains(".")) return false;
        return Pattern.compile("(?m)^import\\s+" + Pattern.quote(outer.substring(0, outer.lastIndexOf('.'))) + "\\.\\*\\s*;").matcher(text).find();
    }

    /** Adds an import of the class (its outer class for a nested one) unless the file has it or its package is the file's own. */
    static String addImport(String text, String name) {
        String outer = outer(name), simple = outer.substring(outer.lastIndexOf('.') + 1);
        Map<String, String> present = imports(text);
        if (outer.equals(present.get(simple))) return text;
        if (present.containsKey(simple)) return text;
        // A wildcard import of its package already names it.
        if (outer.contains(".") && imported(text, outer)) return text;
        Matcher pkg = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
        if (pkg.find() && outer.equals(pkg.group(1) + "." + simple)) return text;
        String line = "import " + outer + ";\n";
        // In sorted order among the file's other imports when it has them, else after the package line.
        Matcher m = IMPORT.matcher(text);
        int insert = -1, last = -1;
        while (m.find()) {
            if (m.group(1) == null && insert < 0 && m.group(2).compareTo(outer) > 0) insert = m.start();
            last = m.end();
        }
        if (insert < 0 && last >= 0) {
            if (last > 0 && text.charAt(last - 1) != '\n') return (
                text.substring(0, last) + "\n" + line + text.substring(last)
            );
            insert = last;
        }
        if (insert < 0) {
            if (pkg.find(0)) {
                int end = text.indexOf('\n', pkg.end());
                end = end < 0 ? text.length() : end + 1;
                return text.substring(0, end) + "\n" + line + text.substring(end);
            }
            return line + text;
        }
        return text.substring(0, insert) + line + text.substring(insert);
    }

    /** True when the simple name is used in code outside import lines. */
    static boolean uses(String text, String simple) {
        String body = IMPORT.matcher(text).replaceAll("");
        boolean[] code = code(body);
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(simple) + "(?![\\w$])").matcher(body);
        while (m.find()) if (code[m.start()]) return true;
        return false;
    }

    /** Removes the import of name when nothing in the file uses its simple name any more. */
    static String dropImport(String text, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (uses(text, simple)) return text;
        return tidy(
            Pattern.compile("(?m)^import\\s+" + Pattern.quote(name) + "\\s*;[^\\n]*\\n?").matcher(text).replaceAll("")
        );
    }

    /** After imports are removed: no run of blank lines between the package line and the code. */
    static String tidy(String text) {
        return text.replaceFirst("^(package [^\\n]*;\\n)\\n{2,}", "$1\n");
    }

    /** The class a simple or dotted name refers to in this file, through its imports or its own package. */
    static boolean refersTo(String text, String written, String name) {
        String outer = outer(name);
        String simple = written.contains(".") ? written.substring(0, written.indexOf('.')) : written;
        String head = outer.substring(outer.lastIndexOf('.') + 1);
        if (!simple.equals(head)) return false;
        return (
            imported(text, outer) ||
            Pattern.compile(
                "(?m)^import\\s+" + Pattern.quote(outer.substring(0, outer.lastIndexOf('.'))) + "\\.\\*\\s*;"
            )
                .matcher(text)
                .find()
        );
    }

    static List<Object> list(Object value) {
        return value == null ? List.of() : Json.array(value);
    }

    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : list(value)) if (item != null) out.add(String.valueOf(item));
        return out;
    }

    static int line(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    // ---- types, expressions, statements ---------------------------------------------------------------

    void type(Result result, Map<String, Object> rule) {
        String source = Json.text(rule, from, ""), target = Json.text(rule, to, "");
        String text = result.text;
        if (!imported(text, source)) return;
        String sourceSimple = source.substring(source.lastIndexOf('.') + 1), targetSimple = target.substring(
            target.lastIndexOf('.') + 1
        );
        Map<String, Object> members = Json.object(rule.get("members"));
        Map<String, String> map = new LinkedHashMap<>();
        for (var entry : members.entrySet()) {
            if (from.equals("fabric")) map.put(entry.getKey(), String.valueOf(entry.getValue()));
            else map.put(String.valueOf(entry.getValue()), entry.getKey());
        }
        StringBuilder out = new StringBuilder();
        boolean[] code = code(text);
        Matcher m = Pattern.compile(
            "(?<![\\w$.])" + Pattern.quote(sourceSimple) + "(?:\\s*\\.\\s*([A-Z_][A-Z0-9_]*))?(?![\\w$])"
        ).matcher(text);
        int last = 0;
        while (m.find()) {
            if (!code[m.start()]) continue;
            out.append(text, last, m.start());
            String member = m.group(1);
            if (member == null) out.append(targetSimple);
            else out.append(targetSimple).append('.').append(map.getOrDefault(member, member));
            last = m.end();
        }
        out.append(text, last, text.length());
        text = out.toString();
        text = Pattern.compile("(?m)^import\\s+" + Pattern.quote(source) + "\\s*;[^\\n]*\\n?")
            .matcher(text)
            .replaceAll("");
        text = addImport(text, target);
        if (!text.equals(result.text)) {
            result.text = text;
            result.applied.add(Json.text(rule, "id", "type"));
        }
    }

    /** The span of the method around index, or the whole text. */
    static int[] scopeSpan(String text, int index) {
        Matcher m = METHOD.matcher(text);
        int start = -1;
        while (m.find() && m.start() < index) start = m.start();
        if (start < 0) return new int[] { 0, text.length() };
        int open = text.indexOf('{', start), end = close(text, code(text), open);
        return end < 0 || end < index ? new int[] { 0, text.length() } : new int[] { start, end };
    }

    /**
     * A local variable that only holds the rule's receiver (FlammableBlockRegistry registry = FlammableBlockRegistry.getDefaultInstance();)
     * is inlined, so the rule's pattern sees registry.add(...) as FlammableBlockRegistry.getDefaultInstance().add(...).
     */
    static String inline(String text, String type, String value) {
        Pattern declaration = Pattern.compile(
            "(?m)^[ \\t]*(?:final\\s+)?" +
            Pattern.quote(type) +
            "\\s+(\\w+)\\s*=\\s*" +
            Pattern.quote(value) +
            "\\s*;[ \\t]*\\n"
        );
        while (true) {
            Matcher m = declaration.matcher(text);
            boolean done = true;
            while (m.find()) {
                if (!code(text)[m.start() + m.group().indexOf(type.charAt(0))]) continue;
                String name = m.group(1);
                int[] span = scopeSpan(text, m.start());
                String rest = text.substring(m.end(), span[1]);
                boolean[] code = code(rest);
                Matcher use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])(\\s*\\.)?").matcher(
                    rest
                );
                boolean onlyReceiver = true;
                StringBuilder out = new StringBuilder();
                int last = 0;
                while (use.find()) {
                    if (!code[use.start()]) continue;
                    if (use.group(1) == null) {
                        onlyReceiver = false;
                        break;
                    }
                    out.append(rest, last, use.start()).append(value).append('.');
                    last = use.end();
                }
                if (!onlyReceiver) continue;
                out.append(rest.substring(last));
                text = text.substring(0, m.start()) + out + text.substring(span[1]);
                done = false;
                break;
            }
            if (done) return text;
        }
    }

    void expression(Result result, Map<String, Object> rule) {
        String source = Json.text(rule, from, ""), target = Json.text(rule, to, "");
        var imports = Json.object(rule.get("imports"));
        List<String> needed = strings(imports.get(from)), added = strings(imports.get(to));
        String text = result.text;
        for (String name : needed) if (!imported(text, name)) return;
        if (rule.get("inline") instanceof Map<?, ?> inline && Json.object(inline).get(from) instanceof Map<?, ?> how) {
            text = inline(text, Json.text(Json.object(how), "type", ""), Json.text(Json.object(how), "value", ""));
        }
        List<Match> matches = find(text, source);
        if (matches.isEmpty()) return;
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Match match : matches) {
            out.append(text, last, match.start()).append(render(target, match.holes()));
            last = match.end();
        }
        out.append(text, last, text.length());
        text = out.toString();
        for (String name : added) text = addImport(text, name);
        for (String name : needed) text = dropImport(text, name);
        result.text = text;
        result.applied.add(Json.text(rule, "id", "expression"));
        if (rule.get("accessTransformers") instanceof Map<?, ?> at) result.accessTransformers.addAll(
            strings(Json.object(at).get(to))
        );
    }

    String modBus() {
        return render(
            Json.text(Json.object(Json.object(rules.get("buses")).get("mod")), "neoforge", ""),
            Map.of("$modid", quote(modid))
        );
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    void statement(Result result, Map<String, Object> rule) {
        String source = Json.text(rule, from, ""), target = Json.text(rule, to, "");
        var imports = Json.object(rule.get("imports"));
        List<String> needed = strings(imports.get(from)), added = strings(imports.get(to));
        String text = result.text;
        if (from.equals("fabric")) {
            for (String name : needed) if (!imported(text, name)) return;
        }
        // The NeoForge side names the mod's bus; read backwards, $modbus matches whatever bus expression was written.
        if (from.equals("neoforge")) {
            if (!text.contains("addListener(")) return;
        }
        List<Match> matches = find(text, source);
        if (matches.isEmpty()) return;
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Match match : matches) {
            Map<String, String> holes = new LinkedHashMap<>(match.holes());
            holes.put("$modbus", modBus());
            if (from.equals("neoforge")) {
                // The listener must register the same field or variable the first statement assigned.
                String assigned = holes.get("$target"), registered = holes.get("$target.name");
                if (
                    registered != null &&
                    assigned != null &&
                    !assigned.matches("(?s).*\\b" + Pattern.quote(registered) + "\\s*$")
                ) continue;
            }
            String indent = indent(text, match.start());
            String rendered = render(target, holes).replace("\n", "\n" + indent);
            out.append(text, last, match.start()).append(rendered);
            last = match.end();
        }
        out.append(text, last, text.length());
        String changed = out.toString();
        if (changed.equals(text)) return;
        for (String name : added) changed = addImport(changed, name);
        for (String name : needed) changed = dropImport(changed, name);
        result.text = changed;
        result.applied.add(Json.text(rule, "id", "statement"));
    }

    static String indent(String text, int index) {
        int lineStart = text.lastIndexOf('\n', index - 1) + 1;
        int i = lineStart;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return text.substring(lineStart, i);
    }

    static String unit(String text) {
        return Pattern.compile("(?m)^\\t").matcher(text).find() ? "\t" : "    ";
    }

    // ---- events ----------------------------------------------------------------------------------------

    /** A callback as written: its parameter names and its body (statements, without the outer braces). */
    record Callback(List<String> params, List<String> lines, boolean expression) {}

    /** Reads a lambda or a method reference; null for anything else (a variable, an anonymous class). */
    static Callback callback(String text, List<String> defaults) {
        String s = text.strip();
        boolean[] code = code(s);
        Matcher ref = Pattern.compile("^([\\w.$]+|this|super)\\s*::\\s*(\\w+)$").matcher(s);
        if (ref.find() && !ref.group(2).equals("new")) {
            String call = ref.group(1) + "." + ref.group(2) + "(" + String.join(", ", defaults) + ");";
            return new Callback(defaults, List.of(call), true);
        }
        int arrow = -1;
        for (int i = 0; i + 1 < s.length(); i++) if (code[i] && s.charAt(i) == '-' && s.charAt(i + 1) == '>') {
            arrow = i;
            break;
        }
        if (arrow < 0) return null;
        String head = s.substring(0, arrow).strip();
        if (head.startsWith("(")) {
            if (!head.endsWith(")")) return null;
            head = head.substring(1, head.length() - 1);
        }
        List<String> params = new ArrayList<>();
        if (!head.isBlank()) for (String p : head.split(",")) {
            String[] words = p.strip().split("\\s+");
            String name = words[words.length - 1];
            if (!name.matches("[A-Za-z_$][\\w$]*")) return null;
            params.add(name);
        }
        String body = s.substring(arrow + 2).strip();
        if (body.startsWith("{") && body.endsWith("}") && close(body, code(body), 0) == body.length()) {
            return new Callback(params, bodyLines(body.substring(1, body.length() - 1)), false);
        }
        return new Callback(params, List.of(body + ";"), true);
    }

    /** A block's statements as lines, with the indentation they share taken off. */
    static List<String> bodyLines(String inner) {
        List<String> lines = new ArrayList<>(inner.lines().toList());
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        if (lines.size() == 1) return List.of(lines.get(0).strip());
        int common = Integer.MAX_VALUE;
        for (String line : lines) if (!line.isBlank()) {
            int n = 0;
            while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
            common = Math.min(common, n);
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) out.add(line.isBlank() ? "" : line.substring(Math.min(common, line.length())));
        return out;
    }

    static boolean mentions(List<String> lines, String name) {
        String joined = String.join("\n", lines);
        boolean[] code = code(joined);
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])").matcher(joined);
        while (m.find()) if (code[m.start()]) return true;
        return false;
    }

    static final Pattern METHOD = Pattern.compile(
        "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|abstract|default)\\s+)*(?:<[^>]*>\\s*)?[\\w.<>\\[\\],? ]+\\s+\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{"
    );

    /** The method around index: from its declaration to its closing brace (the whole text when none is found). */
    static String scope(String text, int index) {
        Matcher m = METHOD.matcher(text);
        int start = -1;
        while (m.find() && m.start() < index) start = m.start();
        if (start < 0) return text;
        int open = text.indexOf('{', start), end = close(text, code(text), open);
        return end < 0 || end < index ? text : text.substring(start, end);
    }

    static String fresh(String text, String... choices) {
        for (String choice : choices) if (!uses(text, choice)) return choice;
        return choices[choices.length - 1] + "_";
    }

    static String bracedLambda(String parameter, List<String> lines, String indent, String unit) {
        StringBuilder out = new StringBuilder(parameter + " -> {\n");
        for (String line : lines) out.append(line.isEmpty() ? "" : indent + unit + line).append('\n');
        return out.append(indent).append('}').toString();
    }

    static String[] eventParts(String event) {
        int hash = event.indexOf('#');
        String owner = event.substring(0, hash), member = event.substring(hash + 1);
        return new String[] { owner, member };
    }

    void eventToNeoForge(Result result, Map<String, Object> rule) {
        Map<String, Object> fabric = Json.object(rule.get("fabric")), neo = Json.object(rule.get("neoforge"));
        String[] parts = eventParts(Json.text(fabric, "event", ""));
        String owner = parts[0], member = parts[1], ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
        String text = result.text;
        if (!imported(text, owner)) return;
        List<String> params = strings(fabric.get("params"));
        List<Object> args = list(neo.get("args"));
        String eventClass = Json.text(neo, "event", ""), bus = Json.text(neo, "bus", "game");
        String pattern = ownerSimple + "." + member + ".register($callback)";
        List<Match> matches = find(text, pattern);
        if (matches.isEmpty()) return;
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        String unit = unit(text);
        for (Match match : matches) {
            Callback callback = callback(match.holes().get("$callback"), params);
            String where = ownerSimple + "." + member.replaceAll("\\(.*", "(...)");
            if (callback == null || callback.params().size() != params.size()) {
                result.left.add(
                    new Left(
                        line(text, match.start()),
                        where,
                        "the listener is not a lambda or method reference with " +
                        params.size() +
                        " parameter(s); convert it by hand"
                    )
                );
                continue;
            }
            String ev = fresh(scope(text, match.start()), "event", "neoEvent", "loaderEvent");
            Map<String, String> holes = new LinkedHashMap<>(match.holes());
            holes.put("$event", ev);
            List<String> lines = new ArrayList<>(callback.lines());
            for (Object item : list(neo.get("rewrite"))) {
                var rewrite = Json.object(item);
                int index = ((Number) rewrite.get("param")).intValue();
                Map<String, String> self = new LinkedHashMap<>(holes);
                self.put("$self", callback.params().get(index));
                String joined = String.join("\n", lines);
                String before = render(Json.text(rewrite, "fabric", ""), self);
                joined = replace(joined, before, Json.text(rewrite, "neoforge", ""), self);
                lines = new ArrayList<>(joined.lines().toList());
            }
            List<String> prelude = new ArrayList<>();
            String filter = Json.text(neo, "filter", "");
            if (!filter.isEmpty()) {
                String rendered = render(filter, holes);
                prelude.add("if (" + (simple(rendered) ? "!" + rendered : "!(" + rendered + ")") + ") return;");
            }
            String missing = null;
            for (int i = 0; i < params.size(); i++) {
                String name = callback.params().get(i);
                if (!mentions(lines, name)) continue;
                Object arg = i < args.size() ? args.get(i) : null;
                if (arg == null) {
                    missing = name;
                    break;
                }
                prelude.add("var " + name + " = " + render(String.valueOf(arg), holes) + ";");
            }
            if (missing != null) {
                result.left.add(
                    new Left(
                        line(text, match.start()),
                        where,
                        "the listener uses " + missing + ", which " + reference(eventClass) + " has no counterpart for"
                    )
                );
                continue;
            }
            List<String> body = new ArrayList<>(prelude);
            body.addAll(lines);
            String busExpression = bus.equals("mod")
                ? modBus()
                : Json.text(Json.object(Json.object(rules.get("buses")).get("game")), "neoforge", "");
            String indent = indent(text, match.start());
            String listener =
                busExpression +
                ".addListener(" +
                reference(eventClass) +
                ".class, " +
                bracedLambda(ev, body, indent, unit) +
                ")";
            out.append(text, last, match.start()).append(listener);
            last = match.end();
            changed = true;
        }
        if (!changed) return;
        out.append(text, last, text.length());
        text = out.toString();
        text = addImport(text, eventClass);
        List<String> busImports = strings(Json.object(Json.object(rules.get("buses")).get(bus)).get("imports"));
        for (String name : busImports) text = addImport(text, name);
        for (String name : strings(neo.get("imports"))) text = addImport(text, name);
        text = dropImport(text, owner);
        result.text = text;
        result.applied.add(Json.text(rule, "id", "event"));
    }

    void eventToFabric(Result result, Map<String, Object> rule) {
        Map<String, Object> fabric = Json.object(rule.get("fabric")), neo = Json.object(rule.get("neoforge"));
        String eventClass = Json.text(neo, "event", "");
        String text = result.text;
        if (!imported(text, outer(eventClass))) return;
        String[] parts = eventParts(Json.text(fabric, "event", ""));
        String owner = parts[0], member = parts[1], ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
        List<String> params = strings(fabric.get("params"));
        List<Object> args = list(neo.get("args"));
        boolean[] code = code(text);
        String ref = reference(eventClass);
        Matcher calls = Pattern.compile("\\.\\s*addListener\\s*\\(").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        String unit = unit(text);
        while (calls.find()) {
            if (!code[calls.start()] || calls.start() < last) continue;
            int open = calls.end() - 1, end = close(text, code, open);
            if (end < 0) continue;
            List<String> arguments = split(text.substring(open + 1, end - 1));
            String listener;
            String ev;
            if (arguments.size() == 2 && arguments.get(0).replaceAll("\\s", "").equals(ref + ".class")) listener =
                arguments.get(1);
            else if (
                arguments.size() == 1 &&
                arguments.get(0).strip().matches("(?s)^\\(\\s*" + Pattern.quote(ref) + "\\s+\\w+\\s*\\)\\s*->.*")
            ) listener = arguments.get(0);
            else continue;
            Callback callback = callback(listener, List.of("event"));
            int start = chainStart(text, code, calls.start());
            if (callback == null || callback.params().size() != 1) {
                result.left.add(
                    new Left(
                        line(text, start),
                        ref + " listener",
                        "the listener is not a one-parameter lambda or method reference; convert it by hand"
                    )
                );
                continue;
            }
            ev = callback.params().get(0);
            List<String> lines = new ArrayList<>(callback.lines());
            Map<String, String> holes = new LinkedHashMap<>();
            holes.put("$event", ev);
            String filter = Json.text(neo, "filter", "");
            if (!filter.isEmpty()) {
                if (lines.isEmpty()) continue;
                String first = lines.get(0);
                List<Match> guard = find(first, "if (!(" + render(filter, holes) + ")) return;");
                if (guard.isEmpty()) guard = find(first, "if (!" + render(filter, holes) + ") return;");
                if (guard.isEmpty()) {
                    if (!shared(eventClass)) result.left.add(
                        new Left(
                            line(text, start),
                            ref + " listener",
                            "Fabric's " +
                            ownerSimple +
                            " event needs " +
                            filter.replace("$event", "the event") +
                            " checked first; the listener does not"
                        )
                    );
                    continue;
                }
                holes.putAll(guard.get(0).holes());
                lines.remove(0);
            }
            List<String> names = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Object arg = i < args.size() ? args.get(i) : null;
                String name = null;
                if (arg != null && !lines.isEmpty()) {
                    Matcher declared = Pattern.compile(
                        "^var\\s+(\\w+)\\s*=\\s*" + Pattern.quote(render(String.valueOf(arg), holes)) + "\\s*;$"
                    ).matcher(lines.get(0).strip());
                    if (declared.find()) {
                        name = declared.group(1);
                        lines.remove(0);
                    }
                }
                names.add(name);
            }
            for (int i = 0; i < params.size(); i++) if (names.get(i) == null) names.set(
                i,
                fresh(scope(text, start), params.get(i), params.get(i) + "Value")
            );
            for (Object item : list(neo.get("rewrite"))) {
                var rewrite = Json.object(item);
                int index = ((Number) rewrite.get("param")).intValue();
                Map<String, String> self = new LinkedHashMap<>(holes);
                self.put("$self", names.get(index));
                String joined = String.join("\n", lines);
                String pattern = render(Json.text(rewrite, "neoforge", ""), self);
                joined = replace(joined, pattern, Json.text(rewrite, "fabric", ""), self);
                lines = new ArrayList<>(joined.lines().toList());
            }
            // NeoForge code written by hand reads the event's getters in place: each becomes the parameter.
            for (int i = 0; i < params.size(); i++) {
                Object arg = i < args.size() ? args.get(i) : null;
                if (arg == null) continue;
                String getter = render(String.valueOf(arg), holes);
                if (getter.equals(ev)) continue;
                String joined = String.join("\n", lines);
                if (!joined.contains(getter)) continue;
                lines = new ArrayList<>(joined.replace(getter, names.get(i)).lines().toList());
            }
            for (int i = 0; i < params.size(); i++) {
                Object arg = i < args.size() ? args.get(i) : null;
                if (arg != null && String.valueOf(arg).equals("$event") && mentions(lines, ev)) {
                    String joined = String.join("\n", lines);
                    lines = new ArrayList<>(
                        joined
                            .replaceAll("(?<![\\w$.])" + Pattern.quote(ev) + "(?![\\w$])", names.get(i))
                            .lines()
                            .toList()
                    );
                }
            }
            if (mentions(lines, ev)) {
                result.left.add(
                    new Left(
                        line(text, start),
                        ref + " listener",
                        "the listener reads " +
                        ev +
                        " in a way Fabric's " +
                        ownerSimple +
                        "." +
                        member.replaceAll("\\(.*", "") +
                        " has no parameter for"
                    )
                );
                continue;
            }
            String head = names.size() == 1 ? names.get(0) : "(" + String.join(", ", names) + ")";
            String indent = indent(text, start);
            String lambda;
            if (lines.size() == 1 && expressionStatement(lines.get(0))) lambda =
                head + " -> " + lines.get(0).substring(0, lines.get(0).length() - 1);
            else lambda = bracedLambda(head, lines, indent, unit);
            String method = render(member, holes);
            out
                .append(text, last, start)
                .append(ownerSimple)
                .append('.')
                .append(method)
                .append(".register(")
                .append(lambda)
                .append(')');
            last = end;
            changed = true;
        }
        if (!changed) return;
        out.append(text, last, text.length());
        text = out.toString();
        text = addImport(text, owner);
        text = dropImport(text, outer(eventClass));
        for (Object b : Json.object(rules.get("buses")).values()) for (String name : strings(
            Json.object(b).get("imports")
        )) text = dropImport(text, name);
        for (String name : strings(neo.get("imports"))) text = dropImport(text, name);
        result.text = text;
        result.applied.add(Json.text(rule, "id", "event"));
    }

    static final Pattern STATEMENT_KEYWORD = Pattern.compile(
        "^(?:if|for|while|do|switch|try|return|throw|synchronized|var|final|break|continue|yield|class|record|assert|int|long|boolean|double|float|char|byte|short)\\b"
    );
    static final Pattern DECLARATION = Pattern.compile(
        "^[A-Za-z_$][\\w$.]*(?:<[^;]*>)?(?:\\[\\])*\\s+[A-Za-z_$][\\w$]*\\s*(?:=|;)"
    );

    /** True for one statement that can be a lambda's expression body: a call, an assignment, an increment. */
    static boolean expressionStatement(String line) {
        String s = line.strip();
        return (
            s.endsWith(";") &&
            !s.contains("\n") &&
            !STATEMENT_KEYWORD.matcher(s).find() &&
            !DECLARATION.matcher(s).find() &&
            !s.startsWith("{")
        );
    }

    /** Every match of pattern in text replaced by template, left to right, each with its own holes and the shared ones. */
    static String replace(String text, String pattern, String template, Map<String, String> shared) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Match match : find(text, pattern)) {
            Map<String, String> all = new LinkedHashMap<>(shared);
            all.putAll(match.holes());
            out.append(text, last, match.start()).append(render(template, all));
            last = match.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** True when more than one event rule turns into this NeoForge event, told apart by their checks. */
    boolean shared(String eventClass) {
        int count = 0;
        for (Object rule : list(rules.get("events"))) if (
            eventClass.equals(Json.text(Json.object(Json.object(rule).get("neoforge")), "event", ""))
        ) count++;
        return count > 1;
    }

    /** Splits an argument list at its top-level commas. */
    static List<String> split(String arguments) {
        boolean[] code = code(arguments);
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < arguments.length(); i++) {
            if (!code[i]) continue;
            char c = arguments.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || (c == '>' && i > 0 && arguments.charAt(i - 1) != '-')) depth--;
            else if (c == ',' && depth == 0) {
                out.add(arguments.substring(start, i).strip());
                start = i + 1;
            }
        }
        if (!arguments.isBlank()) out.add(arguments.substring(start).strip());
        return out;
    }

    /** Where the receiver chain before a "." starts: NeoForge.EVENT_BUS, or ModList.get().getModContainerById(x).orElseThrow().getEventBus(). */
    static int chainStart(String text, boolean[] code, int dot) {
        int i = dot;
        while (true) {
            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(text.charAt(j))) j--;
            if (j < 0) return 0;
            char c = text.charAt(j);
            if (c == ')' || c == ']') {
                int depth = 0;
                for (; j >= 0; j--) {
                    if (!code[j]) continue;
                    char d = text.charAt(j);
                    if (d == ')' || d == ']') depth++;
                    else if ((d == '(' || d == '[') && --depth == 0) break;
                }
                i = j;
                continue;
            }
            if (ident(c)) {
                while (j >= 0 && ident(text.charAt(j))) j--;
                int k = j;
                while (k >= 0 && Character.isWhitespace(text.charAt(k))) k--;
                if (k >= 0 && text.charAt(k) == '.') {
                    i = k;
                    continue;
                }
                return j + 1;
            }
            return i;
        }
    }

    // ---- data generation (Fabric datagen API -> the vanilla providers NeoForge's GatherDataEvent runs) ------------

    /** Fabric's datagen provider bases, the vanilla class each wraps, and the kind that decides how GatherDataEvent registers it. */
    static final Map<String, String[]> DATAGEN = Map.of(
        "FabricModelProvider",
        new String[] { "model", "net.minecraft.client.data.models.ModelProvider" },
        "FabricBlockLootSubProvider",
        new String[] { "blockLoot", "net.minecraft.data.loot.BlockLootSubProvider" },
        "FabricRecipeProvider",
        new String[] { "recipe", "net.minecraft.data.recipes.RecipeProvider" },
        "FabricAdvancementProvider",
        new String[] { "advancement", "net.minecraft.data.advancements.AdvancementSubProvider" }
    );

    /** A converted provider: its kind, and its new text. */
    record Provider(String kind, String text) {}

    /**
     * One Fabric datagen provider class rewritten onto the vanilla provider it wraps: a model provider gets
     * NeoForge's ModelProvider(output, modId) and one registerModels that calls the two Fabric methods; a block loot
     * provider becomes a BlockLootSubProvider for the mod's own blocks; a recipe provider a RecipeProvider.Runner; an
     * advancement provider an AdvancementSubProvider. Null when the file extends none of Fabric's datagen bases.
     */
    static Provider datagenProvider(String text, String modid) {
        Matcher base = Pattern.compile("\\bextends\\s+(Fabric\\w+Provider)\\b").matcher(text);
        if (!base.find()) return null;
        String[] kind = DATAGEN.get(base.group(1));
        if (kind == null) return new Provider("", text);
        String unit = unit(text);
        text = text.replaceAll("\\bFabricPackOutput\\b", "PackOutput");
        text = Pattern.compile(
            "(?m)^import\\s+net\\.fabricmc\\.fabric\\.api\\.(?:client\\.)?datagen\\.v1\\.[\\w.]+\\s*;[^\\n]*\\n?"
        )
            .matcher(text)
            .replaceAll("");
        text = addImport(text, "net.minecraft.data.PackOutput");
        String simple = kind[1].substring(kind[1].lastIndexOf('.') + 1);
        switch (kind[0]) {
            case "model" -> {
                text = text.replaceFirst("\\bextends\\s+FabricModelProvider\\b", "extends ModelProvider");
                text = text.replaceFirst(
                    "\\bsuper\\(\\s*(\\w+)\\s*\\)\\s*;",
                    "super($1, " + Matcher.quoteReplacement(quote(modid)) + ");"
                );
                text = text.replaceAll(
                    "(?m)^([ \\t]*)@Override[ \\t]*\\n[ \\t]*(public\\s+void\\s+generate(?:BlockStateModels|ItemModels)\\s*\\()",
                    "$1$2"
                );
                text = beforeLastBrace(
                    text,
                    unit +
                    "@Override\n" +
                    unit +
                    "protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {\n" +
                    unit +
                    unit +
                    "generateBlockStateModels(blockModels);\n" +
                    unit +
                    unit +
                    "generateItemModels(itemModels);\n" +
                    unit +
                    "}\n"
                );
                text = addImport(text, "net.minecraft.client.data.models.BlockModelGenerators");
                text = addImport(text, "net.minecraft.client.data.models.ItemModelGenerators");
            }
            case "blockLoot" -> {
                text = text.replaceFirst("\\bextends\\s+FabricBlockLootSubProvider\\b", "extends BlockLootSubProvider");
                text = text.replaceFirst(
                    "\\(\\s*PackOutput\\s+\\w+\\s*,\\s*CompletableFuture<\\s*HolderLookup\\.Provider\\s*>\\s+(\\w+)\\s*\\)(\\s*\\{\\s*)super\\(\\s*\\w+\\s*,\\s*\\w+\\s*\\)\\s*;",
                    "(HolderLookup.Provider $1)$2super(Set.of(), FeatureFlags.REGISTRY.allFlags(), $1);"
                );
                text = beforeLastBrace(
                    text,
                    unit +
                    "/** The mod's own blocks: Fabric checks only those for a missing loot table. */\n" +
                    unit +
                    "@Override\n" +
                    unit +
                    "protected Iterable<Block> getKnownBlocks() {\n" +
                    unit +
                    unit +
                    "return BuiltInRegistries.BLOCK.stream().filter(block -> BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(" +
                    quote(modid) +
                    ")).toList();\n" +
                    unit +
                    "}\n"
                );
                for (String name : List.of(
                    "java.util.Set",
                    "net.minecraft.world.flag.FeatureFlags",
                    "net.minecraft.core.registries.BuiltInRegistries",
                    "net.minecraft.world.level.block.Block"
                )) text = addImport(text, name);
                text = dropImport(text, "java.util.concurrent.CompletableFuture");
            }
            case "recipe" -> {
                text = text.replaceFirst("\\bextends\\s+FabricRecipeProvider\\b", "extends RecipeProvider.Runner");
                if (!Pattern.compile("public\\s+String\\s+getName\\s*\\(").matcher(text).find()) text = beforeLastBrace(
                    text,
                    unit +
                    "@Override\n" +
                    unit +
                    "public String getName() {\n" +
                    unit +
                    unit +
                    "return \"Recipes\";\n" +
                    unit +
                    "}\n"
                );
            }
            default -> {
                text = text.replaceFirst(
                    "\\bextends\\s+FabricAdvancementProvider\\b",
                    "implements AdvancementSubProvider"
                );
                text = text.replaceFirst("(?m)^[ \\t]*super\\(\\s*\\w+\\s*,\\s*\\w+\\s*\\)\\s*;[ \\t]*\\n", "");
                text = text.replaceAll("\\bvoid\\s+generateAdvancement\\s*\\(", "void generate(");
            }
        }
        text = addImport(text, kind[1]);
        return new Provider(kind[0], tidy(text));
    }

    static String beforeLastBrace(String text, String member) {
        int last = text.lastIndexOf('}');
        String head = text.substring(0, last).replaceAll("\\s+$", "");
        return head + "\n\n" + member + text.substring(last);
    }

    /**
     * The fabric-datagen entrypoint: onInitializeDataGenerator now takes NeoForge's GatherDataEvent, and each
     * pack.addProvider(X::new) becomes event.createProvider with the vanilla provider X needs around it.
     * Returns null when a provider's kind is not known (it is named instead).
     */
    static String datagenEntry(String text, Map<String, String> kinds, List<String> unknown) {
        text = text.replaceAll("\\s+implements\\s+DataGeneratorEntrypoint\\s*(?=\\{)", " ");
        text = text.replaceAll(
            "(?m)^([ \\t]*)@Override[ \\t]*\\n[ \\t]*(public\\s+void\\s+onInitializeDataGenerator\\s*\\()",
            "$1$2"
        );
        Matcher param = Pattern.compile(
            "onInitializeDataGenerator\\s*\\(\\s*FabricDataGenerator\\s+(\\w+)\\s*\\)"
        ).matcher(text);
        if (!param.find()) return null;
        String event = param.group(1);
        text = text.replace(param.group(), "onInitializeDataGenerator(GatherDataEvent " + event + ")");
        Matcher pack = Pattern.compile(
            "(?m)^[ \\t]*(?:final\\s+)?(?:FabricDataGenerator\\.Pack|var)\\s+(\\w+)\\s*=\\s*" +
            Pattern.quote(event) +
            "\\.createPack\\(\\)\\s*;[ \\t]*\\n\\s*"
        ).matcher(text);
        List<String> receivers = new ArrayList<>(List.of(Pattern.quote(event) + "\\.createPack\\(\\)"));
        if (pack.find()) {
            receivers.add(Pattern.quote(pack.group(1)));
            text =
                text.substring(0, pack.start()) + LoaderRules.indent(text, pack.start()) + text.substring(pack.end());
        }
        for (String receiver : receivers) {
            Matcher add = Pattern.compile(receiver + "\\.addProvider\\(\\s*(\\w+)::new\\s*\\)").matcher(text);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (add.find()) {
                String provider = add.group(1), kind = kinds.get(provider);
                String call = kind == null
                    ? null
                    : switch (kind) {
                        case "model", "recipe" -> event + ".createProvider(" + provider + "::new)";
                        case "blockLoot" -> event +
                        ".createProvider((output, lookup) -> new LootTableProvider(output, Set.of(), List.of(new LootTableProvider.SubProviderEntry(" +
                        provider +
                        "::new, LootContextParamSets.BLOCK)), lookup))";
                        case "advancement" -> event +
                        ".createProvider((output, lookup) -> new AdvancementProvider(output, lookup, List.of(new " +
                        provider +
                        "(output, lookup))))";
                        default -> null;
                    };
                if (call == null) {
                    unknown.add(provider);
                    continue;
                }
                out.append(text, last, add.start()).append(call);
                last = add.end();
            }
            out.append(text.substring(last));
            text = out.toString();
        }
        text = Pattern.compile("(?m)^import\\s+net\\.fabricmc\\.fabric\\.api\\.datagen\\.v1\\.[\\w.]+\\s*;[^\\n]*\\n?")
            .matcher(text)
            .replaceAll("");
        text = addImport(text, "net.neoforged.neoforge.data.event.GatherDataEvent");
        if (kinds.containsValue("blockLoot")) for (String name : List.of(
            "java.util.List",
            "java.util.Set",
            "net.minecraft.data.loot.LootTableProvider",
            "net.minecraft.world.level.storage.loot.parameters.LootContextParamSets"
        )) if (text.contains("LootTableProvider")) text = addImport(text, name);
        if (text.contains("new AdvancementProvider(")) for (String name : List.of(
            "java.util.List",
            "net.minecraft.data.advancements.AdvancementProvider"
        )) text = addImport(text, name);
        return tidy(text);
    }

    // ---- integrations ------------------------------------------------------------------------------------

    static final String SCREEN_FUNCTION =
        "java.util.function.Function<net.minecraft.client.gui.screens.Screen, net.minecraft.client.gui.screens.Screen>";

    /** A Mod Menu entry class, made to stand alone: its factory becomes a Function<Screen, Screen>. */
    static String modMenuToNeoForge(String text) {
        text = tidy(
            Pattern.compile("(?m)^import\\s+com\\.terraformersmc\\.modmenu\\.api\\.\\w+\\s*;[^\\n]*\\n?")
                .matcher(text)
                .replaceAll("")
        );
        text = text.replaceAll("\\s+implements\\s+ModMenuApi\\s*(?=\\{)", " ");
        text = text.replaceAll("(implements\\s+)ModMenuApi\\s*,\\s*", "$1");
        text = text.replaceAll(",\\s*ModMenuApi(?=[\\s,{])", "");
        text = text.replaceAll("(?m)^([ \\t]*)@Override\\s*\\n(\\s*public\\s+ConfigScreenFactory)", "$2");
        text = text.replaceAll("ConfigScreenFactory\\s*<[^>]*>", SCREEN_FUNCTION);
        return text;
    }

    /** The Mod Menu entry class back as a ModMenuApi. */
    static String modMenuToFabric(String text) {
        if (!text.contains(SCREEN_FUNCTION + " getModConfigScreenFactory()")) return text;
        text = text.replace(
            "public " + SCREEN_FUNCTION + " getModConfigScreenFactory()",
            "@Override\n\tpublic ConfigScreenFactory<?> getModConfigScreenFactory()"
        );
        text = text.replaceAll("(?m)^([ \\t]*)@Override\\n\\t(public ConfigScreenFactory)", "$1@Override\n$1$2");
        text = text.replaceFirst("(class\\s+\\w+)\\s*\\{", "$1 implements ModMenuApi {");
        text = addImport(text, "com.terraformersmc.modmenu.api.ModMenuApi");
        text = addImport(text, "com.terraformersmc.modmenu.api.ConfigScreenFactory");
        return text;
    }
}
