package ${package}.fabric.impl.client.rendering;

import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Fabric API's net.fabricmc.fabric.impl.client.rendering.FabricShaderProgram (Minecraft 1.21.1): a core shader read from
 * its own namespace (assets/NAMESPACE/shaders/core/PATH.json), which mods construct directly. NeoForge 21.1's
 * ShaderInstance takes a namespaced location itself.
 */
public class FabricShaderProgram extends ShaderInstance {

    public FabricShaderProgram(ResourceProvider factory, ResourceLocation name, VertexFormat format) throws IOException {
        super(factory, name, format);
    }
}
