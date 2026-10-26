import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Compiler errors from a project that doesn't build yet, each set beside the API of a project that
 * does: what the missing class, method or field looks like in each one's actual jars, or where a
 * class went. Most errors in a port are "what is this called in that version", which otherwise
 * means grepping two decompiled trees. Errors in Stonecutter's generated sources point back at the
 * source file they came from.
 */
final class Explain {
    final Build build; final Map<String, Object> model;
    Explain(Build build, Map<String, Object> model) { this.build = build; this.model = model; }

    record Problem(String file, int line, String message, List<String> notes) {}
    record Member(String owner, String name, String descriptor, int access, boolean method) {}
    record ClassInfo(String name, String superName, List<String> interfaces, int access, List<Member> members) {}

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        String[] selected = build.selected.split(",");
        if (selected.length != 2) throw new IllegalArgumentException("explain needs --project :failing-project --against :building-project");
        Map<String, Object> failing = project(selected[0]), working = project(selected[1]);
        String failingName = label(selected[0]), workingName = label(selected[1]);
        Path log; int exit = 0;
        List<Object> tasks = new ArrayList<>();
        if (options.values.containsKey("log")) log = Path.of(options.get("log", "")).toAbsolutePath();
        else {
            if (options.values.containsKey("task")) tasks.addAll(List.of(options.get("task", "").split(",")));
            else for (Object s : Json.array(failing.get("sourceSets"))) {
                String task = Json.text(Json.object(s), "compileJavaTask", Json.text(Json.object(s), "classesTask", ""));
                if (!task.isEmpty()) tasks.add(selected[0] + ":" + task);
            }
            if (tasks.isEmpty()) throw new IOException("No compile task in " + selected[0] + "; pass --task.");
            List<String> arguments = new ArrayList<>(); for (Object task : tasks) arguments.add(task.toString()); arguments.add("--continue");
            Run.Result compiled = build.gradle(arguments, "compile"); log = compiled.log(); exit = compiled.exit();
        }
        long start = System.nanoTime();
        List<Problem> problems = new ArrayList<>(parse(log));
        problems.sort(Comparator.comparing((Problem problem) -> source(problem.file())).thenComparingInt(Problem::line));
        String only = options.get("only", "");
        Index failingApi = new Index(classpath(failing)), workingApi = new Index(classpath(working));
        StringBuilder out = new StringBuilder();
        Map<String, Integer> perFile = new TreeMap<>();
        int shown = 0;
        String lastFile = "";
        for (Problem problem : problems) {
            String file = source(problem.file());
            if (!only.isEmpty() && !file.contains(only)) continue;
            perFile.merge(file, 1, Integer::sum); shown++;
            if (!file.equals(lastFile)) { out.append('\n').append(file).append('\n'); lastFile = file; }
            out.append("  ").append(problem.line()).append(": ").append(problem.message());
            String symbol = note(problem, "symbol:");
            if (!symbol.isEmpty()) out.append(": ").append(symbol);
            out.append('\n');
            if (!problem.notes().isEmpty() && !problem.notes().get(0).isBlank()) out.append("      ").append(problem.notes().get(0).strip()).append('\n');
            List<String> imports = imports(file);
            for (String line : explain(problem, file, imports, workingName, workingApi, failingName, failingApi)) out.append("    ").append(line).append('\n');
        }
        build.report.stages.add(Json.map("name", "explain", "seconds", (System.nanoTime() - start) / 1e9));
        // A build that failed before javac ran, or on something javac doesn't report, has no errors to explain.
        if (shown == 0 && exit != 0 && only.isEmpty())
            return Json.map("ok", false, "errors", 0, "log", log.toString(), "tasks", tasks, "details", "The compile failed without compiler errors to explain.\n" + Run.failure(log));
        String summary = shown + " compiler error" + (shown == 1 ? "" : "s") + " in " + perFile.size() + " file" + (perFile.size() == 1 ? "" : "s")
                + (only.isEmpty() ? "" : " matching " + only) + ", " + failingName + " beside " + workingName + ".";
        return Json.map("ok", shown == 0, "errors", shown, "files", perFile, "log", log.toString(), "tasks", tasks,
                "details", summary + (shown == 0 ? "" : "\n" + out.toString().stripTrailing()));
    }

    Map<String, Object> project(String path) throws IOException {
        for (Object p : Json.array(model.get("projects"))) if (path.equals(Json.object(p).get("path"))) return Json.object(p);
        throw new IOException("No project " + path + " in the evaluated build.");
    }
    static String label(String path) { return path.startsWith(":") ? path.substring(1) : path; }
    static List<Path> classpath(Map<String, Object> project) {
        Set<String> entries = new LinkedHashSet<>();
        for (Object c : Json.array(project.get("compile"))) entries.addAll(ProjectModel.strings(Json.object(c).get("classpath")));
        List<Path> paths = new ArrayList<>(); for (String entry : entries) paths.add(Path.of(entry)); return paths;
    }

    /** javac's errors, once each: Gradle repeats them, indented, in its failure summary. */
    static List<Problem> parse(Path log) throws IOException {
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        Pattern head = Pattern.compile("^(\\S.*?\\.java):(\\d+): error: (.*)$");
        List<Problem> problems = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = head.matcher(lines.get(i)); if (!m.matches()) continue;
            List<String> notes = new ArrayList<>();
            for (int j = i + 1; j < lines.size() && j <= i + 10; j++) {
                String line = lines.get(j);
                if (head.matcher(line).matches() || line.matches("^\\d+ errors?$|^\\d+ warnings?$|^Note: .*|^FAILURE.*|^\\* What went wrong.*|^> Task .*")) break;
                notes.add(line);
            }
            Problem problem = new Problem(m.group(1), Integer.parseInt(m.group(2)), m.group(3), notes);
            if (seen.add(problem.file() + ":" + problem.line() + ":" + problem.message() + ":" + note(problem, "symbol:"))) problems.add(problem);
        }
        return problems;
    }
    static String note(Problem problem, String prefix) {
        for (String line : problem.notes()) if (line.strip().startsWith(prefix)) return line.strip().substring(prefix.length()).strip();
        return "";
    }
    /** Stonecutter writes each version's source under build/generated/stonecutter, line for line. */
    String source(String file) {
        String unified = file.replace('\\', '/');
        Matcher generated = Pattern.compile("/build/generated/stonecutter/([^/]+)/(java|kotlin)/(.+)$").matcher(unified);
        Path root = build.project.root;
        if (generated.find()) {
            Path original = root.resolve("src/" + generated.group(1) + "/" + generated.group(2) + "/" + generated.group(3));
            if (Files.isRegularFile(original)) return root.relativize(original).toString().replace('\\', '/');
        }
        try { Path path = Path.of(file).toAbsolutePath().normalize(); if (path.startsWith(root)) return root.relativize(path).toString().replace('\\', '/'); }
        catch (InvalidPathException ignored) { }
        return unified;
    }
    List<String> imports(String file) {
        try {
            List<String> found = new ArrayList<>();
            for (String line : Files.readAllLines(build.project.root.resolve(file))) {
                Matcher m = Pattern.compile("^\\s*(?:/\\*)?import\\s+(?:static\\s+)?([\\w.]+);").matcher(line);
                if (m.find()) found.add(m.group(1));
            }
            return found;
        } catch (Exception unreadable) { return List.of(); }
    }
    List<String> sourceLines(String file) {
        try { return Files.readAllLines(build.project.root.resolve(file)); } catch (Exception unreadable) { return List.of(); }
    }

    /** One line per version for each class or member the error is about. */
    List<String> explain(Problem problem, String file, List<String> imports, String workingName, Index working, String failingName, Index failing) {
        String message = problem.message(), symbol = note(problem, "symbol:"), location = note(problem, "location:");
        List<String> out = new ArrayList<>();
        Matcher m;
        if (message.startsWith("cannot find symbol") && !symbol.isEmpty()) {
            String[] parts = symbol.split("\\s+", 2); String kind = parts[0], name = parts.length > 1 ? parts[1].replaceFirst("\\(.*$", "").replaceFirst("<.*$", "") : "";
            String owner = owner(location);
            if (kind.equals("class") || kind.equals("interface") || (kind.equals("variable") && Character.isUpperCase(name.charAt(0)) && (owner.isEmpty() || location.startsWith("class ") || location.startsWith("package ")))) {
                out.addAll(classLines(name, imports, workingName, working, failingName, failing));
                if (kind.equals("class") || kind.equals("interface")) return out;
            }
            if (!owner.isEmpty() && !location.startsWith("package ")) out.addAll(memberLines(owner, name, imports, workingName, working, failingName, failing));
        } else if ((m = Pattern.compile("^package ([\\w.]+) does not exist").matcher(message)).find()) {
            String pkg = m.group(1), code = problem.notes().isEmpty() ? "" : problem.notes().get(0);
            Matcher used = Pattern.compile(Pattern.quote(pkg) + "\\.(\\w+)").matcher(code);
            String name = used.find() ? used.group(1) : pkg.substring(pkg.lastIndexOf('.') + 1);
            // "package Outer does not exist" for Outer.Inner: the outer class is what's missing.
            if (Character.isUpperCase(pkg.substring(pkg.lastIndexOf('.') + 1).charAt(0))) name = pkg.substring(pkg.lastIndexOf('.') + 1);
            out.addAll(classLines(name, imports, workingName, working, failingName, failing));
        } else if ((m = Pattern.compile("^(?:method|constructor) (\\w+) in (?:class|interface|enum|record) ([\\w.$]+)(?:<[^>]*>)? cannot be applied").matcher(message)).find()) {
            String owner = m.group(2), name = message.startsWith("constructor") ? "<init>" : m.group(1);
            out.addAll(memberLines(owner, name, imports, workingName, working, failingName, failing));
        } else if ((m = Pattern.compile("^no suitable (method|constructor) found for (\\w+)").matcher(message)).find()) {
            String name = m.group(1).equals("constructor") ? "<init>" : m.group(2), owner = m.group(1).equals("constructor") ? m.group(2) : "";
            for (String note : problem.notes()) {
                Matcher candidate = Pattern.compile("(?:method|constructor) ([\\w.$]+)\\.(?:<[^>]*>)?(\\w+)\\(").matcher(note.strip());
                if (owner.isEmpty() && candidate.find()) owner = candidate.group(1);
            }
            if (!owner.isEmpty()) out.addAll(memberLines(owner, name, imports, workingName, working, failingName, failing));
        } else if ((m = Pattern.compile("^(\\w+) is not abstract and does not override abstract method (\\w+)\\(.*\\) in ([\\w.$]+)").matcher(message)).find()) {
            out.addAll(memberLines(m.group(3), m.group(2), imports, workingName, working, failingName, failing));
        } else if ((m = Pattern.compile("^(\\w+)(?:\\(.*\\))? has (?:private|protected) access in ([\\w.$]+)").matcher(message)).find()) {
            out.addAll(memberLines(m.group(2), m.group(1), imports, workingName, working, failingName, failing));
        } else if (message.startsWith("method does not override or implement a method from a supertype")) {
            // javac points at @Override; the method and the class it extends are in the source.
            List<String> lines = sourceLines(file);
            String method = "", parent = "";
            for (int i = problem.line() - 1; i >= 0 && i < lines.size() && i < problem.line() + 4; i++) {
                Matcher declared = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(lines.get(i).replaceFirst("^.*?@Override", ""));
                while (declared.find()) if (!Set.of("if", "for", "while", "switch", "catch", "return", "new").contains(declared.group(1))) { method = declared.group(1); break; }
                if (!method.isEmpty()) break;
            }
            for (int i = Math.min(problem.line() - 1, lines.size() - 1); i >= 0 && parent.isEmpty(); i--) {
                Matcher extended = Pattern.compile("\\bclass\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+([\\w.]+)").matcher(lines.get(i));
                if (extended.find()) parent = extended.group(1);
            }
            if (!method.isEmpty() && !parent.isEmpty()) out.addAll(memberLines(parent, method, imports, workingName, working, failingName, failing));
        }
        return out;
    }
    static String owner(String location) {
        if (location.isEmpty()) return "";
        Matcher typed = Pattern.compile("of type ([\\w.$]+)").matcher(location);
        if (typed.find()) return typed.group(1);
        Matcher declared = Pattern.compile("^(?:class|interface|enum|record|package) ([\\w.$]+)").matcher(location);
        return declared.find() ? declared.group(1) : "";
    }

    List<String> classLines(String name, List<String> imports, String workingName, Index working, String failingName, Index failing) {
        List<String> out = new ArrayList<>();
        List<String> inWorking = working.resolve(name, imports), inFailing = failing.resolve(name, imports);
        out.add(workingName + "  " + (inWorking.isEmpty() ? "no class " + name : String.join(", ", inWorking.stream().map(Index::dotted).toList())));
        if (!inFailing.isEmpty()) out.add(failingName + "  " + String.join(", ", inFailing.stream().map(Index::dotted).toList()));
        else {
            String packageHint = inWorking.isEmpty() ? "" : inWorking.get(0).substring(0, Math.max(0, inWorking.get(0).lastIndexOf('/')));
            List<String> similar = failing.similarClasses(name, packageHint);
            out.add(failingName + "  no class " + name + (similar.isEmpty() ? "" : ". Similar: " + String.join(", ", similar.stream().map(Index::dotted).toList())));
        }
        return out;
    }
    /**
     * The JDK's classes aren't on a project's classpath, so a same-named library class (JOML's
     * Math) would stand in for java.lang.Math. Name them instead.
     */
    static String jdkClass(String owner, List<String> imports) {
        String plain = owner.replaceFirst("<.*$", "");
        if (plain.startsWith("java.") || plain.startsWith("javax.")) return plain;
        String first = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        for (String imported : imports) if (imported.endsWith("." + first)) return imported.startsWith("java.") || imported.startsWith("javax.") ? imported + plain.substring(first.length()) : null;
        try { Class.forName("java.lang." + first); return "java.lang." + plain; } catch (ClassNotFoundException | LinkageError notInJavaLang) { return null; }
    }
    List<String> memberLines(String owner, String name, List<String> imports, String workingName, Index working, String failingName, Index failing) {
        String jdk = jdkClass(owner, imports);
        if (jdk != null) return List.of("JDK  " + jdk + "." + (name.equals("<init>") ? "<init>" : name) + " is Java's, not a jar's: if it builds on " + workingName
                + ", it's likely newer than the Java release " + failingName + " compiles for.");
        List<String> out = new ArrayList<>();
        for (String[] side : List.of(new String[] {workingName, "w"}, new String[] {failingName, "f"})) {
            Index index = side[1].equals("w") ? working : failing;
            List<String> classes = index.resolve(owner, imports);
            if (classes.isEmpty()) { out.add(side[0] + "  no class " + owner); continue; }
            String type = classes.get(0);
            List<Member> all = index.members(type);
            List<Member> named = all.stream().filter(member -> member.name().equals(name)).toList();
            String simple = Index.simple(type);
            if (!named.isEmpty()) {
                out.add(side[0] + "  " + simple + ": " + String.join("; ", named.stream().limit(6).map(member -> render(member, type)).toList()));
            } else {
                List<Member> similar = Index.similarMembers(all, name);
                String line = side[0] + "  " + simple + ": no " + (name.equals("<init>") ? "constructor" : name)
                        + (similar.isEmpty() ? "" : ". Similar: " + String.join("; ", similar.stream().map(member -> render(member, type)).toList()));
                // A rename with no word in common (openWorld, loadLevel) shows up among the names
                // the other project doesn't have.
                if (similar.isEmpty() && side[1].equals("f")) {
                    List<String> others = working.resolve(owner, imports);
                    Set<String> known = new HashSet<>();
                    if (!others.isEmpty()) for (Member member : working.members(others.get(0))) known.add(member.name());
                    List<Member> only = all.stream().filter(member -> member.owner().equals(type) && !known.contains(member.name()) && !member.name().equals("<init>") && (member.access() & 0x0002) == 0).limit(8).toList();
                    if (!others.isEmpty() && !only.isEmpty()) line += ". Only in " + failingName + ": " + String.join("; ", only.stream().map(member -> render(member, type)).toList());
                }
                out.add(line);
            }
        }
        return out;
    }
    static String render(Member member, String viewedFrom) {
        StringBuilder text = new StringBuilder();
        if ((member.access() & 0x0004) != 0) text.append("protected ");
        if ((member.access() & 0x0002) != 0) text.append("private ");
        if ((member.access() & 0x0008) != 0) text.append("static ");
        if (!member.method()) return text.append(Index.type(member.descriptor(), new int[1])).append(' ').append(member.name()).append(inherited(member, viewedFrom)).toString();
        int[] position = {1}; List<String> parameters = new ArrayList<>();
        while (member.descriptor().charAt(position[0]) != ')') parameters.add(Index.type(member.descriptor(), position));
        position[0]++;
        String returned = Index.type(member.descriptor(), position);
        if (member.name().equals("<init>")) text.append(Index.simple(member.owner()));
        else text.append(returned).append(' ').append(member.name());
        return text.append('(').append(String.join(", ", parameters)).append(')').append(inherited(member, viewedFrom)).toString();
    }
    static String inherited(Member member, String viewedFrom) { return member.owner().equals(viewedFrom) ? "" : " [" + Index.simple(member.owner()) + "]"; }

    /** Class names and members read straight from a classpath's jars and class folders. */
    static final class Index {
        final Map<String, Path> location = new HashMap<>();
        final Map<String, List<String>> bySimple = new HashMap<>();
        final Map<String, ClassInfo> read = new HashMap<>();
        Index(List<Path> classpath) throws IOException {
            for (Path entry : classpath) {
                if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                    try (ZipFile jar = new ZipFile(entry.toFile())) {
                        var items = jar.entries();
                        while (items.hasMoreElements()) add(items.nextElement().getName(), entry);
                    } catch (IOException unreadable) { }
                } else if (Files.isDirectory(entry)) {
                    try (var walk = Files.walk(entry)) { for (Path file : walk.filter(Files::isRegularFile).toList()) add(entry.relativize(file).toString().replace('\\', '/'), entry); }
                }
            }
        }
        void add(String entry, Path from) {
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.endsWith("module-info.class") || entry.endsWith("package-info.class")) return;
            String name = entry.substring(0, entry.length() - 6);
            if (location.putIfAbsent(name, from) != null) return;
            String simple = name.substring(name.lastIndexOf('/') + 1);
            if (simple.matches(".*\\$\\d.*")) return;
            bySimple.computeIfAbsent(simple.replace('$', '.'), key -> new ArrayList<>()).add(name);
            if (simple.contains("$")) bySimple.computeIfAbsent(simple.substring(simple.lastIndexOf('$') + 1), key -> new ArrayList<>()).add(name);
        }
        /** A simple, nested or qualified name; an import of it, if any, decides between same-named classes. */
        List<String> resolve(String name, List<String> imports) {
            String plain = name.replaceFirst("<.*$", "");
            if (plain.contains(".") && Character.isLowerCase(plain.charAt(0))) {
                String internal = qualified(plain); if (internal != null) return List.of(internal);
                plain = plain.substring(plain.lastIndexOf('.') + 1);
            }
            String first = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
            for (String imported : imports) if (imported.endsWith("." + first)) {
                String internal = qualified(imported + plain.substring(first.length()));
                if (internal != null) return List.of(internal);
            }
            List<String> found = new ArrayList<>(bySimple.getOrDefault(plain, List.of()));
            found.sort(Comparator.comparing((String n) -> !n.startsWith("net/minecraft/") && !n.startsWith("com/mojang/")).thenComparing(n -> n));
            return found.size() > 4 ? found.subList(0, 4) : found;
        }
        /** a.b.Outer.Inner to a/b/Outer$Inner, if the classpath has it. */
        String qualified(String dotted) {
            String internal = dotted.replace('.', '/');
            while (true) {
                if (location.containsKey(internal)) return internal;
                int dot = internal.lastIndexOf('/'); if (dot < 0) return null;
                internal = internal.substring(0, dot) + "$" + internal.substring(dot + 1);
            }
        }
        ClassInfo info(String name) {
            if (read.containsKey(name)) return read.get(name);
            ClassInfo value = null;
            Path from = location.get(name);
            if (from != null) {
                try {
                    byte[] bytes;
                    if (Files.isDirectory(from)) bytes = Files.readAllBytes(from.resolve(name + ".class"));
                    else try (ZipFile jar = new ZipFile(from.toFile()); var input = jar.getInputStream(jar.getEntry(name + ".class"))) { bytes = input.readAllBytes(); }
                    value = parse(bytes);
                } catch (Exception unreadable) { }
            }
            read.put(name, value); return value;
        }
        /** Declared and inherited members, nearest first, without synthetic ones or lambdas. */
        List<Member> members(String name) {
            List<Member> found = new ArrayList<>(); Set<String> visited = new HashSet<>(), signatures = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(List.of(name));
            while (!queue.isEmpty() && visited.size() < 40) {
                String current = queue.poll(); if (!visited.add(current)) continue;
                ClassInfo info = info(current); if (info == null) continue;
                for (Member member : info.members()) {
                    if ((member.access() & 0x1000) != 0 || member.name().startsWith("lambda$") || member.name().equals("<clinit>")) continue;
                    if (!current.equals(name) && (member.name().equals("<init>") || (member.access() & 0x0002) != 0)) continue;
                    if (signatures.add(member.name() + member.descriptor())) found.add(member);
                }
                if (info.superName() != null) queue.add(info.superName());
                queue.addAll(info.interfaces());
            }
            return found;
        }
        List<String> similarClasses(String name, String packageHint) {
            String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            List<String[]> scored = new ArrayList<>();
            for (var entry : bySimple.entrySet()) {
                if (entry.getKey().contains(".")) continue;
                double score = similarity(simple, entry.getKey());
                if (score < 0.5) continue;
                for (String internal : entry.getValue()) {
                    double total = score + (internal.startsWith(packageHint + "/") ? 0.5 : 0);
                    scored.add(new String[] {String.valueOf(total), internal});
                }
            }
            scored.sort(Comparator.comparing((String[] s) -> -Double.parseDouble(s[0])).thenComparing(s -> s[1]));
            return scored.stream().limit(4).map(s -> s[1]).toList();
        }
        static List<Member> similarMembers(List<Member> members, String name) {
            if (name.equals("<init>")) return members.stream().filter(member -> member.name().equals("<init>")).limit(6).toList();
            List<Member> scored = new ArrayList<>(members.stream().filter(member -> !member.name().equals("<init>") && similarity(name, member.name()) >= 0.5).toList());
            scored.sort(Comparator.comparing((Member member) -> -similarity(name, member.name())).thenComparing(Member::name));
            return scored.size() > 6 ? scored.subList(0, 6) : scored;
        }
        /** Shared camel-case words over all words, or an edit or two apart. */
        static double similarity(String a, String b) {
            if (a.equalsIgnoreCase(b)) return 1;
            Set<String> x = words(a), y = words(b), both = new HashSet<>(x); both.retainAll(y);
            Set<String> any = new HashSet<>(x); any.addAll(y);
            double shared = any.isEmpty() ? 0 : (double) both.size() / any.size();
            return distance(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT)) <= 2 && Math.min(a.length(), b.length()) > 4 ? Math.max(shared, 0.75) : shared;
        }
        static Set<String> words(String name) {
            Set<String> words = new HashSet<>();
            for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|_|(?<=[A-Z])(?=[A-Z][a-z])")) if (!word.isEmpty()) words.add(word.toLowerCase(Locale.ROOT));
            return words;
        }
        static int distance(String a, String b) {
            int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) previous[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                current[0] = i;
                for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                int[] swap = previous; previous = current; current = swap;
            }
            return previous[b.length()];
        }
        static String dotted(String internal) { return internal.replace('/', '.').replace('$', '.'); }
        static String simple(String internal) { return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.'); }
        static String type(String descriptor, int[] position) {
            char c = descriptor.charAt(position[0]++);
            return switch (c) {
                case 'B' -> "byte"; case 'C' -> "char"; case 'D' -> "double"; case 'F' -> "float"; case 'I' -> "int";
                case 'J' -> "long"; case 'S' -> "short"; case 'Z' -> "boolean"; case 'V' -> "void";
                case '[' -> type(descriptor, position) + "[]";
                case 'L' -> { int end = descriptor.indexOf(';', position[0]); String name = descriptor.substring(position[0], end); position[0] = end + 1; yield simple(name); }
                default -> "?";
            };
        }
        /** Just enough of the class file format: names, the superclass, interfaces, fields and methods. */
        static ClassInfo parse(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Not a class file");
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort(); Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    case 9, 10, 11, 12, 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    default -> throw new IOException("Unknown constant pool tag " + tag);
                }
            }
            int access = in.readUnsignedShort();
            String name = (String) pool[(Integer) pool[in.readUnsignedShort()]];
            int superIndex = in.readUnsignedShort();
            String superName = superIndex == 0 ? null : (String) pool[(Integer) pool[superIndex]];
            List<String> interfaces = new ArrayList<>();
            for (int i = in.readUnsignedShort(); i > 0; i--) interfaces.add((String) pool[(Integer) pool[in.readUnsignedShort()]]);
            List<Member> members = new ArrayList<>();
            for (int kind = 0; kind < 2; kind++) {
                for (int i = in.readUnsignedShort(); i > 0; i--) {
                    int flags = in.readUnsignedShort(); String member = (String) pool[in.readUnsignedShort()], descriptor = (String) pool[in.readUnsignedShort()];
                    for (int a = in.readUnsignedShort(); a > 0; a--) { in.readUnsignedShort(); in.skipNBytes(in.readInt()); }
                    members.add(new Member(name, member, descriptor, flags, kind == 1));
                }
            }
            return new ClassInfo(name, superName, interfaces, access, members);
        }
    }
}
