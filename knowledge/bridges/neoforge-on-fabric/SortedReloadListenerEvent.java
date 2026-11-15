package ${package};

import com.google.common.graph.ElementOrder;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.Traverser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * NeoForge's net.neoforged.neoforge.event.SortedReloadListenerEvent (26.1 and later): reload listeners by id, with
 * dependencies between them, sorted after the event as NeoForge sorts them: the game's own listeners first in their
 * order, a listener with no path to or from one of them after the last of them, otherwise insertion order where the
 * dependencies allow. A listener of the game is named from its class (ServerFunctionLibrary is
 * minecraft:server_function_library), as NeoForge's VanillaServerListeners names it.
 */
public abstract class SortedReloadListenerEvent extends Event {

    private final Map<Identifier, PreparableReloadListener> registry = new LinkedHashMap<>();
    private final Map<PreparableReloadListener, Identifier> keys = new IdentityHashMap<>();
    private final MutableGraph<PreparableReloadListener> graph = GraphBuilder.directed().nodeOrder(ElementOrder.<PreparableReloadListener>insertion()).build();
    private final PreparableReloadListener lastVanilla;

    protected SortedReloadListenerEvent(List<PreparableReloadListener> vanillaListeners, NameLookup lookup) {
        for (PreparableReloadListener listener : vanillaListeners) {
            Identifier key = lookup.apply(listener);
            this.addListener(key, listener);
        }
        for (int i = 1; i < vanillaListeners.size(); i++) {
            this.graph.putEdge(vanillaListeners.get(i - 1), vanillaListeners.get(i));
        }
        this.lastVanilla = vanillaListeners.isEmpty() ? null : vanillaListeners.get(vanillaListeners.size() - 1);
    }

    public void addListener(Identifier key, PreparableReloadListener listener) {
        if (this.registry.containsKey(key) || this.keys.containsKey(listener)) {
            throw new IllegalArgumentException("Attempted to register two reload listeners for the same key: " + key);
        }
        this.registry.put(key, listener);
        this.keys.put(listener, key);
        this.graph.addNode(listener);
    }

    /** The listener named first reloads before the one named second. */
    public void addDependency(Identifier first, Identifier second) {
        this.graph.putEdge(this.getOrThrow(first), this.getOrThrow(second));
    }

    public Graph<PreparableReloadListener> getGraph() {
        return this.graph;
    }

    public Map<Identifier, PreparableReloadListener> getRegistry() {
        return Collections.unmodifiableMap(this.registry);
    }

    public NameLookup getNameLookup() {
        return this::getOrThrow;
    }

    /** The listeners in reload order (NeoForge's ReloadListenerSort). */
    List<PreparableReloadListener> sorted() {
        if (lastVanilla != null) {
            for (PreparableReloadListener listener : registry.values()) {
                if (needsToBeLinkedToVanilla(listener)) graph.putEdge(lastVanilla, listener);
            }
        }
        Map<PreparableReloadListener, Integer> order = new IdentityHashMap<>();
        Map<PreparableReloadListener, Integer> incoming = new IdentityHashMap<>();
        for (PreparableReloadListener listener : registry.values()) {
            order.put(listener, order.size());
            incoming.put(listener, graph.inDegree(listener));
        }
        PriorityQueue<PreparableReloadListener> ready = new PriorityQueue<>((a, b) -> Integer.compare(order.get(a), order.get(b)));
        for (PreparableReloadListener listener : registry.values()) {
            if (incoming.get(listener) == 0) ready.add(listener);
        }
        List<PreparableReloadListener> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            PreparableReloadListener next = ready.poll();
            out.add(next);
            for (PreparableReloadListener after : graph.successors(next)) {
                int left = incoming.merge(after, -1, Integer::sum);
                if (left == 0) ready.add(after);
            }
        }
        if (out.size() != registry.size()) throw new IllegalStateException("Cycles were detected during reload listener sorting");
        return out;
    }

    private boolean needsToBeLinkedToVanilla(PreparableReloadListener listener) {
        if (isVanilla(listener)) return false;
        for (PreparableReloadListener node : Traverser.forGraph(graph).depthFirstPreOrder(listener)) {
            if (isVanilla(node)) return false;
        }
        for (PreparableReloadListener node : Traverser.forGraph(Graphs.transpose(graph)).depthFirstPreOrder(listener)) {
            if (isVanilla(node)) return false;
        }
        return true;
    }

    private boolean isVanilla(PreparableReloadListener listener) {
        return "minecraft".equals(getOrThrow(listener).getNamespace());
    }

    private PreparableReloadListener getOrThrow(Identifier key) {
        PreparableReloadListener listener = this.registry.get(key);
        if (listener == null) throw new IllegalArgumentException("Unknown reload listener: " + key);
        return listener;
    }

    private Identifier getOrThrow(PreparableReloadListener listener) {
        Identifier key = this.keys.get(listener);
        if (key == null) throw new IllegalArgumentException("Unknown reload listener: " + listener);
        return key;
    }

    /** A listener of the game named as NeoForge's VanillaClassToKey names it (or under "fabric" for another mod's). */
    static Identifier nameOf(PreparableReloadListener listener) {
        StringBuilder name = new StringBuilder();
        listener.getClass().getSimpleName().codePoints().forEachOrdered(c -> {
            if (Character.isUpperCase(c)) {
                name.append('_').append(Character.toString(Character.toLowerCase(c)));
            } else {
                name.append(Character.toString(c));
            }
        });
        String path = name.length() > 0 && name.charAt(0) == '_' ? name.substring(1) : name.toString();
        String namespace = listener.getClass().getPackageName().startsWith("net.minecraft") ? "minecraft" : "fabric";
        return Identifier.fromNamespaceAndPath(namespace, path.replaceAll("[^a-z0-9_./-]", "_"));
    }

    @FunctionalInterface
    public interface NameLookup extends Function<PreparableReloadListener, Identifier> {
        @Override
        Identifier apply(PreparableReloadListener t);
    }
}
