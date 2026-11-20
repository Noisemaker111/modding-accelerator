package ${package};

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RenderLevelStageEvent (26.x), posted on the game bus at each stage of
 * drawing the level (LevelStages: Fabric API's LevelRenderEvents where one fires at NeoForge's stage, the bridge's
 * LevelStageMixin for the stages Fabric has none for). As NeoForge, a stage given no pose stack gets a fresh one, and
 * the model view matrix is the camera's view rotation. NeoForge's render pass and renderable sections are not offered.
 */
public abstract class RenderLevelStageEvent extends Event {

    private final LevelRenderer levelRenderer;
    private final LevelRenderState levelRenderState;
    private final PoseStack poseStack;
    private final Matrix4fc modelViewMatrix;

    public RenderLevelStageEvent(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
        this.levelRenderer = levelRenderer;
        this.levelRenderState = levelRenderState;
        this.poseStack = poseStack != null ? poseStack : new PoseStack();
        this.modelViewMatrix = modelViewMatrix;
    }

    public LevelRenderer getLevelRenderer() {
        return this.levelRenderer;
    }

    public LevelRenderState getLevelRenderState() {
        return this.levelRenderState;
    }

    public PoseStack getPoseStack() {
        return this.poseStack;
    }

    public Matrix4fc getModelViewMatrix() {
        return this.modelViewMatrix;
    }

    public static class AfterSky extends RenderLevelStageEvent {
        public AfterSky(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterOpaqueBlocks extends RenderLevelStageEvent {
        public AfterOpaqueBlocks(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterOpaqueFeatures extends RenderLevelStageEvent {
        public AfterOpaqueFeatures(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterTranslucentFeatures extends RenderLevelStageEvent {
        public AfterTranslucentFeatures(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterTranslucentBlocks extends RenderLevelStageEvent {
        public AfterTranslucentBlocks(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterTranslucentParticles extends RenderLevelStageEvent {
        public AfterTranslucentParticles(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterWeather extends RenderLevelStageEvent {
        public AfterWeather(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }

    public static class AfterLevel extends RenderLevelStageEvent {
        public AfterLevel(LevelRenderer levelRenderer, LevelRenderState levelRenderState, PoseStack poseStack, Matrix4fc modelViewMatrix) {
            super(levelRenderer, levelRenderState, poseStack, modelViewMatrix);
        }
    }
}
