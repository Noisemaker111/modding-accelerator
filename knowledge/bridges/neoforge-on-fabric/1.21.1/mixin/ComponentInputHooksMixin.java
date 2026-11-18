package ${package}.mixin;

import ${package}.extensions.GameHooks;
import org.spongepowered.asm.mixin.Mixin;

/** Gives BlockEntity.DataComponentInput (protected without NeoForge's access transformer) GameHooks.ComponentInput. */
@Mixin(targets = "net.minecraft.world.level.block.entity.BlockEntity$DataComponentInput")
public interface ComponentInputHooksMixin extends GameHooks.ComponentInput {}
