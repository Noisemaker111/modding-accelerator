import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/**
 * What a mod uses of the game and its loader, resolved by the compiler on the mod's own version: every call, field,
 * type, constructor and override, with the member it names exactly (owner, name, descriptor) and where it is written.
 * Joined with the game's change map for a jump, it says before any port what the mod needs, member by member: renamed,
 * moved, re-typed, replaced by what the game's own code does, or redesigned with nothing to follow. Nothing is read
 * from compiler errors; the same list drives every mod and every naming scheme.
 */
final class UsedMembers {

    private UsedMembers() {}

    /** One place a mod names a game member: kind is call, field, type, new or override; start/end are the name's offsets. */
    record Use(String file, long line, long column, long start, long end, String kind, String owner, String name, String desc) {}

    static boolean game(String owner) {
        return owner.startsWith("net.minecraft.") || owner.startsWith("com.mojang.") || owner.startsWith("net.neoforged.")
            || owner.startsWith("net.fabricmc.") || owner.startsWith("net.minecraftforge.");
    }

    /** Every game use in the sources, resolved against the classpath (the mod's own version, where it compiles). */
    static List<Use> record(List<Path> sources, List<Path> classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("mmt uses needs a JDK (javac), not a JRE");
        DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(ignored, Locale.ROOT, StandardCharsets.UTF_8)) {
            StringBuilder cp = new StringBuilder();
            for (Path jar : classpath) cp.append(cp.length() == 0 ? "" : File.pathSeparator).append(jar);
            List<String> options = List.of("-proc:none", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-classpath", cp.toString());
            var units = files.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, files, ignored, options, null, units);
            Iterable<? extends CompilationUnitTree> trees = task.parse();
            try {
                task.analyze();
            } catch (Throwable broken) {
                // javac can stop part way through a source it cannot fully attribute (a missing annotation processor's
                // output); what it attributed before that is still resolved, and the rest is recorded as unresolved.
                StringWriter trace = new StringWriter();
                Throwable cause = broken.getCause() == null ? broken : broken.getCause();
                cause.printStackTrace(new PrintWriter(trace));
                System.err.println("mmt uses: javac stopped while attributing: " + trace.toString().lines().limit(14).toList());
            }
            ignored.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(5)
                .forEach(d -> System.err.println("mmt uses: unresolved: " + d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)));
            Trees resolved = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            List<Use> uses = new ArrayList<>();
            for (CompilationUnitTree unit : trees) new Scanner(unit, resolved, elements, types, uses).scan(unit, null);
            return uses;
        }
    }

    static final class Scanner extends TreePathScanner<Void, Void> {

        final CompilationUnitTree unit;
        final Trees trees;
        final Elements elements;
        final Types types;
        final List<Use> uses;
        final SourcePositions positions;

        Scanner(CompilationUnitTree unit, Trees trees, Elements elements, Types types, List<Use> uses) {
            this.unit = unit;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.uses = uses;
            this.positions = trees.getSourcePositions();
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            long end = positions.getEndPosition(unit, tree);
            note(getCurrentPath(), end - tree.getIdentifier().length(), end);
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            note(getCurrentPath(), positions.getStartPosition(unit, tree), positions.getEndPosition(unit, tree));
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            long start = positions.getStartPosition(unit, tree.getIdentifier());
            note(getCurrentPath(), start, positions.getEndPosition(unit, tree.getIdentifier()));
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            long end = positions.getEndPosition(unit, tree);
            note(getCurrentPath(), end - tree.getName().length(), end);
            return super.visitMemberReference(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            // An override of a game method names that method too: a rename or a new signature reaches it.
            Element self = trees.getElement(getCurrentPath());
            if (self instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD) {
                TypeElement owner = (TypeElement) method.getEnclosingElement();
                for (ExecutableElement overridden : overridden(method, owner)) {
                    String gameOwner = elements.getBinaryName((TypeElement) overridden.getEnclosingElement()).toString();
                    long start = positions.getStartPosition(unit, tree);
                    String text;
                    try {
                        text = unit.getSourceFile().getCharContent(true).toString();
                    } catch (IOException unreadable) {
                        break;
                    }
                    long at = text.indexOf(tree.getName().toString() + "(", (int) start);
                    if (at < 0) continue;
                    add("override", gameOwner, overridden.getSimpleName().toString(), descriptor(overridden), at, at + tree.getName().length());
                    break;
                }
            }
            return super.visitMethod(tree, unused);
        }

        List<ExecutableElement> overridden(ExecutableElement method, TypeElement owner) {
            List<ExecutableElement> found = new ArrayList<>();
            Deque<TypeMirror> todo = new ArrayDeque<>(types.directSupertypes(owner.asType()));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                if (!(types.asElement(todo.pop()) instanceof TypeElement type) || !seen.add(type.getQualifiedName().toString())) continue;
                if (game(elements.getBinaryName(type).toString())) {
                    for (Element member : type.getEnclosedElements()) {
                        if (member instanceof ExecutableElement candidate && elements.overrides(method, candidate, owner)) found.add(candidate);
                    }
                }
                todo.addAll(types.directSupertypes(type.asType()));
            }
            return found;
        }

        void note(TreePath path, long start, long end) {
            Element e = trees.getElement(path);
            if (e == null || start < 0 || end <= start) return;
            switch (e.getKind()) {
                case METHOD, CONSTRUCTOR -> {
                    ExecutableElement m = (ExecutableElement) e;
                    String owner = elements.getBinaryName((TypeElement) m.getEnclosingElement()).toString();
                    String kind = m.getKind() == ElementKind.CONSTRUCTOR ? "new" : "call";
                    String name = m.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : m.getSimpleName().toString();
                    if (game(owner)) add(kind, owner, name, descriptor(m), start, end);
                }
                case FIELD, ENUM_CONSTANT -> {
                    if (!(e.getEnclosingElement() instanceof TypeElement type)) return;
                    String owner = elements.getBinaryName(type).toString();
                    if (game(owner)) add("field", owner, e.getSimpleName().toString(), typeDescriptor(e.asType()), start, end);
                }
                case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
                    String owner = elements.getBinaryName((TypeElement) e).toString();
                    if (game(owner)) add("type", owner, "", "", start, end);
                }
                default -> {}
            }
        }

        void add(String kind, String owner, String name, String desc, long start, long end) {
            LineMap lines = unit.getLineMap();
            uses.add(new Use(Path.of(unit.getSourceFile().toUri()).toString(), lines.getLineNumber(start), lines.getColumnNumber(start), start, end, kind, owner, name, desc));
        }

        String descriptor(ExecutableElement m) {
            StringBuilder d = new StringBuilder("(");
            for (VariableElement p : m.getParameters()) d.append(typeDescriptor(p.asType()));
            return d.append(')').append(m.getKind() == ElementKind.CONSTRUCTOR ? "V" : typeDescriptor(m.getReturnType())).toString();
        }

        String typeDescriptor(TypeMirror t) {
            TypeMirror erased = types.erasure(t);
            return switch (erased.getKind()) {
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case SHORT -> "S";
                case CHAR -> "C";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
                case VOID -> "V";
                case ARRAY -> "[" + typeDescriptor(((ArrayType) erased).getComponentType());
                case DECLARED -> "L" + elements.getBinaryName((TypeElement) types.asElement(erased)).toString().replace('.', '/') + ";";
                default -> "Ljava/lang/Object;";
            };
        }
    }

    /**
     * What the change map says about each use: unchanged, renamed, moved, re-typed, replaced (the game's own code shows
     * the replacement), removed (nothing to follow) or a moved/removed class. Keys follow GameDiff's map: dotted owners,
     * $ for nested classes, JVM descriptors.
     */
    static Map<String, Object> join(List<Use> uses, Map<String, Object> map) {
        Map<String, String> change = new HashMap<>();
        for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(map.get(list))) {
            var m = Json.object(o);
            String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
            String what = list.equals("signatures") ? "re-typed" : list.equals("methodsMoved") ? "moved" : "renamed";
            change.putIfAbsent(key, what);
        }
        for (String list : List.of("rewrites", "chains", "argDefaults", "siblings")) for (Object o : Json.array(map.get(list))) {
            var m = Json.object(o);
            change.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""), "replaced");
        }
        for (Object o : Json.array(map.get("methodsRemoved"))) {
            var m = Json.object(o);
            change.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""), "removed");
        }
        for (Object o : Json.array(map.get("fields"))) {
            var m = Json.object(o);
            change.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", ""), "renamed");
        }
        for (Object o : Json.array(map.get("fieldsRemoved"))) {
            var m = Json.object(o);
            change.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", ""), "removed");
        }
        Map<String, String> classes = new HashMap<>();
        for (Object o : Json.array(map.get("classes"))) classes.put(Json.text(Json.object(o), "from", ""), "moved");
        for (Object o : Json.array(map.get("classRewrites"))) classes.putIfAbsent(Json.text(Json.object(o), "from", ""), "replaced");
        for (Object o : Json.array(map.get("classesRemoved"))) classes.putIfAbsent(String.valueOf(o), "removed");
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Map<String, Object>> members = new TreeMap<>();
        List<Object> changed = new ArrayList<>();
        for (Use u : uses) {
            String what;
            String member = u.owner() + (u.name().isEmpty() ? "" : "." + u.name() + (u.kind().equals("field") ? "" : u.desc()));
            if (u.kind().equals("type")) what = classes.getOrDefault(u.owner(), "unchanged");
            else if (u.kind().equals("field")) what = change.getOrDefault(u.owner() + "." + u.name(), classes.containsKey(u.owner()) ? "owner " + classes.get(u.owner()) : "unchanged");
            else what = change.getOrDefault(member, classes.containsKey(u.owner()) ? "owner " + classes.get(u.owner()) : "unchanged");
            counts.merge(what, 1, Integer::sum);
            if (what.equals("unchanged")) continue;
            members.computeIfAbsent(member, k -> Json.map("member", k, "change", what, "uses", 0));
            members.get(member).put("uses", ((Number) members.get(member).get("uses")).intValue() + 1);
            changed.add(Json.map("file", u.file(), "line", u.line(), "column", u.column(), "kind", u.kind(), "member", member, "change", what));
        }
        return Json.map("uses", uses.size(), "byChange", counts, "changedMembers", new ArrayList<>(members.values()), "changedUses", changed);
    }

    /** mmt uses: the mod's game uses on its own version, joined with --map when given. */
    static Map<String, Object> run(Build build, Map<String, Object> model, Mmt.Options options) throws Exception {
        // The mod's own compile runs once on its version, recording the sources and classpath javac is given (the init
        // script's mmt.recordCompile): what the build model names can differ (NeoGradle's placeholder game jar), and the
        // compile proves the original builds.
        Path records = build.project.state().resolve("compile-records");
        if (Files.isDirectory(records)) LibraryPort.deleteTree(records);
        List<String> tasks = new ArrayList<>(build.tasks(model, "classes"));
        tasks.add(0, "-Dmmt.recompile=true");
        tasks.add(0, "-Dmmt.recordCompile=" + records);
        var compiled = build.gradle(tasks, "uses-compile");
        Set<Path> sourceSet = new LinkedHashSet<>(), entries = new LinkedHashSet<>();
        if (Files.isDirectory(records)) try (var list = Files.list(records)) {
            for (Path record : list.filter(f -> f.toString().endsWith(".json")).toList()) {
                var r = Json.read(record);
                for (String f : ProjectModel.strings(r.get("sources"))) if (f.endsWith(".java")) sourceSet.add(Path.of(f));
                for (String e : ProjectModel.strings(r.get("classpath"))) if (Files.exists(Path.of(e))) entries.add(Path.of(e));
            }
        }
        List<Path> sources = new ArrayList<>(sourceSet), classpath = new ArrayList<>(entries);
        if (sources.isEmpty() || classpath.isEmpty()) throw new IOException(
            "The mod's own compile recorded no Java sources or classpath (" + sources.size() + " sources, " + classpath.size()
                + " entries); its build log: " + compiled.log()
        );
        List<Use> uses = record(sources, classpath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", sources.size());
        if (options.values.containsKey("map")) result.putAll(join(uses, Json.read(Path.of(options.get("map", "")))));
        else result.put("uses", uses.size());
        Path out = build.project.state().resolve("uses.json");
        Files.createDirectories(out.getParent());
        List<Object> all = new ArrayList<>();
        for (Use u : uses) all.add(Json.map("file", u.file(), "line", u.line(), "column", u.column(), "start", u.start(), "end", u.end(),
            "kind", u.kind(), "owner", u.owner(), "name", u.name(), "desc", u.desc()));
        Json.save(out, Json.map("uses", all));
        result.put("written", out.toString());
        result.put("ok", true);
        return result;
    }
}
