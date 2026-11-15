import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/**
 * Holds a shared cache folder (~/.mmt/servers/paper-VERSION, ~/.mmt/tools/...) for one process at a time while
 * it is checked and filled. Two server-checks in different projects at once otherwise download and patch into the same
 * files. Waits for the holder; the lock is released when the process exits, so a killed run cannot leave it held.
 */
final class CacheLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private CacheLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static CacheLock hold(Path cache) throws IOException {
        FileChannel channel = FileChannel.open(cache.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            return new CacheLock(channel, channel.lock());
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
