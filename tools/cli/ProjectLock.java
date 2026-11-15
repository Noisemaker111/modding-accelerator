import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * One command at a time per project. Two builds in the same folder overwrite each other's outputs
 * and fail with Gradle errors about missing temporary files, which say nothing about the cause.
 * The operating system drops the lock when the process ends, however it ends, so it never goes stale.
 */
final class ProjectLock implements AutoCloseable {

    /**
     * Set for a child a lock holder runs as one of its own steps (port --check fingerprint starts the game through
     * mmt commands): the lock file's path. The child does not wait for the lock its parent holds for it.
     */
    static final String HELD = "MMT_HOLDS_LOCK";
    private static volatile String heldHere;

    private final FileChannel channel;
    private final FileLock lock;

    /** The lock file this process holds, if any, for the children it runs. */
    static String heldHere() {
        return heldHere;
    }

    private ProjectLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static ProjectLock acquire(ProjectModel project, String command) throws IOException {
        Path state = project.state();
        Files.createDirectories(state);
        Path file = state.resolve("command.lock"), info = state.resolve("command.json");
        String path = file.toAbsolutePath().normalize().toString();
        if (path.equals(System.getenv(HELD))) return new ProjectLock(null, null);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException sameProcess) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            String who = "";
            try {
                var holder = Json.read(info);
                who =
                    " (" +
                    holder.get("command") +
                    ", process " +
                    holder.get("pid") +
                    ", started at " +
                    holder.get("started") +
                    ")";
            } catch (Exception unreadable) {}
            throw new IOException(
                "Another mmt command" +
                who +
                " is already working on this project. Two at once would overwrite each other's build files, " +
                "so this one did nothing. Wait for that one to finish, or stop it, then rerun."
            );
        }
        Json.save(
            info,
            Json.map(
                "command",
                command,
                "pid",
                ProcessHandle.current().pid(),
                "started",
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
            )
        );
        heldHere = path;
        return new ProjectLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        if (lock == null) return;
        heldHere = null;
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
