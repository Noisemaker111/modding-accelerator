package ${package};

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** NeoForge's net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent: posted once the mod's main entrypoint has run. */
public class FMLCommonSetupEvent extends Event {

    final ModContainer container;

    FMLCommonSetupEvent(ModContainer container) {
        this.container = container;
    }

    /** NeoForge runs queued work on the main thread after setup; Fabric's entrypoints already run there. */
    public CompletableFuture<Void> enqueueWork(Runnable work) {
        work.run();
        return CompletableFuture.completedFuture(null);
    }

    public <T> CompletableFuture<T> enqueueWork(Supplier<T> work) {
        return CompletableFuture.completedFuture(work.get());
    }
}
