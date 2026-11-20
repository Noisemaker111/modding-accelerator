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
 * mmt port-plan: every place in a mod's code that touches game or loader code, found by the compiler on the mod's own
 * version (each call, field, class, constructor and override, with exactly which game method or field it is), and what
 * the target version did to each: renamed, moved to another class, takes different arguments, the game shows the
 * replacement, or removed with nothing replacing it. It comes before any port and reads no compiler errors, so the same
 * plan works for every mod and every naming scheme.
 */
final class PortPlan {

    private PortPlan() {}

    /** One place in the mod that touches game code: kind is call, field, class, new or override; start/end bound the name. */
    record Place(String file, long line, long column, long start, long end, String kind, String owner, String name, String desc) {}

    static boolean game(String owner) {
        return owner.startsWith("net.minecraft.") || owner.startsWith("com.mojang.") || owner.startsWith("net.neoforged.")
            || owner.startsWith("net.fabricmc.") || owner.startsWith("net.minecraftforge.");
    }

    /** Every game use in the sources, resolved against the classpath (the mod's own version, where it compiles). */
    static List<Place> record(List<Path> sources, List<Path> classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("mmt port-plan needs a JDK (javac), not a JRE");
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
                System.err.println("mmt port-plan: javac stopped while attributing: " + trace.toString().lines().limit(14).toList());
            }
            ignored.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(5)
                .forEach(d -> System.err.println("mmt port-plan: unresolved: " + d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)));
            Trees resolved = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            List<Place> places = new ArrayList<>();
            for (CompilationUnitTree unit : trees) new Scanner(unit, resolved, elements, types, places).scan(unit, null);
            return places;
        }
    }

    static final class Scanner extends TreePathScanner<Void, Void> {

        final CompilationUnitTree unit;
        final Trees trees;
        final Elements elements;
        final Types types;
        final List<Place> places;
        final SourcePositions positions;

        Scanner(CompilationUnitTree unit, Trees trees, Elements elements, Types types, List<Place> places) {
            this.unit = unit;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.places = places;
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
                    if (game(owner)) add("class", owner, "", "", start, end);
                }
                default -> {}
            }
        }

        void add(String kind, String owner, String name, String desc, long start, long end) {
            LineMap lines = unit.getLineMap();
            places.add(new Place(Path.of(unit.getSourceFile().toUri()).toString(), lines.getLineNumber(start), lines.getColumnNumber(start), start, end, kind, owner, name, desc));
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

    static final String RENAMED = "renamed";
    static final String MOVED = "moved to another class";
    static final String ARGUMENTS = "takes different arguments";
    static final String KNOWN = "the game shows the replacement";
    static final String REMOVED = "removed, nothing replaces it";

    /**
     * What the target version did to the game code each place touches, read from the game's change map (GameDiff's keys:
     * dotted classes with $ for nested ones, JVM descriptors). A place whose class moved or went is labelled by its class.
     */
    static Map<String, Object> join(List<Place> places, Map<String, Object> map) {
        Map<String, String> verdict = new HashMap<>();
        for (String list : List.of("methods", "methodsMoved", "signatures")) for (Object o : Json.array(map.get(list))) {
            var m = Json.object(o);
            String key = Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", "");
            verdict.putIfAbsent(key, list.equals("signatures") ? ARGUMENTS : list.equals("methodsMoved") ? MOVED : RENAMED);
        }
        for (String list : List.of("rewrites", "chains", "argDefaults", "siblings")) for (Object o : Json.array(map.get(list))) {
            var m = Json.object(o);
            verdict.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""), KNOWN);
        }
        for (Object o : Json.array(map.get("methodsRemoved"))) {
            var m = Json.object(o);
            verdict.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", "") + Json.text(m, "desc", ""), REMOVED);
        }
        for (Object o : Json.array(map.get("fields"))) {
            var m = Json.object(o);
            verdict.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", ""), RENAMED);
        }
        for (Object o : Json.array(map.get("fieldsRemoved"))) {
            var m = Json.object(o);
            verdict.putIfAbsent(Json.text(m, "owner", "") + "." + Json.text(m, "from", ""), REMOVED);
        }
        Map<String, String> classes = new HashMap<>();
        for (Object o : Json.array(map.get("classes"))) classes.put(Json.text(Json.object(o), "from", ""), MOVED);
        for (Object o : Json.array(map.get("classRewrites"))) classes.putIfAbsent(Json.text(Json.object(o), "from", ""), KNOWN);
        for (Object o : Json.array(map.get("classesRemoved"))) classes.putIfAbsent(String.valueOf(o), REMOVED);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Map<String, Object>> targets = new TreeMap<>();
        List<Object> places2 = new ArrayList<>();
        for (Place p : places) {
            String target = p.owner() + (p.name().isEmpty() ? "" : "." + p.name() + (p.kind().equals("field") ? "" : p.desc()));
            String byClass = classes.containsKey(p.owner()) ? "its class: " + classes.get(p.owner()) : null;
            String what = p.kind().equals("class") ? classes.get(p.owner())
                : verdict.getOrDefault(p.kind().equals("field") ? p.owner() + "." + p.name() : target, byClass);
            if (what == null) continue;
            counts.merge(what, 1, Integer::sum);
            targets.computeIfAbsent(target, k -> Json.map("gameCode", k, "change", what, "places", 0));
            targets.get(target).put("places", ((Number) targets.get(target).get("places")).intValue() + 1);
            places2.add(Json.map("file", p.file(), "line", p.line(), "column", p.column(), "kind", p.kind(), "gameCode", target, "change", what));
        }
        return Json.map("placesTouchingGameCode", places.size(), "placesToChange", places2.size(), "byChange", counts,
            "changedGameCode", new ArrayList<>(targets.values()), "places", places2);
    }

    /** mmt port-plan: the places in the mod that touch game code, and, with --map, what the target did to each. */
    static Map<String, Object> run(Build build, Map<String, Object> model, Mmt.Options options) throws Exception {
        // The mod's own compile runs once on its version, recording the sources and classpath javac is given (the init
        // script's mmt.recordCompile): what the build model names can differ (NeoGradle's placeholder game jar), and the
        // compile proves the original builds. Keep Gradle authoritative but skip the refork when nothing that determines
        // the records changed: hash the build inputs AND the records' bytes, the same contract fast keeps for compiled
        // outputs (FastLane). The touch-point scan below always re-runs on the current sources, so a hit skips only Gradle.
        Path records = build.project.state().resolve("compile-records");
        Path stamp = build.project.state().resolve("port-plan-stamp.json");
        String key = Fingerprints.build(model, build.project, build.init);
        boolean reuse = false;
        if (Files.isDirectory(records) && Files.exists(stamp) && !options.has("refresh")) {
            var cached = Json.read(stamp);
            reuse = key.equals(cached.get("inputs")) && Fingerprints.files(List.of(records)).equals(cached.get("outputs"));
        }
        Run.Result compiled = null;
        if (!reuse) {
            if (Files.isDirectory(records)) LibraryPort.deleteTree(records);
            List<String> tasks = new ArrayList<>(build.tasks(model, "classes"));
            tasks.add(0, "-Dmmt.recompile=true");
            tasks.add(0, "-Dmmt.recordCompile=" + records);
            compiled = build.gradle(tasks, "port-plan-compile");
        }
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
                + " entries); its build log: " + (compiled == null ? "reused compile-records" : compiled.log())
        );
        if (!reuse) Json.save(stamp, Json.map("inputs", key, "outputs", Fingerprints.files(List.of(records))));
        List<Place> places = record(sources, classpath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compileRecords", reuse ? "cached" : "recorded");
        result.put("sourceFiles", sources.size());
        if (options.values.containsKey("map")) result.putAll(join(places, Json.read(Path.of(options.get("map", "")))));
        else result.put("placesTouchingGameCode", places.size());
        Path out = build.project.state().resolve("port-plan.json");
        Files.createDirectories(out.getParent());
        List<Object> all = new ArrayList<>();
        for (Place p : places) all.add(Json.map("file", p.file(), "line", p.line(), "column", p.column(), "start", p.start(), "end", p.end(),
            "kind", p.kind(), "owner", p.owner(), "name", p.name(), "desc", p.desc()));
        Json.save(out, Json.map("places", all));
        result.put("written", out.toString());
        result.put("ok", true);
        return result;
    }
}
