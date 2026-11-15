package ${package};

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * What a NeoForge @Mod class's constructor received, and NeoForge's start-up order, for the Fabric entrypoint modaccel
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
        CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register((tab, output) -> Bus.INSTANCE.post(new BuildCreativeModeTabContentsEvent(tab, output)));
    }
}
