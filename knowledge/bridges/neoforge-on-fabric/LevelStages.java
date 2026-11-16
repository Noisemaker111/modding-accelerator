package ${package};

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;

/**
 * Posts NeoForge's RenderLevelStageEvent stages (26.3): after the sky at the start of Fabric's main pass, the opaque and
 * translucent stages at Fabric API's LevelRenderEvents of the same place, and the stages Fabric has no event for
 * (particles, weather, the whole level) from LevelStageMixin. NeoForge 26.3 gives every stage a fresh pose stack.
 */
public final class LevelStages {

    static boolean hooked;

    private LevelStages() {}

    /** A stage's event, made from the level renderer, its render state, a pose stack (null: a fresh one) and the camera's view rotation. */
    public interface Stage {
        RenderLevelStageEvent create(LevelRenderer renderer, LevelRenderState state, PoseStack poseStack, Matrix4fc modelView);
    }

    static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        LevelRenderEvents.START_MAIN.register(context -> post(RenderLevelStageEvent.AfterSky::new, context));
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> post(RenderLevelStageEvent.AfterOpaqueBlocks::new, context));
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> post(RenderLevelStageEvent.AfterOpaqueFeatures::new, context));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> post(RenderLevelStageEvent.AfterTranslucentFeatures::new, context));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> post(RenderLevelStageEvent.AfterTranslucentBlocks::new, context));
    }

    static void post(Stage stage, AbstractLevelRenderContext context) {
        post(stage, context.levelRenderer(), context.levelState());
    }

    /** From LevelStageMixin, for the stages Fabric has no event for. */
    public static void post(Stage stage, LevelRenderer renderer, LevelRenderState state) {
        if (state == null) return;
        Bus.INSTANCE.post(stage.create(renderer, state, null, state.cameraRenderState.viewRotationMatrix));
    }
}
