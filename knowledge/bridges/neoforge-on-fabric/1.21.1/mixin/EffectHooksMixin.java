package ${package}.mixin;

import ${package}.extensions.EffectHooks;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;

/** Gives MobEffect NeoForge 21.1's fillEffectCures (the interface's default). */
@Mixin(MobEffect.class)
public abstract class EffectHooksMixin implements EffectHooks.Effect {}
