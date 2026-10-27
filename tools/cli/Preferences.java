import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-user choices for launches across projects. */
final class Preferences {
    private Preferences() {}
    static Path file() { return Path.of(System.getProperty("user.home"), ".modaccel", "preferences.json"); }
    static Map<String, Object> read(Path path) throws IOException {
        Map<String, Object> saved = Files.exists(path) ? Json.read(path) : new LinkedHashMap<>();
        for (String name : new String[]{"background", "quiet"}) {
            Object value = saved.get(name);
            if (value != null && !(value instanceof Boolean)) throw new IOException("Invalid " + name + " in " + path + ": expected true or false");
            if (value == null) saved.put(name, false);
        }
        return saved;
    }
    static Map<String, Object> change(Path path, Modaccel.Options options) throws IOException {
        Map<String, Object> values = read(path);
        for (String name : new String[]{"background", "quiet"}) {
            if (!options.values.containsKey(name)) continue;
            String value = options.get(name, "");
            if (!value.equals("on") && !value.equals("off")) throw new IllegalArgumentException("--" + name + " must be on or off");
            values.put(name, value.equals("on"));
        }
        if (options.values.containsKey("background") || options.values.containsKey("quiet")) Json.save(path, values);
        return values;
    }
}
