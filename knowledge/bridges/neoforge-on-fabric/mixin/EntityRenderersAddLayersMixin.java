package ${package}.mixin;

import ${package}.EntityRenderersEvent;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's EntityRenderDispatcher patch: once the game has built its entity, player and mannequin renderers (each
 * resource reload), EntityRenderersEvent.AddLayers is posted with them, so a mod adds its render layers to them.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderersAddLayersMixin {

    @Shadow
    private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;

    @Shadow
    private Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers;

    @Shadow
    private Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void addLayers(ResourceManager resourceManager, CallbackInfo ci, @Local EntityRendererProvider.Context context) {
        EntityRenderersEvent.AddLayers.post(renderers, playerRenderers, mannequinRenderers, context);
    }
}
