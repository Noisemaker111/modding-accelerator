package ${package};

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * A mod's pipelines join the game's own lists, which the game precompiles: a required one in requiredPipelines, an
 * optional one in optionalPipelines only, and one at a location the game already uses is refused.
 */
class RegisterRenderPipelinesEventTest {

    static RenderPipeline pipeline(String path) {
        return pipeline(Identifier.fromNamespaceAndPath("demo", path));
    }

    static RenderPipeline pipeline(Identifier location) {
        return RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(Identifier.fromNamespaceAndPath("demo", "core/glow"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("demo", "core/glow"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    }

    @Test
    void pipelinesJoinTheGamesLists() {
        RenderPipeline required = pipeline("glow");
        RenderPipeline optional = pipeline("glow_optional");
        RegisterRenderPipelinesEvent event = new RegisterRenderPipelinesEvent();
        event.registerPipeline(required);
        event.registerOptionalPipeline(optional);

        assertTrue(RenderPipelines.requiredPipelines().contains(required));
        assertFalse(RenderPipelines.requiredPipelines().contains(optional));
        assertTrue(RenderPipelines.optionalPipelines().contains(optional));
        RenderPipeline taken = pipeline(RenderPipelines.requiredPipelines().get(0).getLocation());
        assertThrows(IllegalStateException.class, () -> event.registerPipeline(taken));
        assertFalse(RenderPipelines.requiredPipelines().contains(taken));
    }
}
