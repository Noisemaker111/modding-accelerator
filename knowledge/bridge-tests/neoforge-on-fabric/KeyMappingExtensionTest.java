package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.Test;

/**
 * A key mapping made with a conflict context, written or held in a variable, answers NeoForge's getKeyConflictContext and
 * getKeyModifier with what it was made with, and a modifier set later replaces it; one made with the game's constructor
 * is UNIVERSAL without a modifier. Runs inside Fabric Loader, so the bridge's KeyMappingMixin has added the methods.
 */
class KeyMappingExtensionTest {

    @Test
    void mappingsKeepTheirContextAndModifier() {
        InputConstants.Type keyboard = InputConstants.getKey("key.keyboard.g").getType();
        IKeyConflictContext held = KeyConflictContext.GUI;
        KeyMapping fromVariable = KeyConflictContext.keyMapping("key.bridge.a", held, KeyModifier.SHIFT, keyboard, 71, KeyMapping.Category.MISC);
        assertEquals(KeyConflictContext.GUI, fromVariable.getKeyConflictContext());
        assertEquals(KeyModifier.SHIFT, fromVariable.getKeyModifier());
        fromVariable.setKeyModifierAndCode(KeyModifier.ALT, InputConstants.getKey("key.keyboard.h"));
        assertEquals(KeyModifier.ALT, fromVariable.getKeyModifier());
        assertEquals(KeyModifier.SHIFT, fromVariable.getDefaultKeyModifier());
        KeyMapping written = KeyConflictContext.IN_GAME.keyMapping("key.bridge.b", keyboard, 73, KeyMapping.Category.MISC);
        assertEquals(KeyConflictContext.IN_GAME, written.getKeyConflictContext());
        assertEquals(KeyModifier.NONE, written.getKeyModifier());
        KeyMapping plain = new KeyMapping("key.bridge.c", keyboard, 74, KeyMapping.Category.MISC);
        assertEquals(KeyConflictContext.UNIVERSAL, plain.getKeyConflictContext());
        assertEquals(KeyModifier.NONE, plain.getKeyModifier());
    }
}
