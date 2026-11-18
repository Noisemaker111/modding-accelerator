package ${package}.mixin;

import ${package}.LevelStages;
import ${package}.RenderLevelStageEvent.Stage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 21.1's LevelRenderer and GameRenderer patches posting RenderLevelStageEvent: after the sky, after each chunk
 * layer whose render type has a stage, before the block entities (AFTER_ENTITIES), before the destroy progress
 * (AFTER_BLOCK_ENTITIES), after the particles and the weather, and once renderLevel has drawn the level (AFTER_LEVEL).
 */
@Mixin(LevelRenderer.class)
abstract class LevelStageMixin {

    @Shadow
    private int ticks;

    @Shadow
    private Frustum cullingFrustum;

    @Shadow
    private Frustum capturedFrustum;

    @Unique
    private void neoforgeBridge$stage(Stage stage, DeltaTracker delta, Camera camera, Matrix4f modelView, Matrix4f projection) {
        Frustum frustum = this.capturedFrustum != null ? this.capturedFrustum : this.cullingFrustum;
        LevelStages.post(stage, (LevelRenderer) (Object) this, null, modelView, projection, this.ticks, delta, camera, frustum);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterSky(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_SKY, delta, camera, modelView, projection);
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void neoforgeBridge$afterLayer(RenderType type, double x, double y, double z, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        Minecraft minecraft = Minecraft.getInstance();
        neoforgeBridge$stage(Stage.fromRenderType(type), minecraft.getTimer(), minecraft.gameRenderer.getMainCamera(), modelView, projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=blockentities"))
    private void neoforgeBridge$afterEntities(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_ENTITIES, delta, camera, modelView, projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=destroyProgress"))
    private void neoforgeBridge$afterBlockEntities(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_BLOCK_ENTITIES, delta, camera, modelView, projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterParticles(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_PARTICLES, delta, camera, modelView, projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterWeather(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_WEATHER, delta, camera, modelView, projection);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void neoforgeBridge$afterLevel(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        neoforgeBridge$stage(Stage.AFTER_LEVEL, delta, camera, modelView, projection);
    }
}
