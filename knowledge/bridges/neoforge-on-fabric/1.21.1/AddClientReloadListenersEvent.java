package ${package};

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * NeoForge's net.neoforged.neoforge.client.event.AddClientReloadListenersEvent as it reads on 1.21.1 (NeoForge 21.1 has
 * RegisterClientReloadListenersEvent instead), posted once when the client entrypoint ends. Each listener goes to Fabric
 * API's ResourceManagerHelper.get(PackType.CLIENT_RESOURCES) under its key; addDependency(first, second) makes the second
 * (one added here) a Fabric listener that depends on the first.
 */
public class AddClientReloadListenersEvent extends Event implements IModBusEvent {

    private final Map<ResourceLocation, PreparableReloadListener> registry = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<ResourceLocation>> dependencies = new LinkedHashMap<>();

    AddClientReloadListenersEvent() {}

    public void addListener(ResourceLocation key, PreparableReloadListener listener) {
        if (this.registry.containsKey(key) || this.registry.containsValue(listener)) {
            throw new IllegalArgumentException("Attempted to register two reload listeners for the same key: " + key);
        }
        this.registry.put(key, listener);
        List<ResourceLocation> after = dependencies.computeIfAbsent(key, k -> new ArrayList<>());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return key;
            }

            @Override
            public Collection<ResourceLocation> getFabricDependencies() {
                return after;
            }

            @Override
            public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager manager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
                return listener.reload(barrier, manager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public String getName() {
                return listener.getName();
            }
        });
    }

    public void addDependency(ResourceLocation first, ResourceLocation second) {
        if (!registry.containsKey(second)) throw new IllegalArgumentException("Unknown reload listener: " + second);
        dependencies.get(second).add(first);
    }

    public Map<ResourceLocation, PreparableReloadListener> getRegistry() {
        return Collections.unmodifiableMap(this.registry);
    }
}
