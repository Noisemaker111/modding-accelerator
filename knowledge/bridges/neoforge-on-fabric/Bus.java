package ${package};

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one event bus of a converted mod. NeoForge has a bus per mod and a game bus (NeoForge.EVENT_BUS); on Fabric
 * every event this bridge knows comes from one Fabric callback, so both names deliver to the same listeners. A posted
 * event reaches the listeners of its class and of each of its superclasses, highest priority first; a canceled event
 * skips the listeners that did not ask for canceled events.
 */
public final class Bus implements IEventBus {

    static final Bus INSTANCE = new Bus();

    record Listener(Object owner, EventPriority priority, boolean receiveCanceled, Consumer<Event> consumer) {}

    final Map<Class<?>, List<Listener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void register(Object target) {
        boolean statics = target instanceof Class<?>;
        Class<?> type = statics ? (Class<?>) target : target.getClass();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                SubscribeEvent subscribe = method.getAnnotation(SubscribeEvent.class);
                if (subscribe == null) continue;
                if (Modifier.isStatic(method.getModifiers()) != statics) continue;
                if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    throw new IllegalArgumentException(method + " has @SubscribeEvent but does not take one event");
                }
                method.setAccessible(true);
                Object receiver = statics ? null : target;
                add(target, subscribe.priority(), subscribe.receiveCanceled(), method.getParameterTypes()[0], event -> {
                    try {
                        method.invoke(receiver, event);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause() instanceof RuntimeException r ? r : new RuntimeException(thrown.getCause());
                    } catch (IllegalAccessException denied) {
                        throw new IllegalStateException(denied);
                    }
                });
            }
        }
    }

    @Override
    public <T extends Event> void addListener(Class<T> type, Consumer<T> listener) {
        addListener(EventPriority.NORMAL, false, type, listener);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> type, Consumer<T> listener) {
        addListener(priority, false, type, listener);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> type, Consumer<T> listener) {
        addListener(EventPriority.NORMAL, receiveCanceled, type, listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> type, Consumer<T> listener) {
        add(listener, priority, receiveCanceled, type, event -> listener.accept((T) event));
    }

    void add(Object owner, EventPriority priority, boolean receiveCanceled, Class<?> type, Consumer<Event> consumer) {
        List<Listener> list = listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(new Listener(owner, priority, receiveCanceled, consumer));
        List<Listener> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(Listener::priority));
        list.clear();
        list.addAll(sorted);
    }

    @Override
    public void unregister(Object target) {
        for (List<Listener> list : listeners.values()) list.removeIf(l -> l.owner() == target);
    }

    @Override
    public <T extends Event> T post(T event) {
        List<Listener> all = new ArrayList<>();
        for (Class<?> c = event.getClass(); c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            all.addAll(listeners.getOrDefault(c, List.of()));
        }
        all.sort(Comparator.comparing(Listener::priority));
        for (Listener listener : all) {
            if (event.canceled && !listener.receiveCanceled()) continue;
            listener.consumer().accept(event);
        }
        return event;
    }
}
