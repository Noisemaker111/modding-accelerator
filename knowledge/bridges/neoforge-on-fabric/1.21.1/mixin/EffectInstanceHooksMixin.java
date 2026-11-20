package ${package}.mixin;

import ${package}.extensions.EffectHooks;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;

/** Gives MobEffectInstance NeoForge 21.1's getCures (the interface's default). */
@Mixin(MobEffectInstance.class)
public abstract class EffectInstanceHooksMixin implements EffectHooks.Instance {}
