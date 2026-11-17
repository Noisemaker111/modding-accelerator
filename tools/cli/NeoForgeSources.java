import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * NeoForge's own source files (its published sources jar, LGPL-2.1, license headers kept) for the classes a mod converted
 * to Fabric uses that the NeoForge bridge does not mirror: the bridge's folder lists which of them compile on Fabric
 * (neoforge-sources.txt, measured by bench/bridge_import.py), and convert copies the ones the mod names, with every
 * listed class those name in turn, into the bridge's package (net.neoforged.neoforge.X becomes PACKAGE.neoforge.X).
 */
final class NeoForgeSources {

    static final String PREFIX = "net.neoforged.neoforge.";

    /** The folder under the bridge's package that NeoForge's own classes go in. */
    static final String FOLDER = "neoforge";

    private NeoForgeSources() {}

    /** The jar's top-level classes of net.neoforged.neoforge by name, as source; package-info files left out. */
    static Map<String, String> read(Path jar) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("net/neoforged/neoforge/") || !name.endsWith(".java") || name.endsWith("/package-info.java")) continue;
                try (var in = zip.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.put(name.substring(0, name.length() - ".java".length()).replace('/', '.'), text);
                }
            }
        }
        return out;
    }

    static final Pattern NAMED = Pattern.compile("(?<![\\w.])net\\.neoforged\\.neoforge\\.([\\w.]*\\w\\.?\\*?)");
    static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** The longest dotted prefix of a name that is one of the classes, or null. */
    static String longest(String name, Set<String> classes) {
        String at = name;
        while (true) {
            if (classes.contains(at)) return at;
            int dot = at.lastIndexOf('.');
            if (dot < 0) return null;
            at = at.substring(0, dot);
        }
    }

    static String packageOf(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    static String simpleOf(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * The classes among known that a source names: by full name (an import, a static import, a qualified use), through a
     * wildcard import of their package, or (in NeoForge's own files) by simple name in the same package.
     */
    static Set<String> referenced(String text, Set<String> known, Map<String, List<String>> byPackage) {
        Set<String> out = new TreeSet<>();
        Set<String> words = null;
        Matcher m = NAMED.matcher(text);
        while (m.find()) {
            String rest = m.group(1);
            if (rest.endsWith(".*") || rest.endsWith(".")) {
                String pkg = PREFIX + rest.replaceAll("\\.\\*?$", "");
                if (words == null) words = words(text);
                for (String c : byPackage.getOrDefault(pkg, List.of())) if (words.contains(simpleOf(c))) out.add(c);
                continue;
            }
            String found = longest(PREFIX + rest, known);
            if (found != null) out.add(found);
        }
        Matcher pkg = PACKAGE.matcher(text);
        if (pkg.find() && pkg.group(1).startsWith(PREFIX.substring(0, PREFIX.length() - 1))) {
            if (words == null) words = words(text);
            for (String c : byPackage.getOrDefault(pkg.group(1), List.of())) if (words.contains(simpleOf(c))) out.add(c);
        }
        return out;
    }

    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher w = WORD.matcher(text);
        while (w.find()) out.add(w.group());
        return out;
    }

    static Map<String, List<String>> byPackage(Collection<String> classes) {
        Map<String, List<String>> out = new HashMap<>();
        for (String c : classes) out.computeIfAbsent(packageOf(c), k -> new java.util.ArrayList<>()).add(c);
        return out;
    }

    /** The classes the texts name among allowed, with every allowed class those sources name, to the end. */
    static Set<String> closure(Collection<String> texts, Map<String, String> sources, Set<String> allowed) {
        Map<String, List<String>> packages = byPackage(allowed);
        Set<String> out = new TreeSet<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        for (String text : texts) for (String c : referenced(text, allowed, packages)) if (out.add(c)) todo.add(c);
        while (!todo.isEmpty()) {
            String source = sources.get(todo.poll());
            if (source == null) continue;
            for (String c : referenced(source, allowed, packages)) if (out.add(c)) todo.add(c);
        }
        return out;
    }

    /**
     * One of NeoForge's files in the bridge's package: its package moves under PACKAGE.neoforge, and the classes of its
     * own NeoForge package that the bridge mirrors (named there without an import) are imported by their NeoForge names,
     * which convert then points at the bridge's classes as it does in the mod's own code.
     */
    static String relocate(String text, String bridgePackage, Set<String> mirrored) {
        Matcher pkg = PACKAGE.matcher(text);
        if (!pkg.find()) return text;
        String own = pkg.group(1);
        StringBuilder imports = new StringBuilder();
        Set<String> words = words(text);
        for (String c : new TreeSet<>(mirrored)) if (packageOf(c).equals(own) && words.contains(simpleOf(c))) imports.append("import ").append(c).append(";\n");
        String moved = "package " + bridgePackage + "." + FOLDER + "." + own.substring(PREFIX.length()) + ";\n";
        String head = imports.isEmpty() ? moved : moved + "\n" + imports;
        return text.substring(0, pkg.start()) + head + text.substring(pkg.end()).replaceFirst("^\\n", "");
    }

    /** NeoForge's names of copied classes in a source, pointed at their copies (the longest copied class a name starts with). */
    static String point(String text, Set<String> copied, String bridgePackage) {
        Matcher m = NAMED.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = longest(PREFIX + m.group(1).replaceAll("\\.\\*?$", ""), copied);
            boolean wildcard = m.group(1).endsWith("*") || m.group(1).endsWith(".");
            String pkg = PREFIX + m.group(1).replaceAll("\\.\\*?$", "");
            boolean copiedPackage = wildcard && copied.stream().anyMatch(c -> packageOf(c).equals(pkg));
            if (found == null && !copiedPackage) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(bridgePackage + "." + FOLDER + "." + m.group(1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The way back: the bridge's copies are NeoForge's classes again. */
    static String back(String text, String bridgePackage) {
        return text.replaceAll("(?<![\\w.])" + Pattern.quote(bridgePackage + "." + FOLDER + ".") , Matcher.quoteReplacement(PREFIX));
    }

    /**
     * A member of a type body (a field, method, constructor, initializer or nested type) or an import, from start to end
     * in the source. key is the nested types it is in, '#', and its name: a method's or constructor's with its number of
     * parameters (run(2)), a field's, a nested type's, an initializer's ({} or static{}, all of a type's at once), an import's text.
     * Neither the package, a top-level type nor an @Override method (which would change behaviour silently) can go.
     */
    record Member(int start, int end, String key, boolean removable, int depth) {}

    /** The source with comments and the insides of string, character and text block literals blanked, lines kept. */
    static String mask(String text) {
        char[] out = text.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            // [i, end) is blanked; next is where scanning goes on.
            int end;
            int next;
            if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                end = text.indexOf('\n', i);
                if (end < 0) end = out.length;
                next = end;
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                end = text.indexOf("*/", i + 2);
                end = end < 0 ? out.length : end + 2;
                next = end;
            } else if (text.startsWith("\"\"\"", i)) {
                end = text.indexOf("\"\"\"", i + 3);
                while (end > 0 && text.charAt(end - 1) == '\\') end = text.indexOf("\"\"\"", end + 1);
                if (end < 0) end = out.length;
                next = end + 3;
                i += 3;
            } else if (c == '"' || c == '\'') {
                end = i + 1;
                while (end < out.length && text.charAt(end) != c && text.charAt(end) != '\n') end += text.charAt(end) == '\\' ? 2 : 1;
                next = end + 1;
                i++;
            } else {
                i++;
                continue;
            }
            for (int k = i; k < Math.min(end, out.length); k++) if (out[k] != '\n') out[k] = ' ';
            i = next;
        }
        return new String(out);
    }

    static final Pattern TYPE = Pattern.compile("(?:^|[\\s;])(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");

    /** The source's imports and members of every named type, outer ones before the ones inside them. */
    static List<Member> members(String text) {
        String m = mask(text);
        List<Member> out = new java.util.ArrayList<>();
        body(m, 0, m.length(), "", false, 0, out);
        return out;
    }

    private static int close(String m, int open) {
        int depth = 0;
        for (int i = open; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return m.length() - 1;
    }

    private static String header(String h) {
        // Annotations (with their arguments) and generic parameters out; what is left names the member.
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < h.length()) {
            if (h.charAt(i) == '@' && !h.startsWith("@interface", i)) {
                i++;
                while (i < h.length() && (Character.isJavaIdentifierPart(h.charAt(i)) || h.charAt(i) == '.')) i++;
                int j = i;
                while (j < h.length() && Character.isWhitespace(h.charAt(j))) j++;
                if (j < h.length() && h.charAt(j) == '(') {
                    int depth = 0;
                    for (i = j; i < h.length(); i++) {
                        if (h.charAt(i) == '(') depth++;
                        else if (h.charAt(i) == ')' && --depth == 0) break;
                    }
                    i++;
                }
                continue;
            }
            b.append(h.charAt(i++));
        }
        String s = b.toString();
        for (String before = ""; !before.equals(s); ) {
            before = s;
            s = s.replaceAll("<[^<>]*>", "");
        }
        return s.replaceAll("\\s+", " ").strip();
    }

    private static void body(String m, int from, int to, String path, boolean isEnum, int depth, List<Member> out) {
        int pos = from;
        if (isEnum) {
            // The constants come first, up to the first ';' outside their own bodies and arguments.
            int braces = 0;
            int parens = 0;
            while (pos < to && !(m.charAt(pos) == ';' && braces == 0 && parens == 0)) {
                char c = m.charAt(pos++);
                braces += c == '{' ? 1 : c == '}' ? -1 : 0;
                parens += c == '(' ? 1 : c == ')' ? -1 : 0;
            }
            pos++;
        }
        while (pos < to) {
            while (pos < to && (Character.isWhitespace(m.charAt(pos)) || m.charAt(pos) == ';')) pos++;
            if (pos >= to) break;
            int start = pos;
            int parens = 0;
            int brace = -1;
            int end = -1;
            boolean assigned = false;
            for (int i = pos; i < to; i++) {
                char c = m.charAt(i);
                if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (parens == 0 && c == '=') assigned = true;
                else if (parens == 0 && c == ';') {
                    end = i + 1;
                    break;
                } else if (parens == 0 && c == '{') {
                    brace = i;
                    break;
                }
            }
            if (brace >= 0 && assigned) {
                // A field whose initializer has braces (an array, an anonymous class, a lambda) ends at its ';'.
                int braces = 0;
                parens = 0;
                for (int i = brace; i < to; i++) {
                    char c = m.charAt(i);
                    braces += c == '{' ? 1 : c == '}' ? -1 : 0;
                    parens += c == '(' ? 1 : c == ')' ? -1 : 0;
                    if (c == ';' && braces == 0 && parens == 0) {
                        end = i + 1;
                        break;
                    }
                }
            } else if (brace >= 0) end = close(m, brace) + 1;
            if (end < 0) end = to;
            String raw = m.substring(start, brace >= 0 && !assigned ? brace : end);
            String h = header(raw);
            Matcher type = TYPE.matcher(" " + h);
            String key;
            String inside = null;
            boolean removable = !raw.contains("@Override") && depth > 0;
            if (h.startsWith("package ")) {
                key = h;
                removable = false;
            } else if (h.startsWith("import ")) {
                key = h.replaceAll("\\s*;$", "");
                removable = true;
            } else if (brace >= 0 && !assigned && type.find() && (h.indexOf('(') < 0 || type.start(1) - 1 < h.indexOf('('))) {
                key = type.group(1);
                inside = path + (path.isEmpty() ? "" : ".") + key;
            } else if (brace >= 0 && !assigned && h.replaceAll("\\bstatic\\b", "").isBlank()) {
                String kind = h.contains("static") ? "static{}" : "{}";
                key = kind;
            } else if (h.contains("(") && !assigned) {
                String before = h.substring(0, h.indexOf('(')).strip();
                String name = before.substring(before.lastIndexOf(' ') + 1);
                String params = h.substring(h.indexOf('(') + 1, h.lastIndexOf(')') < 0 ? h.length() : h.lastIndexOf(')')).strip();
                key = name + "(" + (params.isEmpty() ? 0 : params.split(",").length) + ")";
            } else {
                String decl = h.replaceAll("\\s*(=.*|;)$", "").replaceAll("\\[\\s*]", "").strip();
                if (decl.contains(",")) decl = decl.substring(0, decl.indexOf(',')).strip();
                key = decl.substring(decl.lastIndexOf(' ') + 1);
            }
            out.add(new Member(start, end, path + "#" + key, removable, depth));
            if (inside != null) body(m, brace + 1, end - 1, inside, h.matches("(?:.*\\s)?enum\\s.*"), depth + 1, out);
            pos = end;
        }
    }

    /**
     * The member to remove for a compile error on a line (1-based): the innermost member around it that can go, or null
     * when only the whole file can.
     */
    static Member around(String text, int line) {
        int at = 0;
        for (int l = 1; l < line && at >= 0; l++) at = text.indexOf('\n', at) + 1;
        if (at < 0) return null;
        int lineEnd = text.indexOf('\n', at);
        int last = lineEnd < 0 ? text.length() : lineEnd;
        Member best = null;
        for (Member member : members(text)) if (member.start() < last && member.end() > at && (best == null || member.depth() >= best.depth())) best = member;
        while (best != null && !best.removable()) {
            Member inner = best;
            best = null;
            for (Member member : members(text)) {
                boolean outside = member.start() <= inner.start() && member.end() >= inner.end() && member.depth() < inner.depth();
                if (outside && (best == null || member.depth() > best.depth())) best = member;
            }
        }
        return best;
    }

    /** The source without the members whose keys are given (with the whitespace their lines leave). */
    static String remove(String text, Collection<String> keys) {
        List<Member> gone = new java.util.ArrayList<>();
        for (Member member : members(text)) {
            boolean inGone = gone.stream().anyMatch(g -> g.start() <= member.start() && g.end() >= member.end());
            if (keys.contains(member.key()) && member.removable() && !inGone) gone.add(member);
        }
        gone.sort((a, b) -> b.start() - a.start());
        StringBuilder out = new StringBuilder(text);
        for (Member member : gone) {
            int start = member.start();
            while (start > 0 && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) start--;
            int end = member.end();
            while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
            if (end < text.length() && text.charAt(end) == '\n' && (start == 0 || text.charAt(start - 1) == '\n')) end++;
            out.delete(start, end);
        }
        return out.toString();
    }

    /**
     * For bench/bridge_import.py: java NeoForgeSources.java LIST reads lines "FILE<tab>LINE,LINE..." from LIST (the lines
     * javac reports errors on in each file), removes from each file the members around them and prints "FILE<tab>KEY"
     * for each, or "FILE<tab>" when only removing the whole file would do (it is left for the caller to delete).
     */
    public static void main(String[] args) throws IOException {
        for (String row : java.nio.file.Files.readAllLines(Path.of(args[0]))) {
            String[] parts = row.split("\t");
            Path file = Path.of(parts[0]);
            String text = java.nio.file.Files.readString(file);
            Set<String> keys = new TreeSet<>();
            for (String line : parts[1].split(",")) {
                Member member = around(text, Integer.parseInt(line));
                if (member == null) {
                    keys = null;
                    break;
                }
                keys.add(member.key());
            }
            if (keys == null) {
                System.out.println(parts[0] + "\t");
                continue;
            }
            java.nio.file.Files.writeString(file, remove(text, keys));
            for (String key : keys) System.out.println(parts[0] + "\t" + key);
        }
    }
}
