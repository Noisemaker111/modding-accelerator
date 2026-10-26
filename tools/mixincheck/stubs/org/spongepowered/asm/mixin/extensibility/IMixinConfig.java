package org.spongepowered.asm.mixin.extensibility;

/** Compile-time copy of the part of Mixin's interface the check calls. */
public interface IMixinConfig {
    String getName();
    String getMixinPackage();
    boolean isRequired();
    java.util.Set<String> getTargets();
}
