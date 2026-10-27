import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When the user chooses a quiet game, the agent calls {@link #seedArgs(String[])} before
 * Minecraft reads options.txt, so the first-launch accessibility
 * screen (which turns the narrator on) never appears and the master volume starts at zero.
 * Compiled into the agent with --release 8, so it uses only Java 8 APIs.
 *
 * The keys are the same from 1.14 through 26.3 (checked in 1.20.1, 1.21.1, 1.21.6 and 26.3 files);
 * a version that does not know a key ignores it. Other keys in an existing file are kept as they are.
 */
final class QuietOptions {
    static final Map<String, String> VALUES = new LinkedHashMap<>();
    static {
        VALUES.put("narrator", "0");
        VALUES.put("onboardAccessibility", "false");
        VALUES.put("soundCategory_master", "0.0");
        VALUES.put("tutorialStep", "none");
        VALUES.put("skipMultiplayerWarning", "true");
        VALUES.put("joinedFirstServer", "true");
    }
    private QuietOptions() {}

    /** Seeds the game's options before Minecraft reads them. */
    static void seedArgs(String[] args) {
        try {
            Path directory = Paths.get(System.getProperty("user.dir"));
            for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--gameDir")) {
                Path selected = Paths.get(args[i + 1]);
                directory = selected.isAbsolute() ? selected : directory.resolve(selected);
                break;
            }
            seed(directory);
        } catch (Exception error) { throw new IllegalStateException("Cannot start Minecraft quietly: could not set its sound and narrator options", error); }
    }

    static Path seed(Path gameDirectory) throws IOException {
        Path file = gameDirectory.resolve("options.txt"); Files.createDirectories(gameDirectory);
        String existing = Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
        Files.write(file, merge(existing).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Sets the quiet values, keeps every other line and its order, appends keys that were missing. */
    static String merge(String existing) {
        List<String> lines = new ArrayList<>(); Map<String, String> missing = new LinkedHashMap<>(VALUES);
        for (String line : existing.split("\r?\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':'); String key = colon < 0 ? line : line.substring(0, colon);
            if (VALUES.containsKey(key)) { lines.add(key + ":" + VALUES.get(key)); missing.remove(key); }
            else lines.add(line);
        }
        missing.forEach((key, value) -> lines.add(key + ":" + value));
        return String.join("\n", lines) + "\n";
    }
}
