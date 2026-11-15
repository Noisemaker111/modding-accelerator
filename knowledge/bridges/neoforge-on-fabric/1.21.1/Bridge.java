package ${package};

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ObjectShare;

/**
 * What a NeoForge @Mod class's constructor received, and NeoForge's start-up order, for the Fabric entrypoint mmt
 * convert made of it: the entrypoint gets its mod bus and container from here, registers its @EventBusSubscriber
 * classes here, and ends with common(), which registers the mod's DeferredRegisters, hooks NeoForge's game events to
 * the Fabric callbacks that fire at the same moments, and posts FMLCommonSetupEvent. The client entrypoint ends with
 * ClientBridge.client().
 */
public final class Bridge {

    static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();
    static boolean hooked;
    /** The running server, for PacketDistributor.sendToAllPlayers. */
    static net.minecraft.server.MinecraftServer server;

    private Bridge() {}

    public static ModContainer container(String modid) {
        return CONTAINERS.computeIfAbsent(modid, ModContainer::new);
    }

    public static IEventBus bus() {
        return Bus.INSTANCE;
    }

    /** An @EventBusSubscriber class: its static @SubscribeEvent methods. */
    public static void subscribe(Class<?> type) {
        Bus.INSTANCE.register(type);
    }

    /** The end of the main entrypoint: registration, then the game's events, then common setup. */
    public static void common(String modid) {
        DeferredRegister.registerPending();
        hook();
        Bus.INSTANCE.post(new RegisterPayloadHandlersEvent());
        Bus.INSTANCE.post(new FMLCommonSetupEvent(container(modid)));
        lifecycle(modid);
    }

    static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        GameEvents.hook();
        ServerLifecycleEvents.SERVER_STARTING.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Bus.INSTANCE.post(new ServerStartingEvent(server)));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Bus.INSTANCE.post(new ServerStartedEvent(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Bus.INSTANCE.post(new ServerStoppingEvent(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Bus.INSTANCE.post(new ServerStoppedEvent(server)));
        ServerTickEvents.START_SERVER_TICK.register(server -> Bus.INSTANCE.post(new ServerTickEvent.Pre(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> Bus.INSTANCE.post(new ServerTickEvent.Post(server)));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> Bus.INSTANCE.post(new RegisterCommandsEvent(dispatcher, selection, context)));
        ItemGroupEvents.MODIFY_ENTRIES_ALL.register((tab, entries) -> Bus.INSTANCE.post(new BuildCreativeModeTabContentsEvent(tab, entries)));
    }

    // ---- player interaction, block and lifecycle hooks ----

    private static final String LOAD_STAGES = "mmt:neoforge_bridge_load_stages";
    private static final String LOAD_DONE = "mmt:neoforge_bridge_load_done";
    static boolean loadHooked;

    /**
     * Queues a mod's InterModEnqueueEvent, InterModProcessEvent and FMLLoadCompleteEvent, which loadComplete posts once
     * every mod's entrypoints ran: on a client when it has started (Fabric's ClientLifecycleEvents.CLIENT_STARTED), on a
     * dedicated server as it starts (GameEvents, before ServerAboutToStartEvent).
     */
    static synchronized void lifecycle(String modid) {
        ModContainer container = container(modid);
        Runnable enqueue = () -> Bus.INSTANCE.post(new InterModEnqueueEvent(container));
        Runnable process = () -> Bus.INSTANCE.post(new InterModProcessEvent(container));
        Runnable complete = () -> Bus.INSTANCE.post(new FMLLoadCompleteEvent(container));
        loadStages().add(new Runnable[] { enqueue, process, complete });
        if (loadHooked) return;
        loadHooked = true;
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) ClientAccess.onStarted(Bridge::loadComplete);
    }

    /** The queued lifecycle events of every converted mod (each has its own copy of the bridge), in Fabric Loader's ObjectShare. */
    @SuppressWarnings("unchecked")
    static List<Runnable[]> loadStages() {
        ObjectShare share = FabricLoader.getInstance().getObjectShare();
        share.putIfAbsent(LOAD_STAGES, new CopyOnWriteArrayList<Runnable[]>());
        return (List<Runnable[]>) share.get(LOAD_STAGES);
    }

    /** Every converted mod's InterModEnqueueEvent, then every one's InterModProcessEvent, then FMLLoadCompleteEvent, once. */
    static void loadComplete() {
        if (FabricLoader.getInstance().getObjectShare().putIfAbsent(LOAD_DONE, Boolean.TRUE) != null) return;
        List<Runnable[]> stages = loadStages();
        for (int stage = 0; stage < 3; stage++) {
            for (Runnable[] mod : stages) mod[stage].run();
        }
    }
}
