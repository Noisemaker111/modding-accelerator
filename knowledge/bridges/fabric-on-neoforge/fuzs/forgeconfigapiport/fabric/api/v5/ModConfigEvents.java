package ${package}.fuzs.forgeconfigapiport.fabric.api.v5;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import ${package}.fabric.api.event.Event;
import ${package}.fabric.api.event.EventFactory;
import ${package}.loader.api.FabricLoader;

/**
 * Forge Config API Port's fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents, posted from NeoForge's own
 * ModConfigEvent.Loading, Reloading and Unloading on the mod's bus, which NeoForge posts for that mod's configs only,
 * at the moments the library posts them on Fabric (it ports this config system).
 */
public final class ModConfigEvents {

    private static final Map<String, Event<Loading>> LOADING = new ConcurrentHashMap<>();
    private static final Map<String, Event<Reloading>> RELOADING = new ConcurrentHashMap<>();
    private static final Map<String, Event<Unloading>> UNLOADING = new ConcurrentHashMap<>();

    private ModConfigEvents() {}

    public static Event<Loading> loading(String modId) {
        return LOADING.computeIfAbsent(modId, id -> {
            Event<Loading> event = EventFactory.createArrayBacked(Loading.class, callbacks -> config -> {
                for (Loading callback : callbacks) callback.onModConfigLoading(config);
            });
            bus(id).addListener(ModConfigEvent.Loading.class, e -> event.invoker().onModConfigLoading(e.getConfig()));
            return event;
        });
    }

    public static Event<Reloading> reloading(String modId) {
        return RELOADING.computeIfAbsent(modId, id -> {
            Event<Reloading> event = EventFactory.createArrayBacked(Reloading.class, callbacks -> config -> {
                for (Reloading callback : callbacks) callback.onModConfigReloading(config);
            });
            bus(id).addListener(ModConfigEvent.Reloading.class, e -> event.invoker().onModConfigReloading(e.getConfig()));
            return event;
        });
    }

    public static Event<Unloading> unloading(String modId) {
        return UNLOADING.computeIfAbsent(modId, id -> {
            Event<Unloading> event = EventFactory.createArrayBacked(Unloading.class, callbacks -> config -> {
                for (Unloading callback : callbacks) callback.onModConfigUnloading(config);
            });
            bus(id).addListener(ModConfigEvent.Unloading.class, e -> event.invoker().onModConfigUnloading(e.getConfig()));
            return event;
        });
    }

    private static IEventBus bus(String modId) {
        IEventBus bus = FabricLoader.getInstance().getModContainer(modId).orElseThrow(() -> new IllegalArgumentException("No mod " + modId)).neoforge().getEventBus();
        if (bus == null) throw new IllegalArgumentException("The mod " + modId + " has no event bus");
        return bus;
    }

    @FunctionalInterface
    public interface Loading {
        void onModConfigLoading(ModConfig config);
    }

    @FunctionalInterface
    public interface Reloading {
        void onModConfigReloading(ModConfig config);
    }

    @FunctionalInterface
    public interface Unloading {
        void onModConfigUnloading(ModConfig config);
    }
}
