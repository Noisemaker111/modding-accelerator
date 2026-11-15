import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tool was called modaccel before it was mmt. Its per-user folder was ~/.modaccel, each project's
 * folder was .modaccel/, and its environment variables were MODACCEL_*. This class reads the old names
 * as fallbacks and moves the old folders once.
 */
final class UserState {

    /** Written in ~/.mmt once ~/.modaccel has been copied into it. */
    static final String MARKER = ".migrated-from-modaccel.json";

    /** Files at least this big are hard-linked rather than copied when the file system allows it. */
    static final long LINK_FROM_BYTES = 1L << 20;

    private UserState() {}

    /** MMT_NAME, else the old MODACCEL_NAME, else null. */
    static String env(String name) {
        String value = System.getenv("MMT_" + name);
        return value != null ? value : System.getenv("MODACCEL_" + name);
    }

    /** Copies ~/.modaccel into ~/.mmt on the first run; never fails the command it runs before. */
    static void migrateHome() {
        Path home = Path.of(System.getProperty("user.home"));
        try {
            Map<String, Object> moved = migrate(home.resolve(".modaccel"), home.resolve(".mmt"));
            if (moved != null) System.err.println(
                "mmt: copied your settings and caches from " + moved.get("from") + " to " + moved.get("to") +
                " (the old folder is kept; delete it when no older modaccel install needs it)."
            );
        } catch (Exception error) {
            System.err.println("mmt: could not copy ~/.modaccel to ~/.mmt (" + error + "); it starts empty and is tried again next run.");
        }
    }

    /**
     * Copies {@code from} into {@code to} unless {@code to} already holds the marker. Files already in
     * {@code to} are kept. Small files (preferences, records) are copied byte for byte; big cache files
     * (JDKs, game jars, mappings, pack files) are hard-linked when both folders share a file system,
     * so the first run neither doubles disk use nor waits on a multi-gigabyte copy. The old folder is
     * left as it was. Returns what was done, or null when there was nothing to do.
     */
    static Map<String, Object> migrate(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from) || Files.isRegularFile(to.resolve(MARKER))) return null;
        Files.createDirectories(to);
        // Several commands can start at once on a first run: one copies, the others wait for it.
        try (
            FileChannel channel = FileChannel.open(to.resolve(".migration.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.lock()
        ) {
            if (Files.isRegularFile(to.resolve(MARKER))) return null;
            System.err.println("mmt: copying " + from + " to " + to + " once; a large pack cache can take a few minutes.");
            long[] counts = new long[3];
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Path target = to.resolve(from.relativize(file).toString());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    if (attributes.isRegularFile() && attributes.size() >= LINK_FROM_BYTES) {
                        try {
                            Files.createLink(target, file);
                            counts[1]++;
                            return FileVisitResult.CONTINUE;
                        } catch (FileAlreadyExistsException exists) {
                            return FileVisitResult.CONTINUE;
                        } catch (IOException | UnsupportedOperationException | SecurityException cannotLink) {
                            // Another drive or a file system without hard links: copy it instead.
                        }
                    }
                    try {
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                        counts[0]++;
                    } catch (IOException locked) {
                        // A log another running game holds open: the rest still moves, and the marker counts it.
                        counts[2]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    counts[2]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("from", from.toString());
            marker.put("to", to.toString());
            marker.put("at", Instant.now().toString());
            marker.put("copied", counts[0]);
            marker.put("hardLinked", counts[1]);
            marker.put("unreadable", counts[2]);
            marker.put("note", "The old folder was kept. Files of 1 MiB or more are hard links to the old folder's copies where possible.");
            Json.save(to.resolve(MARKER), marker);
            return marker;
        }
    }

    /** Renames a project's old .modaccel/ folder (undo records, selections, secrets) to .mmt/ when it has none yet. */
    static void migrateProject(Path root) {
        Path old = root.resolve(".modaccel"), current = root.resolve(".mmt");
        if (!Files.isDirectory(old) || Files.exists(current)) return;
        try {
            Files.move(old, current);
            System.err.println("mmt: renamed " + old + " to " + current + ".");
        } catch (IOException error) {
            System.err.println("mmt: could not rename " + old + " to " + current + " (" + error + "); its records stay there.");
        }
    }
}
