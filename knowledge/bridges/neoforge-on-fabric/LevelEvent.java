package ${package};

import net.minecraft.world.level.LevelAccessor;

/** NeoForge's net.neoforged.neoforge.event.level.LevelEvent: Load and Unload are posted from Fabric's server level events. */
public abstract class LevelEvent extends Event {

    private final LevelAccessor level;

    public LevelEvent(LevelAccessor level) {
        this.level = level;
    }

    public LevelAccessor getLevel() {
        return level;
    }

    public static class Load extends LevelEvent {

        public Load(LevelAccessor level) {
            super(level);
        }
    }

    public static class Unload extends LevelEvent {

        public Unload(LevelAccessor level) {
            super(level);
        }
    }
}
