package ${package}.extensions;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * NeoForge 21.1's IKeyMappingExtension.isActiveAndMatches as mods call it on a KeyMapping: whether the key is the one
 * the mapping is bound to (Fabric API's KeyBindingHelper.getBoundKeyOf), never the unknown key. Added by KeyMappingMixin,
 * and for the compile by Loom's interface injection.
 */
public interface IKeyMappingExtension {

    default boolean isActiveAndMatches(InputConstants.Key keyCode) {
        return keyCode != InputConstants.UNKNOWN && keyCode.equals(KeyBindingHelper.getBoundKeyOf((KeyMapping) (Object) this));
    }
}
