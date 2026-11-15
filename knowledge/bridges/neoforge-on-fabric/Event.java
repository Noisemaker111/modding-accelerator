package ${package};

/**
 * NeoForge's net.neoforged.bus.api.Event, for a mod converted to Fabric by modaccel convert: the base of every event
 * this bridge posts. Cancellation is kept here, as NeoForge keeps it, for events that implement ICancellableEvent.
 */
public abstract class Event {

    boolean canceled;
}
