package ${package};

import net.minecraft.util.context.ContextKey;

/**
 * NeoForge's net.neoforged.neoforge.client.extensions.IRenderStateExtension: extra data a mod keeps on the game's entity
 * and map render states (NeoForge gives them a BaseRenderState superclass; here mixin/RenderStateDataMixin adds the map).
 */
public interface IRenderStateExtension {

    default <T> T getRenderData(ContextKey<T> key) {
        throw new UnsupportedOperationException("this render state keeps no NeoForge render data");
    }

    default <T> void setRenderData(ContextKey<T> key, T data) {
        throw new UnsupportedOperationException("this render state keeps no NeoForge render data");
    }

    default <T> T getRenderDataOrThrow(ContextKey<T> key) {
        T data = getRenderData(key);
        if (data == null) throw new IllegalStateException("No value associated for key " + key);
        return data;
    }

    default <T> T getRenderDataOrDefault(ContextKey<T> key, T defaultVal) {
        T data = getRenderData(key);
        return data == null ? defaultVal : data;
    }
}
