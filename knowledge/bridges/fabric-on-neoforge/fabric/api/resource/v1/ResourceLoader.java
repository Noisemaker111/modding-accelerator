package ${package}.fabric.api.resource.v1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.SortedReloadListenerEvent;
import ${package}.FabricBridge;

/**
 * Fabric API's net.fabricmc.fabric.api.resource.v1.ResourceLoader on NeoForge's reload listener events. Server data
 * listeners are added in AddServerReloadListenersEvent, which NeoForge posts for every data reload, so they may be
 * registered at any time before one; client resource listeners in AddClientReloadListenersEvent, posted once while
 * Minecraft starts (after the client entrypoints); one registered after it is added to the client's resource manager
 * directly, after every other listener. addListenerOrdering is NeoForge's addDependency, made once both listeners are
 * known by their ids (Fabric's and NeoForge's ids for the game's own listeners differ, so an ordering against one of
 * those may not be made). REGISTRY_LOOKUP_KEY holds the reload's registries for the server listeners, as on Fabric;
 * FEATURE_FLAG_SET_KEY is not filled. Built-in packs (registerBuiltinPack) are not mirrored.
 */
public interface ResourceLoader {

    PreparableReloadListener.StateKey<HolderLookup.Provider> REGISTRY_LOOKUP_KEY = new PreparableReloadListener.StateKey<>();
    PreparableReloadListener.StateKey<FeatureFlagSet> FEATURE_FLAG_SET_KEY = new PreparableReloadListener.StateKey<>();

    static ResourceLoader get(PackType type) {
        return type == PackType.SERVER_DATA ? Listeners.SERVER : Listeners.CLIENT;
    }

    void registerReloadListener(Identifier id, PreparableReloadListener listener);

    void addListenerOrdering(Identifier firstListener, Identifier secondListener);

    /** One pack type's listeners and orderings, kept for NeoForge's event. */
    final class Listeners implements ResourceLoader {
        static final Listeners SERVER = new Listeners();
        static final Listeners CLIENT = new Listeners();

        private final Map<Identifier, PreparableReloadListener> listeners = new LinkedHashMap<>();
        private final List<Identifier[]> orderings = new ArrayList<>();
        private boolean added;

        static {
            NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent.class, event -> {
                Identifier state = Identifier.fromNamespaceAndPath(FabricBridge.modId(), "fabric_bridge_registry_lookup");
                HolderLookup.Provider registries = event.getServerResources().fullRegistries().lookup();
                event.addListener(state, new PreparableReloadListener() {
                    @Override
                    public void prepareSharedState(PreparableReloadListener.SharedState currentReload) {
                        currentReload.set(REGISTRY_LOOKUP_KEY, registries);
                    }

                    @Override
                    public CompletableFuture<Void> reload(PreparableReloadListener.SharedState currentReload, Executor taskExecutor, PreparableReloadListener.PreparationBarrier barrier, Executor reloadExecutor) {
                        return barrier.<Void>wait(null);
                    }
                });
                SERVER.addTo(event);
            });
        }

        private Listeners() {}

        @Override
        public synchronized void registerReloadListener(Identifier id, PreparableReloadListener listener) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(listener, "listener");
            if (listeners.containsKey(id)) throw new IllegalArgumentException("Tried to register a resource reloader twice: " + id);
            listeners.put(id, listener);
            if (this == CLIENT && added) ClientReload.late(listener);
        }

        @Override
        public synchronized void addListenerOrdering(Identifier firstListener, Identifier secondListener) {
            Objects.requireNonNull(firstListener, "firstListener");
            Objects.requireNonNull(secondListener, "secondListener");
            if (firstListener.equals(secondListener)) throw new IllegalArgumentException("Tried to add a reloader ordering with the same id: " + firstListener);
            orderings.add(new Identifier[] { firstListener, secondListener });
        }

        /** Adds every listener and ordering to NeoForge's event (the client one comes through the bridge's ClientHooks). */
        public synchronized void addTo(SortedReloadListenerEvent event) {
            added = true;
            for (var e : listeners.entrySet()) event.addListener(e.getKey(), e.getValue());
            for (Identifier[] order : orderings) {
                if (event.getRegistry().containsKey(order[0]) && event.getRegistry().containsKey(order[1])) event.addDependency(order[0], order[1]);
            }
        }

        public static Listeners client() {
            return CLIENT;
        }
    }

    /** A client listener registered after NeoForge's event: added to the client's resource manager (set by the bridge's ClientHooks). */
    final class ClientReload {
        private static java.util.function.Consumer<PreparableReloadListener> late = listener -> {};

        private ClientReload() {}

        static void late(PreparableReloadListener listener) {
            late.accept(listener);
        }

        public static void lateWith(java.util.function.Consumer<PreparableReloadListener> how) {
            late = how;
        }
    }
}
