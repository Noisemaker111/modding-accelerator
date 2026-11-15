package ${package};

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.ParallelDispatchEvent: a lifecycle event FML posts to mods in parallel,
 * whose enqueueWork runs work on the main thread after it. The bridge posts these events on the main thread, so the
 * work runs at once, in the order it was enqueued.
 */
public abstract class ParallelDispatchEvent extends ModLifecycleEvent {

    public ParallelDispatchEvent(ModContainer container) {
        super(container);
    }

    public CompletableFuture<Void> enqueueWork(Runnable work) {
        work.run();
        return CompletableFuture.completedFuture(null);
    }

    public <T> CompletableFuture<T> enqueueWork(Supplier<T> work) {
        return CompletableFuture.completedFuture(work.get());
    }
}
