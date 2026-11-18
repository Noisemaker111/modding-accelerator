import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import javax.lang.model.element.Modifier;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Lowers modern Java sources to the language level a legacy target compiles: records become final classes,
 * pattern instanceof becomes a test plus a cast, switch and pattern switches become statements, text blocks
 * become concatenations, sealed and permits are stripped, and var becomes the inferred type. Edits are spans of
 * the original source, so comments, formatting, and everything the transform does not touch survive byte for
 * byte. Anything without a faithful lowering is left alone and keeps failing loudly.
 */
final class Desugar {
    /** What javac prints when a legacy release rejects a modern feature. */
    static final String LANGUAGE_MARKER = "not supported in -source 8";

    /** A rewrite of one source span, applied to the original text. */
    record Edit(int start, int end, String replacement) {}

    private Desugar() {}

    /** True when the log shows the legacy release rejecting modern syntax. */
    static boolean needsDesugar(String log) {
        return log != null && log.contains(LANGUAGE_MARKER);
    }

    /**
     * Desugars one file using the target's compile classpath for the types var needs. Returns the new source,
     * which equals the input when nothing applies or when no JDK compiler is available to parse it.
     */
    static String desugarFile(Path file, List<String> classpath) throws IOException {
        String source = Files.readString(file);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return source;
        }
        String current = source;
        for (int pass = 0; pass < 6; pass++) {
            String next = desugarPass(file, current, classpath, compiler);
            if (next == null || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * One parse, analyze, collect, and apply cycle. Returns null when the file cannot be parsed, so the caller
     * keeps the original.
     */
    private static String desugarPass(Path file, String source, List<String> classpath, JavaCompiler compiler) {
        StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null);
        try {
            List<String> options = new ArrayList<>(List.of("-proc:none"));
            if (classpath != null && !classpath.isEmpty()) {
                options.add("-classpath");
                options.add(String.join(java.io.File.pathSeparator, classpath));
            }
            Path probe = writeProbe(file, source);
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(probe.toString());
            DiagnosticCollector<JavaFileObject> quiet = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) compiler.getTask(null, files, quiet, options, null, units);
            CompilationUnitTree unit = task.parse().iterator().next();
            try {
                task.analyze();
            } catch (Exception ignored) {
                // Attribution is best effort: missing game classes fail the analysis, never the parse.
            }
            Collector collector = new Collector(task, unit, source);
            collector.scan(unit, null);
            List<Edit> edits = collector.edits;
            if (edits.isEmpty()) {
                return source;
            }
            return applyInnerFirst(source, edits);
        } catch (Exception failure) {
            return null;
        } finally {
            try {
                files.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Parses from a probe file so the unit keeps the original file name in diagnostics. The probe lives beside
     * scratch state, never in the repo.
     */
    private static Path writeProbe(Path file, String source) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mmt-desugar");
        Files.createDirectories(dir);
        String name = file.getFileName().toString();
        if (!name.endsWith(".java")) {
            name = name + ".java";
        }
        Path probe = dir.resolve(name);
        Files.writeString(probe, source);
        return probe;
    }

    /**
     * Applies non-overlapping edits, innermost first. Overlapping pairs keep the inner edit; the outer one is
     * dropped and re-derived on the next pass over the new text.
     */
    static String applyInnerFirst(String source, List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).reversed().thenComparingInt(Edit::end));
        List<Edit> kept = new ArrayList<>();
        outer:
        for (Edit edit : ordered) {
            for (Edit keptEdit : kept) {
                if (edit.start() < keptEdit.end() && keptEdit.start() < edit.end()) {
                    continue outer;
                }
            }
            kept.add(edit);
        }
        StringBuilder out = new StringBuilder(source);
        kept.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : kept) {
            out.replace(edit.start(), edit.end(), edit.replacement());
        }
        return out.toString();
    }

    /** Masks strings, chars, line comments, and block comments with spaces, keeping every offset. */
    static String maskLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < source.length()) {
                    char d = source.charAt(i);
                    out.append(' ');
                    i++;
                    if (d == '\\' && i < source.length()) {
                        out.append(' ');
                        i++;
                    } else if (d == quote) {
                        break;
                    } else if (d == '\n' && quote == '\'') {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < source.length() && !(source.charAt(i) == '/' && i > 0 && source.charAt(i - 1) == '*')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Walks one parsed file and collects every faithful lowering as a source-span edit. */
    private static final class Collector extends TreePathScanner<Void, Void> {
        private final JavacTask task;
        private final Trees trees;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String source;
        private final String masked;
        private final List<Edit> edits = new ArrayList<>();
        private int freshNames = 0;

        Collector(JavacTask task, CompilationUnitTree unit, String source) {
            this.task = task;
            this.trees = Trees.instance(task);
            this.unit = unit;
            this.positions = trees.getSourcePositions();
            this.source = source;
            this.masked = maskLiterals(source);
        }

        private int start(Tree tree) {
            return (int) positions.getStartPosition(unit, tree);
        }

        private int end(Tree tree) {
            return (int) positions.getEndPosition(unit, tree);
        }

        private String text(Tree tree) {
            if (!spanOk(tree)) {
                return null;
            }
            return source.substring(start(tree), end(tree));
        }

        /** True when a tree names a real source span; error-recovery copies may carry none. */
        private boolean spanOk(Tree tree) {
            if (tree == null) {
                return false;
            }
            int from = start(tree);
            int to = end(tree);
            return from >= 0 && to >= from && to <= source.length();
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            if (tree.getKind() == Tree.Kind.RECORD) {
                recordEdit(tree);
            } else {
                sealedEdit(tree);
            }
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            varEdit(tree);
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            interfacePrivateEdit(tree);
            return super.visitMethod(tree, unused);
        }

        /**
         * Private interface methods predate the legacy release; a private static helper becomes a
         * plain static one and a private instance helper becomes a default method. Both keep their
         * bodies and call sites, widening only the visibility. Bodiless declarations are already
         * legacy-legal and stay untouched.
         */
        private void interfacePrivateEdit(MethodTree tree) {
            if (tree.getBody() == null) {
                return;
            }
            boolean isPrivate = false;
            boolean isStatic = false;
            for (Modifier flag : tree.getModifiers().getFlags()) {
                if (flag == Modifier.PRIVATE) {
                    isPrivate = true;
                }
                if (flag == Modifier.STATIC) {
                    isStatic = true;
                }
            }
            if (!isPrivate) {
                return;
            }
            TreePath parent = getCurrentPath().getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof ClassTree)) {
                return;
            }
            if (((ClassTree) parent.getLeaf()).getKind() != Tree.Kind.INTERFACE) {
                return;
            }
            int from = start(tree.getModifiers());
            int to = end(tree.getModifiers());
            if (from < 0 || to < 0 || to < from) {
                return;
            }
            String modifierText = masked.substring(from, to);
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\bprivate\\b").matcher(modifierText);
            if (!match.find()) {
                return;
            }
            if (isStatic) {
                int end = from + match.end();
                if (end < masked.length() && masked.charAt(end) == ' ') {
                    end++;
                }
                edits.add(new Edit(from + match.start(), end, ""));
            } else {
                edits.add(new Edit(from + match.start(), from + match.end(), "default"));
            }
        }

        @Override
        public Void visitInstanceOf(InstanceOfTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            patternEdit(tree);
            return super.visitInstanceOf(tree, unused);
        }

        @Override
        public Void visitSwitch(SwitchTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            patternSwitchEdit(tree);
            arrowStatementEdit(tree);
            return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            switchExpressionEdit(tree);
            return super.visitSwitchExpression(tree, unused);
        }

        @Override
        public Void visitLiteral(LiteralTree tree, Void unused) {
            if (!spanOk(tree)) {
                return null;
            }
            textBlockEdit(tree);
            return super.visitLiteral(tree, unused);
        }

        /** Records become final classes with fields, a canonical constructor, accessors, and value semantics. */
        private void recordEdit(ClassTree tree) {
            String name = tree.getSimpleName().toString();
            MethodTree compact = null;
            boolean explicitCanonical = false;
            for (Tree member : tree.getMembers()) {
                if (member.getKind() != Tree.Kind.METHOD) {
                    continue;
                }
                MethodTree method = (MethodTree) member;
                if (!spanOk(method) || method.getReturnType() != null) {
                    continue;
                }
                String methodName = method.getName().toString();
                if (!methodName.equals(name) && !methodName.contentEquals("<init>")) {
                    continue;
                }
                if (isCompactConstructor(method, name)) {
                    compact = method;
                } else if (isCanonicalSignature(method, tree)) {
                    explicitCanonical = true;
                }
            }
            if (compact != null && !explicitCanonical) {
                headerEdit(tree, name, compact, true, recordNeedsStatic());
            } else {
                headerEdit(tree, name, null, !explicitCanonical, recordNeedsStatic());
            }
        }

        /** A record nested in a class, enum, or record is implicitly static; the lowered class must say so,
         * or its static members are illegal. Interface members are static already, locals stay inner. */
        private boolean recordNeedsStatic() {
            TreePath parent = getCurrentPath().getParentPath();
            if (parent == null) {
                return false;
            }
            Tree.Kind kind = parent.getLeaf().getKind();
            return kind == Tree.Kind.CLASS || kind == Tree.Kind.ENUM || kind == Tree.Kind.RECORD;
        }

        /** Record components are the non-static member variables; static fields are ordinary members. */
        private List<VariableTree> recordComponents(ClassTree tree) {
            List<VariableTree> components = new ArrayList<>();
            for (Tree member : tree.getMembers()) {
                if (member.getKind() == Tree.Kind.VARIABLE) {
                    VariableTree field = (VariableTree) member;
                    if (!field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        components.add(field);
                    }
                }
            }
            return components;
        }

        /** A normal constructor is the canonical one when its parameters repeat the components in order. */
        private boolean isCanonicalSignature(MethodTree method, ClassTree tree) {
            List<String> components = new ArrayList<>();
            for (VariableTree field : recordComponents(tree)) {
                String type = text(field.getType());
                if (type == null) {
                    return false;
                }
                components.add(type.replaceAll("\\s+", ""));
            }
            if (method.getParameters().size() != components.size()) {
                return false;
            }
            for (int i = 0; i < components.size(); i++) {
                String paramType = text(method.getParameters().get(i).getType());
                if (paramType == null || !paramType.replaceAll("\\s+", "").equals(components.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** A compact constructor has no parameter list: the name runs straight into the body. */
        private boolean isCompactConstructor(MethodTree method, String name) {
            int at = start(method);
            String head = masked.substring(at, Math.min(at + 400, masked.length()));
            int index = 0;
            while ((index = head.indexOf(name, index)) >= 0) {
                boolean boundaryBefore = index == 0 || !Character.isJavaIdentifierPart(head.charAt(index - 1));
                int after = index + name.length();
                boolean boundaryAfter = after >= head.length() || !Character.isJavaIdentifierPart(head.charAt(after));
                if (boundaryBefore && boundaryAfter) {
                    while (after < head.length() && Character.isWhitespace(head.charAt(after))) {
                        after++;
                    }
                    if (after < head.length() && head.charAt(after) == '<') {
                        int depth = 0;
                        while (after < head.length()) {
                            char c = head.charAt(after);
                            if (c == '<') {
                                depth++;
                            } else if (c == '>') {
                                depth--;
                                if (depth == 0) {
                                    after++;
                                    break;
                                }
                            }
                            after++;
                        }
                        while (after < head.length() && Character.isWhitespace(head.charAt(after))) {
                            after++;
                        }
                    }
                    if (after < head.length() && head.charAt(after) == '{') {
                        return true;
                    }
                }
                index++;
            }
            return false;
        }

        /**
         * Replaces the record header with a class header plus synthesized members. A null body means a plain
         * record; a compact constructor body is converted to the canonical constructor it implies. When the
         * record already declares its canonical constructor, no constructor is synthesized.
         */
        private void headerEdit(ClassTree tree, String name, MethodTree compact, boolean synthesizeCanonical, boolean nestedStatic) {
            List<String> components = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (VariableTree field : recordComponents(tree)) {
                String componentType = text(field.getType());
                if (componentType == null) {
                    return;
                }
                types.add(componentType);
                components.add(field.getName().toString());
            }
            int headerEnd = headerEnd(tree, name, types.size());
            if (headerEnd < 0 || start(tree) < 0) {
                return;
            }
            String modifiers = text(tree.getModifiers());
            if (modifiers == null) {
                modifiers = "";
            }
            String typeParams = typeParameters(tree);
            String impl = implementsClause(tree);
            if (typeParams == null || impl == null) {
                return;
            }
            boolean permits = !tree.getPermitsClause().isEmpty();
            String kept = keptModifiers(modifiers, permits);
            String access = recordAccess(kept);
            StringBuilder members = new StringBuilder();
            for (int i = 0; i < components.size(); i++) {
                members.append("private final ").append(types.get(i)).append(' ').append(components.get(i)).append(';');
            }
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                params.append(types.get(i)).append(' ').append(components.get(i));
            }
            StringBuilder assigns = new StringBuilder();
            for (int i = 0; i < components.size(); i++) {
                assigns.append("this.").append(components.get(i)).append(" = ").append(components.get(i)).append(';');
            }
            if (synthesizeCanonical) {
                if (compact == null) {
                    members.append(access).append(name).append('(').append(params).append("){").append(assigns).append('}');
                } else {
                    String body = compactBody(compact);
                    if (body == null) {
                        return;
                    }
                    members.append(access).append(name).append('(').append(params).append("){").append(body).append(assigns).append('}');
                }
            }
            Set<String> declared = new HashSet<>();
            for (Tree member : tree.getMembers()) {
                if (member.getKind() == Tree.Kind.METHOD) {
                    declared.add(((MethodTree) member).getName().toString());
                }
            }
            for (int i = 0; i < components.size(); i++) {
                if (!declared.contains(components.get(i))) {
                    members.append("public ").append(types.get(i)).append(' ').append(components.get(i)).append("(){return ").append(components.get(i)).append(";}");
                }
            }
            if (!declared.contains("equals")) {
                members.append("public boolean equals(Object o){if(!(o instanceof ").append(name).append("))return false;").append(name);
                String wildcards = wildcards(tree.getTypeParameters().size());
                members.append(wildcards);
                members.append(" other=(").append(name);
                if (!typeParams.isEmpty()) {
                    members.append(wildcards);
                }
                members.append(")o;return ");
                for (int i = 0; i < components.size(); i++) {
                    if (i > 0) {
                        members.append("&&");
                    }
                    members.append("java.util.Objects.equals(").append(components.get(i)).append(",other.").append(components.get(i)).append(')');
                }
                if (components.isEmpty()) {
                    members.append("true");
                }
                members.append(";}");
            }
            if (!declared.contains("hashCode")) {
                members.append("public int hashCode(){return java.util.Objects.hash(").append(String.join(",", components)).append(");}");
            }
            if (!declared.contains("toString")) {
                members.append("public String toString(){return \"").append(name).append('(');
                for (int i = 0; i < components.size(); i++) {
                    if (i > 0) {
                        members.append(',');
                    }
                    members.append(components.get(i)).append("=\"+").append(components.get(i)).append("+\"");
                }
                members.append(")\";}");
            }
            String header = kept + (nestedStatic && !kept.contains("static") ? "static " : "") + (permits ? "class " : "final class ") + name + typeParams + impl + "{" + members;
            edits.add(new Edit(start(tree), headerEnd, header));
            if (compact != null) {
                edits.add(new Edit(start(compact), end(compact), ""));
            }
        }

        private String typeParameters(ClassTree tree) {
            if (tree.getTypeParameters().isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (Tree param : tree.getTypeParameters()) {
                String part = text(param);
                if (part == null) {
                    return null;
                }
                parts.add(part);
            }
            return "<" + String.join(",", parts) + ">";
        }

        /** One wildcard per type parameter: a two-parameter record casts to Pair<?,?>, never Pair<?>. */
        private String wildcards(int count) {
            if (count == 0) {
                return "";
            }
            StringBuilder out = new StringBuilder("<");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('?');
            }
            return out.append('>').toString();
        }

        /**
         * Keeps the record's own modifiers on the class it becomes, minus the final it already
         * implies and a dropped permits clause's sealed. Whole tokens only, so annotation strings
         * are never half-eaten; only multi-space runs inside one would collapse, which records
         * never carry.
         */
        private String keptModifiers(String modifiers, boolean permits) {
            StringBuilder kept = new StringBuilder();
            for (String token : modifiers.split("\s+")) {
                if (token.isEmpty() || token.equals("final") || permits && token.equals("sealed")) {
                    continue;
                }
                kept.append(token).append(' ');
            }
            return kept.toString();
        }

        /** A record's access travels to its canonical constructor; its accessors stay public. */
        private String recordAccess(String kept) {
            for (String token : kept.split("\s+")) {
                if (token.equals("public") || token.equals("protected") || token.equals("private")) {
                    return token + " ";
                }
            }
            return "";
        }

        private String implementsClause(ClassTree tree) {
            if (tree.getImplementsClause().isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (Tree parent : tree.getImplementsClause()) {
                String part = text(parent);
                if (part == null) {
                    return null;
                }
                parts.add(part);
            }
            return " implements " + String.join(",", parts);
        }

        /**
         * Finds the end of the record header: the matching close paren of the component list plus any
         * implements clause. Counts depth textually so generic components with commas stay intact.
         */
        private int headerEnd(ClassTree tree, String name, int components) {
            int rec = masked.indexOf("record", start(tree));
            while (rec >= 0) {
                int after = rec + "record".length();
                while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
                    after++;
                }
                if (source.startsWith(name, after) && (after + name.length() >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after + name.length())))) {
                    break;
                }
                rec = masked.indexOf("record", rec + 1);
            }
            if (rec < 0) {
                return -1;
            }
            int at = rec + "record".length();
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
                at++;
            }
            at += name.length();
            while (at < source.length() && (Character.isWhitespace(source.charAt(at)) || source.charAt(at) == '<')) {
                if (source.charAt(at) == '<') {
                    at = skipBalanced(at, '<', '>');
                    if (at < 0) {
                        return -1;
                    }
                } else {
                    at++;
                }
            }
            if (at >= source.length() || source.charAt(at) != '(') {
                return -1;
            }
            at = skipBalanced(at, '(', ')');
            if (at < 0) {
                return -1;
            }
            int rest = at;
            while (rest < source.length() && Character.isWhitespace(source.charAt(rest))) {
                rest++;
            }
            if (source.startsWith("implements", rest) && (rest + 10 >= source.length() || !Character.isJavaIdentifierPart(source.charAt(rest + 10)))) {
                rest += 10;
                int depth = 0;
                while (rest < source.length()) {
                    char c = masked.charAt(rest);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    } else if (c == '{' && depth == 0) {
                        break;
                    }
                    rest++;
                }
            }
            if (source.startsWith("permits", rest) && (rest + 7 >= source.length() || !Character.isJavaIdentifierPart(source.charAt(rest + 7)))) {
                rest += 7;
                int depth = 0;
                while (rest < source.length()) {
                    char c = masked.charAt(rest);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    } else if (c == '{' && depth == 0) {
                        break;
                    }
                    rest++;
                }
            }
            if (rest < source.length() && source.charAt(rest) == '{') {
                rest++;
            }
            return rest;
        }

        private int skipBalanced(int open, char openChar, char closeChar) {
            int depth = 0;
            int at = open;
            while (at < source.length()) {
                char c = masked.charAt(at);
                if (c == openChar) {
                    depth++;
                } else if (c == closeChar) {
                    depth--;
                    if (depth == 0) {
                        return at + 1;
                    }
                }
                at++;
            }
            return -1;
        }

        /** The compact body without its braces, so the canonical constructor can wrap it. */
        private String compactBody(MethodTree method) {
            if (method.getBody() == null) {
                return null;
            }
            String body = text(method.getBody());
            if (body == null || !body.startsWith("{") || !body.endsWith("}")) {
                return null;
            }
            return body.substring(1, body.length() - 1);
        }

        /** Strips sealed from a class and permits from its header; non-sealed becomes a plain class. */
        private void sealedEdit(ClassTree tree) {
            ModifiersTree modifiers = tree.getModifiers();
            String text = text(modifiers);
            int base = start(modifiers);
            if (text != null && base >= 0) {
                int sealedAt = masked.indexOf("sealed", base);
                while (sealedAt >= 0 && sealedAt < base + text.length()) {
                    boolean nonSealed = sealedAt >= 4 && masked.startsWith("non-", sealedAt - 4);
                    int wordEnd = sealedAt + 6;
                    if (nonSealed) {
                        edits.add(new Edit(sealedAt - 4, wordEnd, ""));
                    } else if ((sealedAt == 0 || !Character.isJavaIdentifierPart(masked.charAt(sealedAt - 1))) && (wordEnd >= masked.length() || !Character.isJavaIdentifierPart(masked.charAt(wordEnd)))) {
                        edits.add(new Edit(sealedAt, wordEnd, ""));
                    }
                    sealedAt = masked.indexOf("sealed", wordEnd);
                }
            }
            if (!tree.getPermitsClause().isEmpty()) {
                Tree first = tree.getPermitsClause().get(0);
                Tree last = tree.getPermitsClause().get(tree.getPermitsClause().size() - 1);
                int at = start(first);
                while (at > start(tree) && Character.isWhitespace(source.charAt(at - 1))) {
                    at--;
                }
                int back = at;
                while (back > start(tree) && Character.isJavaIdentifierPart(source.charAt(back - 1))) {
                    back--;
                }
                if (source.startsWith("permits", back)) {
                    at = back;
                }
                edits.add(new Edit(at, end(last), ""));
            }
        }

        /**
         * Replaces a var declarator with its type: the attributed type when it names something real, or the
         * constructed type for a plain new expression. Anything else is left for a human.
         */
        private void varEdit(VariableTree tree) {
            if (tree.getInitializer() == null) {
                return;
            }
            int[] typeSpan = varTypeSpan(tree);
            if (typeSpan == null) {
                return;
            }
            String inferred = constructedType(tree.getInitializer());
            if (inferred == null) {
                inferred = inferredType(tree);
            }
            if (inferred == null) {
                return;
            }
            edits.add(new Edit(typeSpan[0], typeSpan[1], inferred));
        }

        /**
         * The source span of the var word. The type tree of a var declaration carries no positions, so the
         * word is located textually: the last var before the declarator name in the masked header.
         */
        private int[] varTypeSpan(VariableTree tree) {
            int start = start(tree);
            int initStart = start(tree.getInitializer());
            if (initStart <= start || initStart > masked.length()) {
                return null;
            }
            String head = masked.substring(start, initStart);
            String name = tree.getName().toString();
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(".*\\bvar(?=\\s+" + java.util.regex.Pattern.quote(name) + "\\s*=)").matcher(head);
            if (!match.find()) {
                return null;
            }
            int local = match.end() - 3;
            return new int[] {start + local, start + local + 3};
        }

        private String inferredType(VariableTree tree) {
            try {
                TypeMirror mirror = trees.getTypeMirror(getCurrentPath());
                if (mirror == null || mirror.getKind() == TypeKind.ERROR || mirror.getKind() == TypeKind.NULL || mirror.getKind() == TypeKind.NONE) {
                    return null;
                }
                if (mirror.getKind() == TypeKind.DECLARED) {
                    Element element = ((DeclaredType) mirror).asElement();
                    if (element instanceof TypeElement && ((TypeElement) element).getNestingKind() == NestingKind.ANONYMOUS) {
                        return null;
                    }
                }
                String name = mirror.toString();
                if (name.contains("&") || name.contains("? extends") && name.contains("? super")) {
                    return null;
                }
                return name;
            } catch (Exception ignored) {
                return null;
            }
        }

        /** The explicit type of a new expression: new Foo<Bar>(...) names Foo<Bar>. */
        private String constructedType(ExpressionTree initializer) {
            if (initializer.getKind() != Tree.Kind.NEW_CLASS) {
                return null;
            }
            String raw = text(initializer);
            if (raw.contains("{")) {
                return null;
            }
            int open = raw.indexOf('(');
            if (open < 0) {
                return null;
            }
            String head = raw.substring("new".length(), open).strip();
            if (head.isEmpty() || !Character.isJavaIdentifierStart(head.charAt(0))) {
                return null;
            }
            return head;
        }

        /**
         * Lowers a pattern instanceof in a clean guard shape: the test stays, each bound use becomes a cast of
         * the tested expression. Anything with unclear scope is left alone.
         */
        private void patternEdit(InstanceOfTree tree) {
            if (!(tree.getPattern() instanceof BindingPatternTree)) {
                return;
            }
            BindingPatternTree pattern = (BindingPatternTree) tree.getPattern();
            String variable = pattern.getVariable().getName().toString();
            String type = text(pattern.getVariable().getType());
            String tested = text(tree.getExpression());
            TreePath path = getCurrentPath();
            Scope scope = guardScope(path, tree);
            if (scope == null) {
                return;
            }
            if (declares(scope, variable)) {
                return;
            }
            List<Edit> uses = new ArrayList<>();
            uses.add(new Edit(start(tree), end(tree), tested + " instanceof " + type));
            for (int[] span : scope.spans) {
                uses.addAll(castUses(span[0], span[1], variable, type, tested));
                if (uses == null) {
                    return;
                }
            }
            List<Edit> checked = new ArrayList<>();
            for (Edit use : uses) {
                if (overlapsKnown(use)) {
                    return;
                }
                checked.add(use);
            }
            edits.addAll(checked);
        }

        private boolean overlapsKnown(Edit edit) {
            for (Edit kept : edits) {
                if (edit.start() < kept.end() && kept.start() < edit.end()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The labels of a case through the labels accessor, which sees pattern and default labels the
         * expressions accessor omits. Falls back to expressions if the accessor ever goes away.
         */
        private List<? extends Tree> caseLabels(CaseTree kase) {
            try {
                return kase.getLabels();
            } catch (RuntimeException | LinkageError e) {
                return kase.getExpressions();
            }
        }

        /** The single pattern inside a pattern label wrapper, without the post-17 label sub-interfaces. */
        private Tree patternOf(Tree label) {
            Tree[] found = {null};
            label.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void scan(Tree node, Void unused) {
                    if (node != null && node != label && found[0] == null && node.getKind() == Tree.Kind.BINDING_PATTERN) {
                        found[0] = node;
                    }
                    return super.scan(node, unused);
                }
            }, null);
            return found[0];
        }

        /** The regions where the pattern variable is definitely bound: the guard remainder plus the body. */
        private Scope guardScope(TreePath path, InstanceOfTree tree) {
            TreePath up = path.getParentPath();
            while (up != null && up.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
                up = up.getParentPath();
            }
            if (up == null) {
                return null;
            }
            if (up.getLeaf().getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                return negatedScope(up, tree);
            }
            if (up.getLeaf().getKind() == Tree.Kind.CONDITIONAL_AND) {
                int from = end(tree);
                Tree top = up.getLeaf();
                TreePath outer = up.getParentPath();
                while (outer != null && outer.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
                    outer = outer.getParentPath();
                }
                while (outer != null && outer.getLeaf().getKind() == Tree.Kind.CONDITIONAL_AND) {
                    top = outer.getLeaf();
                    outer = outer.getParentPath();
                    while (outer != null && outer.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
                        outer = outer.getParentPath();
                    }
                }
                if (outer == null) {
                    return null;
                }
                if (outer.getLeaf().getKind() == Tree.Kind.CONDITIONAL_OR) {
                    Scope inner = new Scope(new ArrayList<>());
                    inner.spans.add(new int[] {from, end(top)});
                    return inner;
                }
                Scope scope = continuationScope(outer, outer.getLeaf(), tree);
                if (scope == null) {
                    return null;
                }
                scope.spans.add(0, new int[] {from, end(top)});
                return scope;
            }
            Scope scope = continuationScope(up, up.getLeaf(), tree);
            return scope;
        }

        /**
         * Scope for a guard clause: if (!(shape with bindings)) { abort; } establishes the bindings in
         * the code after the if, because reaching past it proves the condition was false. The walk tracks
         * whether the if condition must be false for the bindings to hold: && keeps a true requirement,
         * || keeps a false one, and ! flips it, so !(A && E) and !A || !(E) both qualify while !(A || E)
         * bails. The then-branch must abort and have no else, and the scope is the rest of the enclosing
         * block or if-branch.
         */
        private Scope negatedScope(TreePath bang, InstanceOfTree tree) {
            boolean needTrue = false;
            TreePath current = bang.getParentPath();
            while (current != null && current.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
                current = current.getParentPath();
            }
            Tree andTop = null;
            int from = end(tree);
            while (current != null) {
                Tree.Kind kind = current.getLeaf().getKind();
                if (kind == Tree.Kind.CONDITIONAL_AND) {
                    if (!needTrue) {
                        return null;
                    }
                    andTop = current.getLeaf();
                    current = current.getParentPath();
                } else if (kind == Tree.Kind.CONDITIONAL_OR) {
                    if (needTrue) {
                        return null;
                    }
                    current = current.getParentPath();
                } else if (kind == Tree.Kind.LOGICAL_COMPLEMENT) {
                    needTrue = !needTrue;
                    current = current.getParentPath();
                } else {
                    break;
                }
                while (current != null && current.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
                    current = current.getParentPath();
                }
            }
            if (current == null || current.getLeaf().getKind() != Tree.Kind.IF || needTrue) {
                return null;
            }
            IfTree statement = (IfTree) current.getLeaf();
            if (statement.getElseStatement() != null || !aborts(statement.getThenStatement())) {
                return null;
            }
            TreePath holder = current.getParentPath();
            if (holder == null) {
                return null;
            }
            Tree parent = holder.getLeaf();
            if (parent.getKind() != Tree.Kind.BLOCK && parent.getKind() != Tree.Kind.IF) {
                return null;
            }
            Scope scope = new Scope(new ArrayList<>());
            if (andTop != null) {
                scope.spans.add(new int[] {from, end(andTop)});
            }
            scope.spans.add(new int[] {end(statement), end(parent)});
            return scope;
        }

        /** True when the statement never falls through to what follows it. */
        private boolean aborts(StatementTree statement) {
            if (statement == null) {
                return false;
            }
            if (statement.getKind() == Tree.Kind.BLOCK) {
                List<? extends StatementTree> body = ((BlockTree) statement).getStatements();
                if (body.isEmpty()) {
                    return false;
                }
                return aborts(body.get(body.size() - 1));
            }
            Tree.Kind kind = statement.getKind();
            return kind == Tree.Kind.RETURN || kind == Tree.Kind.THROW || kind == Tree.Kind.BREAK || kind == Tree.Kind.CONTINUE;
        }

        private boolean contains(Tree root, Tree target) {
            if (root == target) {
                return true;
            }
            boolean[] found = {false};
            root.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void scan(Tree node, Void unused) {
                    if (node == target) {
                        found[0] = true;
                    }
                    return super.scan(node, unused);
                }
            }, null);
            return found[0];
        }

        /**
         * The body or branch a guard shape continues into: the then of an if, the body of a while, the details
         * of an assert, the arms of a ternary, or the rest of the current expression when the guard sits
         * directly in a return or expression statement with no branch to continue into.
         */
        private Scope continuationScope(TreePath holder, Tree leaf, Tree condition) {
            if (leaf.getKind() == Tree.Kind.IF) {
                IfTree statement = (IfTree) leaf;
                if (statement.getCondition() != condition && !contains(statement.getCondition(), condition)) {
                    return null;
                }
                return new Scope(List.of(spanOf(statement.getThenStatement())));
            }
            if (leaf.getKind() == Tree.Kind.WHILE_LOOP) {
                WhileLoopTree statement = (WhileLoopTree) leaf;
                if (statement.getCondition() != condition && !contains(statement.getCondition(), condition)) {
                    return null;
                }
                return new Scope(List.of(spanOf(statement.getStatement())));
            }
            if (leaf.getKind() == Tree.Kind.ASSERT) {
                AssertTree statement = (AssertTree) leaf;
                if (statement.getCondition() != condition && !contains(statement.getCondition(), condition)) {
                    return null;
                }
                if (statement.getDetail() == null) {
                    return new Scope(new ArrayList<>());
                }
                return new Scope(List.of(spanOf(statement.getDetail())));
            }
            if (leaf.getKind() == Tree.Kind.CONDITIONAL_EXPRESSION) {
                ConditionalExpressionTree expression = (ConditionalExpressionTree) leaf;
                if (expression.getCondition() != condition && !contains(expression.getCondition(), condition)) {
                    return null;
                }
                return new Scope(List.of(spanOf(expression.getTrueExpression()), spanOf(expression.getFalseExpression())));
            }
            if (leaf.getKind() == Tree.Kind.RETURN || leaf.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                return new Scope(new ArrayList<>());
            }
            return null;
        }

        private int[] spanOf(Tree tree) {
            return new int[] {start(tree), end(tree)};
        }

        /** True when the scope declares its own variable of that name, which ends the propagation. A
         * statement keyword before the name is not a type: return, throw, and yield statements use
         * the name instead of declaring it. */
        private boolean declares(Scope scope, String variable) {
            java.util.regex.Pattern declaration = java.util.regex.Pattern.compile("(?s).*\\b([A-Za-z_][\\w$]*)(<[^;{}]*>)?(\\[\\])*\\s+" + variable + "\\s*[=;,)].*");
            for (int[] span : scope.spans) {
                String region = masked.substring(span[0], span[1]);
                java.util.regex.Matcher match = declaration.matcher(region);
                if (match.matches()) {
                    String typeWord = match.group(1);
                    if (!typeWord.equals("return") && !typeWord.equals("throw") && !typeWord.equals("yield")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Every bound use of the variable becomes a cast of the tested expression. */
        private List<Edit> castUses(int from, int to, String variable, String type, String tested) {
            List<Edit> out = new ArrayList<>();
            String region = masked.substring(from, to);
            java.util.regex.Matcher use = java.util.regex.Pattern.compile("\\b" + variable + "\\b").matcher(region);
            while (use.find()) {
                if (!isVariableRead(region, from, use.start(), use.end())) {
                    continue;
                }
                out.add(new Edit(from + use.start(), from + use.end(), "((" + type + ")" + tested + ")"));
            }
            return out;
        }

        /**
         * True when the match reads the variable. Field selects, annotations, method references,
         * calls, labels, assignments, increments, lambdas, and switch arrows name something else
         * that merely shares the spelling; rewriting those corrupts the file.
         */
        private boolean isVariableRead(String region, int from, int start, int end) {
            char before = start > 0 ? region.charAt(start - 1) : from > 0 ? masked.charAt(from - 1) : 0;
            char beforeTwo = start > 1 ? region.charAt(start - 2) : 0;
            int afterIndex = end;
            while (afterIndex < region.length() && Character.isWhitespace(region.charAt(afterIndex))) {
                afterIndex++;
            }
            char after = afterIndex < region.length() ? region.charAt(afterIndex) : 0;
            char afterTwo = afterIndex + 1 < region.length() ? region.charAt(afterIndex + 1) : 0;
            if (before == '.' || before == '@' || before == ':') {
                return false;
            }
            if ((before == '+' || before == '-') && beforeTwo == before) {
                return false;
            }
            if (after == '(' || after == ':') {
                return false;
            }
            if (after == '-' && afterTwo == '>') {
                return false;
            }
            if (after == '=' && afterTwo != '=') {
                return false;
            }
            if ((after == '+' || after == '-') && (afterTwo == '=' || afterTwo == after)) {
                return false;
            }
            if ((after == '*' || after == '/' || after == '%' || after == '&' || after == '|' || after == '^') && afterTwo == '=') {
                return false;
            }
            if ((after == '<' && afterTwo == '<') || (after == '>' && afterTwo == '>')) {
                return false;
            }
            if (region.substring(0, start).matches("(?s).*\\b(break|continue|case)\\s+")) {
                return false;
            }
            return true;
        }

        /**
         * Lowers a switch statement over type patterns into an if-else chain. Guards, record patterns, null
         * labels, and fallthrough are left for a human.
         */
        private void patternSwitchEdit(SwitchTree tree) {
            List<? extends CaseTree> cases = tree.getCases();
            if (cases.isEmpty()) {
                return;
            }
            List<Branch> branches = new ArrayList<>();
            Branch fallback = null;
            for (CaseTree kase : cases) {
                if (hasGuard(kase)) {
                    return;
                }
                List<? extends Tree> labels = caseLabels(kase);
                if (labels.isEmpty()) {
                    if (fallback != null) {
                        return;
                    }
                    if (!isTerminal(kase.getStatements())) {
                        return;
                    }
                    fallback = new Branch(null, null, kase.getStatements());
                    continue;
                }
                if (labels.size() != 1) {
                    return;
                }
                Tree label = labels.get(0);
                if (label.getKind() == Tree.Kind.BINDING_PATTERN) {
                    BindingPatternTree pattern = (BindingPatternTree) label;
                    if (!isTerminal(kase.getStatements())) {
                        return;
                    }
                    branches.add(new Branch(text(pattern.getVariable().getType()), pattern.getVariable().getName().toString(), kase.getStatements()));
                } else if (label.getKind().name().equals("DEFAULT_CASE_LABEL")) {
                    if (fallback != null) {
                        return;
                    }
                    if (!isTerminal(kase.getStatements())) {
                        return;
                    }
                    fallback = new Branch(null, null, kase.getStatements());
                } else if (label.getKind().name().equals("PATTERN_CASE_LABEL")) {
                    Tree pattern = patternOf(label);
                    if (!(pattern instanceof BindingPatternTree)) {
                        return;
                    }
                    BindingPatternTree bound = (BindingPatternTree) pattern;
                    if (!isTerminal(kase.getStatements())) {
                        return;
                    }
                    branches.add(new Branch(text(bound.getVariable().getType()), bound.getVariable().getName().toString(), kase.getStatements()));
                } else {
                    return;
                }
            }
            if (branches.isEmpty()) {
                return;
            }
            String selectorType = selectorType(tree);
            if (selectorType == null) {
                return;
            }
            String tested = text(unparen(tree.getExpression()));
            String tmp = freshName("sel");
            StringBuilder out = new StringBuilder();
            out.append('{').append(selectorType).append(' ').append(tmp).append('=').append(tested).append(';');
            for (int i = 0; i < branches.size(); i++) {
                Branch branch = branches.get(i);
                out.append(i == 0 ? "if(" : "else if(").append(tmp).append(" instanceof ").append(branch.type).append("){");
                out.append(branch.type).append(' ').append(branch.variable).append("=(").append(branch.type).append(')').append(tmp).append(';');
                for (StatementTree statement : branch.statements) {
                    out.append(text(statement));
                }
                out.append('}');
            }
            if (fallback != null) {
                out.append("else{");
                for (StatementTree statement : fallback.statements) {
                    out.append(text(statement));
                }
                out.append('}');
            }
            out.append('}');
            edits.add(new Edit(start(tree), end(tree), out.toString()));
        }

        /**
         * Lowers arrow cases in a statement switch over constants into colon cases with breaks. An arrow
         * never falls through, so appending a break keeps the meaning. Pattern cases belong to the
         * if-else lowering, and anything unrecognized is left for a human.
         */
        private void arrowStatementEdit(SwitchTree tree) {
            List<? extends CaseTree> cases = tree.getCases();
            if (cases.isEmpty()) {
                return;
            }
            List<Edit> arrowEdits = new ArrayList<>();
            for (CaseTree kase : cases) {
                if (hasGuard(kase)) {
                    return;
                }
                List<? extends Tree> labels = caseLabels(kase);
                if (labels.isEmpty()) {
                    return;
                }
                for (Tree label : labels) {
                    String kind = label.getKind().name();
                    boolean constant = label.getKind() == Tree.Kind.IDENTIFIER || label.getKind().name().endsWith("_LITERAL") || kind.equals("CONSTANT_CASE_LABEL");
                    if (!constant && !kind.equals("DEFAULT_CASE_LABEL")) {
                        return;
                    }
                }
                List<? extends StatementTree> statements = kase.getStatements();
                if (statements != null && statements.isEmpty()) {
                    return;
                }
                int caseStart = start(kase);
                int bodyStart;
                int bodyEnd;
                if (statements != null) {
                    bodyStart = start(statements.get(0));
                    bodyEnd = end(statements.get(statements.size() - 1));
                } else {
                    Tree body = kase.getBody();
                    if (body == null || body.getKind() == Tree.Kind.THROW) {
                        return;
                    }
                    bodyStart = start(body);
                    bodyEnd = end(body);
                }
                if (caseStart < 0 || bodyStart < 0 || bodyEnd < 0) {
                    return;
                }
                int arrow = masked.indexOf("->", caseStart);
                if (arrow < 0 || arrow + 2 > bodyStart) {
                    return;
                }
                arrowEdits.add(new Edit(arrow, arrow + 2, ":"));
                arrowEdits.add(new Edit(bodyEnd, bodyEnd, "break;"));
            }
            edits.addAll(arrowEdits);
        }

        private String selectorType(SwitchTree tree) {
            try {
                TypeMirror mirror = trees.getTypeMirror(new TreePath(getCurrentPath(), tree.getExpression()));
                if (mirror == null || mirror.getKind() == TypeKind.ERROR || mirror.getKind() == TypeKind.NULL) {
                    return "java.lang.Object";
                }
                return mirror.toString();
            } catch (Exception ignored) {
                return "java.lang.Object";
            }
        }

        private boolean isTerminal(List<? extends StatementTree> statements) {
            if (statements == null || statements.isEmpty()) {
                return false;
            }
            Tree last = statements.get(statements.size() - 1);
            Tree.Kind kind = last.getKind();
            if (kind == Tree.Kind.RETURN || kind == Tree.Kind.THROW || kind == Tree.Kind.BREAK || kind == Tree.Kind.CONTINUE) {
                return true;
            }
            if (last.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                String tail = masked.substring(start(last), end(last)).strip();
                return tail.startsWith("yield ");
            }
            return false;
        }

        /**
         * Lowers a switch expression into a statement switch over a scratch variable. A direct variable
         * initializer reuses its own variable; every other shape hoists a fresh temporary before the enclosing
         * statement, a block-lambda body, or an instance initializer for field declarations.
         */
        private void switchExpressionEdit(SwitchExpressionTree tree) {
            List<? extends CaseTree> cases = tree.getCases();
            if (cases.isEmpty()) {
                return;
            }
            for (CaseTree kase : cases) {
                if (hasGuard(kase)) {
                    return;
                }
                for (Tree label : kase.getExpressions()) {
                    if (label.getKind() == Tree.Kind.BINDING_PATTERN && !(label instanceof BindingPatternTree)) {
                        return;
                    }
                    if (label.getKind() != Tree.Kind.BINDING_PATTERN && label.getKind().name().equals("DEFAULT_CASE_LABEL")) {
                        continue;
                    }
                    if (label.getKind() != Tree.Kind.BINDING_PATTERN && !(label instanceof LiteralTree) && !(label instanceof IdentifierTree)) {
                        return;
                    }
                    if (label instanceof LiteralTree && ((LiteralTree) label).getValue() == null) {
                        return;
                    }
                }
            }
            boolean binds = false;
            for (CaseTree kase : cases) {
                for (Tree label : kase.getExpressions()) {
                    if (label instanceof BindingPatternTree) {
                        binds = true;
                    }
                }
            }
            String switchType = expressionType(tree);
            TreePath path = getCurrentPath();
            if (switchType == null) {
                switchType = enclosingReturnType(path);
            }
            if (switchType == null) {
                return;
            }
            Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
            if (parent instanceof VariableTree && ((VariableTree) parent).getInitializer() == tree) {
                directInitEdit(tree, (VariableTree) parent, switchType, binds);
                return;
            }
            hoistEdit(tree, path, switchType, binds);
        }

        private String expressionType(SwitchExpressionTree tree) {
            try {
                TypeMirror mirror = trees.getTypeMirror(getCurrentPath());
                if (mirror == null || mirror.getKind() == TypeKind.ERROR || mirror.getKind() == TypeKind.NULL || mirror.getKind() == TypeKind.NONE) {
                    return null;
                }
                String name = mirror.toString();
                if (name.contains("&")) {
                    return null;
                }
                return name;
            } catch (Exception ignored) {
                return null;
            }
        }

        /** The declared method return type when the switch expression is the whole return value, so the
         * scratch variable keeps the method's type without attribution. Lambdas stop the climb: their
         * target type is not recoverable from source text. */
        private String enclosingReturnType(TreePath path) {
            TreePath up = path.getParentPath();
            if (up == null || up.getLeaf().getKind() != Tree.Kind.RETURN) {
                return null;
            }
            up = up.getParentPath();
            while (up != null && !(up.getLeaf() instanceof MethodTree)) {
                if (up.getLeaf().getKind() == Tree.Kind.LAMBDA_EXPRESSION) {
                    return null;
                }
                up = up.getParentPath();
            }
            if (up == null) {
                return null;
            }
            String declared = text(((MethodTree) up.getLeaf()).getReturnType());
            if (declared == null || declared.equals("void")) {
                return null;
            }
            return declared;
        }

        /** int zeroCost = switch ... becomes a pre-initialized variable plus a statement switch. */
        private void directInitEdit(SwitchExpressionTree tree, VariableTree variable, String switchType, boolean binds) {
            String declared = text(variable.getType());
            String target;
            String zero;
            if ("var".equals(declared)) {
                target = switchType;
                zero = zeroLiteral(switchType);
            } else {
                target = declared;
                zero = zeroLiteral(declared);
            }
            if (zero == null) {
                return;
            }
            String tmp = variable.getName().toString();
            StringBuilder out = new StringBuilder();
            out.append(target).append(' ').append(tmp).append('=').append(zero).append(';');
            out.append(convertedSwitch(tree, tmp, binds));
            if (end(variable) > start(variable) && source.charAt(end(variable) - 1) == ';') {
                out.append(';');
            }
            edits.add(new Edit(start(variable), end(variable), out.toString().strip()));
        }

        /**
         * Any other shape replaces its enclosing statement with a scratch declaration, the statement switch,
         * and the statement rewritten around the scratch variable.
         */
        private void hoistEdit(SwitchExpressionTree tree, TreePath path, String switchType, boolean binds) {
            String zero = zeroLiteral(switchType);
            if (zero == null) {
                return;
            }
            TreePath up = path.getParentPath();
            Tree child = tree;
            while (up != null) {
                Tree leaf = up.getLeaf();
                if (leaf.getKind() == Tree.Kind.LAMBDA_EXPRESSION) {
                    lambdaEdit(tree, up, switchType, zero, binds);
                    return;
                }
                if (leaf instanceof VariableTree && up.getParentPath() != null && up.getParentPath().getLeaf().getKind() == Tree.Kind.CLASS) {
                    fieldEdit(tree, (VariableTree) leaf, switchType, zero, binds);
                    return;
                }
                if (leaf instanceof StatementTree) {
                    Tree grandparent = up.getParentPath() == null ? null : up.getParentPath().getLeaf();
                    if (grandparent != null && (grandparent.getKind() == Tree.Kind.BLOCK || grandparent.getKind() == Tree.Kind.CASE)) {
                        statementEdit(tree, up, switchType, zero, binds);
                        return;
                    }
                    return;
                }
                if (!(leaf instanceof ExpressionTree) && leaf.getKind() != Tree.Kind.CASE && leaf.getKind() != Tree.Kind.PARENTHESIZED && leaf.getKind() != Tree.Kind.ANNOTATION) {
                    return;
                }
                child = leaf;
                up = up.getParentPath();
            }
        }

        private void statementEdit(SwitchExpressionTree tree, TreePath statement, String switchType, String zero, boolean binds) {
            String tmp = freshName("sw");
            String original = text(statement.getLeaf());
            int relative = start(tree) - start(statement.getLeaf());
            String rewritten = original.substring(0, relative) + tmp + original.substring(relative + text(tree).length());
            StringBuilder out = new StringBuilder();
            out.append(switchType).append(' ').append(tmp).append('=').append(zero).append(';').append(convertedSwitch(tree, tmp, binds)).append(rewritten);
            edits.add(new Edit(start(statement.getLeaf()), end(statement.getLeaf()), out.toString()));
        }

        private void lambdaEdit(SwitchExpressionTree tree, TreePath lambda, String switchType, String zero, boolean binds) {
            String tmp = freshName("sw");
            String original = text(tree);
            int relative = start(tree) - start(lambda.getLeaf());
            String lambdaText = text(lambda.getLeaf());
            String arrow = lambdaText.substring(0, relative) + tmp + lambdaText.substring(relative + original.length());
            int arrowAt = arrow.indexOf("->");
            if (arrowAt < 0) {
                return;
            }
            String params = arrow.substring(0, arrowAt + 2);
            String out = params + "{" + switchType + " " + tmp + "=" + zero + ";" + convertedSwitch(tree, tmp, binds) + "return " + tmp + ";}";
            edits.add(new Edit(start(lambda.getLeaf()), end(lambda.getLeaf()), out));
        }

        private void fieldEdit(SwitchExpressionTree tree, VariableTree field, String switchType, String zero, boolean binds) {
            String declared = text(field.getType());
            String target = "var".equals(declared) ? switchType : declared;
            boolean isStatic = field.getModifiers().getFlags().toString().contains("STATIC");
            String tmp = field.getName().toString();
            StringBuilder out = new StringBuilder();
            out.append(target).append(' ').append(tmp).append('=').append(zero).append(';');
            out.append(isStatic ? "static{" : "{").append(convertedSwitch(tree, tmp, binds)).append('}');
            edits.add(new Edit(start(field), end(field), out.toString()));
        }

        /**
         * The statement-switch form of the expression: every arrow becomes a colon case assigning the scratch
         * variable, yields become assignments, and multi-labels split apart for the legacy grammar.
         */
        /** A switch selector keeps its parentheses in the tree; the re-emitted switch adds its own. */
        private ExpressionTree unparen(ExpressionTree expression) {
            while (expression != null && expression.getKind() == Tree.Kind.PARENTHESIZED) {
                expression = ((ParenthesizedTree) expression).getExpression();
            }
            return expression;
        }

        private String convertedSwitch(SwitchExpressionTree tree, String tmp, boolean binds) {
            String selector = text(unparen(tree.getExpression()));
            StringBuilder out = new StringBuilder();
            String sel = selector;
            if (binds) {
                String selTmp = freshName("sel");
                String selType = selectorTypeOf(tree);
                if (selType == null) {
                    return null;
                }
                out.append('{').append(selType).append(' ').append(selTmp).append('=').append(selector).append(';');
                sel = selTmp;
            }
            out.append("switch(").append(sel).append("){");
            for (CaseTree kase : tree.getCases()) {
                List<String> labels = new ArrayList<>();
                String binding = null;
                if (kase.getExpressions().isEmpty()) {
                    labels.add("default");
                }
                for (Tree label : kase.getExpressions()) {
                    if (label instanceof BindingPatternTree) {
                        BindingPatternTree pattern = (BindingPatternTree) label;
                        labels.add(text(pattern.getVariable().getType()));
                        binding = text(pattern.getVariable().getType()) + " " + pattern.getVariable().getName() + "=(" + text(pattern.getVariable().getType()) + ")" + sel + ";";
                    } else if (label.getKind().name().equals("DEFAULT_CASE_LABEL")) {
                        labels.add("default");
                    } else {
                        labels.add(text(label));
                    }
                }
                for (String label : labels) {
                    if (label.equals("default")) {
                        out.append("default:");
                    } else {
                        out.append("case ").append(label).append(':');
                    }
                }
                if (binding != null) {
                    out.append(binding);
                }
                String converted = convertedCase(kase, tmp);
                if (converted == null) {
                    return null;
                }
                out.append(converted);
            }
            out.append('}');
            if (binds) {
                out.append('}');
            }
            return out.toString();
        }

        private String selectorTypeOf(SwitchExpressionTree tree) {
            try {
                TypeMirror mirror = trees.getTypeMirror(new TreePath(getCurrentPath(), tree.getExpression()));
                if (mirror == null || mirror.getKind() == TypeKind.ERROR || mirror.getKind() == TypeKind.NULL) {
                    return "java.lang.Object";
                }
                return mirror.toString();
            } catch (Exception ignored) {
                return "java.lang.Object";
            }
        }

        /** An arrow body that arrives outside a statement list: a throw, a block with yields, or a value. */
        private String arrowBody(Tree body, String tmp) {
            if (body == null) {
                return null;
            }
            String text = text(body);
            if (text == null) {
                return null;
            }
            if (body.getKind() == Tree.Kind.THROW) {
                return text;
            }
            if (body.getKind() == Tree.Kind.BLOCK) {
                String blockConverted = convertedBlock((BlockTree) body, tmp);
                if (blockConverted == null) {
                    return null;
                }
                if (endsTerminally((BlockTree) body)) {
                    return blockConverted;
                }
                return blockConverted + "break;";
            }
            if (!(body instanceof ExpressionTree)) {
                return null;
            }
            String value = text;
            if (value.endsWith(";")) {
                value = value.substring(0, value.length() - 1);
            }
            return tmp + "=" + value + ";break;";
        }

        /** One case body with yields turned into assignments; arrows never fall through, colons are preserved. */
        private String convertedCase(CaseTree kase, String tmp) {
            List<? extends StatementTree> statements = kase.getStatements();
            if (statements == null) {
                return arrowBody(kase.getBody(), tmp);
            }
            if (kase.getKind().name().equals("RULE_CASE")) {
                if (statements.size() != 1) {
                    return null;
                }
                StatementTree only = statements.get(0);
                String body = text(only);
                if (only.getKind() == Tree.Kind.THROW) {
                    return body;
                }
                if (only.getKind() == Tree.Kind.BLOCK) {
                    String blockConverted = convertedBlock((BlockTree) only, tmp);
                    if (blockConverted == null) {
                        return null;
                    }
                    if (endsTerminally((BlockTree) only)) {
                        return blockConverted;
                    }
                    return blockConverted + "break;";
                }
                String value = body;
                if (value.endsWith(";")) {
                    value = value.substring(0, value.length() - 1);
                }
                return tmp + "=" + value + ";break;";
            }
            StringBuilder out = new StringBuilder();
            for (StatementTree statement : statements) {
                if (statement.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                    String body = text(statement);
                    if (body.strip().startsWith("yield ")) {
                        out.append(tmp).append('=').append(body.strip().substring("yield ".length()));
                        if (!out.toString().endsWith(";")) {
                            out.append(';');
                        }
                    } else {
                        out.append(body);
                    }
                } else if (statement instanceof YieldTree) {
                    ExpressionTree value = ((YieldTree) statement).getValue();
                    if (value == null) {
                        return null;
                    }
                    out.append(tmp).append('=').append(text(value)).append(";break;");
                } else {
                    out.append(text(statement));
                }
            }
            return out.toString();
        }

        /**
         * An arrow block with yields turned into assignments. The appended break is safe because arrow cases
         * never fall through; returns and throws keep their own termination.
         */
        private String convertedBlock(BlockTree block, String tmp) {
            StringBuilder out = new StringBuilder();
            for (StatementTree statement : block.getStatements()) {
                if (statement instanceof YieldTree) {
                    ExpressionTree value = ((YieldTree) statement).getValue();
                    if (value == null) {
                        return null;
                    }
                    out.append(tmp).append('=').append(text(value)).append(';');
                } else if (statement.getKind() == Tree.Kind.EXPRESSION_STATEMENT && text(statement).strip().startsWith("yield ")) {
                    String body = text(statement).strip().substring("yield ".length());
                    out.append(tmp).append('=').append(body);
                    if (!body.endsWith(";")) {
                        out.append(';');
                    }
                } else {
                    out.append(text(statement));
                }
            }
            return out.toString();
        }

        /** True when a block's last statement already terminates it: no break may follow. */
        private boolean endsTerminally(BlockTree block) {
            if (block.getStatements().isEmpty()) {
                return false;
            }
            StatementTree last = block.getStatements().get(block.getStatements().size() - 1);
            Tree.Kind kind = last.getKind();
            return kind == Tree.Kind.RETURN || kind == Tree.Kind.THROW || kind == Tree.Kind.BREAK || kind == Tree.Kind.CONTINUE;
        }

        /** A text block becomes the equivalent quoted literal; the value comes from the parser, not the raw. */
        private void textBlockEdit(LiteralTree tree) {
            if (tree.getKind() != Tree.Kind.STRING_LITERAL) {
                return;
            }
            if (!source.startsWith("\"\"\"", start(tree))) {
                return;
            }
            Object value = tree.getValue();
            if (!(value instanceof String)) {
                return;
            }
            edits.add(new Edit(start(tree), end(tree), quote((String) value)));
        }

        private String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"') {
                    out.append("\\\"");
                } else if (c == '\\') {
                    out.append("\\\\");
                } else if (c == '\n') {
                    out.append("\\n");
                } else if (c == '\r') {
                    out.append("\\r");
                } else if (c == '\t') {
                    out.append("\\t");
                } else if (c < 32) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            return out.append('"').toString();
        }

        private String zeroLiteral(String type) {
            String base = type.strip();
            int generics = base.indexOf('<');
            if (generics >= 0) {
                base = base.substring(0, generics);
            }
            int array = base.indexOf('[');
            if (array >= 0) {
                base = base.substring(0, array);
            }
            int annotations = base.lastIndexOf(' ');
            if (annotations >= 0) {
                base = base.substring(annotations + 1);
            }
            if (base.contains(".")) {
                base = base.substring(base.lastIndexOf('.') + 1);
            }
            if (base.equals("boolean")) {
                return "false";
            }
            if (base.equals("char")) {
                return "'\\0'";
            }
            if (base.equals("byte") || base.equals("short") || base.equals("int") || base.equals("long") || base.equals("float") || base.equals("double")) {
                return "0";
            }
            if (base.equals("void")) {
                return null;
            }
            return "null";
        }

        /** A guarded case needs its condition, which has no lowering; detected textually before the body. */
        private boolean hasGuard(CaseTree kase) {
            int from = start(kase);
            if (from < 0) {
                return true;
            }
            List<? extends StatementTree> statements = kase.getStatements();
            int to;
            if (statements == null || statements.isEmpty()) {
                Tree body = kase.getBody();
                to = body == null ? end(kase) : start(body);
            } else {
                to = start(statements.get(0));
            }
            if (to <= from || to > masked.length()) {
                return true;
            }
            return masked.substring(from, to).matches("(?s).*\\bwhen\\b.*");
        }

        private String freshName(String base) {
            while (true) {
                String candidate = "_desugar" + base + freshNames;
                freshNames++;
                if (!masked.matches("(?s).*\\b" + candidate + "\\b.*")) {
                    return candidate;
                }
            }
        }

        private static final class Branch {
            final String type;
            final String variable;
            final List<? extends StatementTree> statements;
            Branch(String type, String variable, List<? extends StatementTree> statements) {
                this.type = type;
                this.variable = variable;
                this.statements = statements;
            }
        }

        private static final class Scope {
            final List<int[]> spans;
            Scope(List<int[]> spans) {
                this.spans = new ArrayList<>(spans);
            }
        }
    }
}
