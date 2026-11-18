package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * A level stage reaches the mod's listener for that stage only, as NeoForge 26.x posts it: with the level render state,
 * the camera's view rotation as the model view matrix, and a fresh (identity) pose stack.
 */
class RenderLevelStageEventTest {

    @Test
    void stagePostsWithTheCamerasViewRotation() {
        List<RenderLevelStageEvent.AfterWeather> weather = new ArrayList<>();
        List<RenderLevelStageEvent.AfterTranslucentParticles> particles = new ArrayList<>();
        Bus.INSTANCE.addListener(RenderLevelStageEvent.AfterWeather.class, weather::add);
        Bus.INSTANCE.addListener(RenderLevelStageEvent.AfterTranslucentParticles.class, particles::add);
        LevelRenderState state = new LevelRenderState();
        state.cameraRenderState = new CameraRenderState();
        state.cameraRenderState.viewRotationMatrix = new Matrix4f().rotateY(1.0F);

        LevelStages.post(RenderLevelStageEvent.AfterWeather::new, null, state);

        assertEquals(1, weather.size());
        assertTrue(particles.isEmpty());
        RenderLevelStageEvent event = weather.get(0);
        assertSame(state, event.getLevelRenderState());
        assertSame(state.cameraRenderState.viewRotationMatrix, event.getModelViewMatrix());
        assertNotNull(event.getPoseStack());
        assertTrue(event.getPoseStack().last().pose().equals(new Matrix4f()));
    }
}
