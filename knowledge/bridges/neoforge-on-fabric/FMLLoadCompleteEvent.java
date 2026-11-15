package ${package};

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent: the last lifecycle event, posted once per mod by the
 * bridge after every converted mod's InterModProcessEvent (on the client when it has started, Fabric's
 * ClientLifecycleEvents.CLIENT_STARTED; on a dedicated server as it starts, ServerLifecycleEvents.SERVER_STARTING).
 */
public class FMLLoadCompleteEvent extends ParallelDispatchEvent {

    public FMLLoadCompleteEvent(ModContainer container) {
        super(container);
    }
}
