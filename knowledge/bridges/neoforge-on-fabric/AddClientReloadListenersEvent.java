package ${package};

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * NeoForge's net.neoforged.neoforge.client.event.AddClientReloadListenersEvent, posted once when the client entrypoint
 * ends (before the game first loads its resources, as on NeoForge). Each listener goes to Fabric API's
 * ResourceLoader.get(PackType.CLIENT_RESOURCES) under its key, and addDependency to its addListenerOrdering, so a
 * dependency on one of the game's own listeners takes Fabric API's key for it (ResourceReloaderKeys). Unlike NeoForge's,
 * the registry holds only the listeners added here, and the event has no graph of the game's listeners.
 */
public class AddClientReloadListenersEvent extends Event implements IModBusEvent {

    private final Map<Identifier, PreparableReloadListener> registry = new LinkedHashMap<>();

    AddClientReloadListenersEvent() {}

    public void addListener(Identifier key, PreparableReloadListener listener) {
        if (this.registry.containsKey(key) || this.registry.containsValue(listener)) {
            throw new IllegalArgumentException("Attempted to register two reload listeners for the same key: " + key);
        }
        this.registry.put(key, listener);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(key, listener);
    }

    public void addDependency(Identifier first, Identifier second) {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).addListenerOrdering(first, second);
    }

    public Map<Identifier, PreparableReloadListener> getRegistry() {
        return Collections.unmodifiableMap(this.registry);
    }
}
