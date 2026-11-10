import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.ToolProvider;

/**
 * The hidden-window transform keeps a game's window hidden and records that it did, with no error,
 * even when the launch names the agent twice (recorded launches did: every saved 26.3 launch logged
 * MODACCEL_BACKGROUND_ERROR and hooked each frame twice). Stand-in org.lwjgl classes copy LWJGL
 * 3.4.3's SDL call shapes and fail if a call reaches "native" code, so a window that would show is caught.
 *
 *   java tools/tests/BackgroundWindowsTest.java PROJECT_ROOT path/to/modaccel.jar
 *
 * PROJECT_ROOT is a project modaccel can read (it builds the agent with that project's ASM).
 */
public class BackgroundWindowsTest {

    static final String JNI =
        """
        package org.lwjgl.system;
        public final class JNI {
            static void reached(String what) { throw new IllegalStateException("native window call reached: " + what); }
            public static long invokePJP(long title, int w, int h, long flags, long function) { return flags; }
            public static boolean invokePZ(long window, long function) { reached("PZ(JJ)"); return false; }
            public static boolean invokePZ(long window, boolean on, long function) { reached("PZ(JZJ)"); return false; }
            public static void invokePV(long window, float x, float y, long function) { reached("PV(JFFJ)"); }
            public static boolean invokeZ(float x, float y, long function) { reached("Z(FFJ)"); return false; }
        }
        """;
    static final String VIDEO =
        """
        package org.lwjgl.sdl;
        import org.lwjgl.system.JNI;
        public class SDLVideo {
            static long f = 1;
            public static long nSDL_CreateWindow(long title, int w, int h, long flags) { long fn = f; return JNI.invokePJP(title, w, h, flags, fn); }
            public static boolean SDL_ShowWindow(long window) { long fn = f; return JNI.invokePZ(window, fn); }
            public static boolean SDL_RaiseWindow(long window) { long fn = f; return JNI.invokePZ(window, fn); }
            public static boolean SDL_SetWindowMouseGrab(long window, boolean on) { long fn = f; return JNI.invokePZ(window, on, fn); }
            public static boolean SDL_SetWindowKeyboardGrab(long window, boolean on) { long fn = f; return JNI.invokePZ(window, on, fn); }
        }
        """;
    static final String MOUSE =
        """
        package org.lwjgl.sdl;
        import org.lwjgl.system.JNI;
        public class SDLMouse {
            static long f = 1;
            public static boolean SDL_SetWindowRelativeMouseMode(long window, boolean on) { long fn = f; return JNI.invokePZ(window, on, fn); }
            public static void SDL_WarpMouseInWindow(long window, float x, float y) { long fn = f; JNI.invokePV(window, x, y, fn); }
            public static boolean SDL_WarpMouseGlobal(float x, float y) { long fn = f; return JNI.invokeZ(x, y, fn); }
        }
        """;
    static final String PROBE =
        """
        import org.lwjgl.sdl.*;
        public class Probe {
            public static void main(String[] a) {
                long flags = SDLVideo.nSDL_CreateWindow(0, 1280, 720, 0);
                if ((flags & 8) == 0) throw new IllegalStateException("window created without SDL_WINDOW_HIDDEN, flags " + flags);
                SDLVideo.SDL_ShowWindow(1); SDLVideo.SDL_RaiseWindow(1);
                SDLVideo.SDL_SetWindowMouseGrab(1, true); SDLVideo.SDL_SetWindowKeyboardGrab(1, true);
                SDLMouse.SDL_SetWindowRelativeMouseMode(1, true); SDLMouse.SDL_WarpMouseInWindow(1, 2, 3); SDLMouse.SDL_WarpMouseGlobal(2, 3);
                String applied = System.getProperty("modaccel.background.applied", ""), error = System.getProperty("modaccel.background.error", "");
                if (!error.isEmpty()) throw new IllegalStateException("agent recorded an error: " + error);
                if (!applied.equals("org/lwjgl/sdl/SDLVideo,org/lwjgl/sdl/SDLMouse")) throw new IllegalStateException("agent recorded applied=" + applied);
                System.out.println("HIDDEN");
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(), modaccel = Path.of(args[1]).toAbsolutePath();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process built = new ProcessBuilder(
            java,
            "-jar",
            modaccel.toString(),
            "legacy-agent",
            "--root",
            root.toString(),
            "--json"
        )
            .redirectErrorStream(true)
            .start();
        String json = new String(built.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (built.waitFor() != 0) throw new AssertionError("legacy-agent failed:\n" + json);
        Matcher m = Pattern.compile("\"agent\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new AssertionError("no agent path in:\n" + json);
        String agent = m.group(1).replace("\\\\", "\\");

        Path dir = Files.createTempDirectory("modaccel-background");
        Map<String, String> sources = Map.of(
            "org/lwjgl/system/JNI.java",
            JNI,
            "org/lwjgl/sdl/SDLVideo.java",
            VIDEO,
            "org/lwjgl/sdl/SDLMouse.java",
            MOUSE,
            "Probe.java",
            PROBE
        );
        List<String> files = new ArrayList<>(
            List.of("--release", "8", "-nowarn", "-d", dir.resolve("classes").toString())
        );
        for (var e : sources.entrySet()) {
            Path f = dir.resolve("src").resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        if (
            ToolProvider.getSystemJavaCompiler().run(null, null, null, files.toArray(String[]::new)) != 0
        ) throw new AssertionError("stand-in classes did not compile");

        int checks = 0;
        // Once and twice: the window stays hidden and nothing reaches native code.
        for (int copies : new int[] { 1, 2 }) {
            String out = probe(java, agent, dir, copies, true);
            if (!out.contains("HIDDEN")) throw new AssertionError(
                "agent named " + copies + "x: window not hidden:\n" + out
            );
            checks++;
        }
        // The stand-ins can fail: without the hidden preference the first native call is caught.
        String visible = probe(java, agent, dir, 1, false);
        if (!visible.contains("window created without SDL_WINDOW_HIDDEN")) throw new AssertionError(
            "stand-ins did not catch a visible window:\n" + visible
        );
        checks++;
        System.out.println(checks + " hidden-window checks passed");
    }

    static String probe(String java, String agent, Path dir, int copies, boolean background) throws Exception {
        List<String> command = new ArrayList<>(List.of(java));
        for (int i = 0; i < copies; i++) command.add("-javaagent:" + agent + "=legacy");
        command.addAll(
            List.of("-Dmodaccel.background=" + background, "-cp", dir.resolve("classes").toString(), "Probe")
        );
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }
}
