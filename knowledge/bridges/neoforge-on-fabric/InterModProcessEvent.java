package ${package};

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.InterModProcessEvent: the time to read the messages other mods sent this
 * mod (getIMCStream). Posted once per mod by the bridge after every converted mod's InterModEnqueueEvent and before
 * FMLLoadCompleteEvent, as FML orders them.
 */
public class InterModProcessEvent extends ParallelDispatchEvent {

    public InterModProcessEvent(ModContainer container) {
        super(container);
    }
}
