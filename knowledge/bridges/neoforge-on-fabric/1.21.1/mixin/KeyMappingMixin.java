package ${package}.mixin;

import ${package}.extensions.IKeyMappingExtension;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;

/** Gives KeyMapping NeoForge 21.1's isActiveAndMatches (the interface's default is the method). */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin implements IKeyMappingExtension {}
