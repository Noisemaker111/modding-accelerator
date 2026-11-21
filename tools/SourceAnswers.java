import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/**
 * SourceAnswers - what JarPort writes into a class, written into the mod's source at the places the compiler resolved.
 *
 *   java SourceAnswers --map route.json --fixes knowledge/fixes.json --from 1.21.1 --to 1.21.2 --loader neoforge
 *       --sources sources.txt --classpath classpath.txt --report out.json [--dry-run]
 *
 * The answers are the ones JarPort uses, from the same files: the route map's "learned" list (what the game's own code
 * computes in place of a member, GameDiff's Learner) and every port rule's "inClass" (FixExpr), both Java expressions
 * over the old member's operands ($0 the receiver, $1... the arguments). javac attributes the mod's sources against the
 * classpath its own build used on the release it was written for, so each call, field read and constructor names exactly
 * the game member it uses (LocalPlayer.sendSystemMessage is not ServerPlayer.sendSystemMessage). A use of a member the new
 * release no longer has as declared is replaced by the first answer JarPort itself would compile there (JarPort.answerLinks,
 * against the new release's classes), with the use's own receiver and arguments, inner uses first. Renames and moves stay
 * the typed fixer's (VersionMap), which reads the same map; nothing is written for a member with no answer that links.
 */
final class SourceAnswers {

    /** One answer for a member: name matches the member's name (a rule's may be a regex), args a descriptor's start or null. */
    record Answer(String source, Pattern name, String args, String expr) {}

    /** One use to replace: its span, the expression to write, and the trees standing for $0, $1, ... (null where absent). */
    record Site(int start, int end, String expr, List<Tree> operands, String member, String source) {}

    final Map<String, List<Answer>> answers = new HashMap<>(); // binary owner name -> answers, learned first
    JarPort port; // the new release as JarPort reads it: whether a member is kept, whether an answer links
    final Set<String> renamed = new HashSet<>(); // members the typed fixer carries by the route map's renames and moves

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dry-run")) {
                flags.add("dry-run");
            } else {
                o.put(args[i].substring(2), args[++i]);
            }
        }
        SourceAnswers s = new SourceAnswers();
        String loader = o.getOrDefault("loader", "");
        s.port = new JarPort(Exposure.snap(o.get("from"), loader.isEmpty() ? null : loader), Exposure.snap(o.get("to"), loader.isEmpty() ? null : loader), Path.of(o.get("map")));
        if (!loader.isEmpty()) {
            s.port.loader = loader;
        }
        s.load(Path.of(o.get("map")), o.containsKey("fixes") ? Path.of(o.get("fixes")) : null, o.getOrDefault("from", ""), o.getOrDefault("to", ""), o.getOrDefault("loader", ""));
        List<Path> sources = lines(Path.of(o.get("sources"))).stream().map(Path::of).toList();
        List<Path> classpath = lines(Path.of(o.get("classpath"))).stream().map(Path::of).toList();
        Map<String, Object> report = s.apply(sources, classpath, flags.contains("dry-run"));
        String json = Json.write(report);
        if (o.containsKey("report")) {
            Files.writeString(Path.of(o.get("report")), json);
        }
        System.out.println(json);
    }

    static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    // ---- The answers, chosen as JarPort chooses them.

    void load(Path map, Path book, String from, String to, String loader) throws IOException {
        Map<String, Object> raw = Json.read(map);
        for (String list : List.of("methods", "methodsMoved", "fields")) {
            for (Object x : Json.array(raw.getOrDefault(list, List.of()))) {
                var w = Json.object(x);
                renamed.add(Json.text(w, "owner", "") + "." + Json.text(w, "from", "") + (list.equals("fields") ? "" : Json.text(w, "desc", "")));
            }
        }
        // What the game's own code computes in place of a member: a majority of its uses agree (JarPort.majority).
        for (Object x : Json.array(raw.getOrDefault("learned", List.of()))) {
            var w = Json.object(x);
            String expr = Json.text(w, "expr", ""), name = Json.text(w, "from", ""), desc = Json.text(w, "desc", "");
            if (w.get("then") != null || expr.isEmpty() || name.endsWith("=") || !majority(w)) {
                continue;
            }
            String owner = Json.text(w, "owner", "");
            answers.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Answer("game " + Json.text(w, "step", ""), Pattern.compile(Pattern.quote(name)),
                desc.isEmpty() ? null : desc.substring(0, desc.indexOf(')') + 1), expr));
        }
        // Every port rule's inClass whose update lies on this port (JarPort.fixes and covers).
        if (book != null && Files.isRegularFile(book)) {
            for (Object x : Json.array(Json.read(book).get("rules"))) {
                var rule = Json.object(x);
                if (!covers(Json.text(rule, "jump", ""), Json.text(rule, "loader", ""), from, to, loader)) {
                    continue;
                }
                for (var e : Json.object(rule.getOrDefault("inClass", Map.of())).entrySet()) {
                    int dot = e.getKey().lastIndexOf('.');
                    if (dot < 0) {
                        continue;
                    }
                    String rest = e.getKey().substring(dot + 1);
                    if (Character.isUpperCase(rest.isEmpty() ? 'a' : rest.charAt(0)) && !rest.contains("(") && String.valueOf(e.getValue()).matches("[\\w.$]+")) {
                        continue; // a class mapped to the class its values become: the typed fixer's class map
                    }
                    Matcher d = DESCRIBED.matcher(rest);
                    boolean described = d.matches();
                    answers.computeIfAbsent(e.getKey().substring(0, dot), k -> new ArrayList<>()).add(new Answer(Json.text(rule, "id", ""),
                        Pattern.compile(described ? Pattern.quote(d.group(1)) : rest), described ? d.group(2) : null, String.valueOf(e.getValue())));
                }
            }
        }
    }

    static final Pattern DESCRIBED = Pattern.compile("([\\w$<>]+)(\\((?:\\[*(?:[ZBCSIJFD]|L[\\w/$]+;))*\\)\\S*)");

    static boolean majority(Map<String, Object> w) {
        Object votes = w.get("votes"), of = w.get("of");
        return !(votes instanceof Number v) || !(of instanceof Number n) || v.intValue() * 2 > n.intValue();
    }

    static boolean covers(String jump, String ruleLoader, String from, String to, String loader) {
        if (!ruleLoader.isEmpty() && !ruleLoader.equals(loader)) {
            return false;
        }
        String[] range = jump.replaceAll("\\(.*", "").trim().split("\\s*->\\s*");
        if (range.length != 2 || from.isEmpty() || to.isEmpty()) {
            return true;
        }
        return JarPort.compareVersions(from, range[1]) < 0 && JarPort.compareVersions(to, range[0]) > 0;
    }

    /**
     * The expression for a use of owner.name (desc: the method's, or the field's type), when the new release no longer has
     * the member as the old one declared it, nothing renames it, and an answer compiles against the new release for this
     * use's operands, exactly as JarPort decides it; null otherwise.
     */
    String[] answer(String owner, String name, String desc, boolean field, boolean statik) {
        String internal = owner.replace('.', '/');
        if (renamed.contains(owner + "." + name + (field ? "" : desc)) || port.kept(internal, field, name, desc)) {
            return null;
        }
        String self = "L" + internal + ";";
        String opDesc = name.equals("<init>") ? desc.substring(0, desc.indexOf(')') + 1) + self
            : field ? (statik ? "()" : "(" + self + ")") + desc : statik ? desc : "(" + self + desc.substring(1);
        for (Answer a : answers.getOrDefault(owner, List.of())) {
            Matcher n = a.name().matcher(name);
            if (!n.matches() || a.args() != null && (field || !desc.startsWith(a.args()))) {
                continue;
            }
            String expr;
            try {
                expr = FixExpr.fill(a.expr(), n);
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (port.answerLinks(expr, opDesc, !statik && !name.equals("<init>"))) {
                return new String[] { expr, a.source() };
            }
        }
        return null;
    }

    // ---- The uses, resolved by javac on the mod's own release.

    Map<String, Object> apply(List<Path> sources, List<Path> classpath, boolean dryRun) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
        Map<String, Integer> bySource = new TreeMap<>();
        int files = 0, replaced = 0;
        List<String> examples = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, Locale.ROOT, StandardCharsets.UTF_8)) {
            StringBuilder cp = new StringBuilder();
            for (Path jar : classpath) {
                cp.append(cp.length() == 0 ? "" : File.pathSeparator).append(jar);
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fm, ignored,
                List.of("-proc:none", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-classpath", cp.toString()), null, fm.getJavaFileObjectsFromPaths(sources));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            try {
                task.analyze();
            } catch (Throwable partial) {
                // What javac attributed before it stopped is still resolved; the rest has no element and is left alone.
            }
            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            for (CompilationUnitTree unit : units) {
                String text = unit.getSourceFile().getCharContent(true).toString();
                Finder finder = new Finder(unit, trees, elements, types);
                finder.scan(unit, null);
                if (finder.sites.isEmpty()) {
                    continue;
                }
                String out = rewrite(text, finder.sites, trees.getSourcePositions(), unit);
                if (out.equals(text)) {
                    continue;
                }
                files++;
                for (Site s : finder.sites) {
                    // An answer that writes the use as it was (a field whose type changed, read the same way) changes nothing.
                    if (render(text, s, finder.sites, trees.getSourcePositions(), unit).equals(text.substring(s.start(), s.end()))) {
                        continue;
                    }
                    replaced++;
                    bySource.merge(s.source().startsWith("game ") ? "learned from the game's code" : "rule " + s.source(), 1, Integer::sum);
                    if (examples.size() < 12) {
                        examples.add(Path.of(unit.getSourceFile().toUri()).getFileName() + ": " + s.member() + " -> " + s.expr());
                    }
                }
                if (!dryRun) {
                    Files.writeString(Path.of(unit.getSourceFile().toUri()), out, StandardCharsets.UTF_8);
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("answers", answers.values().stream().mapToInt(List::size).sum());
        report.put("files", files);
        report.put("replaced", replaced);
        report.put("bySource", bySource);
        report.put("examples", examples);
        return report;
    }

    /** The source with every site replaced, inner sites first: an operand that is itself a site is written as its replacement. */
    static String rewrite(String text, List<Site> sites, SourcePositions positions, CompilationUnitTree unit) {
        List<Site> outer = new ArrayList<>();
        for (Site s : sites) {
            if (sites.stream().noneMatch(o -> o != s && o.start() <= s.start() && s.end() <= o.end())) {
                outer.add(s);
            }
        }
        outer.sort(Comparator.comparingInt(Site::start));
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (Site s : outer) {
            out.append(text, at, s.start()).append(render(text, s, sites, positions, unit));
            at = s.end();
        }
        return out.append(text.substring(at)).toString();
    }

    static String render(String text, Site site, List<Site> sites, SourcePositions positions, CompilationUnitTree unit) {
        String expr = site.expr().replaceAll("(?<=[\\w])\\$(?=[A-Za-z_])", "."); // nested classes as source names them
        Matcher m = Pattern.compile("\\$(\\d+)").matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            Tree t = i < site.operands().size() ? site.operands().get(i) : null;
            String operand = t == null ? (i == 0 ? "this" : "null") : span(text, (int) positions.getStartPosition(unit, t), (int) positions.getEndPosition(unit, t), sites, positions, unit);
            // An operand needs parentheses only where the expression goes on from it ($0.m(), $1[i]); as an argument it stands alone.
            boolean continued = m.end() < expr.length() && (expr.charAt(m.end()) == '.' || expr.charAt(m.end()) == '[');
            if (t != null && continued && !simple(t)) {
                operand = "(" + operand + ")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(operand));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The text of start..end with the sites inside it replaced. */
    static String span(String text, int start, int end, List<Site> sites, SourcePositions positions, CompilationUnitTree unit) {
        List<Site> inside = new ArrayList<>();
        for (Site s : sites) {
            if (start <= s.start() && s.end() <= end) {
                inside.add(s);
            }
        }
        if (inside.isEmpty()) {
            return text.substring(start, end);
        }
        List<Site> top = new ArrayList<>();
        for (Site s : inside) {
            if (inside.stream().noneMatch(o -> o != s && o.start() <= s.start() && s.end() <= o.end())) {
                top.add(s);
            }
        }
        top.sort(Comparator.comparingInt(Site::start));
        StringBuilder out = new StringBuilder();
        int at = start;
        for (Site s : top) {
            out.append(text, at, s.start()).append(render(text, s, sites, positions, unit));
            at = s.end();
        }
        return out.append(text, at, end).toString();
    }

    /** An operand that needs no parentheses wherever an expression puts it. */
    static boolean simple(Tree t) {
        return switch (t.getKind()) {
            case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, PARENTHESIZED, ARRAY_ACCESS, NEW_CLASS, INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL,
                DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, NULL_LITERAL -> true;
            default -> false;
        };
    }

    final class Finder extends TreePathScanner<Void, Void> {

        final CompilationUnitTree unit;
        final Trees trees;
        final Elements elements;
        final Types types;
        final SourcePositions positions;
        final List<Site> sites = new ArrayList<>();

        Finder(CompilationUnitTree unit, Trees trees, Elements elements, Types types) {
            this.unit = unit;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.positions = trees.getSourcePositions();
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            Tree select = tree.getMethodSelect();
            if (trees.getElement(new TreePath(getCurrentPath(), select)) instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                String owner = elements.getBinaryName((TypeElement) m.getEnclosingElement()).toString();
                Tree receiver = select instanceof MemberSelectTree s ? s.getExpression() : null;
                boolean statik = m.getModifiers().contains(Modifier.STATIC);
                String[] a = answer(owner, m.getSimpleName().toString(), descriptor(m), false, statik);
                if (a != null && (statik || receiver != null || ownReceiver(m))) {
                    List<Tree> operands = new ArrayList<>();
                    operands.add(statik ? null : receiver);
                    operands.addAll(tree.getArguments());
                    add(tree, a, operands, owner + "." + m.getSimpleName() + descriptor(m), m.getSimpleName().toString(), statik);
                }
            }
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            if (tree.getClassBody() == null && trees.getElement(getCurrentPath()) instanceof ExecutableElement c && c.getKind() == ElementKind.CONSTRUCTOR) {
                String owner = elements.getBinaryName((TypeElement) c.getEnclosingElement()).toString();
                String[] a = answer(owner, "<init>", descriptor(c), false, true);
                if (a != null && tree.getEnclosingExpression() == null) {
                    List<Tree> operands = new ArrayList<>();
                    operands.add(null);
                    operands.addAll(tree.getArguments());
                    add(tree, a, operands, owner + ".<init>" + descriptor(c), "<init>", true);
                }
            }
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            field(tree, tree.getExpression());
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            field(tree, null);
            return super.visitIdentifier(tree, unused);
        }

        /** A read of a game field: not a call's name, not written, not a constant a case label or an annotation needs. */
        void field(ExpressionTree tree, Tree receiver) {
            if (!(trees.getElement(getCurrentPath()) instanceof VariableElement f) || f.getKind() != ElementKind.FIELD && f.getKind() != ElementKind.ENUM_CONSTANT
                || !(f.getEnclosingElement() instanceof TypeElement type)) {
                return;
            }
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            if (parent instanceof MethodInvocationTree call && call.getMethodSelect() == tree
                || parent instanceof AssignmentTree as && as.getVariable() == tree
                || parent instanceof CompoundAssignmentTree ca && ca.getVariable() == tree
                || parent instanceof UnaryTree u && (u.getKind().name().contains("CREMENT"))
                || parent instanceof ImportTree) {
                return;
            }
            for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof CaseTree || p.getLeaf() instanceof AnnotationTree) {
                    return;
                }
            }
            String owner = elements.getBinaryName(type).toString();
            boolean statik = f.getModifiers().contains(Modifier.STATIC);
            String[] a = answer(owner, f.getSimpleName().toString(), typeDescriptor(f.asType()), true, statik);
            if (a != null && (statik || receiver != null || ownReceiver(f))) {
                List<Tree> operands = new ArrayList<>();
                operands.add(statik ? null : receiver);
                add(tree, a, operands, owner + "." + f.getSimpleName(), f.getSimpleName().toString(), statik);
            }
        }

        /** An unqualified use of an instance member is this's only when the class written around it is the member's owner or extends it. */
        boolean ownReceiver(Element member) {
            for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof ClassTree && trees.getElement(p) instanceof TypeElement around) {
                    return types.isSubtype(types.erasure(around.asType()), types.erasure(member.getEnclosingElement().asType()));
                }
            }
            return false;
        }

        void add(Tree tree, String[] a, List<Tree> operands, String member, String name, boolean statik) {
            int start = (int) positions.getStartPosition(unit, tree), end = (int) positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return;
            }
            String expr = a[0];
            // An answer that needs an operand the use does not have (a receiver for a static use) is not written.
            Matcher used = Pattern.compile("\\$(\\d+)").matcher(expr);
            while (used.find()) {
                int i = Integer.parseInt(used.group(1));
                if (i >= operands.size() || i == 0 && statik) {
                    return;
                }
            }
            sites.add(new Site(start, end, expr, operands, member, a[1]));
        }

        String descriptor(ExecutableElement m) {
            StringBuilder d = new StringBuilder("(");
            for (VariableElement p : m.getParameters()) {
                d.append(typeDescriptor(p.asType()));
            }
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
}
