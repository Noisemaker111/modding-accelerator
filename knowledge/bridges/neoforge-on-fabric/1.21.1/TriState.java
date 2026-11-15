package ${package};

/** NeoForge 21.1's net.neoforged.neoforge.common.util.TriState: allow, deny or leave to the game (DEFAULT). */
public enum TriState {
    TRUE,
    DEFAULT,
    FALSE;

    public boolean isTrue() {
        return this == TRUE;
    }

    public boolean isDefault() {
        return this == DEFAULT;
    }

    public boolean isFalse() {
        return this == FALSE;
    }
}
