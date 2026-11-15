package ${package};

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * The Fabric bridge on NeoForge (modaccel convert): the Fabric API classes this mod's code uses that no rule rewrites,
 * written on NeoForge's API with Fabric's names and members, under this package (net.fabricmc.X is ${package}.X). The
 * mod's @Mod constructors call init(modBus) first; it hands the mod's bus to every part of the bridge that is posted
 * from a NeoForge mod-bus event (payload registration, GUI layers, key mappings, capabilities ...). Game-bus events are
 * hooked on NeoForge.EVENT_BUS when a bridge class is first used. Client parts are hooked on the physical client only.
 */
public final class FabricBridge {

    private static IEventBus modBus;
    private static final List<Consumer<IEventBus>> PENDING = new ArrayList<>();
    private static volatile Function<Player, Connection> clientConnections = player -> null;

    private FabricBridge() {}

    /** Called first in each @Mod constructor convert writes; the first call wins. */
    public static synchronized void init(IEventBus bus) {
        if (modBus != null) return;
        modBus = bus;
        for (Consumer<IEventBus> hook : PENDING) hook.accept(bus);
        PENDING.clear();
        Hooks.common(bus);
        if (FMLEnvironment.getDist().isClient()) ClientHooks.client(bus);
    }

    /** Runs a hook on the mod's bus now, or once init has it. */
    public static synchronized void onModBus(Consumer<IEventBus> hook) {
        if (modBus != null) hook.accept(modBus);
        else PENDING.add(hook);
    }

    /** The client's connection for its own player; ClientHooks sets how it is found on the physical client. */
    public static Connection clientConnection(Player player) {
        return player == null ? null : clientConnections.apply(player);
    }

    static void clientConnections(Function<Player, Connection> lookup) {
        clientConnections = lookup;
    }

    /** The mod's id on NeoForge. */
    public static String modId() {
        return "${modid}";
    }
}
