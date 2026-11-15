package ${package};

import java.util.Iterator;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.fabricmc.loader.api.FabricLoader;

/**
 * NeoForge's net.neoforged.fml.InterModComms: messages one mod queues for another (sendTo, usually in
 * InterModEnqueueEvent) and the receiver reads once (getMessages, usually in InterModProcessEvent). The same members as
 * FML's. The queues live in Fabric Loader's ObjectShare (key mmt:neoforge_bridge_imc, as plain Java objects), so
 * every converted mod, each with its own copy of this bridge, reads and writes the same queues. Difference: sendTo
 * without a sender names this converted mod (FML names the mod whose code is running).
 */
public class InterModComms {

    private static final String SHARE_KEY = "mmt:neoforge_bridge_imc";

    public record IMCMessage(String senderModId, String modId, String method, Supplier<?> messageSupplier) {}

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, ConcurrentLinkedQueue<Object[]>> queues() {
        Object shared = FabricLoader.getInstance().getObjectShare().get(SHARE_KEY);
        if (shared == null) {
            FabricLoader.getInstance().getObjectShare().putIfAbsent(SHARE_KEY, new ConcurrentHashMap<String, ConcurrentLinkedQueue<Object[]>>());
            shared = FabricLoader.getInstance().getObjectShare().get(SHARE_KEY);
        }
        return (ConcurrentMap<String, ConcurrentLinkedQueue<Object[]>>) shared;
    }

    public static boolean sendTo(String modId, String method, Supplier<?> thing) {
        return sendTo("${modid}", modId, method, thing);
    }

    public static boolean sendTo(String senderModId, String modId, String method, Supplier<?> thing) {
        if (!ModList.get().isLoaded(modId)) return false;
        queues().computeIfAbsent(modId, k -> new ConcurrentLinkedQueue<>()).add(new Object[] { senderModId, modId, method, thing });
        return true;
    }

    public static Stream<IMCMessage> getMessages(String modId, Predicate<String> methodMatcher) {
        ConcurrentLinkedQueue<Object[]> queue = queues().get(modId);
        if (queue == null) return Stream.empty();
        return StreamSupport.stream(new QueueFilteringSpliterator(queue, methodMatcher), false);
    }

    public static Stream<IMCMessage> getMessages(String modId) {
        return getMessages(modId, s -> Boolean.TRUE);
    }

    /** Hands out, and removes, the queued messages whose method passes the filter. */
    private static class QueueFilteringSpliterator implements Spliterator<IMCMessage> {

        private final ConcurrentLinkedQueue<Object[]> queue;
        private final Predicate<String> methodFilter;
        private final Iterator<Object[]> iterator;

        QueueFilteringSpliterator(ConcurrentLinkedQueue<Object[]> queue, Predicate<String> methodFilter) {
            this.queue = queue;
            this.iterator = queue.iterator();
            this.methodFilter = methodFilter;
        }

        @Override
        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.NONNULL | Spliterator.ORDERED;
        }

        @Override
        public long estimateSize() {
            return queue.size();
        }

        @Override
        public boolean tryAdvance(Consumer<? super IMCMessage> action) {
            Object[] next;
            do {
                if (!iterator.hasNext()) return false;
                next = this.iterator.next();
            } while (!methodFilter.test((String) next[2]));
            action.accept(new IMCMessage((String) next[0], (String) next[1], (String) next[2], (Supplier<?>) next[3]));
            this.iterator.remove();
            return true;
        }

        @Override
        public Spliterator<IMCMessage> trySplit() {
            return null;
        }
    }
}
