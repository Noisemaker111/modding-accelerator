package ${package}.mixin;

import ${package}.extensions.BlockEntityHooks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Gives BlockEntity NeoForge's sync hooks (the interface's defaults); BlockEntityDataMixin and BlockEntityChunkDataMixin call them. */
@Mixin(BlockEntity.class)
public abstract class BlockEntityHooksMixin implements BlockEntityHooks {}
