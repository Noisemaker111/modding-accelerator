package ${package}.mixin;

import ${package}.capabilities.LevelCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Level the bridge's LevelCapabilities at runtime (NeoForge's level.getCapability, see the interface). */
@Mixin(Level.class)
public abstract class LevelCapabilitiesMixin implements LevelCapabilities {

    @Override
    public void invalidateCapabilities(BlockPos pos) {}

    @Override
    public void invalidateCapabilities(ChunkPos pos) {}
}
