package ${package};

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent (26.3), posted on the mod bus with the
 * client's registration events (ClientGameEvents.registration): each pipeline goes into the game's own table of
 * required or optional pipelines (RenderPipelines, by its location), as NeoForge's RenderPipelines patch puts it, so the
 * game precompiles it with its own; a second pipeline at the same location is refused, as NeoForge refuses it.
 */
public class RegisterRenderPipelinesEvent extends Event implements IModBusEvent {

    public RegisterRenderPipelinesEvent() {}

    public void registerPipeline(RenderPipeline pipeline) {
        register(pipeline, false);
    }

    public void registerOptionalPipeline(RenderPipeline pipeline) {
        register(pipeline, true);
    }

    public void registerOitPipelineSet(OitPipelineSet pipelineSet) {
        register(pipelineSet.depthBoundsPipeline(), false);
        register(pipelineSet.transmittancePipeline(), false);
        register(pipelineSet.accumulatePipeline(), false);
    }

    static void register(RenderPipeline pipeline, boolean optional) {
        Map<Identifier, RenderPipeline> registry = table(optional ? "OPTIONAL_PIPELINES_BY_LOCATION" : "PIPELINES_BY_LOCATION");
        if (registry.putIfAbsent(pipeline.getLocation(), pipeline) != null) throw new IllegalStateException("Duplicate RenderPipeline registration for location: " + pipeline.getLocation());
    }

    /** The game's pipeline table (26.x keeps the game's own names at run time). */
    @SuppressWarnings("unchecked")
    static Map<Identifier, RenderPipeline> table(String name) {
        try {
            Field field = RenderPipelines.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<Identifier, RenderPipeline>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No RenderPipelines." + name + " in this game", e);
        }
    }
}
