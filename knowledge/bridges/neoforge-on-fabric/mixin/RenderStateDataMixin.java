package ${package}.mixin;

import ${package}.IRenderStateExtension;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.util.context.ContextKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** NeoForge's BaseRenderState on the game's entity and map render states: a map of mod data by key. */
@Mixin({ EntityRenderState.class, MapRenderState.class })
abstract class RenderStateDataMixin implements IRenderStateExtension {

    @Unique
    private final Map<ContextKey<?>, Object> bridge$renderData = new IdentityHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getRenderData(ContextKey<T> key) {
        return (T) bridge$renderData.get(key);
    }

    @Override
    public <T> void setRenderData(ContextKey<T> key, T data) {
        if (data == null) bridge$renderData.remove(key);
        else bridge$renderData.put(key, data);
    }
}
