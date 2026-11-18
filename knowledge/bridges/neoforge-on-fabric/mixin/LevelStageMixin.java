package ${package}.mixin;

import ${package}.LevelStages;
import ${package}.RenderLevelStageEvent;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge 26.3's LevelRenderer and GameRenderer patches, for the RenderLevelStageEvent stages Fabric API has no event
 * at: AfterTranslucentParticles once the translucent features after terrain are drawn, AfterWeather once the weather
 * is, and AfterLevel when the level renderer has drawn the whole level.
 */
@Mixin(LevelRenderer.class)
abstract class LevelStageMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterTranslucentParticles(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterTranslucentParticles::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }

    @Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterWeather(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterWeather::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void neoforgeBridge$afterLevel(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterLevel::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }
}
