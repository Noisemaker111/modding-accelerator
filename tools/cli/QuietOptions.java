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
 * The agent calls {@link #launchArgs(String[], boolean)} before Minecraft reads options.txt.
 * A quiet game gets no first-launch accessibility screen (which turns the narrator on) and starts
 * with the master volume at zero; the values it replaced are kept in {@link #SAVED} beside options.txt.
 * A game that is not quiet puts those values back, so turning quiet off brings the sound back.
 * Compiled into the agent with --release 8, so it uses only Java 8 APIs.
 *
 * The keys are the same from 1.14 through 26.3 (checked in 1.20.1, 1.21.1, 1.21.6 and 26.3 files);
 * a version that does not know a key ignores it. Other keys in an existing file are kept as they are.
 */
final class QuietOptions {

    static final Map<String, String> VALUES = new LinkedHashMap<>();
    /** The values quiet replaced, one {@code key:value} per line; a bare {@code key} was absent before. */
    static final String SAVED = "mmt-quiet.txt";

    static {
        VALUES.put("narrator", "0");
        VALUES.put("onboardAccessibility", "false");
        VALUES.put("soundCategory_master", "0.0");
        VALUES.put("tutorialStep", "none");
        VALUES.put("skipMultiplayerWarning", "true");
        VALUES.put("joinedFirstServer", "true");
    }

    private QuietOptions() {}

    /** Seeds a quiet game's options, or restores the ones an earlier quiet game replaced. */
    static void launchArgs(String[] args, boolean quiet) {
        if (quiet) {
            seedArgs(args);
            return;
        }
        try {
            restore(directory(args));
        } catch (Exception error) {
            // The game still starts; it only keeps the quiet values.
            System.err.println("MMT_QUIET_RESTORE_ERROR " + error);
        }
    }

    /** Seeds the game's options before Minecraft reads them. */
    static void seedArgs(String[] args) {
        try {
            seed(directory(args));
        } catch (Exception error) {
            throw new IllegalStateException(
                "Cannot start Minecraft quietly: could not set its sound and narrator options",
                error
            );
        }
    }

    static Path directory(String[] args) {
        Path directory = Paths.get(System.getProperty("user.dir"));
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--gameDir")) {
            Path selected = Paths.get(args[i + 1]);
            return selected.isAbsolute() ? selected : directory.resolve(selected);
        }
        return directory;
    }

    static Path seed(Path gameDirectory) throws IOException {
        Path file = gameDirectory.resolve("options.txt"), saved = gameDirectory.resolve(SAVED);
        Files.createDirectories(gameDirectory);
        String existing = read(file);
        // Saved before options.txt changes, so an interrupted launch never loses the user's values.
        Files.write(saved, save(existing, read(saved)).getBytes(StandardCharsets.UTF_8));
        Files.write(file, merge(existing).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Puts back what quiet replaced, unless the user changed that option since; then forgets it. */
    static Path restore(Path gameDirectory) throws IOException {
        Path file = gameDirectory.resolve("options.txt"), saved = gameDirectory.resolve(SAVED);
        if (!Files.exists(saved)) return file;
        if (Files.exists(file)) Files.write(file, restore(read(file), read(saved)).getBytes(StandardCharsets.UTF_8));
        Files.delete(saved);
        return file;
    }

    /** Sets the quiet values, keeps every other line and its order, appends keys that were missing. */
    static String merge(String existing) {
        List<String> lines = new ArrayList<>();
        Map<String, String> missing = new LinkedHashMap<>(VALUES);
        for (String line : lines(existing)) {
            String key = key(line);
            if (VALUES.containsKey(key)) {
                lines.add(key + ":" + VALUES.get(key));
                missing.remove(key);
            } else lines.add(line);
        }
        for (Map.Entry<String, String> entry : missing.entrySet()) lines.add(entry.getKey() + ":" + entry.getValue());
        return String.join("\n", lines) + "\n";
    }

    /**
     * The record of what quiet replaces in {@code options}. A key that already holds its quiet value keeps
     * the earlier record, since an earlier quiet launch wrote it; any other value is the user's latest.
     */
    static String save(String options, String saved) {
        Map<String, String> current = values(options), before = values(saved);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : VALUES.entrySet()) {
            String key = entry.getKey(), value = current.get(key);
            if (entry.getValue().equals(value) && before.containsKey(key)) value = before.get(key);
            lines.add(value == null ? key : key + ":" + value);
        }
        return String.join("\n", lines) + "\n";
    }

    /** Recorded values replace quiet's; an option the user changed since is kept, and an absent one is removed for the game's default. */
    static String restore(String options, String saved) {
        Map<String, String> before = values(saved);
        List<String> lines = new ArrayList<>();
        for (String line : lines(options)) {
            String key = key(line);
            if (!before.containsKey(key) || !line.equals(key + ":" + VALUES.get(key))) lines.add(line);
            else if (before.get(key) != null) lines.add(key + ":" + before.get(key));
        }
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    /** {@code key -> value}; a bare key maps to null. */
    static Map<String, String> values(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines(text)) {
            int colon = line.indexOf(':');
            values.put(key(line), colon < 0 ? null : line.substring(colon + 1));
        }
        return values;
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n")) if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    private static String key(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? line : line.substring(0, colon);
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
    }
}
