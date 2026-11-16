import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * A yarn mod's mixin strings in Mojang's names, exactly, from the mod's own refmap: Loom's build of the untouched mod
 * resolves every string Mixin reads by name (method = ..., @At targets, @Accessor and @Invoker names, @Mixin targets)
 * to an intermediary member, and VersionMap --intermediary names each intermediary member in Mojang's names. A lambda
 * keeps its intermediary name, as Loom's game does. After the renamed mod compiles, its own refmap must resolve to the
 * same intermediary members (check): that proves the strings without starting the game.
 */
final class RefmapNames {

    final Map<String, String> classes = new HashMap<>();
    final Map<String, List<String>> members = new HashMap<>();

    RefmapNames(Map<String, Object> intermediary) {
        for (var e : Json.object(intermediary.get("classes")).entrySet()) classes.put(e.getKey(), String.valueOf(e.getValue()));
        for (var e : Json.object(intermediary.get("members")).entrySet()) {
            members.put(e.getKey(), Json.array(e.getValue()).stream().map(String::valueOf).toList());
        }
    }

    /** Every refmap under a build's class folders, merged: mixin class (slash form) -> source string -> intermediary. */
    static Map<String, Map<String, String>> read(Path folder) throws IOException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        if (!Files.isDirectory(folder)) return out;
        try (var walk = Files.walk(folder)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith("refmap.json")).sorted().toList()) {
                Object mappings = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8))).get("mappings");
                if (!(mappings instanceof Map<?, ?> m)) continue;
                for (var mixin : m.entrySet()) {
                    var into = out.computeIfAbsent(String.valueOf(mixin.getKey()), k -> new TreeMap<>());
                    for (var e : Json.object(mixin.getValue()).entrySet()) into.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    String type(String intermediary) {
        return classes.getOrDefault(intermediary, intermediary);
    }

    String descriptor(String desc) {
        return Pattern.compile("L([^;]+);").matcher(desc).replaceAll(r -> Matcher.quoteReplacement("L" + type(r.group(1)) + ";"));
    }

    /** A member's Mojang name; a lambda (or a name Mojang does not give) keeps its intermediary name, as in Loom's game. */
    String name(String intermediary) {
        List<String> m = members.get(intermediary);
        if (m == null || MixinNames.synthetic(m.get(1))) return intermediary;
        return m.get(1);
    }

    static final Pattern VALUE = Pattern.compile("^(?:L([^;]+);)?([^(:;]*)(\\(.*)?(?::(.+))?$");

    /**
     * The string to write for one refmap entry: key as the source wrote it, value as the refmap resolved it. A value
     * with its owner is written in full (Lowner;name(desc), which every injector selector and @At target accepts); one
     * without (an accessor or invoker) keeps the source's shape, a name with a descriptor only when the source had one.
     */
    String mojang(String key, String value) {
        boolean classOnly = !value.contains("(") && !value.contains(":") && !value.contains(";") && value.contains("/");
        if (classOnly) {
            String to = type(value);
            return key.contains("/") ? to : to.replace('/', '.');
        }
        Matcher m = VALUE.matcher(value);
        if (!m.matches()) return key;
        String owner = m.group(1), name = m.group(2), desc = m.group(3), field = m.group(4);
        String to = name.isEmpty() ? "" : name(name);
        String tail = desc != null ? descriptor(desc) : field != null ? ":" + descriptor(field) : "";
        if (owner != null) return "L" + type(owner) + ";" + to + tail;
        return key.contains("(") || key.contains(":") ? to + tail : to;
    }

    static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /**
     * A mixin source with its refmap entries written in Mojang's names: each string literal inside an annotation whose
     * text is a key, and each @Accessor or @Invoker without a value whose derived name is one (it gets the Mojang name as
     * its value). Keys that two mixins of one file resolve differently are left (in conflicts).
     */
    String apply(String source, Map<String, String> entries) {
        boolean[] code = LoaderRules.code(source);
        Matcher literal = LITERAL.matcher(source);
        StringBuilder out = new StringBuilder();
        Set<String> used = new HashSet<>();
        while (literal.find()) {
            // Mixin's processor keys a string without its whitespace (owner.name (desc) is owner.name(desc) in the refmap).
            String key = literal.group(1).replaceAll("\\s+", "");
            String to = entries.containsKey(key) && inAnnotation(source, literal.start(), code) ? mojang(key, entries.get(key)) : null;
            if (to != null) used.add(key);
            literal.appendReplacement(out, Matcher.quoteReplacement(to == null ? literal.group() : "\"" + to + "\""));
        }
        literal.appendTail(out);
        String text = out.toString();
        Matcher bare = Pattern.compile("@(Accessor|Invoker)(?!\\s*\\()((?:\\s*@\\w+(?:\\([^)]*\\))?)*(?:\\s+(?:public|static|abstract))*\\s+[\\w.<>\\[\\], ?$]+\\s+)([\\w$]+)\\s*\\(").matcher(text);
        out = new StringBuilder();
        while (bare.find()) {
            String derived = MixinNames.derived(bare.group(3), bare.group(1).equals("Accessor"));
            String to = derived != null && entries.containsKey(derived) && !used.contains(derived) ? mojang(derived, entries.get(derived)) : null;
            String replacement = to == null || to.equals(derived) ? bare.group() : "@" + bare.group(1) + "(\"" + to + "\")" + bare.group(2) + bare.group(3) + "(";
            bare.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        bare.appendTail(out);
        return out.toString();
    }

    /** Whether the literal at index is an annotation's argument: an @ since the last statement, block or member end. */
    static boolean inAnnotation(String source, int index, boolean[] code) {
        for (int i = index - 1; i >= 0; i--) {
            if (!code[i]) continue;
            char c = source.charAt(i);
            if (c == '@') return true;
            if (c == ';' || c == '{' || c == '}') return false;
        }
        return false;
    }

    /** What refmap values name: the intermediary member (method_N, field_N) where there is one, else the class or the value. */
    static Set<String> resolved(Collection<String> values) {
        Set<String> out = new TreeSet<>();
        for (String v : values) {
            Matcher m = Pattern.compile("\\b(method_\\d+|field_\\d+)\\b").matcher(v);
            out.add(m.find() ? m.group(1) : v);
        }
        return out;
    }

    /**
     * The renamed mod's refmap against the original's: each mixin must resolve the same intermediary members (the
     * strings changed, what they name must not). Lists each mixin whose set differs, with what it lost and gained.
     */
    List<String> check(Map<String, Map<String, String>> original, Map<String, Map<String, String>> renamed) {
        List<String> out = new ArrayList<>();
        for (var e : original.entrySet()) {
            // An entry that maps to itself names something unobfuscated (RenderSystem, a library): no build needs it.
            Set<String> before = resolved(e.getValue().entrySet().stream().filter(x -> !x.getKey().equals(x.getValue())).map(Map.Entry::getValue).toList());
            Set<String> after = resolved(renamed.getOrDefault(e.getKey(), Map.of()).values());
            // A lambda is named by its intermediary name in both builds, so the renamed refmap needs no entry for it.
            before.removeIf(m -> m.startsWith("method_") && name(m).equals(m) && !after.contains(m));
            if (before.equals(after)) continue;
            Set<String> lost = new TreeSet<>(before);
            lost.removeAll(after);
            Set<String> gained = new TreeSet<>(after);
            gained.removeAll(before);
            out.add(e.getKey().replace('/', '.') + ": no longer resolves " + lost + (gained.isEmpty() ? "" : ", now resolves " + gained));
        }
        return out;
    }
}
