package ${package}.mixin;

import ${package}.extensions.GameHooks;
import net.minecraft.world.item.Rarity;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Rarity GameHooks.RarityStyle (the interface's defaults are the methods). */
@Mixin(Rarity.class)
public abstract class RarityHooksMixin implements GameHooks.RarityStyle {}
