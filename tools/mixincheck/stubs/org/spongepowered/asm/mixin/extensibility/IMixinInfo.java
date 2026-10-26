package org.spongepowered.asm.mixin.extensibility;

/** Compile-time copy of the part of Mixin's interface the check calls. */
public interface IMixinInfo {
    IMixinConfig getConfig();
    String getName();
    String getClassName();
    java.util.List<String> getTargetClasses();
}
