package ${package};

import java.util.List;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * NeoForge 26.1's net.neoforged.neoforge.event.AddServerReloadListenersEvent, which NeoForge 21.1 does not have (its
 * mods use AddReloadListenerEvent, which the bridge posts on 1.21.1); kept so the bridge compiles, and not posted. On
 * 26.1 it is posted on every server data load with the game's listeners of that load; the listeners added here reload with them, in the order SortedReloadListenerEvent
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
     * EventHooks.onResourceReload). Called by nothing on 1.21.1, where ReloadListenersMixin posts
     * AddReloadListenerEvent.
     */
    public static List<PreparableReloadListener> post(ReloadableServerResources resources, List<PreparableReloadListener> vanilla) {
        return Bus.INSTANCE.post(new AddServerReloadListenersEvent(resources, vanilla)).sorted();
    }
}
