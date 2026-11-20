package ${package}.mixin;

import ${package}.extensions.MiscHooks;
import net.minecraft.client.resources.model.BakedModel;
import org.spongepowered.asm.mixin.Mixin;

/** Gives BakedModel MiscHooks.ModelTransform (the interface's defaults are the methods). */
@Mixin(BakedModel.class)
public interface ModelTransformHooksMixin extends MiscHooks.ModelTransform {}
