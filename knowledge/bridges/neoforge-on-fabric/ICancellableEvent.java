package ${package};

/** NeoForge's net.neoforged.bus.api.ICancellableEvent: an event a listener may cancel. */
public interface ICancellableEvent {

    default void setCanceled(boolean canceled) {
        ((Event) this).canceled = canceled;
    }

    default boolean isCanceled() {
        return ((Event) this).canceled;
    }
}
