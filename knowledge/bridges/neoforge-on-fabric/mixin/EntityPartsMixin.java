package ${package}.mixin;

import ${package}.extensions.EntityParts;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Entity NeoForge's isMultipartEntity and getParts at runtime (the interface's defaults are the methods). */
@Mixin(Entity.class)
public abstract class EntityPartsMixin implements EntityParts {}
