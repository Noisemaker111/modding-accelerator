package ${package};

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.settings.KeyConflictContext. GUI is active while a screen is open (the
 * screen Fabric API's screen events last initialised and did not remove), IN_GAME while none is.
 *
 * NeoForge adds constructors to KeyMapping that take a conflict context (and a KeyModifier); the game on Fabric has
 * none, so mmt convert writes new KeyMapping(name, KeyConflictContext.X, ...) as KeyConflictContext.X.keyMapping(name,
 * ...), one method per NeoForge constructor, which makes a KeyMapping whose isDown() is true only while its context and
 * modifier are active and whose consumeClick() drops the clicks made while they are not. The game's controls screen still
 * treats every key mapping as UNIVERSAL.
 */
public enum KeyConflictContext implements IKeyConflictContext {
    UNIVERSAL {
        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return true;
        }
    },
    GUI {
        @Override
        public boolean isActive() {
            return ClientHooks.screen != null;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    },
    IN_GAME {
        @Override
        public boolean isActive() {
            return !GUI.isActive();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Type, int, String). */
    public KeyMapping keyMapping(String name, InputConstants.Type inputType, int keyCode, String category) {
        return of(name, this, KeyModifier.NONE, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Key, String). */
    public KeyMapping keyMapping(String name, InputConstants.Key key, String category) {
        return of(name, this, KeyModifier.NONE, key.getType(), key.getValue(), category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Type, int, String). */
    public KeyMapping keyMapping(String name, KeyModifier keyModifier, InputConstants.Type inputType, int keyCode, String category) {
        return of(name, this, keyModifier, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Key, String). */
    public KeyMapping keyMapping(String name, KeyModifier keyModifier, InputConstants.Key key, String category) {
        return of(name, this, keyModifier, key.getType(), key.getValue(), category);
    }

    static KeyMapping of(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Type type, int code, String category) {
        return new KeyMapping(name, type, code, category) {
            @Override
            public boolean isDown() {
                return super.isDown() && context.isActive() && modifier.isActive(context);
            }

            @Override
            public boolean consumeClick() {
                boolean clicked = super.consumeClick();
                if (clicked && context.isActive() && modifier.isActive(context)) return true;
                while (super.consumeClick()) {}
                return false;
            }
        };
    }
}
