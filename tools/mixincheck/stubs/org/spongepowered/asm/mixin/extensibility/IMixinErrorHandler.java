package org.spongepowered.asm.mixin.extensibility;

/** Compile-time copy of Mixin's interface. Never shipped to a game: the mod's own Mixin jar supplies the real one. */
public interface IMixinErrorHandler {
    enum ErrorAction { NONE, WARN, ERROR }
    ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action);
    ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action);
}
