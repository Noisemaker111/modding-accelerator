package ${package};

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 21.1's net.neoforged.neoforge.event.level.block.CropGrowEvent, posted on the game bus by CommonHooks.canCropGrow
 * (Pre: a listener may make the crop grow or not) and fireCropGrowPost (Post), which crops call as NeoForge's own crops
 * do. The game's own crops do not call them on Fabric.
 */
public abstract class CropGrowEvent extends BlockEvent {

    public CropGrowEvent(Level level, BlockPos pos, BlockState state) {
        super(level, pos, state);
    }

    public static class Pre extends CropGrowEvent {

        private Result result = Result.DEFAULT;

        public Pre(Level level, BlockPos pos, BlockState state) {
            super(level, pos, state);
        }

        public void setResult(Result result) {
            this.result = result;
        }

        public Result getResult() {
            return this.result;
        }

        public enum Result {
            GROW,
            DEFAULT,
            DO_NOT_GROW
        }
    }

    public static class Post extends CropGrowEvent {

        private final BlockState originalState;

        public Post(Level level, BlockPos pos, BlockState original, BlockState state) {
            super(level, pos, state);
            this.originalState = original;
        }

        public BlockState getOriginalState() {
            return this.originalState;
        }
    }
}
