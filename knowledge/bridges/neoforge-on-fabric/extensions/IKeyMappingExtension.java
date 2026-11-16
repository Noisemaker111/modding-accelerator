package ${package}.extensions;

import ${package}.IKeyConflictContext;
import ${package}.KeyConflictContext;
import ${package}.KeyModifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * NeoForge's IKeyMappingExtension as mods call it on a KeyMapping. A mapping made with a conflict context
 * (KeyConflictContext.keyMapping) keeps its context and modifier; one made with the game's constructor is UNIVERSAL
 * without a modifier, as on NeoForge. The bound key is Fabric API's KeyMappingHelper.getBoundKeyOf. Added by
 * KeyMappingMixin, and for the compile by Loom's interface injection.
 */
public interface IKeyMappingExtension {

    private KeyMapping self() {
        return (KeyMapping) (Object) this;
    }

    default InputConstants.Key getKey() {
        return KeyMappingHelper.getBoundKeyOf(self());
    }

    default IKeyConflictContext getKeyConflictContext() {
        return self() instanceof KeyConflictContext.Mapping mapping ? mapping.context() : KeyConflictContext.UNIVERSAL;
    }

    default void setKeyConflictContext(IKeyConflictContext keyConflictContext) {
        if (self() instanceof KeyConflictContext.Mapping mapping) mapping.context(keyConflictContext);
    }

    default KeyModifier getKeyModifier() {
        return self() instanceof KeyConflictContext.Mapping mapping ? mapping.modifier() : KeyModifier.NONE;
    }

    default KeyModifier getDefaultKeyModifier() {
        return self() instanceof KeyConflictContext.Mapping mapping ? mapping.defaultModifier() : KeyModifier.NONE;
    }

    default void setKeyModifierAndCode(KeyModifier keyModifier, InputConstants.Key keyCode) {
        if (self() instanceof KeyConflictContext.Mapping mapping) mapping.modifier(keyModifier);
        self().setKey(keyCode);
        KeyMapping.resetMapping();
    }

    default boolean isConflictContextAndModifierActive() {
        return getKeyConflictContext().isActive() && getKeyModifier().isActive(getKeyConflictContext());
    }

    default boolean isActiveAndMatches(InputConstants.Key keyCode) {
        return keyCode != InputConstants.UNKNOWN && keyCode.equals(getKey()) && isConflictContextAndModifierActive();
    }
}
