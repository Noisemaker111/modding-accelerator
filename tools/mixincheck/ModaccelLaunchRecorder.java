import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * A Java agent that writes down how the build started the game's JVM, then stops it before the
 * game's main class runs. Some loader plugins (NeoGradle 7, ForgeGradle) only assemble the class path,
 * module path and program arguments inside Gradle's exec step, after any task hook could read them,
 * so the one reliable place to see the real launch is inside the launched JVM itself.
 *
 * Usage: -javaagent:recorder.jar=OUTPUT_FILE. Records the JVM options (with @argfiles already
 * expanded by the java launcher), the class and module paths, the main class with its arguments, and
 * the working directory. Nothing from the environment is recorded.
 */
public final class ModaccelLaunchRecorder {
    public static void premain(String output, java.lang.instrument.Instrumentation instrumentation) throws Exception {
        List<String> options = new ArrayList<String>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        StringBuilder json = new StringBuilder("{");
        json.append("\"java\":").append(quote(System.getProperty("java.home"))).append(',');
        json.append("\"directory\":").append(quote(System.getProperty("user.dir"))).append(',');
        json.append("\"classPath\":").append(quote(System.getProperty("java.class.path", ""))).append(',');
        json.append("\"modulePath\":").append(quote(System.getProperty("jdk.module.path", ""))).append(',');
        json.append("\"command\":").append(quote(System.getProperty("sun.java.command", ""))).append(',');
        json.append("\"options\":[");
        for (int i = 0; i < options.size(); i++) json.append(i == 0 ? "" : ",").append(quote(options.get(i)));
        json.append("]}");
        Writer out = new OutputStreamWriter(new FileOutputStream(output), "UTF-8");
        try { out.write(json.toString()); } finally { out.close(); }
        System.out.println("MODACCEL_RECORDED " + output);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"') quoted.append("\\\"");
            else if (c == '\\') quoted.append("\\\\");
            else if (c < 0x20) quoted.append(String.format("\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
