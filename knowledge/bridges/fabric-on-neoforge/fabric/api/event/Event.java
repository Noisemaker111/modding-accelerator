package ${package}.fabric.api.event;

import net.minecraft.resources.Identifier;

/**
 * Fabric API's net.fabricmc.fabric.api.event.Event: a callback holder whose invoker() calls every registered listener,
 * the listeners of each phase together, phases in the order addPhaseOrdering gives them (the default phase where none
 * is given). EventFactory makes them; the bridge's own Fabric callbacks are posted from the NeoForge events that fire at
 * the same moment.
 */
public abstract class Event<T> {

    public static final Identifier DEFAULT_PHASE = Identifier.fromNamespaceAndPath("fabric", "default");

    protected volatile T invoker;

    public final T invoker() {
        return invoker;
    }

    public abstract void register(T listener);

    public void register(Identifier phase, T listener) {
        register(listener);
    }

    public void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
    }
}
