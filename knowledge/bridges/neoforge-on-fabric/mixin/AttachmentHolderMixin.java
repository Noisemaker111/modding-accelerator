package ${package}.mixin;

import ${package}.IAttachmentHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/** NeoForge's patches: the game's entities, block entities, levels and chunks are attachment holders. */
@Mixin({ Entity.class, BlockEntity.class, Level.class, ChunkAccess.class })
abstract class AttachmentHolderMixin implements IAttachmentHolder {}
