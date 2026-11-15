package ${package};

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent: posted once the mod's main entrypoint has run.
 * NeoForge runs queued work (enqueueWork) on the main thread after setup; Fabric's entrypoints already run there, so it
 * runs at once.
 */
public class FMLCommonSetupEvent extends ParallelDispatchEvent {

    FMLCommonSetupEvent(ModContainer container) {
        super(container);
    }
}
