package ${package};

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent, posted on the mod bus when the client
 * starts (ClientBridge.client): each provider goes into Fabric API's ParticleProviderRegistry, a sprite set's provider
 * with the sprite set Fabric loads for that particle type (from assets/NAMESPACE/particles/PATH.json, as NeoForge).
 * The game's ParticleResources.SpriteParticleRegistration is private without NeoForge's access transformer, so a sprite
 * set's registration is this event's own interface of the same shape (a mod passes a lambda or constructor reference).
 */
public class RegisterParticleProvidersEvent extends Event implements IModBusEvent {

    @FunctionalInterface
    public interface SpriteParticleRegistration<T extends ParticleOptions> {
        ParticleProvider<T> create(SpriteSet sprites);
    }

    public RegisterParticleProvidersEvent() {}

    public <T extends ParticleOptions> void registerSpecial(ParticleType<T> type, ParticleProvider<T> provider) {
        ParticleProviderRegistry.getInstance().register(type, provider);
    }

    public <T extends ParticleOptions> void registerSpriteSet(ParticleType<T> type, SpriteParticleRegistration<T> registration) {
        ParticleProviderRegistry.getInstance().register(type, registration::create);
    }
}
