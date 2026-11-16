import java.util.*;
import java.util.regex.*;

/**
 * The names Mixin reads from a mixin's strings and members, moved from yarn's names to Mojang's with port's
 * yarn -> Mojang table (VersionMap --from-names yarn --to-names mojmap). Loom's migrateMappings renames a mixin's Java
 * references (imports, types, @Mixin(X.class)) but not what Mixin finds by name when the game runs: @Mixin(targets),
 * method = "...", @At(target = "Lowner;member(desc)"), @Accessor and @Invoker names, and @Shadow and @Overwrite members.
 * Each is looked up by the yarn owner (the mixin's target, named back to yarn), the yarn name and, when the string has
 * one, the descriptor. A name the table leaves out is the same in both. A name with overloads that the table renames
 * differently and no descriptor to tell them apart is kept, and named in left.
 */
final class MixinNames {

    /** Classes, yarn -> Mojang and back, in slash form. */
    final Map<String, String> classes = new HashMap<>(), back = new HashMap<>();
    /** Members by yarn owner (slash form) and yarn name: each yarn descriptor's Mojang name. */
    final Map<String, Map<String, String>> methods = new HashMap<>(), fields = new HashMap<>();
    /** Mojang members by name, for a name the mixin's own target does not declare (an inherited member). */
    final Map<String, Set<String>> methodsByName = new HashMap<>(), fieldsByName = new HashMap<>();
    final List<String> left = new ArrayList<>();

    MixinNames(Map<String, Object> table) {
        for (Object o : Json.array(table.get("classes"))) {
            var c = Json.object(o);
            String from = Json.text(c, "from", "").replace('.', '/'), to = Json.text(c, "to", "").replace('.', '/');
            classes.put(from, to);
            back.put(to, from);
        }
        load(table.get("methods"), methods, methodsByName);
        for (Object o : Json.array(table.get("methods"))) {
            var m = Json.object(o);
            mojangMethods.computeIfAbsent(Json.text(m, "newOwner", "").replace('.', '/'), k -> new HashSet<>()).add(Json.text(m, "to", ""));
        }
        load(table.get("fields"), fields, fieldsByName);
        if (table.get("supers") instanceof Map<?, ?> up) for (var e : up.entrySet()) supers.put(String.valueOf(e.getKey()), Json.array(e.getValue()).stream().map(String::valueOf).toList());
    }

    /** The Mojang names of the methods the table renames, by Mojang owner (slash form). */
    final Map<String, Set<String>> mojangMethods = new HashMap<>();

    /** Whether a Mojang class or one of its supertypes has a method of this Mojang name (that the table renames). */
    boolean mojangDeclares(String owner, String name) {
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String up = todo.poll();
            if (!seen.add(up)) continue;
            if (mojangMethods.getOrDefault(up, Set.of()).contains(name)) return true;
            todo.addAll(supers.getOrDefault(up, List.of()));
        }
        return false;
    }

    /** Each game class's supertypes in Mojang's names (slash form), from the table's supers (VersionMap --hierarchy). */
    final Map<String, List<String>> supers = new HashMap<>();

    /**
     * A member the yarn owner inherits or overrides: the mappings name it once, on the supertype that declares it first
     * (readNbt on BlockEntity, not on DecoratedPotBlockEntity), so the game's own hierarchy is walked up to it.
     */
    Map<String, String> declaredAbove(Map<String, Map<String, String>> kind, String owner, String name) {
        Deque<String> todo = new ArrayDeque<>(supers.getOrDefault(type(owner), List.of()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String up = todo.poll();
            if (!seen.add(up)) continue;
            Map<String, String> found = kind.get(back.getOrDefault(up, up) + " " + name);
            if (found != null) return found;
            todo.addAll(supers.getOrDefault(up, List.of()));
        }
        return null;
    }

    /** A Mojang name the compiler made up (lambda$name$0, access$000), which Loom's game does not use. */
    static boolean synthetic(String name) {
        return name.startsWith("lambda$") || name.startsWith("access$");
    }

    static void load(Object list, Map<String, Map<String, String>> into, Map<String, Set<String>> byName) {
        for (Object o : Json.array(list)) {
            var m = Json.object(o);
            String owner = Json.text(m, "owner", "").replace('.', '/'), name = Json.text(m, "from", "");
            // Loom's Mojang-named game keeps intermediary names (method_NNNN, as yarn has them) for lambdas, whose
            // Mojang names are not stable: a lambda keeps its yarn name.
            if (synthetic(Json.text(m, "to", ""))) continue;
            into.computeIfAbsent(owner + " " + name, k -> new LinkedHashMap<>()).put(Json.text(m, "desc", ""), Json.text(m, "to", ""));
            byName.computeIfAbsent(name, k -> new TreeSet<>()).add(Json.text(m, "to", ""));
        }
    }

    String type(String yarn) {
        return classes.getOrDefault(yarn, yarn);
    }

    String descriptor(String desc) {
        return Pattern.compile("L([^;]+);").matcher(desc).replaceAll(r -> Matcher.quoteReplacement("L" + type(r.group(1)) + ";"));
    }

    /**
     * The Mojang name of a member of the yarn owner: by descriptor when given, else the one name its overloads share.
     * Null when the table does not name it on that owner.
     */
    String member(Map<String, Map<String, String>> kind, Map<String, Set<String>> byName, String owner, String name, String desc, boolean inherited) {
        return member(kind, byName, owner, name, desc, inherited, -1);
    }

    /** As above; arity, when 0 or more, is the parameter count of the mixin method that names it (an @Invoker or @Shadow), which tells overloads apart. */
    String member(Map<String, Map<String, String>> kind, Map<String, Set<String>> byName, String owner, String name, String desc, boolean inherited, int arity) {
        Map<String, String> found = kind.get(owner + " " + name);
        if (found == null) found = declaredAbove(kind, owner, name);
        if (found != null) {
            if (desc != null && found.containsKey(desc)) return found.get(desc);
            // A method the mod declares or invokes with a parameter count none of these have is another method.
            if (arity >= 0 && found.keySet().stream().noneMatch(d -> descArity(d) == arity)) return null;
            Set<String> names = new TreeSet<>(found.values());
            if (names.size() == 1) return names.iterator().next();
            if (arity >= 0) {
                Set<String> same = new TreeSet<>();
                for (var e : found.entrySet()) if (descArity(e.getKey()) == arity) same.add(e.getValue());
                if (same.size() == 1) return same.iterator().next();
            }
            left.add(owner.replace('/', '.') + "." + name + (desc == null ? "" : desc) + ": its overloads have different Mojang names (" + String.join(", ", names) + ")");
            return null;
        }
        // Without the game's hierarchy, a supertype's member is guessed: one Mojang name for every member of that yarn
        // name. With it, a member found on no supertype has the same name in both (the table lists only renames), and
        // the guess would take an unrelated class's rename (getAttributes is getBasicAttributes on another class).
        if (inherited && supers.isEmpty() && byName.containsKey(name) && byName.get(name).size() == 1) return byName.get(name).iterator().next();
        return null;
    }

    /** A Java source with its mixins' names in Mojang's; a file that declares no @Mixin is returned as it is. */
    String remap(String source) {
        if (!source.contains("@Mixin")) return source;
        Map<String, String> imports = new HashMap<>();
        Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(source);
        while (imp.find()) imports.put(imp.group(1).substring(imp.group(1).lastIndexOf('.') + 1), imp.group(1));
        // Each @Mixin type is renamed against its own targets, the last (innermost or later) first; a type already done
        // is cut out of its outer type's text, so the outer mixin's targets never rename a nested mixin's names.
        String text = source;
        List<String> done = new ArrayList<>();
        boolean[] code = LoaderRules.code(text);
        List<int[]> scopes = new ArrayList<>();
        Matcher mixin = Pattern.compile("@Mixin\\s*\\(([^)]*)\\)").matcher(text);
        while (mixin.find()) if (code[mixin.start()]) scopes.add(new int[] {mixin.start(), mixin.end()});
        for (int i = scopes.size() - 1; i >= 0; i--) {
            int start = scopes.get(i)[0];
            code = LoaderRules.code(text);
            int open = text.indexOf('{', scopes.get(i)[1]);
            while (open >= 0 && !code[open]) open = text.indexOf('{', open + 1);
            if (open < 0) continue;
            int end = open, depth = 0;
            for (; end < text.length(); end++) {
                if (!code[end]) continue;
                if (text.charAt(end) == '{') depth++;
                else if (text.charAt(end) == '}' && --depth == 0) break;
            }
            end = Math.min(end + 1, text.length());
            String scope = text.substring(start, end);
            Matcher args = Pattern.compile("@Mixin\\s*\\(([^)]*)\\)").matcher(scope);
            List<String> owners = args.find() ? targets(args.group(1), imports) : List.of();
            String out = rewriteStrings(scope, owners);
            out = rewriteNamedAnnotations(out, owners);
            out = rewriteShadows(out, owners, null);
            done.add(out);
            text = text.substring(0, start) + "\u0000" + (done.size() - 1) + "\u0000" + text.substring(end);
        }
        // Put each renamed type back, outer ones first, so the nested ones they hold come back too.
        for (int i = done.size() - 1; i >= 0; i--) text = text.replace("\u0000" + i + "\u0000", done.get(i));
        return text;
    }

    /**
     * What Loom's migrateMappings leaves in yarn's names in any source, mixin or not: a nested game type named through
     * its outer class (Block.Settings is Block.Properties), and the declaration of a method that overrides a game
     * method (its super call is renamed, its own name is not). Overrides are found through the game's hierarchy from
     * the classes the file extends or implements; a name the table does not rename stays.
     */
    String javaNames(String source) {
        Map<String, String> imports = new HashMap<>();
        Matcher imp = Pattern.compile("(?m)^import\\s+([\\w.]+)\\s*;").matcher(source);
        while (imp.find()) imports.put(imp.group(1).substring(imp.group(1).lastIndexOf('.') + 1), imp.group(1));
        boolean[] code = LoaderRules.code(source);
        StringBuilder out = new StringBuilder();
        Matcher nested = Pattern.compile("(?<![\\w$.])([A-Z]\\w*)\\.([A-Z]\\w*)(?![\\w$])").matcher(source);
        while (nested.find()) {
            String outer = imports.get(nested.group(1)), to = null;
            if (code[nested.start()] && outer != null && outer.startsWith("net.minecraft.")) {
                String mojang = outer.replace('.', '/');
                // Outer.Inner already in Mojang's names (CustomPacketPayload.Type) is left: yarn's Inner of that name is
                // another class. Inner may be declared by a supertype (Block.Settings is AbstractBlock's).
                Deque<String> todo = new ArrayDeque<>(List.of(mojang));
                Set<String> seen = new HashSet<>();
                boolean mojangAlready = back.containsKey(mojang + "$" + nested.group(2));
                while (to == null && !todo.isEmpty() && !mojangAlready) {
                    String up = todo.poll();
                    if (!seen.add(up)) continue;
                    String renamed = classes.get(back.getOrDefault(up, up) + "$" + nested.group(2));
                    if (renamed != null && renamed.startsWith(up + "$")) to = renamed.substring(up.length() + 1);
                    todo.addAll(supers.getOrDefault(up, List.of()));
                }
            }
            nested.appendReplacement(out, Matcher.quoteReplacement(to == null ? nested.group() : nested.group(1) + "." + to));
        }
        nested.appendTail(out);
        String text = out.toString();
        List<String> owners = new ArrayList<>();
        Matcher sup = Pattern.compile("\\b(?:extends|implements)\\s+([\\w.<>,\\s?]+?)\\s*\\{").matcher(text);
        while (sup.find()) for (String t : sup.group(1).replaceAll("<[^<>]*>", "").split("[,\\s]+|\\b(?:extends|implements)\\b")) {
            String simple = t.strip();
            if (simple.isEmpty()) continue;
            String head = simple.contains(".") ? simple.substring(0, simple.indexOf('.')) : simple;
            String full = imports.containsKey(head) ? imports.get(head) + simple.substring(head.length()) : null;
            if (full == null || !full.startsWith("net.minecraft.")) continue;
            String slash = toBinary(full.replace('.', '/'));
            owners.add(back.getOrDefault(slash, slash));
        }
        if (owners.isEmpty()) return text;
        code = LoaderRules.code(text);
        Matcher m = Pattern.compile("@Override\\b((?:\\s*@[\\w.]+(?:\\([^)]*\\))?)*(?:\\s+(?:public|protected|private|static|final|abstract|synchronized))*\\s+[\\w.<>\\[\\], ?$]+\\s+)([\\w$]+)\\s*\\(").matcher(text);
        out = new StringBuilder();
        while (m.find()) {
            String name = m.group(2), to = null;
            // A name a supertype already has in Mojang's names was renamed by migrateMappings: renaming it again as if
            // it were yarn's would pick another method.
            if (code[m.start()] && owners.stream().noneMatch(o -> mojangDeclares(type(o), name))) for (String owner : owners) {
                to = member(methods, methodsByName, owner, name, null, false, javaArity(text, m.end() - 1));
                if (to != null) break;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(to == null || synthetic(to) ? m.group() : "@Override" + m.group(1) + to + "("));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The parameter count of a JVM method descriptor. */
    static int descArity(String desc) {
        int count = 0;
        for (int i = 1; i < desc.length() && desc.charAt(i) != ')'; i++) {
            char c = desc.charAt(i);
            if (c == '[') continue;
            if (c == 'L') i = desc.indexOf(';', i);
            count++;
        }
        return count;
    }

    /** The parameter count of the Java method whose parameter list opens at index open (its annotations and generics skipped). */
    static int javaArity(String source, int open) {
        int depth = 0, angle = 0, count = 0;
        boolean any = false;
        boolean[] code = LoaderRules.code(source);
        for (int i = open; i < source.length(); i++) {
            if (!code[i]) continue;
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return any ? count + 1 : 0;
            else if (depth == 1 && c == '<') angle++;
            else if (depth == 1 && c == '>') angle--;
            else if (depth == 1 && angle == 0 && c == ',') count++;
            else if (depth == 1 && !Character.isWhitespace(c)) any = true;
        }
        return -1;
    }

    /** A mixin's targets (the arguments of its @Mixin), as yarn names in slash form (the source names them in Mojang's after migrateMappings). */
    List<String> targets(String args, Map<String, String> imports) {
        List<String> out = new ArrayList<>();
        Matcher cls = Pattern.compile("([\\w.]+)\\.class").matcher(args);
        while (cls.find()) {
            String simple = cls.group(1), outer = simple.contains(".") ? simple.substring(0, simple.indexOf('.')) : simple;
            String full = imports.containsKey(outer) ? imports.get(outer) + simple.substring(outer.length()) : simple;
            String slash = toBinary(full.replace('.', '/'));
            out.add(back.getOrDefault(slash, slash));
        }
        Matcher str = Pattern.compile("\"([\\w.$/]+)\"").matcher(args.replaceAll("\\bvalue\\s*=", ""));
        while (str.find()) {
            String slash = str.group(1).replace('.', '/');
            out.add(classes.containsKey(slash) || back.containsKey(slash) ? (back.containsKey(slash) ? back.get(slash) : slash) : slash);
        }
        return out;
    }

    /** a/b/Outer/Inner as the game names it: the first capitalised segment is the class, the rest are nested ($). */
    String toBinary(String slash) {
        String[] parts = slash.split("/");
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (String part : parts) {
            if (out.length() > 0) out.append(inClass ? '$' : '/');
            out.append(part);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) inClass = true;
        }
        return out.toString();
    }

    /** Lowner;method(desc)ret, Lowner;field:type, or a class alone (Lowner;), each matched once from the left. */
    static final Pattern REFERENCE = Pattern.compile(
        "L([\\w/$]+);([\\w<>$]+)(?:(\\([^)\"]*\\)(?:\\[*L[\\w/$]+;|\\[*[BCDFIJSZV]))|:(\\[*L[\\w/$]+;|\\[*[BCDFIJSZ]))|L([\\w/$]+);"
    );
    static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /** Every string literal: references (Lowner;member(desc), Lowner;field:type), mixin targets and method = "..." names. */
    String rewriteStrings(String source, List<String> owners) {
        Matcher literal = LITERAL.matcher(source);
        StringBuilder out = new StringBuilder();
        while (literal.find()) {
            String value = literal.group(1), next = value;
            // method = "name(desc)ret" holds ';' too: it is a method of the target, not an @At reference.
            if (methodString(source, literal.start())) next = methodName(value, owners);
            else if (value.contains(";")) next = reference(value);
            else if (value.matches("net[./]minecraft[./][\\w./$]+")) {
                String slash = value.replace('.', '/');
                String to = type(slash);
                next = value.contains("/") ? to : to.replace('/', '.');
            }
            literal.appendReplacement(out, Matcher.quoteReplacement("\"" + next + "\""));
        }
        literal.appendTail(out);
        return out.toString();
    }

    /** A Mixin reference in a string: Lowner;name(desc)ret or Lowner;name:type, and bare descriptors. */
    String reference(String value) {
        Matcher m = REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String rewritten;
            if (m.group(5) != null) {
                rewritten = "L" + type(m.group(5)) + ";";
            } else if (m.group(3) != null) {
                String owner = m.group(1), name = m.group(2);
                String to = name.startsWith("<") ? name : member(methods, methodsByName, owner, name, m.group(3), true);
                rewritten = "L" + type(owner) + ";" + (to == null ? name : to) + descriptor(m.group(3));
            } else {
                String owner = m.group(1), name = m.group(2);
                String to = member(fields, fieldsByName, owner, name, m.group(4), true);
                rewritten = "L" + type(owner) + ";" + (to == null ? name : to) + ":" + descriptor(m.group(4));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rewritten));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Whether the literal at index is a method = ... value (alone or in an array). */
    static boolean methodString(String source, int index) {
        int start = Math.max(0, index - 300);
        String before = source.substring(start, index);
        Matcher m = Pattern.compile("\\bmethod\\s*=\\s*(\\{[^}]*)?$").matcher(before.replaceAll("\"(?:[^\"\\\\\\n]|\\\\.)*\"", "\"\""));
        return m.find();
    }

    /** A method = name: "name", "name(desc)ret" or "name*", on each of the mixin's targets. */
    String methodName(String value, List<String> owners) {
        if (value.startsWith("<")) return value;
        int paren = value.indexOf('(');
        String name = paren < 0 ? value.replaceAll("[*]$", "") : value.substring(0, paren);
        String desc = paren < 0 ? null : value.substring(paren);
        String to = null;
        for (String owner : owners) {
            to = member(methods, methodsByName, owner, name, desc, false);
            if (to != null) break;
        }
        if (to == null) return desc == null ? value : name + descriptor(desc);
        return to + (paren < 0 ? value.substring(name.length()) : descriptor(desc));
    }

    /** @Accessor("name") and @Invoker("name") values, and the names Mixin derives when they have none. */
    String rewriteNamedAnnotations(String source, List<String> owners) {
        Matcher m = Pattern.compile("@(Accessor|Invoker)(\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"[^)]*\\))?(\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public\\s+|static\\s+|abstract\\s+)*[\\w.<>\\[\\], ?$]+\\s+)([\\w$]+)\\s*\\(").matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean accessor = m.group(1).equals("Accessor");
            String given = m.group(3), method = m.group(5);
            String name = given != null ? given : derived(method, accessor);
            String to = null;
            if (name != null) for (String owner : owners) {
                to = accessor ? member(fields, fieldsByName, owner, name, null, false) : member(methods, methodsByName, owner, name, null, false, javaArity(source, m.end() - 1));
                if (to != null) break;
            }
            String replacement = m.group();
            if (to != null && !to.equals(name)) {
                replacement = "@" + m.group(1) + "(\"" + to + "\")" + m.group(4) + method + "(";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The member name Mixin derives from an accessor (getX, setX, isX) or invoker (callX, invokeX) method name. */
    static String derived(String method, boolean accessor) {
        for (String prefix : accessor ? List.of("get", "set", "is") : List.of("call", "invoke")) {
            if (method.startsWith(prefix) && method.length() > prefix.length() && Character.isUpperCase(method.charAt(prefix.length()))) {
                String rest = method.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    /** @Shadow and @Overwrite members: the declaration and every use of the name in the mixin. */
    String rewriteShadows(String source, List<String> owners, boolean[] ignored) {
        Matcher m = Pattern.compile("@(Shadow|Overwrite)\\b(?:\\([^)]*\\))?((?:\\s*@\\w+(?:\\([^)]*\\))?)*(?:\\s+(?:public|protected|private|static|final|abstract|native|synchronized))*\\s+[\\w.<>\\[\\], ?$]+\\s+)([\\w$]+)\\s*([(;=])").matcher(source);
        Map<String, String> methodRenames = new LinkedHashMap<>(), fieldRenames = new LinkedHashMap<>();
        while (m.find()) {
            String name = m.group(3);
            boolean method = m.group(4).equals("(");
            String to = null;
            for (String owner : owners) {
                to = method ? member(methods, methodsByName, owner, name, null, true, javaArity(source, m.end() - 1)) : member(fields, fieldsByName, owner, name, null, true);
                if (to != null) break;
            }
            if (to != null && !to.equals(name)) (method ? methodRenames : fieldRenames).put(name, to);
        }
        String text = source;
        for (var e : methodRenames.entrySet()) {
            String name = Pattern.quote(e.getKey());
            text = renameInCode(text, "(?<![\\w$.])" + name + "(?=\\s*\\()|(?<=\\bthis\\.)" + name + "(?=\\s*\\()", e.getValue());
        }
        for (var e : fieldRenames.entrySet()) {
            String name = Pattern.quote(e.getKey());
            text = renameInCode(text, "(?<![\\w$.])" + name + "(?![\\w$(])|(?<=\\bthis\\.)" + name + "(?![\\w$(])", e.getValue());
        }
        return text;
    }

    static String renameInCode(String text, String pattern, String to) {
        boolean[] code = LoaderRules.code(text);
        Matcher m = Pattern.compile(pattern).matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(code[m.start()] ? to : m.group()));
        m.appendTail(out);
        return out.toString();
    }
}
