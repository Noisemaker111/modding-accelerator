package ${package}.mixin;

import ${package}.extensions.MiscHooks;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;

/** Gives HolderLookup.Provider MiscHooks.LookupHolder (the interface's defaults are the methods). */
@Mixin(HolderLookup.Provider.class)
public interface LookupHolderHooksMixin extends MiscHooks.LookupHolder {}
