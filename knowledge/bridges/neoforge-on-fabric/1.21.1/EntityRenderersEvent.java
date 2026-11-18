package ${package};

import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.EntityRenderersEvent, posted on the mod bus while the client mod
 * loads (ClientGameEvents.registration): RegisterLayerDefinitions to Fabric API's EntityModelLayerRegistry,
 * RegisterRenderers to its EntityRendererRegistry and BlockEntityRendererRegistry, which the game reads when it builds
 * its renderers, as NeoForge's events fill them.
 */
public abstract class EntityRenderersEvent extends Event implements IModBusEvent {

    protected EntityRenderersEvent() {}

    public static class RegisterLayerDefinitions extends EntityRenderersEvent {

        public void registerLayerDefinition(ModelLayerLocation layerLocation, Supplier<LayerDefinition> supplier) {
            EntityModelLayerRegistry.registerModelLayer(layerLocation, supplier::get);
        }
    }

    public static class RegisterRenderers extends EntityRenderersEvent {

        public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> entityType, EntityRendererProvider<T> provider) {
            EntityRendererRegistry.register(entityType, provider);
        }

        @SuppressWarnings({"unchecked", "deprecation"})
        public <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<? extends T> blockEntityType, BlockEntityRendererProvider<T> provider) {
            BlockEntityRendererRegistry.register((BlockEntityType<T>) blockEntityType, provider);
        }
    }
}
