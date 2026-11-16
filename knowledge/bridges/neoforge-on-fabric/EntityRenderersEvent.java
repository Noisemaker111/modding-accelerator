package ${package};

import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * NeoForge's net.neoforged.neoforge.client.event.EntityRenderersEvent, posted on the mod bus while the client mod loads
 * (ClientGameEvents.registration), where NeoForge posts it while the game builds its renderers. Each registration is
 * Fabric API's own: model layers (ModelLayerRegistry), entity renderers (EntityRendererRegistry) and block entity
 * renderers (BlockEntityRendererRegistry), which the game reads when it builds its renderers, as NeoForge's do. AddLayers
 * and CreateSkullModels are not mirrored (a mod that listens to them is listed).
 */
public abstract class EntityRenderersEvent extends Event implements IModBusEvent {

    protected EntityRenderersEvent() {}

    public static class RegisterLayerDefinitions extends EntityRenderersEvent {

        public void registerLayerDefinition(ModelLayerLocation layerLocation, Supplier<LayerDefinition> supplier) {
            ModelLayerRegistry.registerModelLayer(layerLocation, supplier::get);
        }
    }

    public static class RegisterRenderers extends EntityRenderersEvent {

        public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> entityType, EntityRendererProvider<T> provider) {
            EntityRendererRegistry.register(entityType, provider);
        }

        @SuppressWarnings("unchecked")
        public <T extends BlockEntity, S extends BlockEntityRenderState> void registerBlockEntityRenderer(BlockEntityType<? extends T> blockEntityType, BlockEntityRendererProvider<T, S> provider) {
            BlockEntityRendererRegistry.register((BlockEntityType<T>) blockEntityType, provider);
        }
    }
}
