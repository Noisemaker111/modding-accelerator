package ${package}.mixin;

import ${package}.capabilities.EntityCapabilities;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Entity the bridge's EntityCapabilities at runtime (NeoForge's entity.getCapability). */
@Mixin(Entity.class)
public abstract class EntityCapabilitiesMixin implements EntityCapabilities {}
