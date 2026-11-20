package ${package}.mixin;

import ${package}.extensions.IMenuProviderExtension;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;

/** Gives every MenuProvider NeoForge's shouldTriggerClientSideContainerClosingOnOpen (the interface's default). */
@Mixin(MenuProvider.class)
public interface MenuProviderMixin extends IMenuProviderExtension {}
