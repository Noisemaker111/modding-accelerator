import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The eval wrapper must name the Minecraft accessor the running client actually has: getInstance on
 * modern Mojang names, getMinecraft on MCP names (1.7.10 through 1.12). Compiles Console against
 * three stub clients and runs a snippet through each: legacy and modern must compile, a client with
 * neither accessor must be refused. The old hardcoded getInstance wrapper fails the legacy case.
 * Console itself is compiled at --release 8 by the check, because the game compiles it in its own
 * Java 8 JVM; this test keeps that level too.
 */
public final class ConsoleAccessorTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toRealPath();
        Path work = root.resolve(".runs/console-tests/work");
        Files.createDirectories(work);
        String consoleClasspath = System.getProperty("java.class.path");
        String[][] stubs = {
            {"legacy", "    public static Minecraft getMinecraft() { return new Minecraft(); }\n"},
            {"modern", "    public static Minecraft getInstance() { return new Minecraft(); }\n"},
            {"neither", "    public static Minecraft notAnAccessor() { return new Minecraft(); }\n"}
        };
        for (String[] stub : stubs) compileStub(work, stub[0], stub[1]);
        Path driver = work.resolve("Driver.java");
        Files.write(driver, (
            "public final class Driver {\n" +
            "    public static void main(String[] args) throws Exception {\n" +
            "        Console.compile(\"return mc != null;\", ClassLoader.getSystemClassLoader());\n" +
            "        System.out.println(\"COMPILED\");\n" +
            "    }\n" +
            "}\n"
        ).getBytes(StandardCharsets.UTF_8));
        Path driverOut = work.resolve("driver");
        Files.createDirectories(driverOut);
        javac(driverOut, consoleClasspath, driver);
        String java = Paths.get(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        int failures = 0;
        for (String[] stub : stubs) {
            String kind = stub[0];
            boolean wantOk = !kind.equals("neither");
            Process process = new ProcessBuilder(java, "-cp",
                consoleClasspath + File.pathSeparator + work.resolve(kind) + File.pathSeparator + driverOut,
                "Driver").redirectErrorStream(true).start();
            String output = new String(readAll(process), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            boolean ok = (exit == 0) == wantOk;
            // The refusal must name the accessor it tried, not fail for some unrelated reason.
            if (kind.equals("neither") && !output.contains("getMinecraft")) ok = false;
            System.out.println((ok ? "ok   " : "FAIL ") + kind + " (exit " + exit + "): " + firstLine(output));
            if (!ok) failures++;
        }
        if (failures > 0) {
            System.out.println("FAIL console accessor: " + failures + " of " + stubs.length + " cases wrong");
            System.exit(1);
        }
        System.out.println("PASS console accessor: legacy getMinecraft, modern getInstance, neither refused");
    }

    private static void compileStub(Path work, String kind, String accessor) throws IOException {
        Path source = work.resolve("src-" + kind + "/net/minecraft/client/Minecraft.java");
        Files.createDirectories(source.getParent());
        Files.write(source, (
            "package net.minecraft.client;\n" +
            "public final class Minecraft {\n" + accessor + "}\n"
        ).getBytes(StandardCharsets.UTF_8));
        javac(work.resolve(kind), null, source);
    }

    private static void javac(Path out, String classpath, Path source) throws IOException {
        Files.createDirectories(out);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null);
        Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(source.toFile());
        java.util.List<String> options = new java.util.ArrayList<String>(Arrays.asList("-d", out.toString(), "-proc:none", "-nowarn"));
        if (classpath != null) options.addAll(Arrays.asList("-classpath", classpath));
        boolean ok = compiler.getTask(null, files, diagnostics, options, null, units).call();
        files.close();
        if (!ok) throw new IOException("javac failed for " + source + ": " + diagnostics.getDiagnostics());
    }

    private static byte[] readAll(Process process) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) bytes.write(buffer, 0, read);
        return bytes.toByteArray();
    }

    private static String firstLine(String text) {
        String trimmed = text.trim();
        int line = trimmed.indexOf('\n');
        return line < 0 ? trimmed : trimmed.substring(0, line);
    }
}
