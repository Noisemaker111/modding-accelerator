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
}
