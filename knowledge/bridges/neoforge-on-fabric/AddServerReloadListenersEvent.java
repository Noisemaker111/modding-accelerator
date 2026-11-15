package ${package};

import java.util.List;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * NeoForge's net.neoforged.neoforge.event.AddServerReloadListenersEvent (26.1 and later): posted on every server data
 * load, where NeoForge's patch posts it, from ReloadListenersMixin in ReloadableServerResources.loadResources, with the
 * game's listeners of that load; the listeners added here reload with them, in the order SortedReloadListenerEvent
 * gives. Differences: NeoForge's retained listeners (addRetainedListener, ListenerKey) and condition context
 * (getConditionContext) have no Fabric counterpart and are left out.
 */
public class AddServerReloadListenersEvent extends SortedReloadListenerEvent {

    private final ReloadableServerResources serverResources;

    public AddServerReloadListenersEvent(ReloadableServerResources serverResources, List<PreparableReloadListener> vanillaListeners) {
        super(vanillaListeners, SortedReloadListenerEvent::nameOf);
        this.serverResources = serverResources;
    }

    public ReloadableServerResources getServerResources() {
        return serverResources;
    }

    /**
     * Not NeoForge's: posts the event for a data load's resources and returns the listeners to reload, sorted (NeoForge's
     * EventHooks.onResourceReload). Called by ReloadListenersMixin; also records the load's resources for
     * TagsUpdatedEvent.ServerDataLoad.
     */
    public static List<PreparableReloadListener> post(ReloadableServerResources resources, List<PreparableReloadListener> vanilla) {
        TagsUpdatedEvent.loading = resources;
        return Bus.INSTANCE.post(new AddServerReloadListenersEvent(resources, vanilla)).sorted();
    }
}
