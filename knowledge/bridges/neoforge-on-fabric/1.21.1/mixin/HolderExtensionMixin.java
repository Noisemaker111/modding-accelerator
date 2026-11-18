package ${package}.mixin;

import ${package}.extensions.IHolderExtension;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;

/** Gives the game's Holder NeoForge's getData for data maps (IHolderExtension). */
@Mixin(Holder.class)
public interface HolderExtensionMixin<T> extends IHolderExtension<T> {}
