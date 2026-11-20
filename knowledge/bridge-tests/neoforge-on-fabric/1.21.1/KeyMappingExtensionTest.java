package ${package};

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.Test;

/**
 * NeoForge 21.1's isActiveAndMatches, the one IKeyMappingExtension method this layer gives KeyMapping: the unknown key
 * matches no mapping, not even one bound to it. Runs inside Fabric Loader, so the bridge's KeyMappingMixin has added it.
 */
class KeyMappingExtensionTest {

    @Test
    void theUnknownKeyMatchesNothing() {
        KeyMapping unbound = new KeyMapping("key.bridge.unbound", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.misc");
        assertFalse(unbound.isActiveAndMatches(InputConstants.UNKNOWN));
    }
}
