package ${package};

import java.util.function.BooleanSupplier;
import net.minecraft.world.level.Level;

/**
 * NeoForge's net.neoforged.neoforge.event.tick.LevelTickEvent, posted for server levels from Fabric's ServerTickEvents
 * and for the client level from ClientTickEvents. Fabric's callbacks do not say whether the server has time left in
 * the tick, so hasTime() is true.
 */
public abstract class LevelTickEvent extends Event {

    private final BooleanSupplier haveTime;
    private final Level level;

    protected LevelTickEvent(BooleanSupplier haveTime, Level level) {
        this.haveTime = haveTime;
        this.level = level;
    }

    public boolean hasTime() {
        return haveTime.getAsBoolean();
    }

    public Level getLevel() {
        return level;
    }

    public static class Pre extends LevelTickEvent {

        public Pre(BooleanSupplier haveTime, Level level) {
            super(haveTime, level);
        }
    }

    public static class Post extends LevelTickEvent {

        public Post(BooleanSupplier haveTime, Level level) {
            super(haveTime, level);
        }
    }
}
