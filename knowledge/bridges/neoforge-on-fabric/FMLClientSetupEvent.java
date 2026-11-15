package ${package};

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** NeoForge's net.neoforged.fml.event.lifecycle.FMLClientSetupEvent: posted once the mod's client entrypoint has run. */
public class FMLClientSetupEvent extends Event {

    final ModContainer container;

    FMLClientSetupEvent(ModContainer container) {
        this.container = container;
    }

    public CompletableFuture<Void> enqueueWork(Runnable work) {
        work.run();
        return CompletableFuture.completedFuture(null);
    }

    public <T> CompletableFuture<T> enqueueWork(Supplier<T> work) {
        return CompletableFuture.completedFuture(work.get());
    }
}
