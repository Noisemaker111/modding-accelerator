package ${package};

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * NeoForge 26.1's net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent, posted on the mod bus with the
 * client's registration events (ClientGameEvents.registration): each pipeline goes into the game's own table of static
 * pipelines (RenderPipelines, by its location), as NeoForge's RenderPipelines patch puts it, so the game precompiles it
 * with its own; a second pipeline at the same location is refused, as NeoForge refuses it.
 */
public class RegisterRenderPipelinesEvent extends Event implements IModBusEvent {

    public RegisterRenderPipelinesEvent() {}

    public void registerPipeline(RenderPipeline pipeline) {
        Map<Identifier, RenderPipeline> registry = table();
        if (registry.putIfAbsent(pipeline.getLocation(), pipeline) != null) throw new IllegalStateException("Duplicate RenderPipeline registration for location: " + pipeline.getLocation());
    }

    /** The game's pipeline table (26.x keeps the game's own names at run time). */
    @SuppressWarnings("unchecked")
    static Map<Identifier, RenderPipeline> table() {
        try {
            Field field = RenderPipelines.class.getDeclaredField("PIPELINES_BY_LOCATION");
            field.setAccessible(true);
            return (Map<Identifier, RenderPipeline>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No RenderPipelines.PIPELINES_BY_LOCATION in this game", e);
        }
    }
}
