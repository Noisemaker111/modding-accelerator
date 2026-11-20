package ${package};

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * A mod's pipeline joins the game's own static pipelines, which the game precompiles; one at a location the game
 * already uses is refused.
 */
class RegisterRenderPipelinesEventTest {

    @Test
    void pipelinesJoinTheGamesList() {
        RenderPipeline pipeline = pipeline(Identifier.fromNamespaceAndPath("demo", "glow"));
        RegisterRenderPipelinesEvent event = new RegisterRenderPipelinesEvent();
        event.registerPipeline(pipeline);

        assertTrue(RenderPipelines.getStaticPipelines().contains(pipeline));
        RenderPipeline taken = pipeline(RenderPipelines.getStaticPipelines().get(0).getLocation());
        assertThrows(IllegalStateException.class, () -> event.registerPipeline(taken));
        assertFalse(RenderPipelines.getStaticPipelines().contains(taken));
    }

    static RenderPipeline pipeline(Identifier location) {
        return RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(Identifier.fromNamespaceAndPath("demo", "core/glow"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("demo", "core/glow"))
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .build();
    }
}
