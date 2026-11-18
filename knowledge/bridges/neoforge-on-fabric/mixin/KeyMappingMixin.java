package ${package}.mixin;

import ${package}.extensions.IKeyMappingExtension;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;

/** Gives KeyMapping NeoForge's IKeyMappingExtension methods (the interface's defaults are the methods). */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin implements IKeyMappingExtension {}
