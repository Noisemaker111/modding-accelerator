package ${package};

import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent, posted on the mod bus when the
 * client starts (ClientBridge.client): each provider goes into Fabric API's ParticleFactoryRegistry, a sprite set's
 * provider with the sprite set Fabric loads for that particle type, and registerSprite's particle picks its sprite from
 * that set (as the game's ParticleEngine.register does). The game's ParticleEngine.SpriteParticleRegistration is
 * private without NeoForge's access transformer, so a sprite set's registration is this event's own interface.
 */
public class RegisterParticleProvidersEvent extends Event implements IModBusEvent {

    @FunctionalInterface
    public interface SpriteParticleRegistration<T extends ParticleOptions> {
        ParticleProvider<T> create(SpriteSet sprites);
    }

    public RegisterParticleProvidersEvent() {}

    public <T extends ParticleOptions> void registerSpecial(ParticleType<T> type, ParticleProvider<T> provider) {
        ParticleFactoryRegistry.getInstance().register(type, provider);
    }

    public <T extends ParticleOptions> void registerSprite(ParticleType<T> type, ParticleProvider.Sprite<T> sprite) {
        ParticleFactoryRegistry.getInstance().register(type, sprites -> (options, level, x, y, z, dx, dy, dz) -> {
            TextureSheetParticle particle = sprite.createParticle(options, level, x, y, z, dx, dy, dz);
            if (particle != null) particle.pickSprite(sprites);
            return particle;
        });
    }

    public <T extends ParticleOptions> void registerSpriteSet(ParticleType<T> type, SpriteParticleRegistration<T> registration) {
        ParticleFactoryRegistry.getInstance().register(type, registration::create);
    }
}
