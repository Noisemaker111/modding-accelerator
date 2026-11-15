package ${package};

import java.util.function.Consumer;

/**
 * NeoForge's net.neoforged.bus.api.IEventBus. NeoForge reads a listener's event type from its generic signature;
 * modaccel convert writes the type into each addListener call instead (the Class overloads), so the forms without
 * a Class are never called on Fabric.
 */
public interface IEventBus {
    void register(Object target);

    <T extends Event> void addListener(Class<T> type, Consumer<T> listener);

    <T extends Event> void addListener(EventPriority priority, Class<T> type, Consumer<T> listener);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> type, Consumer<T> listener);

    <T extends Event> void addListener(boolean receiveCanceled, Class<T> type, Consumer<T> listener);

    void unregister(Object target);

    <T extends Event> T post(T event);
}
