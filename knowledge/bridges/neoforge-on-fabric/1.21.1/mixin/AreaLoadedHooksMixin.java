package ${package}.mixin;

import ${package}.extensions.GameHooks;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;

/** Gives LevelReader GameHooks.AreaLoaded (the interface's defaults are the methods). */
@Mixin(LevelReader.class)
public interface AreaLoadedHooksMixin extends GameHooks.AreaLoaded {}
