package ${package};

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.player.PlayerModelType;
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
 * is posted where NeoForge posts it, each time the game builds its entity renderers (mixin/EntityRenderersAddLayersMixin).
 * CreateSkullModels is not mirrored (a mod that listens to it is listed).
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

    public static class AddLayers extends EntityRenderersEvent {

        private final Map<EntityType<?>, EntityRenderer<?, ?>> renderers;
        private final Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers;
        private final Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers;
        private final EntityRendererProvider.Context context;

        public AddLayers(
            Map<EntityType<?>, EntityRenderer<?, ?>> renderers,
            Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers,
            Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers,
            EntityRendererProvider.Context context
        ) {
            this.renderers = renderers;
            this.playerRenderers = playerRenderers;
            this.mannequinRenderers = mannequinRenderers;
            this.context = context;
        }

        /** Posts the event on the bridge's bus (for the bridge's mixin, which is in another package). */
        public static void post(
            Map<EntityType<?>, EntityRenderer<?, ?>> renderers,
            Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers,
            Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers,
            EntityRendererProvider.Context context
        ) {
            Bus.INSTANCE.post(new AddLayers(renderers, playerRenderers, mannequinRenderers, context));
        }

        public Set<PlayerModelType> getSkins() {
            return playerRenderers.keySet();
        }

        @SuppressWarnings("unchecked")
        public <R extends AvatarRenderer<AbstractClientPlayer>> R getPlayerRenderer(PlayerModelType skinModel) {
            return (R) playerRenderers.get(skinModel);
        }

        @SuppressWarnings("unchecked")
        public <R extends AvatarRenderer<ClientMannequin>> R getMannequinRenderer(PlayerModelType skinModel) {
            return (R) mannequinRenderers.get(skinModel);
        }

        public Set<EntityType<?>> getEntityTypes() {
            return renderers.keySet();
        }

        @SuppressWarnings("unchecked")
        public <T extends Entity, R extends EntityRenderer<T, ?>> R getRenderer(EntityType<? extends T> entityType) {
            return (R) renderers.get(entityType);
        }

        public EntityModelSet getEntityModels() {
            return this.context.getModelSet();
        }

        public EntityRendererProvider.Context getContext() {
            return context;
        }
    }
}
