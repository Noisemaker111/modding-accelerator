package ${package}.fabric.api.networking.v1.context;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;

/** A packet context lives only as long as its provider (a player or a connection), never longer. */
class PacketContextTest {

    @Test
    void sameContextWhileTheProviderLives() {
        Object provider = new Object();
        assertSame(PacketContext.of(provider), PacketContext.of(provider));
    }

    @Test
    void contextDoesNotKeepItsProviderAlive() throws InterruptedException {
        WeakReference<Object> provider = provide();
        for (int i = 0; i < 100 && provider.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(provider.get(), "a provider with a packet context must be collected once nothing else holds it");
    }

    private static WeakReference<Object> provide() {
        Object provider = new Object();
        PacketContext.of(provider).set(PacketContext.key(net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "value")), "kept");
        return new WeakReference<>(provider);
    }
}
