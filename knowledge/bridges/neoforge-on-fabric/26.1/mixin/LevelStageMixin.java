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
 * NeoForge 26.1's LevelRenderer patch, for the RenderLevelStageEvent stages Fabric API has no event at:
 * AfterTranslucentParticles once the main pass has drawn the particles, AfterWeather once the weather pass has drawn
 * the weather, and AfterLevel when renderLevel has drawn the whole level.
 */
@Mixin(LevelRenderer.class)
abstract class LevelStageMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;reset()V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterTranslucentParticles(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterTranslucentParticles::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }

    @Inject(method = "lambda$addWeatherPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V", shift = At.Shift.AFTER))
    private void neoforgeBridge$afterWeather(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterWeather::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void neoforgeBridge$afterLevel(CallbackInfo info) {
        LevelStages.post(RenderLevelStageEvent.AfterLevel::new, (LevelRenderer) (Object) this, this.levelRenderState);
    }
}
