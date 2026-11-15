package ${package}.fabric.api.networking.v1.context;

import com.mojang.authlib.GameProfile;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import ${package}.FabricBridge;

/**
 * Fabric API's net.fabricmc.fabric.api.networking.v1.context.PacketContext: values about one connection (its server,
 * registries, game profile and the Connection itself, and values mods set under their own keys), bound for the code a
 * runWithContext call runs (a ScopedValue, as on Fabric). On Fabric any player, packet listener or connection provides
 * one (an interface injected into those game classes); here runWithContext and supplyWithContext take a player or a
 * Connection. The values set under a mod's own keys live as long as the player or connection they were set for.
 */
public interface PacketContext {

    ReadKey<MinecraftServer> SERVER_INSTANCE = new KeyImpl<>(Identifier.fromNamespaceAndPath("fabric", "server_instance"));
    ReadKey<RegistryAccess> REGISTRY_ACCESS = new KeyImpl<>(Identifier.fromNamespaceAndPath("fabric", "registry_access"));
    ReadKey<GameProfile> GAME_PROFILE = new KeyImpl<>(Identifier.fromNamespaceAndPath("fabric", "game_profile"));
    ReadKey<Connection> CONNECTION = new KeyImpl<>(Identifier.fromNamespaceAndPath("fabric", "connection"));

    <T> T get(ReadKey<T> key);

    default <T> T orElseThrow(ReadKey<T> key) {
        return Objects.requireNonNull(get(key), () -> "Packet Context is missing the '" + key + "' value!");
    }

    default <T> T orElse(ReadKey<T> key, T defaultValue) {
        return Objects.requireNonNullElse(get(key), defaultValue);
    }

    <T> void set(Key<T> key, T value);

    static PacketContext get() {
        return Impl.VALUE.isBound() ? Impl.VALUE.get() : null;
    }

    static PacketContext orElseThrow() {
        if (!Impl.VALUE.isBound()) throw new RuntimeException("PacketContext is required, but it wasn't set up!");
        PacketContext context = Impl.VALUE.get();
        if (context == null) throw new RuntimeException("PacketContext is required, but it was disabled!");
        return context;
    }

    static void runWithContext(Player provider, Runnable runnable) {
        ScopedValue.where(Impl.VALUE, of(provider)).run(runnable);
    }

    static void runWithContext(Connection provider, Runnable runnable) {
        ScopedValue.where(Impl.VALUE, of(provider)).run(runnable);
    }

    static <T> T supplyWithContext(Player provider, Supplier<T> supplier) {
        return ScopedValue.where(Impl.VALUE, of(provider)).call(supplier::get);
    }

    static <T> T supplyWithContext(Connection provider, Supplier<T> supplier) {
        return ScopedValue.where(Impl.VALUE, of(provider)).call(supplier::get);
    }

    static void runWithoutContext(Runnable runnable) {
        if (Impl.VALUE.isBound()) ScopedValue.where(Impl.VALUE, null).run(runnable);
        else runnable.run();
    }

    static <T> T supplyWithoutContext(Supplier<T> supplier) {
        if (Impl.VALUE.isBound()) return ScopedValue.where(Impl.VALUE, null).call(supplier::get);
        return supplier.get();
    }

    static <T> Key<T> key(Identifier key) {
        return new KeyImpl<>(key);
    }

    /** The context of a player or a Connection (the bridge's own; Fabric reaches it through the provider). */
    static PacketContext of(Object provider) {
        Objects.requireNonNull(provider, "provider");
        synchronized (Impl.CONTEXTS) {
            return Impl.CONTEXTS.computeIfAbsent(provider, Impl::new);
        }
    }

    interface ReadKey<T> {
    }

    interface Key<T> extends ReadKey<T> {
    }

    /** A key: its identifier names it. */
    record KeyImpl<T>(Identifier id) implements Key<T> {
        @Override
        public String toString() {
            return id.toString();
        }
    }

    /**
     * One provider's values. The map holds each provider only weakly, and so does its context (a strong reference from
     * the value would keep the weak key, and a disconnected player's level, alive): a context goes with its provider.
     */
    final class Impl implements PacketContext {
        static final ScopedValue<PacketContext> VALUE = ScopedValue.newInstance();
        static final Map<Object, PacketContext> CONTEXTS = new WeakHashMap<>();

        private final WeakReference<Object> provider;
        private final Map<Key<?>, Object> values = new ConcurrentHashMap<>();

        Impl(Object provider) {
            this.provider = new WeakReference<>(provider);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(ReadKey<T> key) {
            Object provider = this.provider.get();
            Player player = provider instanceof Player p ? p : null;
            if (key == SERVER_INSTANCE) return player == null ? null : (T) player.level().getServer();
            if (key == REGISTRY_ACCESS) return player == null ? null : (T) player.registryAccess();
            if (key == GAME_PROFILE) return player == null ? null : (T) player.getGameProfile();
            if (key == CONNECTION) {
                if (provider instanceof Connection connection) return (T) connection;
                if (player instanceof ServerPlayer server) return (T) server.connection.getConnection();
                return (T) FabricBridge.clientConnection(player);
            }
            return (T) values.get(key);
        }

        @Override
        public <T> void set(Key<T> key, T value) {
            if (value == null) values.remove(key);
            else values.put(key, value);
        }
    }
}
