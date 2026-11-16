import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/**
 * One Fabric API source file as a convert bridge mirror skeleton (run by bench/bridge_mirror.py, with the JDK's own
 * source launcher: java Skeleton.java IN OUT MIRRORED_LIST).
 *
 * The mirror keeps Fabric's package (under ${package}), names, members and every body that uses only public API: event
 * fields made with EventFactory, callback interfaces, records and constants work as they are. Fabric's internals
 * (net.fabricmc.*.impl and *.mixin) are not mirrored: a method body that uses one throws UnsupportedOperationException,
 * a field initialised from one starts null (or 0/false), and a static block that uses one is dropped. Each is listed in
 * the report, with every Event field as UNGLUED: the hand-written glue posts it from NeoForge's nearest event.
 *
 * Prints a JSON report: needs (Fabric classes the skeleton imports that no bridge mirrors), stubbed, events, symbols
 * (artifact|class#member for loader_api.py verify) and callbacks (each event's callback arity, for the test stubs).
 */
public final class Skeleton {

    record Edit(int start, int end, String text) {}

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Set<String> mirrored = new HashSet<>(Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8));
        String source = Files.readString(in, StandardCharsets.UTF_8);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, files, d -> {}, List.of("-proc:none"), null, files.getJavaFileObjects(in.toFile()));
        CompilationUnitTree unit = task.parse().iterator().next();
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        List<Edit> edits = new ArrayList<>();
        Set<String> impl = new TreeSet<>();
        List<String> needs = new ArrayList<>();
        List<String> stubbed = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        Map<String, Integer> callbacks = new LinkedHashMap<>();

        String pkg = unit.getPackageName().toString();
        // Everything before the package line is Fabric's licence header; the mirror says where it came from instead.
        int pkgStart = (int) pos.getStartPosition(unit, unit.getPackage());
        int pkgEnd = (int) pos.getEndPosition(unit, unit.getPackage());
        edits.add(new Edit(0, pkgEnd, "package " + bridged(pkg) + ";"));
        for (ImportTree imp : unit.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            int start = (int) pos.getStartPosition(unit, imp);
            int end = (int) pos.getEndPosition(unit, imp);
            if (!name.startsWith("net.fabricmc.")) continue;
            if (internal(name)) {
                impl.add(name.substring(name.lastIndexOf('.') + 1));
                int lineEnd = source.indexOf('\n', end);
                edits.add(new Edit(start, lineEnd < 0 ? end : lineEnd + 1, ""));
                continue;
            }
            String cls = imp.isStatic() ? name.substring(0, name.lastIndexOf('.')) : name;
            if (!known(cls, mirrored)) needs.add(cls);
            edits.add(new Edit(start, end, "import " + (imp.isStatic() ? "static " : "") + bridged(name) + ";"));
        }

        new TreePathScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree c, String outer) {
                String binary = outer == null ? pkg + "." + c.getSimpleName() : outer + "$" + c.getSimpleName();
                boolean iface = c.getKind() == Tree.Kind.INTERFACE;
                List<MethodTree> abstracts = new ArrayList<>();
                for (Tree member : c.getMembers()) {
                    if (member instanceof ClassTree nested) {
                        visitClass(nested, binary);
                        continue;
                    }
                    if (member instanceof MethodTree m) {
                        Set<Modifier> mods = m.getModifiers().getFlags();
                        boolean visible = iface || mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
                        String name = m.getName().toString();
                        if (visible && !name.equals("<init>")) symbols.add(binary + "#" + name);
                        if (m.getBody() == null) {
                            if (iface && !mods.contains(Modifier.STATIC) && !mods.contains(Modifier.DEFAULT)) abstracts.add(m);
                            continue;
                        }
                        if (usesInternal(text(m.getBody()), impl)) {
                            String label = binary + "#" + (name.equals("<init>") ? c.getSimpleName() : name);
                            stubbed.add(label);
                            edits.add(new Edit(start(m.getBody()), end(m.getBody()),
                                "{\n\t\tthrow new UnsupportedOperationException(\"" + label.replace("$", ".") + ": Fabric's implementation is not mirrored yet\");\n\t}"));
                        }
                    } else if (member instanceof VariableTree v) {
                        Set<Modifier> mods = v.getModifiers().getFlags();
                        if (iface || mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED)) symbols.add(binary + "#" + v.getName());
                        String type = text(v.getType());
                        if (type.startsWith("Event<")) events.add(binary.substring(binary.lastIndexOf('.') + 1).replace('$', '.') + "." + v.getName() + " " + type);
                        if (v.getInitializer() != null && usesInternal(text(v.getInitializer()), impl)) {
                            stubbed.add(binary + "#" + v.getName());
                            edits.add(new Edit(start(v.getInitializer()), end(v.getInitializer()), zero(type)));
                        }
                    } else if (member instanceof BlockTree b && usesInternal(text(b), impl)) {
                        stubbed.add(binary + " static block");
                        edits.add(new Edit(start(b), end(b), "// A static block that used Fabric's implementation: the glue replaces it."));
                    }
                }
                if (iface && abstracts.size() == 1) callbacks.put(binary, abstracts.get(0).getParameters().size());
                return null;
            }

            int start(Tree t) {
                return (int) pos.getStartPosition(unit, t);
            }

            int end(Tree t) {
                return (int) pos.getEndPosition(unit, t);
            }

            String text(Tree t) {
                return source.substring(start(t), end(t));
            }
        }.scan(unit, null);

        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Edit e : edits) text.replace(e.start(), e.end(), e.text());
        String body = text.toString();
        // Qualified Fabric names in code point at the mirror too.
        body = body.replaceAll("(?<![\\w.$])net\\.fabricmc\\.(?!.*\\.impl\\.)", "\\${package}.");
        StringBuilder head = new StringBuilder();
        int firstType = body.indexOf('\n', body.indexOf("package ")) + 1;
        head.append("\n// Fabric API's ").append(pkg).append('.').append(in.getFileName().toString().replace(".java", ""))
            .append(", mirrored by bench/bridge_mirror.py from Fabric's published sources; the glue is hand-written.\n");
        for (String e : events) head.append("// UNGLUED: ").append(e).append(" (post it from NeoForge's nearest event, then delete this line)\n");
        for (String s : stubbed) head.append("// STUBBED: ").append(s).append(" (used Fabric's implementation)\n");
        body = body.substring(0, firstType) + head + body.substring(firstType);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, body, StandardCharsets.UTF_8);
        System.out.println(json(Map.of("needs", needs, "stubbed", stubbed, "events", events, "symbols", symbols, "callbacks", callbacks)));
    }

    static boolean internal(String name) {
        return name.contains(".impl.") || name.contains(".mixin.") || name.endsWith(".impl") || name.contains(".fabric.api.base.") && name.endsWith("Impl");
    }

    static boolean known(String cls, Set<String> mirrored) {
        for (String c = cls; c.contains("."); c = c.substring(0, c.lastIndexOf('.'))) if (mirrored.contains(c)) return true;
        return false;
    }

    static String bridged(String name) {
        return name.startsWith("net.fabricmc.") ? "${package}." + name.substring("net.fabricmc.".length()) : name;
    }

    static boolean usesInternal(String code, Set<String> impl) {
        if (code.matches("(?s).*\\bnet\\.fabricmc\\.[\\w.]*\\.(impl|mixin)\\..*")) return true;
        for (String name : impl) if (Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(code).find()) return true;
        return false;
    }

    static String zero(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "int", "long", "short", "byte", "char", "float", "double" -> "0";
            default -> "null";
        };
    }

    static String json(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(", ", "{", "}");
            for (var e : new TreeMap<>(m).entrySet()) j.add(json(e.getKey()) + ": " + json(e.getValue()));
            return j.toString();
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            for (Object x : c) j.add(json(x));
            return j.toString();
        }
        if (o instanceof Number) return o.toString();
        return "\"" + String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
