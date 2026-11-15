package ${package};

import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * NeoForge's net.neoforged.fml.event.lifecycle.ModLifecycleEvent: the base of the mod-bus lifecycle events, for one mod
 * (its container). The same members as FML's; the messages are the ones InterModComms queued for this mod.
 */
public abstract class ModLifecycleEvent extends Event implements IModBusEvent {

    private final ModContainer container;

    public ModLifecycleEvent(ModContainer container) {
        this.container = container;
    }

    public Stream<InterModComms.IMCMessage> getIMCStream() {
        return InterModComms.getMessages(this.container.getModId());
    }

    public Stream<InterModComms.IMCMessage> getIMCStream(Predicate<String> methodFilter) {
        return InterModComms.getMessages(this.container.getModId(), methodFilter);
    }

    public ModContainer getContainer() {
        return this.container;
    }
}
