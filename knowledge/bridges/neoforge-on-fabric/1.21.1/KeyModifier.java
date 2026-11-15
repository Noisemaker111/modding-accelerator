package ${package};

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.settings.KeyModifier, read by the key mappings
 * KeyConflictContext.keyMapping makes. The combined names use NeoForge's translation keys, which the bridge does not ship.
 */
public enum KeyModifier {
    CONTROL {
        private static final InputConstants.Key[] KEYS = new InputConstants.Key[] {
            InputConstants.getKey("key.keyboard.left.control"),
            InputConstants.getKey("key.keyboard.right.control"),
            InputConstants.getKey("key.keyboard.left.win"),
            InputConstants.getKey("key.keyboard.right.win")
        };

        @Override
        public boolean matches(InputConstants.Key key) {
            for (InputConstants.Key control : KEYS) if (control.equals(key)) return true;
            return false;
        }

        @Override
        public boolean isActive(IKeyConflictContext conflictContext) {
            return Screen.hasControlDown();
        }

        @Override
        public Component getCombinedName(InputConstants.Key key, Supplier<Component> defaultLogic) {
            return Component.translatable("neoforge.controlsgui.control", defaultLogic.get());
        }

        @Override
        public InputConstants.Key[] codes() {
            return KEYS;
        }
    },
    SHIFT {
        private static final InputConstants.Key[] KEYS = new InputConstants.Key[] {
            InputConstants.getKey("key.keyboard.left.shift"),
            InputConstants.getKey("key.keyboard.right.shift")
        };

        @Override
        public boolean matches(InputConstants.Key key) {
            return key.equals(KEYS[0]) || key.equals(KEYS[1]);
        }

        @Override
        public boolean isActive(IKeyConflictContext conflictContext) {
            return Screen.hasShiftDown();
        }

        @Override
        public Component getCombinedName(InputConstants.Key key, Supplier<Component> defaultLogic) {
            return Component.translatable("neoforge.controlsgui.shift", defaultLogic.get());
        }

        @Override
        public InputConstants.Key[] codes() {
            return KEYS;
        }
    },
    ALT {
        private static final InputConstants.Key[] KEYS = new InputConstants.Key[] {
            InputConstants.getKey("key.keyboard.left.alt"),
            InputConstants.getKey("key.keyboard.right.alt")
        };

        @Override
        public boolean matches(InputConstants.Key key) {
            return key.equals(KEYS[0]) || key.equals(KEYS[1]);
        }

        @Override
        public boolean isActive(IKeyConflictContext conflictContext) {
            return Screen.hasAltDown();
        }

        @Override
        public Component getCombinedName(InputConstants.Key keyCode, Supplier<Component> defaultLogic) {
            return Component.translatable("neoforge.controlsgui.alt", defaultLogic.get());
        }

        @Override
        public InputConstants.Key[] codes() {
            return KEYS;
        }
    },
    NONE {
        @Override
        public boolean matches(InputConstants.Key key) {
            return false;
        }

        @Override
        public boolean isActive(IKeyConflictContext conflictContext) {
            if (conflictContext != null && !conflictContext.conflicts(KeyConflictContext.IN_GAME)) {
                for (KeyModifier keyModifier : MODIFIER_VALUES) {
                    if (keyModifier.isActive(conflictContext)) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public Component getCombinedName(InputConstants.Key key, Supplier<Component> defaultLogic) {
            return defaultLogic.get();
        }
    };

    public static final KeyModifier[] MODIFIER_VALUES = { SHIFT, CONTROL, ALT };

    public static KeyModifier getActiveModifier() {
        for (KeyModifier keyModifier : MODIFIER_VALUES) {
            if (keyModifier.isActive(null)) {
                return keyModifier;
            }
        }
        return NONE;
    }

    public static List<KeyModifier> getActiveModifiers() {
        List<KeyModifier> modifiers = new ArrayList<>();
        for (KeyModifier keyModifier : MODIFIER_VALUES) {
            if (keyModifier.isActive(null)) {
                modifiers.add(keyModifier);
            }
        }
        return modifiers;
    }

    public static KeyModifier getKeyModifier(InputConstants.Key key) {
        for (KeyModifier keyModifier : MODIFIER_VALUES) {
            if (keyModifier.matches(key)) {
                return keyModifier;
            }
        }
        return NONE;
    }

    public static boolean isKeyCodeModifier(InputConstants.Key key) {
        return getKeyModifier(key) != NONE;
    }

    public static KeyModifier valueFromString(String stringValue) {
        try {
            return valueOf(stringValue);
        } catch (NullPointerException | IllegalArgumentException ignored) {
            return NONE;
        }
    }

    public abstract boolean matches(InputConstants.Key key);

    public abstract boolean isActive(IKeyConflictContext conflictContext);

    public abstract Component getCombinedName(InputConstants.Key key, Supplier<Component> defaultLogic);

    public InputConstants.Key[] codes() {
        return new InputConstants.Key[0];
    }
}
