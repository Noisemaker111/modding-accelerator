package ${package};

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

/** Posts NeoForge 21.1's RenderLevelStageEvent for LevelStageMixin, as NeoForge's ClientHooks.dispatchRenderStage. */
public final class LevelStages {

    private LevelStages() {}

    /** On 1.21.1 every stage comes from LevelStageMixin. */
    static void hook() {}

    public static void post(RenderLevelStageEvent.Stage stage, LevelRenderer renderer, PoseStack poseStack, Matrix4f modelView, Matrix4f projection, int ticks, DeltaTracker delta, Camera camera, Frustum frustum) {
        if (stage == null) return;
        Bus.INSTANCE.post(new RenderLevelStageEvent(stage, renderer, poseStack, modelView, projection, ticks, delta, camera, frustum));
    }
}
