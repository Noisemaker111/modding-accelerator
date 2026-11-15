package ${package}.fabric.api.event;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * Fabric API's net.fabricmc.fabric.api.event.EventFactory: array-backed events, optionally with ordered phases. A
 * listener registered in a phase runs after every listener of the phases ordered before it; phases with no ordering
 * between them run in the order of their identifiers, as Fabric API sorts them.
 */
public final class EventFactory {

    private EventFactory() {}

    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        return new ArrayBacked<>(type, invokerFactory);
    }

    public static <T> Event<T> createArrayBacked(Class<T> type, T emptyInvoker, Function<T[], T> invokerFactory) {
        return createArrayBacked(type, listeners -> listeners.length == 0 ? emptyInvoker : listeners.length == 1 ? listeners[0] : invokerFactory.apply(listeners));
    }

    public static <T> Event<T> createWithPhases(Class<? super T> type, Function<T[], T> invokerFactory, Identifier... defaultPhases) {
        Set<Identifier> seen = new HashSet<>();
        boolean hasDefault = false;
        for (Identifier phase : defaultPhases) {
            if (!seen.add(phase)) throw new IllegalArgumentException("Duplicate event phase: " + phase);
            if (phase.equals(Event.DEFAULT_PHASE)) hasDefault = true;
        }
        if (!hasDefault) throw new IllegalArgumentException("The event phases must contain Event.DEFAULT_PHASE.");
        Event<T> event = createArrayBacked(type, invokerFactory);
        for (int i = 1; i < defaultPhases.length; i++) event.addPhaseOrdering(defaultPhases[i - 1], defaultPhases[i]);
        return event;
    }

    public static String getHandlerName(Object handler) {
        return handler.getClass().getName();
    }

    public static boolean isProfilingEnabled() {
        return false;
    }

    static final class ArrayBacked<T> extends Event<T> {

        private final Class<? super T> type;
        private final Function<T[], T> invokerFactory;
        private final Map<Identifier, List<T>> phases = new LinkedHashMap<>();
        private final Map<Identifier, Set<Identifier>> before = new LinkedHashMap<>();

        ArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
            this.type = type;
            this.invokerFactory = invokerFactory;
            rebuild();
        }

        @Override
        public void register(T listener) {
            register(DEFAULT_PHASE, listener);
        }

        @Override
        public synchronized void register(Identifier phase, T listener) {
            Objects.requireNonNull(phase, "Tried to register a listener for a null phase!");
            Objects.requireNonNull(listener, "Tried to register a null listener!");
            phases.computeIfAbsent(phase, k -> new ArrayList<>()).add(listener);
            rebuild();
        }

        @Override
        public synchronized void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
            Objects.requireNonNull(firstPhase, "Tried to add an ordering for a null phase.");
            Objects.requireNonNull(secondPhase, "Tried to add an ordering for a null phase.");
            if (firstPhase.equals(secondPhase)) throw new IllegalArgumentException("Tried to add a phase that depends on itself.");
            phases.computeIfAbsent(firstPhase, k -> new ArrayList<>());
            phases.computeIfAbsent(secondPhase, k -> new ArrayList<>());
            before.computeIfAbsent(secondPhase, k -> new HashSet<>()).add(firstPhase);
            rebuild();
        }

        /** The phases in dependency order (ties by identifier), then every listener in that order. */
        @SuppressWarnings("unchecked")
        private void rebuild() {
            List<Identifier> order = new ArrayList<>();
            Set<Identifier> placed = new HashSet<>();
            List<Identifier> pending = new ArrayList<>(phases.keySet());
            pending.sort(Identifier::compareTo);
            while (!pending.isEmpty()) {
                Identifier next = null;
                for (Identifier phase : pending) if (placed.containsAll(before.getOrDefault(phase, Set.of()))) {
                    next = phase;
                    break;
                }
                // A cycle: Fabric API runs the phases of a cycle together; here the first of them by identifier goes next.
                if (next == null) next = pending.get(0);
                pending.remove(next);
                placed.add(next);
                order.add(next);
            }
            List<T> all = new ArrayList<>();
            for (Identifier phase : order) all.addAll(phases.getOrDefault(phase, List.of()));
            T[] array = (T[]) Array.newInstance(type, all.size());
            invoker = invokerFactory.apply(all.toArray(array));
        }
    }
}
