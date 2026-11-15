package ${package};

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.FMLClientSetupEvent: posted once the mod's client entrypoint has run;
 * queued work (enqueueWork) runs at once, on the main thread.
 */
public class FMLClientSetupEvent extends ParallelDispatchEvent {

    FMLClientSetupEvent(ModContainer container) {
        super(container);
    }
}
