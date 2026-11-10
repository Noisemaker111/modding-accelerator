import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Starts the game's code without starting the game, so data-check can read what the game would load.
 *
 * modaccel starts this in place of the game's main class, inside the mod's own dev launch (same Java,
 * class path, loader properties), like mixin-check. It sets Fabric Loader's Knot up the way
 * fabric-loader-junit does, on the dedicated-server side (the side that loads data packs), then runs
 * ModaccelDataProbe through Knot's class loader so the probe sees the game exactly as the game does:
 * Mixin applied, mods loaded. No window, no world, no network.
 *
 * Output: one JSON object per line, appended to the file named by -Dmodaccel.datacheck.out.
 */
public final class ModaccelDataCheck {

    public static void main(String[] args) throws Throwable {
        String out = System.getProperty("modaccel.datacheck.out");
        new FileOutputStream(out).close();
        ClassLoader game;
        try {
            Class<?> envType = Class.forName("net.fabricmc.api.EnvType");
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object server = Enum.valueOf((Class) envType, "SERVER");
            Class<?> knot = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
            Object instance = knot.getConstructor(envType).newInstance(server);
            game = (ClassLoader) knot.getMethod("init", String[].class).invoke(instance, (Object) args);
        } catch (Throwable failure) {
            fail(out, "loader", failure);
            Runtime.getRuntime().halt(3);
            return;
        }
        Thread.currentThread().setContextClassLoader(game);
        int code = 0;
        try {
            Class.forName("ModaccelDataProbe", true, game).getMethod("run").invoke(null);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException
                ? failure.getCause()
                : failure;
            fail(out, "probe", cause);
            code = 4;
        }
        // The game never started; its background threads must not keep this JVM alive.
        Runtime.getRuntime().halt(code);
    }

    static void fail(String out, String phase, Throwable failure) throws Exception {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        try (
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8)
            )
        ) {
            writer.println(
                "{\"event\":\"error\",\"phase\":" +
                quote(phase) +
                ",\"message\":" +
                quote(String.valueOf(failure)) +
                ",\"trace\":" +
                quote(trace.toString()) +
                "}"
            );
        }
    }

    static String quote(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
