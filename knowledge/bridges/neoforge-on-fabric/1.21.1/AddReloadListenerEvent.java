package ${package};

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.AddReloadListenerEvent: posted on every server data load, where NeoForge's
 * patch posts it, from ReloadListenersMixin in ReloadableServerResources.loadResources; the listeners added here reload
 * after the game's own, in the order they were added. Differences: NeoForge's condition context (getConditionContext)
 * has no Fabric counterpart and is left out, and a listener is added as it is (NeoForge wraps it to skip reloading
 * after a mod loading error).
 */
public class AddReloadListenerEvent extends Event {

    private final List<PreparableReloadListener> listeners = new ArrayList<>();
    private final ReloadableServerResources serverResources;
    private final RegistryAccess registryAccess;

    public AddReloadListenerEvent(ReloadableServerResources serverResources, RegistryAccess registryAccess) {
        this.serverResources = serverResources;
        this.registryAccess = registryAccess;
    }

    public void addListener(PreparableReloadListener listener) {
        listeners.add(listener);
    }

    public List<PreparableReloadListener> getListeners() {
        return List.copyOf(listeners);
    }

    public ReloadableServerResources getServerResources() {
        return serverResources;
    }

    public RegistryAccess getRegistryAccess() {
        return registryAccess;
    }

    /**
     * Not NeoForge's: the game's listeners of a data load followed by the ones the event added (NeoForge's patch to
     * loadResources with EventHooks.onResourceReload). Called by ReloadListenersMixin.
     */
    public static List<PreparableReloadListener> post(ReloadableServerResources resources, RegistryAccess registries, List<PreparableReloadListener> vanilla) {
        List<PreparableReloadListener> all = new ArrayList<>(vanilla);
        all.addAll(Bus.INSTANCE.post(new AddReloadListenerEvent(resources, registries)).getListeners());
        return all;
    }
}
