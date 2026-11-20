package ${package};

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * NeoForge's net.neoforged.neoforge.client.settings.KeyConflictContext. GUI is active while a screen is open (the screen
 * Fabric API's screen events last initialised and did not remove), IN_GAME while none is.
 *
 * NeoForge adds constructors to KeyMapping that take a conflict context (and a KeyModifier); the game on Fabric has
 * none, so mmt convert writes new KeyMapping(name, KeyConflictContext.X, ...) as KeyConflictContext.X.keyMapping(name,
 * ...), one method per NeoForge constructor (a context held in a variable: the static KeyConflictContext.keyMapping(name,
 * context, ...)), which makes a KeyMapping whose isDown() is true only while its context and
 * modifier are active and whose consumeClick() drops the clicks made while they are not (NeoForge's KeyMapping patch keeps
 * such clicks from reaching it). The game's controls screen still treats every key mapping as UNIVERSAL.
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

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Type, int, KeyMapping.Category). */
    public KeyMapping keyMapping(String name, InputConstants.Type inputType, int keyCode, KeyMapping.Category category) {
        return of(name, this, KeyModifier.NONE, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Key, KeyMapping.Category). */
    public KeyMapping keyMapping(String name, InputConstants.Key key, KeyMapping.Category category) {
        return of(name, this, KeyModifier.NONE, key.getType(), key.getValue(), category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Type, int, KeyMapping.Category). */
    public KeyMapping keyMapping(String name, KeyModifier keyModifier, InputConstants.Type inputType, int keyCode, KeyMapping.Category category) {
        return of(name, this, keyModifier, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Key, KeyMapping.Category). */
    public KeyMapping keyMapping(String name, KeyModifier keyModifier, InputConstants.Key key, KeyMapping.Category category) {
        return of(name, this, keyModifier, key.getType(), key.getValue(), category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Type, int, KeyMapping.Category), for a context held in a variable. */
    public static KeyMapping keyMapping(String name, IKeyConflictContext context, InputConstants.Type inputType, int keyCode, KeyMapping.Category category) {
        return of(name, context, KeyModifier.NONE, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, InputConstants.Key, KeyMapping.Category), for a context held in a variable. */
    public static KeyMapping keyMapping(String name, IKeyConflictContext context, InputConstants.Key key, KeyMapping.Category category) {
        return of(name, context, KeyModifier.NONE, key.getType(), key.getValue(), category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Type, int, KeyMapping.Category), for a context held in a variable. */
    public static KeyMapping keyMapping(String name, IKeyConflictContext context, KeyModifier keyModifier, InputConstants.Type inputType, int keyCode, KeyMapping.Category category) {
        return of(name, context, keyModifier, inputType, keyCode, category);
    }

    /** NeoForge's KeyMapping(String, IKeyConflictContext, KeyModifier, InputConstants.Key, KeyMapping.Category), for a context held in a variable. */
    public static KeyMapping keyMapping(String name, IKeyConflictContext context, KeyModifier keyModifier, InputConstants.Key key, KeyMapping.Category category) {
        return of(name, context, keyModifier, key.getType(), key.getValue(), category);
    }

    static KeyMapping of(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Type type, int code, KeyMapping.Category category) {
        return new Mapping(name, context, modifier, type, code, category);
    }

    /**
     * A key mapping with NeoForge's conflict context and modifier, which IKeyMappingExtension's getKeyConflictContext and
     * getKeyModifier read (a mapping made with the game's own constructor is UNIVERSAL, without a modifier).
     */
    public static final class Mapping extends KeyMapping {

        IKeyConflictContext context;
        KeyModifier modifier;
        final KeyModifier defaultModifier;

        Mapping(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Type type, int code, KeyMapping.Category category) {
            super(name, type, code, category);
            this.context = context;
            this.modifier = modifier;
            this.defaultModifier = modifier;
        }

        public IKeyConflictContext context() {
            return context;
        }

        public void context(IKeyConflictContext context) {
            this.context = context;
        }

        public KeyModifier modifier() {
            return modifier;
        }

        public void modifier(KeyModifier modifier) {
            this.modifier = modifier;
        }

        public KeyModifier defaultModifier() {
            return defaultModifier;
        }

        boolean active() {
            return context.isActive() && modifier.isActive(context);
        }

        @Override
        public boolean isDown() {
            return super.isDown() && active();
        }

        @Override
        public boolean consumeClick() {
            boolean clicked = super.consumeClick();
            if (clicked && active()) return true;
            while (super.consumeClick()) {}
            return false;
        }
    }
}
