import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.*;

/**
 * Runs a Java snippet inside the running game. The snippet is compiled against the game's own
 * classpath and loaded by a child of the loader that loaded Minecraft, so it sees the game and the
 * mod exactly as they are running, mixins included. Values stored in {@code vars} survive between
 * snippets, so a session can build up state the way a REPL does.
 */
final class Console {
    private static final AtomicInteger counter = new AtomicInteger();
    static final Map<String, Object> vars = Collections.synchronizedMap(new LinkedHashMap<String, Object>());

    /** A compiled snippet, ready to run on whichever thread the caller picks. */
    static final class Snippet {
        final Class<?> type; final double compileSeconds; final int bodyLine;
        Snippet(Class<?> type, double compileSeconds, int bodyLine) { this.type = type; this.compileSeconds = compileSeconds; this.bodyLine = bodyLine; }
        Map<String, Object> run() throws Exception {
            Object instance = type.getDeclaredConstructor().newInstance();
            StringBuilder out = new StringBuilder();
            type.getField("out").set(instance, out);
            long start = System.nanoTime();
            Object value;
            try { value = type.getMethod("run", Map.class).invoke(instance, vars); }
            catch (java.lang.reflect.InvocationTargetException thrown) { throw new SnippetFailure(trace(thrown.getCause(), type.getName(), bodyLine), out.toString()); }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", true);
            result.put("value", show(value));
            result.put("type", value == null ? "null" : value.getClass().getName());
            if (out.length() > 0) result.put("output", clip(out.toString()));
            result.put("compileSeconds", compileSeconds);
            result.put("runSeconds", (System.nanoTime() - start) / 1e9);
            return result;
        }
    }

    static final class SnippetFailure extends Exception {
        final String output;
        SnippetFailure(String message, String output) { super(message); this.output = output; }
    }

    static Snippet compile(String code, ClassLoader game) throws Exception {
        long start = System.nanoTime();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("The game runs on a JRE without a Java compiler; run it on a JDK to use eval");
        List<String> imports = new ArrayList<String>(); StringBuilder body = new StringBuilder();
        for (String line : code.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (body.toString().trim().isEmpty() && trimmed.startsWith("import ") && trimmed.endsWith(";")) imports.add(trimmed);
            else body.append(line).append('\n');
        }
        String text = body.toString().trim();
        boolean minecraft = loads(game, "net.minecraft.client.Minecraft");
        // Fabric builds on Yarn names call the same class MinecraftClient; mc must exist there too.
        boolean yarn = !minecraft && loads(game, "net.minecraft.client.MinecraftClient");
        // An expression is returned as it is; statements return what they return, or null.
        List<String> forms = new ArrayList<String>();
        if (!text.endsWith(";") && !text.endsWith("}")) { forms.add("return " + text + ";"); forms.add(text + ";\nreturn null;"); }
        forms.add(text + "\nreturn null;");
        forms.add(text);
        String firstErrors = null;
        for (String form : forms) {
            String name = "ModaccelSnippet" + counter.incrementAndGet();
            StringBuilder source = new StringBuilder();
            source.append("import java.util.*;\nimport java.util.stream.*;\n");
            if (minecraft) source.append("import net.minecraft.client.Minecraft;\n");
            for (String line : imports) source.append(line).append('\n');
            source.append("public final class ").append(name).append(" {\n");
            source.append("  public StringBuilder out;\n");
            source.append("  void print(Object value) { out.append(String.valueOf(value)).append('\\n'); }\n");
            source.append("  @SuppressWarnings(\"unchecked\") <T> T get(String key) { return (T) vars.get(key); }\n");
            source.append("  Map<String, Object> vars;\n");
            // Reach private and package-private members: snippets live in another package and loader.
            source.append("  static Object call(Object target, String name, Object... args) throws Exception {\n"
                    + "    Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();\n"
                    + "    for (Class<?> c = type; c != null; c = c.getSuperclass()) for (java.lang.reflect.Method m : c.getDeclaredMethods())\n"
                    + "      if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true);\n"
                    + "        return m.invoke(java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : target, args); }\n"
                    + "    throw new NoSuchMethodException(type.getName() + \".\" + name + \" with \" + args.length + \" arguments\");\n  }\n");
            source.append("  static Object field(Object target, String name) throws Exception {\n"
                    + "    Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();\n"
                    + "    for (Class<?> c = type; c != null; c = c.getSuperclass()) try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true);\n"
                    + "      return f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : target); } catch (NoSuchFieldException next) { }\n"
                    + "    throw new NoSuchFieldException(type.getName() + \".\" + name);\n  }\n");
            source.append("  public Object run(Map<String, Object> vars) throws Throwable {\n    this.vars = vars;\n");
            if (minecraft) source.append("    final Minecraft mc = Minecraft.getInstance();\n");
            else if (yarn) source.append("    final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();\n");
            int bodyLine = source.toString().split("\n", -1).length;
            source.append(form).append("\n  }\n}\n");
            Map<String, ByteArrayOutputStream> classes = new LinkedHashMap<String, ByteArrayOutputStream>();
            String errors = compile(compiler, name, source.toString(), classes, bodyLine);
            if (errors == null) return new Snippet(define(game, classes).loadClass(name), (System.nanoTime() - start) / 1e9, bodyLine);
            if (firstErrors == null) firstErrors = errors;
            // "unreachable statement" or "missing return" mean the snippet returns by itself; try the next form.
        }
        throw new IllegalArgumentException("Snippet did not compile:\n" + firstErrors);
    }

    private static String compile(JavaCompiler compiler, String name, final String source, final Map<String, ByteArrayOutputStream> classes, int bodyLine) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
        JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); classes.put(className, bytes);
                return new SimpleJavaFileObject(URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() { return bytes; }
                };
            }
        };
        JavaFileObject file = new SimpleJavaFileObject(URI.create("memory:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<String> options = Arrays.asList("-classpath", System.getProperty("java.class.path"), "-proc:none", "-g", "-nowarn");
        boolean ok = compiler.getTask(null, memory, diagnostics, options, null, Collections.singletonList(file)).call();
        memory.close();
        if (ok) return null;
        StringBuilder errors = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            long line = d.getLineNumber() - bodyLine + 1;
            errors.append(line >= 1 ? "line " + line + ": " : "").append(d.getMessage(Locale.ROOT)).append('\n');
        }
        return errors.toString().trim();
    }

    private static ClassLoader define(ClassLoader game, final Map<String, ByteArrayOutputStream> classes) {
        return new ClassLoader(game) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                byte[] code = bytes.toByteArray();
                return defineClass(name, code, 0, code.length);
            }
        };
    }

    private static boolean loads(ClassLoader loader, String name) {
        try { Class.forName(name, false, loader); return true; } catch (Throwable missing) { return false; }
    }

    static String show(Object value) {
        if (value == null) return "null";
        if (value instanceof Object[]) return clip(Arrays.deepToString((Object[]) value));
        if (value.getClass().isArray()) {
            try { return clip((String) Arrays.class.getMethod("toString", value.getClass()).invoke(null, value)); }
            catch (Exception ignored) { }
        }
        return clip(String.valueOf(value));
    }

    private static String clip(String text) { return text.length() <= 20000 ? text : text.substring(0, 20000) + "\n... (" + (text.length() - 20000) + " more characters)"; }

    /** The stack trace down to the snippet's own frame; the reflection frames below it are ours. */
    static String trace(Throwable error, String snippet, int bodyLine) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current == error ? "" : "Caused by: ").append(current).append('\n');
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().equals(snippet)) {
                    text.append("    at snippet line ").append(frame.getLineNumber() - bodyLine + 1).append('\n');
                    break;
                }
                text.append("    at ").append(frame).append('\n');
            }
        }
        return clip(text.toString().trim());
    }
}
