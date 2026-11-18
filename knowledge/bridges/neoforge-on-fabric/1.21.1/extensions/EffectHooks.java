package ${package}.extensions;

import ${package}.EffectCure;
import ${package}.EffectCures;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * NeoForge 21.1's effect cures: an effect names what ends it early (MobEffect.fillEffectCures, NeoForge's default milk
 * and the totem, honey for poison) and an effect instance gives them (getCures). The game's milk, totem and honey
 * bottle end only the effects their cure is in (EffectCureMixins), as NeoForge's patches make them do.
 */
public final class EffectHooks {

    private EffectHooks() {}

    public interface Effect {
        default void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
            cures.addAll(EffectCures.DEFAULT_CURES);
            if ((Object) this == MobEffects.POISON.value()) cures.add(EffectCures.HONEY);
        }
    }

    public interface Instance {
        default Set<EffectCure> getCures() {
            MobEffectInstance self = (MobEffectInstance) (Object) this;
            Set<EffectCure> cures = new HashSet<>();
            ((Effect) self.getEffect().value()).fillEffectCures(cures, self);
            return cures;
        }
    }

    /** Whether the cure ends this effect instance. */
    public static boolean curedBy(MobEffectInstance instance, EffectCure cure) {
        return ((Instance) instance).getCures().contains(cure);
    }

    static Holder<MobEffect> unused() {
        return MobEffects.POISON;
    }
}
