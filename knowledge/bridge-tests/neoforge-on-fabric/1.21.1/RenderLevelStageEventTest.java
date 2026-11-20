package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NeoForge 21.1's block stages follow the chunk layer drawn (Stage.fromRenderType: solid, cutout mipped, cutout,
 * translucent, tripwire; other render types none), and a stage reaches listeners with the matrices it was posted with.
 */
class RenderLevelStageEventTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blockStagesFollowTheChunkLayer() {
        assertSame(RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS, RenderLevelStageEvent.Stage.fromRenderType(RenderType.solid()));
        assertSame(RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS, RenderLevelStageEvent.Stage.fromRenderType(RenderType.cutoutMipped()));
        assertSame(RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS, RenderLevelStageEvent.Stage.fromRenderType(RenderType.cutout()));
        assertSame(RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS, RenderLevelStageEvent.Stage.fromRenderType(RenderType.translucent()));
        assertSame(RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS, RenderLevelStageEvent.Stage.fromRenderType(RenderType.tripwire()));
        assertNull(RenderLevelStageEvent.Stage.fromRenderType(RenderType.lines()));
        assertEquals("minecraft:after_translucent_blocks", RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS.toString());
    }

    @Test
    void stagePostsWithItsMatrices() {
        List<RenderLevelStageEvent> seen = new ArrayList<>();
        Bus.INSTANCE.addListener(RenderLevelStageEvent.class, seen::add);
        Matrix4f modelView = new Matrix4f().rotateY(1.0F);
        Matrix4f projection = new Matrix4f().perspective(1.0F, 1.0F, 0.05F, 100.0F);
        LevelStages.post(null, null, null, modelView, projection, 7, null, null, null);
        LevelStages.post(RenderLevelStageEvent.Stage.AFTER_WEATHER, null, null, modelView, projection, 7, null, null, null);
        assertEquals(1, seen.size());
        RenderLevelStageEvent event = seen.get(0);
        assertSame(RenderLevelStageEvent.Stage.AFTER_WEATHER, event.getStage());
        assertSame(modelView, event.getModelViewMatrix());
        assertSame(projection, event.getProjectionMatrix());
        assertEquals(7, event.getRenderTick());
        assertNotNull(event.getPoseStack());
    }
}
