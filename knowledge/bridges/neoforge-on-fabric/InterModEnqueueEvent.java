package ${package};

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.InterModEnqueueEvent: the time to send messages to other mods
 * (InterModComms.sendTo). Posted once per mod by the bridge after every mod's entrypoints ran (on the client when it
 * has started, Fabric's ClientLifecycleEvents.CLIENT_STARTED; on a dedicated server as it starts,
 * ServerLifecycleEvents.SERVER_STARTING), every converted mod's before any InterModProcessEvent, as FML orders them
 * after common and client setup.
 */
public class InterModEnqueueEvent extends ParallelDispatchEvent {

    public InterModEnqueueEvent(ModContainer container) {
        super(container);
    }
}
