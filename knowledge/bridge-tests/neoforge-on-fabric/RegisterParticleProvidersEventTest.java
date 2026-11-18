package ${package};

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import org.junit.jupiter.api.Test;

/**
 * A NeoForge mod's particle providers reach Fabric API's particle registry: registerSpecial's provider as it is, and
 * registerSpriteSet's (a lambda, as mods write it) as a provider made from the sprite set Fabric hands it. Read back
 * from Fabric's registry before the game starts (its deferred registry, replayed into a recorder), by reflection, so
 * one test serves Fabric API's ParticleProviderRegistry (26.x) and ParticleFactoryRegistry (1.21.1).
 */
class RegisterParticleProvidersEventTest {

    @Test
    void providersReachFabricsRegistry() throws Exception {
        SimpleParticleType special = new SimpleParticleType(false) {};
        SimpleParticleType sprited = new SimpleParticleType(false) {};
        ParticleProvider<SimpleParticleType> provider = (ParticleProvider<SimpleParticleType>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ParticleProvider.class }, (p, m, a) -> null);
        SpriteSet[] seen = new SpriteSet[1];
        RegisterParticleProvidersEvent event = new RegisterParticleProvidersEvent();
        event.registerSpecial(special, provider);
        event.registerSpriteSet(sprited, sprites -> {
            seen[0] = sprites;
            return provider;
        });

        Class<?> api = api();
        Object registry = api.getMethod("getInstance").invoke(null);
        java.lang.reflect.Field internal = registry.getClass().getDeclaredField("internalRegistry");
        internal.setAccessible(true);
        Object deferred = internal.get(registry);
        Map<Object, Object> recorded = new LinkedHashMap<>();
        Object recorder = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] { api }, (p, m, a) -> recorded.put(a[0], a[1]));
        Method applyTo = deferred.getClass().getDeclaredMethod("applyTo", api);
        applyTo.setAccessible(true);
        applyTo.invoke(deferred, recorder);

        assertSame(provider, recorded.get(special));
        Object pending = recorded.get(sprited);
        Method create = null;
        for (Method m : pending.getClass().getMethods()) if (m.getName().equals("create") && m.getParameterCount() == 1) create = m;
        create.setAccessible(true);
        SpriteSet sprites = (SpriteSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { create.getParameterTypes()[0] }, (p, m, a) -> null);
        assertSame(provider, create.invoke(pending, sprites));
        assertSame(sprites, seen[0]);
    }

    static Class<?> api() throws ClassNotFoundException {
        try {
            return Class.forName("net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry");
        } catch (ClassNotFoundException e) {
            return Class.forName("net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry");
        }
    }
}
